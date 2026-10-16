# -*- coding: utf-8 -*-
"""功能验证：今日消耗少算修复（credit_json 累计快照 + 跨午夜会话）。
复现真实场景：迁移会话在昨日创建，credit_json 为累计快照；
今日最新累计 102.94，但今日真实增量应为 最新累计 - 午夜基线(=1.0) = 101.94，
而不应把累计快照直接相加（旧逻辑虚高）或只报桥启动后的增量(=26.8)。
"""
import os, sys, time, json, sqlite3, tempfile, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bridge_multi as bm

tmp = tempfile.mkdtemp()
db = os.path.join(tmp, "workbuddy.db")
con = sqlite3.connect(db)
con.execute("CREATE TABLE sessions(id TEXT,title TEXT,status TEXT,created_at INTEGER,updated_at INTEGER,last_activity_at INTEGER,user_id TEXT,deleted_at INTEGER)")
con.execute("CREATE TABLE session_usage(session_id TEXT,used INTEGER,size INTEGER,updated_at INTEGER,credit_json TEXT)")

nd = datetime.datetime.now()
today0 = datetime.datetime(nd.year, nd.month, nd.day)
yest = today0 - datetime.timedelta(days=1)
def ms(dt):
    return int(time.mktime(dt.timetuple()) * 1000)

t_yest23 = ms(yest + datetime.timedelta(hours=23))
t_t08 = ms(today0 + datetime.timedelta(hours=8))
t_t10 = ms(today0 + datetime.timedelta(hours=10))
t_t0136 = ms(today0 + datetime.timedelta(hours=1, minutes=36))

# 迁移会话：昨日创建，跨午夜；累计快照 1.0(昨日) -> 50.0(今08) -> 102.94(今10)
con.execute("INSERT INTO sessions VALUES('s1','示例任务','working',?,?,?,?,NULL)", (ms(yest + datetime.timedelta(hours=21)), t_t10, t_t10, 'u_local'))
con.execute("INSERT INTO session_usage VALUES('s1',0,0,?,?)", (t_yest23, json.dumps({"c1": 1.0})))
con.execute("INSERT INTO session_usage VALUES('s1',0,0,?,?)", (t_t08, json.dumps({"c1": 50.0})))
con.execute("INSERT INTO session_usage VALUES('s1',0,0,?,?)", (t_t10, json.dumps({"c1": 102.94})))

# 领取会话：今日创建，累计 0.0（无消耗）
con.execute("INSERT INTO sessions VALUES('s2','领取美团优惠券','completed',?,?,?,?,NULL)", (t_t0136, t_t0136, t_t0136, 'u_local'))
con.execute("INSERT INTO session_usage VALUES('s2',0,0,?,?)", (t_t0136, json.dumps({"c2": 0.0})))
con.commit()
con.close()

bm.DB = db
out = bm.read_tasks()
print("usedToday  =", out["usedToday"])
print("usedMonth  =", out["usedMonth"])
print("totalUsed  =", out["totalUsed"])
print("flow       =", [(f["name"], f["delta"], f["account"]) for f in out["flow"]])

# 断言：今日真实增量 = 102.94 - 1.0 = 101.94
ok_today = abs(out["usedToday"] - 101.94) < 0.01
ok_not_low = out["usedToday"] > 50.0                 # 必须明显大于旧值 26.8
ok_not_high = abs(out["totalUsed"] - 102.94) < 0.01  # 累计 = 最新累计之和，不应虚高到 152.94
print("\nusedToday~101.94 :", "PASS" if ok_today else "FAIL")
print("usedToday>50(非26.8):", "PASS" if ok_not_low else "FAIL")
print("totalUsed~102.94(未虚高):", "PASS" if ok_not_high else "FAIL")
sys.exit(0 if (ok_today and ok_not_low and ok_not_high) else 1)
