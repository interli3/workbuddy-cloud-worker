#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WB 多账号 Monitor 电脑端伴侣服务（多账号版）
--------------------------------------------------
读取 workbuddy-accounts 的凭证池 credentials.json，对每个账号调用官方额度接口
取余额（仅统计 Status==0 生效中的包），按每日基线算当日消耗，聚合成多账号快照，
通过局域网 HTTP 提供快照；用户明确启用 MQTT 后，才会每 15 秒以 retain
方式推到公共 broker（手机会合点，无需知道电脑地址）。

数据源：本机凭证池 credentials.json（由面板/import-local 维护）。
额度接口：https://www.codebuddy.cn/v2/billing/meter/get-user-resource （需 Bearer + UA）

启动：  python bridge_multi.py            (默认端口 8791)
接口：  GET /snapshot   -> 多账号 JSON
        GET /health     -> {"ok":true}
        GET /app        -> 下载安卓 APK
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import socket
import sqlite3
import threading
import time
import urllib.request
import urllib.error
from datetime import date, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.normpath(os.path.join(HERE, "..", "config.local.json"))


def load_local_config():
    try:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


LOCAL_CONFIG = load_local_config()
# 凭证池：与 workbuddy-accounts 面板共用同一份 credentials.json
CRED_FILE = os.path.normpath(os.path.join(HERE, "..", "credentials.json"))
DAYMARK = os.path.join(HERE, "_daymark_multi.json")

# 官方额度接口：域名是 www.codebuddy.cn（copilot.tencent.com 已废弃），必须带 UA 否则 403
CLOUD_RES = "https://www.codebuddy.cn/v2/billing/meter/get-user-resource"

# MQTT 会合点：电脑推快照、手机取，双方只认 broker+topic，地址永不漂移
MQTT_HOST = os.environ.get("WBMON_MQTT_HOST", LOCAL_CONFIG.get("mqttHost", "broker.emqx.io"))
MQTT_PORT = int(os.environ.get("WBMON_MQTT_PORT", LOCAL_CONFIG.get("mqttPort", 1883)))
MQTT_TOPIC = os.environ.get("WBMON_MQTT_TOPIC", LOCAL_CONFIG.get("mqttTopic", ""))
MQTT_INTERVAL = 15  # 秒
BRIDGE_TOKEN = os.environ.get("WBMON_BRIDGE_TOKEN", LOCAL_CONFIG.get("bridgeToken", ""))


# 本机 WorkBuddy 会话库：「当前执行任务」与消耗流水的来源
def find_db():
    """定位本机会话库。

    伴侣服务是 SYSTEM 身份常驻的，此时 expanduser("~") 指向
    C:\\Windows\\System32\\config\\systemprofile 而不是真实用户目录，
    直接拼 ~ 会找不到库 -> 任务列表永远为空。这里多处探测兜底。
    """
    cands = []
    env = os.environ.get("WB_DB")
    if env:
        cands.append(env)
    cands.append(os.path.join(os.path.expanduser("~"), ".workbuddy", "workbuddy.db"))
    try:
        drv = os.path.expandvars("%SystemDrive%") or "C:"
        # 注意：os.path.join("C:", "Users") 会拼成 "C:Users"（驱动器相对路径），
        # 必须补上分隔符才是真正的 C:\Users
        if not drv.endswith(("\\", "/")):
            drv += os.sep
        users = os.path.join(drv, "Users")
        for name in sorted(os.listdir(users)):
            cands.append(os.path.join(users, name, ".workbuddy", "workbuddy.db"))
    except Exception:
        pass
    for p in cands:
        if p and os.path.exists(p):
            return p
    return cands[0] if cands else ""


DB = find_db()
# 实测本机会话库出现的取值：completed / working / terminated / error
# 注意：本版本用 working 表示「运行中」，旧映射表里没有它，
# 导致所有进行中的任务都被误判成 idle（任务状态显示不出来的根因）。
STATE_MAP = {
    "completed": "done", "complete": "done", "done": "done", "success": "done",
    "failed": "failed", "error": "failed", "errored": "failed",
    "aborted": "stopped", "stopped": "stopped",
    "terminated": "stopped", "cancelled": "stopped", "canceled": "stopped",
    "running": "running", "active": "running", "in_progress": "running",
    "working": "running", "busy": "running",
    "waiting": "waiting", "pending": "waiting", "queued": "waiting",
    "idle": "idle",
}
ACTIVE_WINDOW_MS = 5 * 60 * 1000  # 5 分钟内有活动 -> 视为运行中

APK = os.path.join(HERE, "out", "wbmon.apk")

_bal_cache = {}  # uid -> {"ts":, "val":, "checked":}

# ---------------- 请求审计（排障用）----------------
# 手机端"显示旧数据"这类问题，只有看到"手机到底请求了什么、拿到什么响应"才能定性。
# 这里记录每个客户端的 IP / UA / App 版本头 / 响应码，并暴露 /access 供随时查看。
# 客户端若不是本机回环地址，即视为"手机已连上电脑端"。
ACCESS_LOG = os.path.join(HERE, "access.log")
ACCESS_KEEP = 400          # 内存环形缓冲条数
LOCAL_IPS = ("127.0.0.1", "::1", "localhost")


def is_request_authorized(client_ip, authorization, token=None):
    """Protect private LAN endpoints while keeping local diagnostics convenient."""
    expected = BRIDGE_TOKEN if token is None else token
    if not expected or client_ip in LOCAL_IPS:
        return True
    return hmac.compare_digest(authorization or "", "Bearer " + expected)


def app_version():
    """从同目录 AndroidManifest.xml 读版本号，用于自检页显示（读不到返回分号）。"""
    try:
        with open(os.path.join(HERE, "AndroidManifest.xml"), encoding="utf-8") as f:
            txt = f.read()
        vc = re.search(r'versionCode="(\d+)"', txt)
        vn = re.search(r'versionName="([^"]+)"', txt)
        return "%s(%s)" % (vn.group(1) if vn else "?", vc.group(1) if vc else "?")
    except Exception:
        return "?"


_apk_meta = {"path": APK, "size": 0, "md5": "", "mtime": 0, "ver": ""}


def apk_meta(force=False):
    """APK 指纹：手机装的是不是这一份，比 md5 即可判定（避免"以为装了新版"）。"""
    try:
        st = os.stat(APK)
        if not force and _apk_meta["size"] == st.st_size and _apk_meta["mtime"] == st.st_mtime:
            return _apk_meta
        h = hashlib.md5()
        with open(APK, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 16), b""):
                h.update(chunk)
        _apk_meta.update({"size": st.st_size, "md5": h.hexdigest(), "mtime": st.st_mtime,
                          "ver": app_version()})
    except Exception as e:
        _apk_meta["ver"] = app_version()
        _apk_meta["md5"] = "读取失败: %s" % e
    return _apk_meta


_access = {"ring": [], "by": {}, "start": 0}


def _log_access(ip, method, path, code, ua, ver):
    t = now_ms()
    if not _access["start"]:
        _access["start"] = t
    ring = _access["ring"]
    ring.append({"t": t, "ip": ip, "m": method, "p": path, "code": code,
                 "ua": ua[:110], "ver": ver or ""})
    if len(ring) > ACCESS_KEEP:
        del ring[:-ACCESS_KEEP]
    b = _access["by"].setdefault(ip, {"n": 0, "first": t, "last": t, "ver": "",
                                      "ua": "", "paths": {}, "bad": 0})
    b["n"] += 1
    b["last"] = t
    if ver:
        b["ver"] = ver
    if ua and not b["ua"]:
        b["ua"] = ua[:110]
    b["paths"][path] = b["paths"].get(path, 0) + 1
    if code != 200:
        b["bad"] += 1
    try:
        with open(ACCESS_LOG, "a", encoding="utf-8") as f:
            f.write("%s %-15s %-4s %-24s %-4s app=%-7s %s\n" % (
                datetime.now().strftime("%m-%d %H:%M:%S"), ip, method, path, code,
                ver or "-", ua[:60]))
    except Exception:
        pass


def access_summary():
    """给自检页/接口用：谁连过、多久一次、跑的是哪个版本的 App。"""
    now = now_ms()
    clients = []
    for ip, b in _access["by"].items():
        span = max(1, b["last"] - b["first"])
        clients.append({
            "ip": ip,
            "isPhone": ip not in LOCAL_IPS,
            "requests": b["n"],
            "bad": b["bad"],
            "appVer": b["ver"] or "(未上报，≤2.7 或浏览器)",
            "ua": b["ua"],
            "firstSeen": datetime.fromtimestamp(b["first"] / 1000).strftime("%m-%d %H:%M:%S"),
            "lastSeen": datetime.fromtimestamp(b["last"] / 1000).strftime("%m-%d %H:%M:%S"),
            "lastAgeSec": int((now - b["last"]) / 1000),
            "avgIntervalSec": int(span / 1000 / max(1, b["n"] - 1)) if b["n"] > 1 else -1,
            "paths": b["paths"],
        })
    clients.sort(key=lambda c: (not c["isPhone"], c["lastAgeSec"]))
    recent = list(reversed(_access["ring"][-25:]))
    return {"ok": True, "now": now, "clients": clients, "recent": recent}


def now_ms():
    return int(time.time() * 1000)


def today_str():
    return date.today().isoformat()


def load_accounts():
    """读凭证池，返回账号列表（含 token）。"""
    try:
        if not os.path.exists(CRED_FILE):
            return []
        j = json.load(open(CRED_FILE, encoding="utf-8"))
        accs = j.get("accounts") or []
        out = []
        for a in accs:
            raw_token = a.get("token")
            # WorkBuddy/WorkDaddy may persist DPAPI/envelope-wrapped values as
            # an object. It is not a usable Bearer token; isolate that account
            # instead of calling .startswith() on a dict and taking down the
            # whole snapshot service.
            token = raw_token if isinstance(raw_token, str) else ""
            if raw_token and not token:
                print("  [凭证池] %s 的 token 不是可用字符串，已隔离" % (a.get("name") or a.get("uid") or "账号"))
            expires_at = a.get("expiresAt") or _jwt_exp_ms(token)
            out.append({
                "id": a.get("id", ""),
                "name": a.get("name") or a.get("nickname") or a.get("phone") or "账号",
                "region": a.get("region") or "CN",
                "token": token,
                "disabled": bool(a.get("disabled", False)),
                "lastCheckin": a.get("lastCheckin") or "",
                "expiresAt": expires_at,
            })
        return out
    except Exception as e:
        print("  [凭证池] 读取失败:", e)
        return []


def _jwt_sub(token):
    """从 Bearer JWT 的 payload 里取 sub（即账号唯一 ID）。"""
    try:
        p = token.split(".")[1]
        p += "=" * (-len(p) % 4)
        return json.loads(base64.urlsafe_b64decode(p)).get("sub") or ""
    except Exception:
        return ""


def _jwt_exp_ms(token):
    """从 JWT payload 读取真实过期时间；旧凭证未保存 expiresAt 时作为可靠回退。"""
    try:
        p = token.split(".")[1]
        p += "=" * (-len(p) % 4)
        exp = json.loads(base64.urlsafe_b64decode(p)).get("exp")
        return int(exp) * 1000 if exp else 0
    except Exception:
        return 0


def _db_user_id():
    """本机 WorkBuddy 会话库里当前登录用户的 user_id。"""
    if not os.path.exists(DB):
        return ""
    con = None
    try:
        con = sqlite3.connect("file:" + DB + "?mode=ro", uri=True, timeout=3)
        cur = con.cursor()
        cur.execute("SELECT DISTINCT user_id FROM sessions WHERE deleted_at IS NULL LIMIT 1")
        r = cur.fetchone()
        return r[0] if r else ""
    except Exception:
        return ""
    finally:
        if con is not None:
            try:
                con.close()
            except Exception:
                pass


def local_account_uid():
    """本机正在使用的账号：把凭证池里每个 token 的 JWT sub 与本地会话库的 user_id 比对。

    命中即视为本机账号——它的"今日消耗"应直接取自本机会话库的 session_usage，
    而不是余额口径（订阅式额度不随单次使用实时扣减，会导致 usedToday 永远为 0）。
    """
    uid = _db_user_id()
    if not uid:
        return None
    for a in load_accounts():
        if _jwt_sub(a.get("token", "")) == uid:
            return a["id"] or a["name"]
    return None


def cloud_balance(token):
    """官方额度。返回 dict 或 None。仅统计 Status==0 生效中的包。"""
    if not token or not token.startswith("eyJ"):
        return None
    try:
        req = urllib.request.Request(
            CLOUD_RES,
            data=b"{}",
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + token,
                "User-Agent": "Mozilla/5.0",  # 缺这个头会 403
                "Accept": "application/json",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=8) as r:
            j = json.loads(r.read().decode("utf-8", "ignore"))
        data = ((j.get("data") or {}).get("Response") or {}).get("Data") or {}
        accts = data.get("Accounts") or []
        if not accts:
            return None
        remain = size = 0.0
        cyc_remain = cyc_size = 0.0
        start = end = ""
        live = 0
        for a in accts:
            if a.get("Status") == 0:  # 仅生效中的包
                remain += float(a.get("CapacityRemain") or 0)
                size += float(a.get("CapacitySize") or 0)
                live += 1
            cyc_remain += float(a.get("CycleCapacityRemain") or 0)
            cyc_size += float(a.get("CycleCapacitySize") or 0)
            if not start:
                start = a.get("CycleStartTime") or ""
                end = a.get("CycleEndTime") or ""
        cyc_used = max(0.0, cyc_size - cyc_remain)
        if size <= 0:  # 生效包没报总量时退回周期口径
            size, remain = cyc_size, cyc_remain
        return {
            "remain": round(remain, 2),
            "size": round(size, 2),
            "cycleSize": round(cyc_size, 2),
            "cycleUsed": round(cyc_used, 2),
            "cycleEnd": end,
            "packages": live,
        }
    except Exception:
        return None


def cloud_balance_cached(uid, token):
    """每账号缓存：成功 120s，失败 30s，避免多账号一秒一刷打爆接口。"""
    c = _bal_cache.get(uid)
    if c is None:
        c = {"ts": 0, "val": None, "checked": False}
        _bal_cache[uid] = c
    ttl = 120000 if c["val"] else 30000
    if c["checked"] and now_ms() - c["ts"] < ttl:
        return c["val"]
    v = cloud_balance(token)
    c["ts"] = now_ms()
    c["checked"] = True
    c["val"] = v
    return v


def apply_daymark(uid, total_balance, cycle_used=None):
    """当日消耗 = 余额下降量 / 周期已用增量（取较大者）。

    2026-09-15 修复：旧公式「当前余额 − 基线」会把签到涨上来的余额误算成消耗
    （签到 +100 被显示成"今日消耗 100"）。
    新逻辑：余额下降才累加消耗；余额上涨视为收益（签到/赠送），仅上移基线、不计数。
    另加一路兜底：部分套餐余额不动、但「周期已用」会涨，用周期口径补上，避免漏报。
    """
    today = today_str()
    try:
        d = json.load(open(DAYMARK, encoding="utf-8"))
    except Exception:
        d = {}
    rec = d.get(uid)
    if not isinstance(rec, dict) or rec.get("date") != today:
        rec = {"date": today, "base": total_balance, "used": 0.0, "cyc": cycle_used}

    base = float(rec.get("base") or total_balance)
    used = float(rec.get("used") or 0.0)

    # ---- 口径 A：余额（下降才算消耗）----
    delta = total_balance - base
    bal_drop = 0.0
    if delta < 0:
        bal_drop = -delta          # 余额下降 = 真实消耗
        base = total_balance
    elif delta > 0:
        base = total_balance       # 余额上涨 = 收益，只上移基线

    # ---- 口径 B：周期已用（余额不动时的兜底）----
    cyc_drop = 0.0
    if cycle_used is not None:
        try:
            cu = float(cycle_used)
        except Exception:
            cu = None
        if cu is not None:
            old = rec.get("cyc")
            try:
                cd = cu - float(old) if old is not None else 0.0
            except Exception:
                cd = 0.0
            if cd > 0:
                cyc_drop = cd      # 周期已用上涨 = 真实消耗
            # cd < 0 表示额度周期重置，此处不计数，只更新基线
            rec["cyc"] = round(cu, 2)

    used += max(bal_drop, cyc_drop)   # 取较大者，避免两条口径重复累加

    rec["base"] = round(base, 2)
    rec["used"] = round(used, 2)
    try:
        d[uid] = rec
        json.dump(d, open(DAYMARK, "w", encoding="utf-8"))
    except Exception:
        pass
    return round(max(0.0, used), 2)


def read_tasks():
    """读取本机 WorkBuddy 会话库，返回任务列表 / 流水 / 统计。

    这是「当前执行任务」的数据来源。多账号改造时这段被整个丢掉，
    导致手机和小组件不再显示任务状态，这里重新补回。
    任何异常一律降级为空，绝不影响额度与签到主流程。
    """
    out = {"tasks": [], "flow": [], "running": 0, "waiting": 0,
           "totalUsed": 0.0, "usedMonth": 0.0, "usedToday": 0.0, "err": ""}
    if not os.path.exists(DB):
        out["err"] = "DB 不存在: %s" % DB
        return out
    con = None
    try:
        con = sqlite3.connect("file:" + DB + "?mode=ro", uri=True, timeout=3)
        con.row_factory = sqlite3.Row
        cur = con.cursor()
        now = now_ms()
        nd = datetime.now()
        month_start = int(time.mktime(datetime(nd.year, nd.month, 1).timetuple()) * 1000)
        today0 = int(time.mktime(datetime(nd.year, nd.month, nd.day).timetuple()) * 1000)

        try:
            cur.execute(
                "SELECT id,title,status,created_at,updated_at,last_activity_at "
                "FROM sessions WHERE deleted_at IS NULL ORDER BY updated_at DESC LIMIT 30"
            )
            rows = cur.fetchall()
        except Exception:
            rows = []

        # 会话积分 = credit_json 各项之和（used 字段是 token 数，不是积分）。
        # 关键：credit_json 是「自会话创建起的累计积分」，数据库每一行都是当时的累计快照，
        # 不是某一笔增量。因此「今日/本月/累计」必须用 最新累计 − 当日(月)起点基线 做差，
        # 绝不能直接把每行相加（累计快照相加会严重虚高 / 重复计数）。
        # 跨午夜的会话：today 基线 = 今日 00:00 之前最后一行的累计值（已含昨日部分），
        # today 增量 = 最新累计 − 该基线，只算今日真实消耗。
        # 不再加 LIMIT，否则多账号/多会话时行被截断会漏算。
        sess_series = {}   # sid -> [(updated_at_ms, 累计积分), ...] 时间升序
        usage_cost = {}    # sid -> 最新累计积分（任务卡片显示用）
        try:
            cur.execute("SELECT session_id, credit_json, updated_at FROM session_usage "
                        "ORDER BY updated_at ASC")
            for r in cur.fetchall():
                try:
                    cj = json.loads(r["credit_json"] or "{}")
                    s = 0.0
                    if isinstance(cj, dict):
                        for v in cj.values():
                            try:
                                s += float(v)
                            except Exception:
                                pass
                    sid = str(r["session_id"])
                    ts = int(r["updated_at"] or 0)
                    cum = round(s, 2)
                    sess_series.setdefault(sid, []).append((ts, cum))
                    usage_cost[sid] = cum   # 升序遍历，最后赋值即最新累计
                except Exception:
                    pass
        except Exception:
            pass

        for r in rows:
            sid = str(r["id"])
            status = (r["status"] or "").lower()
            last = r["last_activity_at"] or r["updated_at"] or 0
            state = STATE_MAP.get(status, "idle")
            if state == "idle" and now - last < ACTIVE_WINDOW_MS:
                state = "running"
            if status == "" and now - last < ACTIVE_WINDOW_MS:
                state = "running"

            ts = r["updated_at"] or 0
            if state == "running":
                age = max(0, now - last) / ACTIVE_WINDOW_MS
                progress = max(5, min(95, int(100 - age * 60)))
            elif state in ("done", "failed", "stopped"):
                progress = 100
            else:
                progress = 0

            out["tasks"].append({
                "id": sid,
                "title": r["title"] or "(未命名会话)",
                "state": state,
                "progress": progress,
                "meta": datetime.fromtimestamp(ts / 1000).strftime("%m-%d %H:%M") if ts else "",
                "cost": round(usage_cost.get(sid, 0.0), 2),
                "lastAgeMs": max(0, now - last),
            })

        # ---- 流水按 session 归属到账号（数据驱动，不写死任何具体账号）----
        # 归属推导链：session_usage.session_id -> sessions.user_id -> 凭证池账号名(JWT sub 匹配)。
        # 找不到对应用户(孤儿/清理过的会话)时，回退到「本机登录账号」(local_account_uid)。
        # 未来新增账号只要进了 credentials.json，归属会自动生效，无需改任何代码。
        _accs = load_accounts()
        _uid_to_name = {}
        for a in _accs:
            sub = _jwt_sub(a.get("token", ""))
            if sub:
                _uid_to_name[sub] = a["name"]
        _db_uid = _db_user_id()
        _local_uid = None
        for a in _accs:
            if _jwt_sub(a.get("token", "")) == _db_uid and _db_uid:
                _local_uid = a["id"] or a["name"]
                break
        _sid_user = {}
        try:
            cur.execute("SELECT id, user_id FROM sessions WHERE deleted_at IS NULL")
            for r in cur.fetchall():
                _sid_user[str(r["id"])] = r["user_id"] or ""
        except Exception:
            pass

        # ---- 消耗口径（今日/本月/累计）：按会话对 00:00 / 月初 做差 ----
        # 每个会话取「起点时间之前的最后累计值」为基线，最新累计 − 基线 = 该口径真实消耗。
        # 跨午夜会话：today 基线 = 今日 00:00 之前最后一行的累计（已含昨日部分），
        #   今日增量 = 最新累计 − 该基线；本月基线 = 本月 1 号 00:00 之前最后一行的累计。
        # 负值（退款 / 跨周期重置）按 0 计，避免虚高。
        titles = {str(r["id"]): (r["title"] or "(未命名会话)") for r in rows}
        items = []  # 最近消耗流水（展示用，按会话）
        for sid, series in sess_series.items():
            if not series:
                continue
            latest = series[-1][1]
            base_today = 0.0
            base_month = 0.0
            for ts, cum in series:
                if ts < today0:
                    base_today = cum
                else:
                    break
            for ts, cum in series:
                if ts < month_start:
                    base_month = cum
                else:
                    break
            out["usedToday"] += max(0.0, latest - base_today)
            out["usedMonth"] += max(0.0, latest - base_month)
            out["totalUsed"] += latest
            # 流水：该会话最新累计（带账号归属），展示为「已用」
            _uid = _sid_user.get(sid, "")
            _acc = _uid_to_name.get(_uid) if _uid else None
            if not _acc:
                _acc = _local_uid
            items.append({
                "ts": series[-1][0],
                "name": (titles.get(sid, "会话"))[:18],
                "delta": -round(latest, 2),
                "account": _acc or "",
            })
        items.sort(key=lambda x: x["ts"], reverse=True)
        out["flow"] = items[:25]
        out["running"] = sum(1 for t in out["tasks"] if t["state"] == "running")
        out["waiting"] = sum(1 for t in out["tasks"] if t["state"] == "waiting")
        out["usedToday"] = round(out["usedToday"], 2)
        out["totalUsed"] = round(out["totalUsed"], 2)
        out["usedMonth"] = round(out["usedMonth"], 2)
    except Exception as e:
        out["err"] = "%s: %s" % (type(e).__name__, e)
    finally:
        if con is not None:
            try:
                con.close()
            except Exception:
                pass
    return out


_task_cache = {"ts": 0, "val": None}


def read_tasks_cached():
    """会话库不必每次都读，5 秒缓存，避免频繁 IO。"""
    if _task_cache["val"] is not None and now_ms() - _task_cache["ts"] < 5000:
        return _task_cache["val"]
    v = read_tasks()
    _task_cache["ts"] = now_ms()
    _task_cache["val"] = v
    return v


def build_snapshot(force=False):
    accounts_raw = load_accounts()
    acc_out = []
    total_balance = 0.0
    total_used_today = 0.0
    active_count = 0
    signed_count = 0
    any_cloud = False

    for a in accounts_raw:
        uid = a["id"] or a["name"]
        entry = {
            "id": a["id"],
            "name": a["name"],
            "region": a["region"],
            "balance": 0.0,
            "balanceIsTotal": False,
            "usedToday": 0.0,
            "cycleSize": 0.0,
            "cycleUsed": 0.0,
            "cycleEnd": "",
            "signedToday": (a["lastCheckin"] == today_str()),
            "disabled": a["disabled"],
            "expired": (a["expiresAt"] and a["expiresAt"] < now_ms()),
            "error": "",
            "packages": 0,
            # 凭证剩余天数（-1 = 未知）；用于 App 端"凭证临期"预警
            "expireDays": (int((a["expiresAt"] - now_ms()) // 86400000)
                           if a.get("expiresAt") else -1),
        }
        if a["disabled"]:
            entry["error"] = "已禁用"
            acc_out.append(entry)
            continue
        if not a["token"]:
            entry["error"] = "无凭证"
            acc_out.append(entry)
            continue
        cb = cloud_balance_cached(uid, a["token"])
        if cb is None:
            entry["error"] = "额度获取失败"
            acc_out.append(entry)
            continue
        any_cloud = True
        entry["balance"] = cb["remain"]
        entry["cycleSize"] = cb["cycleSize"]
        entry["cycleUsed"] = cb["cycleUsed"]
        entry["cycleEnd"] = cb["cycleEnd"]
        entry["packages"] = cb["packages"]
        entry["usedToday"] = apply_daymark(uid, cb["remain"], cb.get("cycleUsed"))
        total_balance += cb["remain"]
        active_count += 1
        if entry["signedToday"]:
            signed_count += 1
        acc_out.append(entry)

    tk = read_tasks_cached()
    # 本机真实消耗归属：session_usage 中“今日”的增量直接算作当前登录账号的今日消耗。
    # 订阅式额度不随单次使用实时扣减余额，光看余额口径 usedToday 会恒为 0，这里用会话库实测值兜底。
    local_uid = local_account_uid()
    if local_uid:
        lt = tk.get("usedToday", 0.0)
        for e in acc_out:
            match = (e["id"] == local_uid) or (not e["id"] and e["name"] == local_uid)
            if match and lt > 0:
                # 本机账号直接采用会话库实测的今日消耗(session_usage 总和)。
                # 它是该账号在本机消费的权威来源：订阅式额度余额不随单次使用实时扣减，
                # 光看余额/周期口径会恒为 0；而周期口径(cycUsed)与本机积分口径单位不同，
                # 用 max(daymark, lt) 有可能虚高。故本机账号一律以 lt 为准，
                # 仅当 lt 为 0 时保留 daymark 的余额/周期口径(订阅式此时也恰为 0，不会虚高)。
                e["usedToday"] = round(lt, 2)
    total_used_today = round(sum(e["usedToday"] for e in acc_out), 2)
    payload = {
        "ok": True,
        "ts": now_ms(),
        "online": True,
        "accounts": acc_out,
        "totalBalance": round(total_balance, 2),
        "totalUsedToday": round(total_used_today, 2),
        "accountCount": len(acc_out),
        "activeCount": active_count,
        "signedCount": signed_count,
        "cloud": any_cloud,
        # ---- 当前执行任务 / 流水（来自本机会话库）----
        "tasks": tk["tasks"],
        "running": tk["running"],
        "waiting": tk["waiting"],
        "flow": tk["flow"],
        "totalUsed": tk["totalUsed"],
        "usedMonth": tk["usedMonth"],
        "taskErr": tk.get("err", ""),  # 空=正常；非空便于排障
    }
    return payload


# ---------------- MQTT 发布（手写 3.1.1，零依赖） ----------------

def _mqtt_rl(n):
    out = bytearray()
    while True:
        d = n % 128
        n //= 128
        if n > 0:
            d |= 0x80
        out.append(d)
        if n <= 0:
            break
    return bytes(out)


def _mqtt_frame(t, body):
    return bytes([t]) + _mqtt_rl(len(body)) + body


def _mqtt_str(s):
    b = s.encode("utf-8")
    return bytes([len(b) >> 8, len(b) & 0xFF]) + b


def mqtt_publish(topic, payload, host=MQTT_HOST, port=MQTT_PORT):
    try:
        sk = socket.create_connection((host, port), timeout=6)
        sk.settimeout(8)
        vh = b"\x00\x04MQTT" + bytes([4]) + bytes([2]) + b"\x00\x3c"
        sk.sendall(_mqtt_frame(0x10, vh + _mqtt_str("wbmon-multi-%d" % os.getpid())))
        sk.recv(4)  # CONNACK
        sk.sendall(_mqtt_frame(0x30 | 0x01, _mqtt_str(topic) + payload.encode("utf-8")))
        sk.close()
        return True
    except Exception:
        return False


def _mqtt_read(sk):
    """读一个 MQTT 报文，返回 (类型, body)；连接结束返回 (None, None)。"""
    h = sk.recv(1)
    if not h:
        return None, None
    mult, val = 1, 0
    while True:
        b = sk.recv(1)
        if not b:
            return None, None
        b = b[0]
        val += (b & 127) * mult
        mult *= 128
        if not (b & 128):
            break
    body = b""
    while len(body) < val:
        chunk = sk.recv(val - len(body))
        if not chunk:
            break
        body += chunk
    return h[0] >> 4, body


_phone = {"ver": "", "ts": 0, "ch": ""}


def mqtt_sub_loop():
    """订阅手机上报主题：记录手机 App 版本 / 最后在线时间 / 走的通道。

    为什么要这条反向通道：电脑端无法主动反问手机装了哪个 APK，
    "明明装了新版却没变化"只能靠人反复确认。手机每次刷新后回报一次，
    排障就从"猜"变成"看"，也顺带能确认手机到底有没有连上过电脑端。
    独立线程 + 全程异常隔离，失败绝不影响额度与快照主流程。
    """
    if not MQTT_TOPIC:
        return
    topic = MQTT_TOPIC + "/app"
    while True:
        sk = None
        try:
            sk = socket.create_connection((MQTT_HOST, MQTT_PORT), timeout=10)
            sk.settimeout(30)
            vh = b"\x00\x04MQTT" + bytes([4]) + bytes([2]) + b"\x00\x3c"
            sk.sendall(_mqtt_frame(0x10, vh + _mqtt_str("wbmon-bridge-sub-%d" % os.getpid())))
            if _mqtt_read(sk)[0] != 2:
                raise IOError("CONNACK 未通过")
            sk.sendall(_mqtt_frame(0x82, b"\x00\x01" + _mqtt_str(topic) + b"\x00"))
            print("  [MQTT] 已订阅手机上报主题: %s" % topic)
            last_ping = time.time()
            while True:
                try:
                    t, body = _mqtt_read(sk)
                except socket.timeout:
                    if time.time() - last_ping > 45:
                        sk.sendall(_mqtt_frame(0xC0, b""))   # PINGREQ 保活
                        last_ping = time.time()
                    continue
                if t is None:
                    raise IOError("连接结束")
                if t == 3 and body:
                    tl = (body[0] << 8) | body[1]
                    try:
                        j = json.loads(body[2 + tl:].decode("utf-8", "ignore"))
                    except Exception:
                        continue
                    _phone["ver"] = str(j.get("ver") or "")
                    _phone["ts"] = int(j.get("ts") or now_ms())
                    _phone["ch"] = str(j.get("ch") or "")
                    print("  [手机上报] App v%s · 通道 %s" % (_phone["ver"], _phone["ch"]))
        except Exception as e:
            print("  [MQTT订阅] 断开(%s: %s)，10 秒后重连" % (type(e).__name__, e))
        finally:
            try:
                if sk:
                    sk.close()
            except Exception:
                pass
        time.sleep(10)


def phone_summary():
    """手机侧的权威状态：自报版本 + 最后在线时间（与 access_summary 互补）。"""
    if not _phone["ts"]:
        return {"seen": False, "ver": "", "lastAgeSec": -1, "channel": ""}
    return {"seen": True, "ver": _phone["ver"],
            "lastAgeSec": int((now_ms() - _phone["ts"]) / 1000),
            "channel": _phone["ch"],
            "lastSeen": datetime.fromtimestamp(_phone["ts"] / 1000).strftime("%m-%d %H:%M:%S")}


def mqtt_loop():
    if not MQTT_TOPIC:
        print("  [MQTT] 未启用；仅提供本机/局域网 HTTP")
        return
    while True:
        try:
            payload = json.dumps(build_snapshot(force=True), ensure_ascii=False)
            if mqtt_publish(MQTT_TOPIC, payload):
                print("  [MQTT] 多账号快照已发布 (%d 字节)" % len(payload))
            else:
                print("  [MQTT] 发布失败")
        except Exception as e:
            print("  [MQTT] 异常:", e)
        time.sleep(MQTT_INTERVAL)


HOME_PAGE = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>work buddy工具</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#F6F8FB;color:#0F172A;
font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;padding:22px}
h1{font-size:20px;margin:0 0 4px}.sub{color:#64748B;font-size:13px;margin-bottom:20px}
a.btn{display:block;background:#059669;color:#FFFFFF;text-decoration:none;
text-align:center;padding:16px;border-radius:14px;font-weight:700;font-size:16px;margin-bottom:14px}
.card{background:#FFFFFF;border:1px solid #E2E8F0;border-radius:16px;padding:16px;margin-bottom:14px}
.k{color:#64748B;font-size:12px;margin-bottom:6px}
.v{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:14px;color:#047857;word-break:break-all}
.ok{color:#059669;font-weight:700}.warn{color:#B45309;font-weight:700}
ol{padding-left:20px;line-height:1.9;font-size:14px}
code{background:#EEF2F7;padding:2px 6px;border-radius:5px;font-size:13px;color:#047857}
</style></head><body>
<h1>work buddy工具 · 伴侣服务</h1>
<div class="sub">电脑端已就绪 · 手机同一 WiFi 打开本页即可下载安装</div>
<a class="btn" href="/app">下载安装最新版 APK（%(appver)s）</a>
<div class="card"><div class="k">手机连接状态（决定手机能不能看到实时数据）</div>
<div class="v">%(phone)s</div></div>
<div class="card"><div class="k">当前数据</div>
<div class="v">账号 %(cnt)s 个 · 总余额 %(bal)s · 今日消耗 %(today)s</div></div>
<div class="card"><div class="k">App 里可填的伴侣服务地址</div><div class="v">%(base)s</div></div>
<div class="card"><div class="k">安装包校验（装完可与手机显示的版本号对照）</div>
<div class="v">版本 %(appver)s · 大小 %(size)s 字节<br>MD5 %(md5)s</div></div>
<div class="card"><div class="k">使用步骤</div><ol>
<li>点上面绿色按钮下载并安装（需允许"未知来源"）</li>
<li>打开 App 自动连（无需填地址，走云端会合点）</li>
<li>桌面长按 → 小组件 → 找到 work buddy工具 拖回桌面（旧占位请先删除）</li>
</ol></div>
</body></html>"""


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _audit(self, code):
        """记录每一次响应，含客户端 IP / UA / App 自报版本，排障靠它。"""
        try:
            _log_access(self.client_address[0], self.command, self.path.split("?")[0], code,
                        self.headers.get("User-Agent") or "",
                        self.headers.get("X-Wbmon-Ver") or "")
        except Exception:
            pass

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        b = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(b)
        except Exception:
            pass
        self._audit(code)

    def _phone_line(self):
        """一句话回答：手机现在能不能连上电脑端、跑的是哪个版本。"""
        try:
            ps = phone_summary()
            cs = [c for c in access_summary()["clients"] if c["isPhone"]]
        except Exception:
            ps, cs = {"seen": False}, []
        parts = []
        if ps.get("seen"):
            age = ps.get("lastAgeSec", -1)
            when = ("%d 秒前" % age) if age < 120 else \
                   (("%d 分钟前" % (age // 60)) if age < 7200 else "%d 小时前" % (age // 3600))
            parts.append("<span class='%s'>手机 App v%s</span> · 最后在线 %s · 通道 %s"
                         % ("ok" if age <= 900 else "warn", ps.get("ver") or "?",
                            when, ps.get("channel") or "-"))
        else:
            parts.append("<span class='warn'>尚未收到手机上报</span>"
                         "（v2.8 起手机每次刷新都会自报版本）")
        if cs:
            c = cs[0]
            parts.append("局域网请求 %d 次 · 最后一次 %d 秒前 · App 自报版本 %s"
                         % (c["requests"], c["lastAgeSec"], c["appVer"]))
        else:
            parts.append("局域网请求 0 次（手机不在家，或尚未安装/打开新版 App）")
        return "<br>".join(parts)

    def do_GET(self):
        path = self.path.split("?")[0]
        public_paths = ("/health", "/app", "/app.apk", "/wbmon.apk")
        client_ip = self.client_address[0] if self.client_address else ""
        if path not in public_paths and not is_request_authorized(
                client_ip, self.headers.get("Authorization", "")):
            self._send(401, json.dumps({"ok": False, "error": "unauthorized"}))
            return
        if path in ("/", "/index.html"):
            try:
                s = build_snapshot()
                host = self.headers.get("Host") or ("127.0.0.1:%d" % self.server.server_address[1])
                m = apk_meta()
                html = HOME_PAGE % {
                    "base": "http://" + host,
                    "cnt": s.get("accountCount", 0),
                    "bal": s.get("totalBalance"),
                    "today": s.get("totalUsedToday"),
                    "appver": m.get("ver") or "?",
                    "size": m.get("size") or "?",
                    "md5": m.get("md5") or "?",
                    "phone": self._phone_line(),
                }
            except Exception:
                host = self.headers.get("Host") or "127.0.0.1:8791"
                html = HOME_PAGE % {"base": "http://" + host, "cnt": "-", "bal": "-",
                                    "today": "-", "appver": app_version(), "size": "-",
                                    "md5": "-", "phone": "自检读取失败"}
            self._send(200, html, "text/html; charset=utf-8")
        elif path == "/health":
            self._send(200, json.dumps({"ok": True, "service": "wb-multi-bridge"}))
        elif path == "/snapshot":
            try:
                self._send(200, json.dumps(build_snapshot(), ensure_ascii=False))
            except Exception as e:
                self._send(500, json.dumps({"ok": False, "error": str(e)}))
        elif path == "/access":
            self._send(200, json.dumps(access_summary(), ensure_ascii=False))
        elif path == "/status":
            try:
                s = build_snapshot()
                m = apk_meta(force=True)
                tk = read_tasks_cached()
                self._send(200, json.dumps({
                    "ok": True,
                    "apk": {"ver": m.get("ver"), "size": m.get("size"), "md5": m.get("md5")},
                    "db": DB,
                    "dbExists": os.path.exists(DB),
                    "localAccount": local_account_uid(),
                    "snapshot": {"ts": s["ts"], "ageSec": int((now_ms() - s["ts"]) / 1000),
                                 "accounts": s["accountCount"], "active": s["activeCount"],
                                 "signed": s["signedCount"], "running": s["running"],
                                 "totalBalance": s["totalBalance"],
                                 "totalUsedToday": s["totalUsedToday"],
                                 "usedMonth": s["usedMonth"],
                                 "tasks": [t["title"] for t in s["tasks"][:3]]},
                    "tasksErr": tk.get("err", ""),
                    "phone": phone_summary(),
                    "clients": access_summary()["clients"],
                }, ensure_ascii=False))
            except Exception as e:
                self._send(500, json.dumps({"ok": False, "error": str(e)}))
        elif path in ("/app", "/app.apk", "/wbmon.apk"):
            try:
                with open(APK, "rb") as f:
                    data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Content-Disposition", 'attachment; filename="wbmon.apk"')
                self.end_headers()
                self.wfile.write(data)
                self._audit(200)
            except Exception as e:
                self._send(500, json.dumps({"ok": False, "error": str(e)}))
        else:
            self._send(404, json.dumps({"ok": False}))

    def log_message(self, *a):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8791)
    ap.add_argument("--bind", default="0.0.0.0")
    args = ap.parse_args()

    # 审计日志轮转：只保留最近一段，避免长期常驻把磁盘写满
    try:
        if os.path.exists(ACCESS_LOG) and os.path.getsize(ACCESS_LOG) > 1024 * 1024:
            with open(ACCESS_LOG, encoding="utf-8", errors="ignore") as f:
                tail = f.readlines()[-800:]
            with open(ACCESS_LOG, "w", encoding="utf-8") as f:
                f.writelines(tail)
    except Exception:
        pass

    srv = ThreadingHTTPServer((args.bind, args.port), H)
    print("WB 多账号 bridge listening on http://%s:%d" % (args.bind, args.port))
    print("  /snapshot  手机 App 读这个")
    print("  /health    健康检查")
    print("  /status    端到端自检（APK 指纹 / 数据库 / 掉线判定 / 客户端清单）")
    print("  /access    客户端请求审计（谁连过、多久一次、App 版本）")
    print("  会话库: %s (%s)" % (DB, "存在" if os.path.exists(DB) else "不存在!"))
    print("  APK   : %s -> %s" % (APK, apk_meta(force=True).get("md5", "")[:16]))
    if MQTT_TOPIC:
        print("  MQTT 会合点: %s:%d topic=%s (每 %d 秒发布)" %
              (MQTT_HOST, MQTT_PORT, MQTT_TOPIC, MQTT_INTERVAL))
    else:
        print("  MQTT 会合点: 未启用")
    print("  局域网鉴权: %s" % ("已启用" if BRIDGE_TOKEN else "未启用"))
    print("按 Ctrl+C 停止")

    t = threading.Thread(target=mqtt_loop, daemon=True)
    t.start()
    # 反向通道：订阅手机上报（App 版本 / 最后在线 / 通道），失败不影响主流程
    threading.Thread(target=mqtt_sub_loop, daemon=True).start()

    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")


if __name__ == "__main__":
    main()
