// wbmon-cloud —— 云端 WorkBuddy 自动化：每日积分领取 + 猫猫旅行循环 + 令牌自我续期
// 零依赖，Node >= 18。监听 PORT（环境变量），绑定 0.0.0.0。
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT || 8080);
const TICK_MINUTES = Number(process.env.TICK_MINUTES || 20);
const REFRESH_CHECK_MINUTES = 360;       // 每 6 小时检查一次续期
const REFRESH_THRESHOLD_MS = 7 * 86400000; // access 剩 7 天内就刷新
const HERE = __dirname;
const CFG_PATH = path.join(HERE, 'config.json');
const STATE_PATH = path.join(HERE, 'state.json');

const HOST_WB = 'https://www.workbuddy.cn';
const HOST_CB = 'https://www.codebuddy.cn';

// ---------- 配置与状态 ----------
let CFG = null;
function loadCfg() { CFG = JSON.parse(fs.readFileSync(CFG_PATH, 'utf8')); }
function saveCfg() {
  try { fs.writeFileSync(CFG_PATH, JSON.stringify(CFG, null, 1)); }
  catch (e) { log('config保存失败(令牌可能无法持久化): ' + e.message); }
}
function loadState() { try { return JSON.parse(fs.readFileSync(STATE_PATH, 'utf8')); } catch { return {}; } }
function saveState(st) { try { fs.writeFileSync(STATE_PATH, JSON.stringify(st, null, 1)); } catch (e) { log('state保存失败: ' + e.message); } }

// ---------- 日志 ----------
const LOG_RING = [];
function log(msg) {
  const line = '[' + new Date().toISOString() + '] ' + msg;
  LOG_RING.push(line);
  if (LOG_RING.length > 600) LOG_RING.splice(0, 200);
  try { fs.appendFileSync(path.join(HERE, 'run.log'), line + '\n'); } catch {}
  console.log(line);
}

// ---------- HTTP 客户端 ----------
async function reqJson(host, urlPath, token, method, body, timeoutMs, extraHeaders) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs || 15000);
  try {
    const headers = {
      'accept': 'application/json, text/plain, */*',
      'content-type': 'application/json',
      'x-codebuddy-request': '1',
      'x-client-platform': 'web',
      'origin': host, 'referer': host + '/',
      'user-agent': 'Mozilla/5.0',
    };
    if (token) headers.authorization = 'Bearer ' + token;
    Object.assign(headers, extraHeaders || {});
    const opt = { method: method || 'GET', headers, signal: ctrl.signal };
    if (body !== undefined) opt.body = JSON.stringify(body || {});
    const r = await fetch(host + urlPath, opt);
    const text = await r.text();
    let j = null; try { j = JSON.parse(text); } catch {}
    return { status: r.status, json: j, raw: text };
  } catch (e) {
    return { status: -1, json: null, raw: String(e && e.message || e) };
  } finally { clearTimeout(t); }
}

function daysLeft(ms) { return ms ? Math.round((ms - Date.now()) / 8640000) / 10 : null; }

// ---------- 云数据库（BaaS）接入：运行历史落库（可选） ----------
// 从 config.json 的 cloud 字段（或环境变量）读取，缺失则自动跳过（不影响主流程）。
// publicConfig 两项（endpoint / publishableKey）均为可公开值；密钥与令牌永不落库。
// 故障隔离：落库失败只记日志，绝不阻断签到/旅行主流程。
let CLOUD_DB = null;
function initCloudDb() {
  try {
    const c = (CFG && CFG.cloud) || {};
    const endpoint = c.endpoint || process.env.CLOUD_ENDPOINT || '';
    const publishableKey = c.publishableKey || process.env.CLOUD_PUBLISHABLE_KEY || '';
    if (!endpoint || !publishableKey) return;
    const { createWorkBuddyCloud } = require('@tencent-ai/workbuddy-cloud-sdk');
    CLOUD_DB = createWorkBuddyCloud({ endpoint, publishableKey });
  } catch (e) { CLOUD_DB = null; }
}

async function dbEvent(account, kind, detail, extra) {
  if (!CLOUD_DB) return;
  try {
    const r = await CLOUD_DB.database.from('wbmon_events')
      .insert({ account, kind, detail, extra: extra || null }).select();
    if (r.error) log('云数据库写入失败: ' + JSON.stringify(r.error).slice(0, 120));
  } catch (e) {
    log('云数据库异常: ' + (e && e.message || e));
  }
}

// ---------- 令牌自我续期 ----------
async function refreshAccountToken(a) {
  if (!a.refreshToken) return false;
  const r = await reqJson(HOST_WB, '/v2/plugin/auth/token/refresh', a.accessToken, 'POST', {}, 20000, {
    'X-Refresh-Token': a.refreshToken,
  });
  const j = r.json || {};
  const d = j.data || {};
  const at = d.accessToken || d.access_token;
  if (r.status !== 200 || (j.code !== 0 && j.code !== 200) || !at) {
    log(`⛔ ${a.name} 令牌刷新失败 HTTP=${r.status} code=${j.code} ${String(j.msg || j.message || '').slice(0, 80)}`);
    return false;
  }
  a.accessToken = at;
  const rt = d.refreshToken || d.refresh_token;
  if (rt) a.refreshToken = rt;
  a.expiresAt = d.expiresAt ?? d.expires_at ?? (Date.now() + (d.expiresIn ?? d.expires_in ?? 2592000) * 1000);
  a.refreshExpiresAt = d.refreshExpiresAt ?? d.refresh_expires_at ?? (Date.now() + (d.refreshExpiresIn ?? d.refresh_expires_in ?? 5184000) * 1000);
  saveCfg(); // 轮换出的新 refresh 令牌必须立刻持久化
  log(`🔁 ${a.name} 令牌已续期：access剩${daysLeft(a.expiresAt)}天 refresh剩${daysLeft(a.refreshExpiresAt)}天`);
  return true;
}

async function ensureTokens() {
  for (const a of CFG.accounts) {
    const left = (a.expiresAt || 0) - Date.now();
    if (left < REFRESH_THRESHOLD_MS) {
      try { await refreshAccountToken(a); } catch (e) { log(a.name + ' 续期异常: ' + e.message); }
    }
  }
}

// ---------- 日期（东八区） ----------
function cnDateStr() {
  return new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10);
}

// ---------- 每日积分领取（签到） ----------
async function doCheckin(acct, st) {
  const a = st.accounts[acct.name] || (st.accounts[acct.name] = { checkin: {} });
  const c = a.checkin || (a.checkin = {});
  const today = cnDateStr();
  if (c.day === today && c.done) return '已领(跳过)';
  const s1 = await reqJson(HOST_CB, '/v2/billing/meter/checkin-activity-status', acct.accessToken, 'POST', {});
  const d1 = (s1.json && s1.json.data) || {};
  if (s1.status !== 200 || !s1.json || s1.json.code !== 0) {
    c.day = today; c.done = false; c.note = '查询失败';
    return '查询失败';
  }
  if (!d1.active) { c.day = today; c.done = true; c.note = '活动未开启'; return '活动未开启'; }
  if (d1.today_checked_in === true) {
    c.day = today; c.done = true; c.note = '先前已领';
    c.streak = d1.streak_days; c.credit = d1.today_credit;
    return '先前已领';
  }
  const s2 = await reqJson(HOST_CB, '/v2/billing/meter/daily-checkin', acct.accessToken, 'POST', {});
  const code2 = s2.json && s2.json.code;
  if (code2 === 10001) { c.day = today; c.done = true; c.note = '已领(10001)'; return '已领(10001)'; }
  if (s2.status === 200 && code2 === 0) {
    c.day = today; c.done = true; c.note = '云端领取成功';
    c.streak = d1.streak_days; c.credit = d1.today_credit;
    log(acct.name + ' ✅ 每日积分云端领取成功');
    dbEvent(acct.name, 'checkin', '每日积分领取成功', { credit: d1.today_credit, streak: d1.streak_days });
    return '领取成功';
  }
  log(acct.name + ' 签到失败 HTTP=' + s2.status + ' code=' + code2);
  return '失败';
}

// ---------- 猫猫旅行 ----------
async function getBuddyList(acct) {
  const r = await reqJson(HOST_WB, '/activity/growth/buddy/list', acct.accessToken, 'GET');
  return (r.json && r.json.code === 0 && r.json.data && r.json.data.buddies) || [];
}

async function doBuddyTravel(acct, st) {
  const a = st.accounts[acct.name] || (st.accounts[acct.name] = {});
  const b = a.buddy || (a.buddy = {});
  const acts = [];

  // 0) 未领养 → 尝试领取第一只（每天最多试一次）
  let buddies = await getBuddyList(acct);
  if (!buddies.length) {
    const today = cnDateStr();
    if (b.adoptTriedDay !== today) {
      b.adoptTriedDay = today;
      const r = await reqJson(HOST_WB, '/activity/growth/buddy/first', acct.accessToken, 'POST', {});
      const j = r.json || {};
      if (r.status === 200 && j.code === 0) {
        log(acct.name + ' 🎉 已自动领取第一只 Buddy');
        acts.push('自动领养成功');
        dbEvent(acct.name, 'buddy_adopt', '自动领取第一只 Buddy', {});
        buddies = await getBuddyList(acct);
      } else {
        b.note = '领养需官网同意协议';
        acts.push('未领养(需官网协议)');
      }
    } else {
      acts.push('未领养，跳过');
    }
    b.owned = buddies.length;
    if (!buddies.length) return acts;
  }
  b.owned = buddies.length;

  // 1) 记住当前猫，结束时恢复
  const rInfo = await reqJson(HOST_WB, '/activity/growth/buddy/info', acct.accessToken, 'GET');
  const origBuddy = rInfo.json && rInfo.json.data && rInfo.json.data.buddy;
  const origId = origBuddy && (origBuddy.instance_id || origBuddy.id);

  let limitReached = false;
  for (const bd of buddies) {
    const nm = bd.name || bd.title || ('#' + bd.instance_id);
    const sw = await reqJson(HOST_WB, '/activity/growth/buddy/switch', acct.accessToken, 'POST', { instance_id: Number(bd.instance_id) });
    if (!(sw.json && sw.json.code === 0)) { acts.push(nm + ':切换失败'); continue; }
    let sSt = await reqJson(HOST_WB, '/activity/growth/buddy/travel/status', acct.accessToken, 'GET');
    let stj = (sSt.json && sSt.json.data) || {};
    if (stj.state === 'arrived') {
      const cl = await reqJson(HOST_WB, '/activity/growth/buddy/travel/claim', acct.accessToken, 'POST', {});
      if (cl.json && cl.json.code === 0) {
        const reward = cl.json.data || {};
        const cr = reward.credit_granted ?? reward.credits ?? '';
        log(acct.name + ' ✅ ' + nm + ' 旅行礼物已领取' + (cr ? ' +' + cr + '积分' : ''));
        acts.push(nm + ':领取成功' + (cr ? '(+' + cr + ')' : ''));
        dbEvent(acct.name, 'travel_claim', nm + ' 旅行礼物已领取' + (cr ? ' +' + cr + '积分' : ''), reward);
      } else {
        acts.push(nm + ':领取失败');
      }
      sSt = await reqJson(HOST_WB, '/activity/growth/buddy/travel/status', acct.accessToken, 'GET');
      stj = (sSt.json && sSt.json.data) || {};
    } else if (stj.state === 'traveling') {
      // 旅行中：等到达，不是错误
      acts.push(nm + ':旅行中');
    } else if (stj.state !== 'idle') {
      acts.push(nm + ':' + stj.state);
    }
    if (stj.daily_limit_reached) { limitReached = true; continue; }
    if (stj.state === 'idle') {
      const cfg = await reqJson(HOST_WB, '/v2/activity/growth/buddy/travel/config', acct.accessToken, 'GET');
      const locs = (cfg.json && cfg.json.data && cfg.json.data.locations) || [];
      if (!locs.length) { acts.push(nm + ':无可用地点'); continue; }
      const dp = await reqJson(HOST_WB, '/v2/activity/growth/buddy/travel/depart', acct.accessToken, 'POST', { location_id: locs[0].id });
      if (dp.json && dp.json.code === 0) {
        log(acct.name + ' ✈️ ' + nm + ' 已派出 → ' + (locs[0].name || locs[0].id));
        acts.push(nm + ':已派出');
        dbEvent(acct.name, 'travel_depart', nm + ' 已派出 → ' + (locs[0].name || locs[0].id), { buddy: nm, location: locs[0].name || locs[0].id });
      } else {
        acts.push(nm + ':派出失败');
      }
    }
  }
  // 2) 恢复原当前猫
  if (origId) {
    const rb = await reqJson(HOST_WB, '/activity/growth/buddy/switch', acct.accessToken, 'POST', { instance_id: Number(origId) });
    if (rb.json && rb.json.code === 0) acts.push('已恢复原猫');
  }
  b.limitReached = limitReached;
  b.day = cnDateStr();
  return acts;
}

// ---------- 一轮完整工作 ----------
async function tick() {
  const st = loadState();
  st.lastTickAt = new Date().toISOString();
  st.tickCount = (st.tickCount || 0) + 1;
  if (!st.accounts) st.accounts = {};
  try { await ensureTokens(); } catch (e) { log('续期检查异常: ' + e.message); }
  for (const acct of CFG.accounts) {
    try {
      const r1 = await doCheckin(acct, st);
      const r2 = await doBuddyTravel(acct, st);
      log(acct.name + ' 一轮完成：签到=' + r1 + '；猫猫=' + (r2.join(' | ') || '无动作'));
    } catch (e) {
      log(acct.name + ' 本轮异常：' + (e && e.message || e));
    }
  }
  // 成长任务（限速：每账号每天最多 2 轮）
  await maybeGrowthTasks(st).catch(e => log('成长任务轮异常: ' + e.message));
  st.tokenInfo = CFG.accounts.map(a => ({ name: a.name, daysLeft: daysLeft(a.expiresAt), refreshDaysLeft: daysLeft(a.refreshExpiresAt) }));
  saveState(st);
}

// ---------- 成长任务自动化（每轮顺带补缺口） ----------
// 策略：每轮都做一次「轻量检查」（全量任务一次请求）；无缺口则几乎零成本。
// 有缺口时跑配方，但**同一任务当天最多尝试 3 次**（防卡死任务空转）。
// 好处：官方"不定期上线"的新任务最多 20 分钟就被抓到，不必等到次日。
const { runGrowthTasks } = require('./growth-tasks.js');
async function maybeGrowthTasks(st) {
  const today = cnDateStr();
  st.growthRuns = st.growthRuns || {};
  for (const acct of CFG.accounts) {
    const rec = st.growthRuns[acct.name] || (st.growthRuns[acct.name] = {});
    if (rec.day !== today) { rec.day = today; rec.tries = {}; rec.count = 0; }
    rec.tries = rec.tries || {};
    try {
      const r = await runGrowthTasks(acct.accessToken, (m) => log(acct.name + ' ' + m),
        (kind, detail, extra) => dbEvent(acct.name, kind, detail, extra),
        { tries: rec.tries, maxTries: 3 });
      if (r.didWork) {
        rec.count = (rec.count || 0) + 1;
        log(acct.name + ' 📋 成长任务（当日第 ' + rec.count + ' 次补缺口）：' + r.results.join(' | '));
      }
    } catch (e) {
      log(acct.name + ' 成长任务异常：' + (e && e.message || e));
    }
  }
}

// ---------- 定时器 ----------
const START_AT = Date.now();
function schedule() {
  setInterval(() => { tick().catch(e => log('tick异常: ' + e.message)); }, TICK_MINUTES * 60 * 1000);
  setInterval(() => { ensureTokens().catch(e => log('续期异常: ' + e.message)); }, REFRESH_CHECK_MINUTES * 60 * 1000);
  log('定时器已启动：工作每 ' + TICK_MINUTES + ' 分钟一轮，续期每 ' + REFRESH_CHECK_MINUTES + ' 分钟检查');
}

// ---------- HTTP 服务 ----------
function authed(req, urlObj) {
  const key = urlObj.searchParams.get('key') || (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  return key && CFG.access_key && key === CFG.access_key;
}

const server = http.createServer((req, res) => {
  const urlObj = new URL(req.url, 'http://localhost');
  const send = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj, null, 1)); };
  if (urlObj.pathname === '/health') {
    const st = loadState();
    return send(200, {
      ok: true, service: 'wbmon-cloud',
      uptimeMinutes: Math.round((Date.now() - START_AT) / 60000),
      tickCount: st.tickCount || 0, lastTickAt: st.lastTickAt || null,
      tickMinutes: TICK_MINUTES,
      accounts: CFG.accounts.map(a => ({ name: a.name, daysLeft: daysLeft(a.expiresAt) })),
    });
  }
  if (!authed(req, urlObj)) return send(401, { ok: false, msg: '未授权' });
  if (urlObj.pathname === '/status') {
    return send(200, { ok: true, now: new Date().toISOString(), state: loadState() });
  }
  if (urlObj.pathname === '/log') {
    return send(200, { ok: true, log: LOG_RING.slice(-100) });
  }
  if (urlObj.pathname === '/history') {
    // 云数据库中的运行历史（签到/领奖/派出事件），insert-only 审计表
    const limit = Math.min(Math.max(Number(urlObj.searchParams.get('limit') || 50), 1), 200);
    (async () => {
      if (!CLOUD_DB) return { ok: true, source: 'cloud-db-unavailable', events: [] };
      const r = await CLOUD_DB.database.from('wbmon_events')
        .select('id, account, kind, detail, created_at')
        .neq('kind', 'selftest')
        .order('id', { ascending: false })
        .limit(limit);
      if (r.error) return { ok: false, err: String((r.error && r.error.message) || r.error).slice(0, 200) };
      return { ok: true, source: 'cloud-db', count: (r.data || []).length, events: r.data };
    })().then(r => send(r.ok ? 200 : 500, r)).catch(e => send(500, { ok: false, err: String(e) }));
    return;
  }
  if (urlObj.pathname === '/tokens') {
    // 供本机 bridge 同步：只下发 access 令牌（不含 refresh）
    return send(200, {
      ok: true, now: Date.now(),
      tokens: CFG.accounts.map(a => ({ name: a.name, uid: a.uid, accessToken: a.accessToken, expiresAt: a.expiresAt })),
    });
  }
  if (urlObj.pathname === '/config') {
    // 完整配置导出（含 refresh 令牌）——仅用于"重新部署前回拉最新令牌链"，请勿外传
    return send(200, { ok: true, config: CFG });
  }
  if (urlObj.pathname === '/tick' && req.method === 'POST') {
    tick().then(() => send(200, { ok: true, msg: '已执行一轮', state: loadState() })).catch(e => send(500, { ok: false, err: String(e) }));
    return;
  }
  if (urlObj.pathname === '/refresh' && req.method === 'POST') {
    const force = urlObj.searchParams.get('force') === '1';
    if (force) {
      (async () => {
        const results = [];
        for (const a of CFG.accounts) {
          try { results.push({ name: a.name, ok: await refreshAccountToken(a) }); }
          catch (e) { results.push({ name: a.name, ok: false, err: String(e.message) }); }
        }
        return { ok: true, forced: true, results, tokens: CFG.accounts.map(a => ({ name: a.name, daysLeft: daysLeft(a.expiresAt), refreshDaysLeft: daysLeft(a.refreshExpiresAt) })) };
      })().then(r => send(200, r)).catch(e => send(500, { ok: false, err: String(e) }));
      return;
    }
    ensureTokens().then(() => send(200, { ok: true, msg: '续期检查完成' })).catch(e => send(500, { ok: false, err: String(e) }));
    return;
  }
  send(404, { ok: false });
});

loadCfg();
initCloudDb();
server.listen(PORT, '0.0.0.0', () => {
  log('wbmon-cloud 已启动，端口 ' + PORT + '，账号 ' + CFG.accounts.length + ' 个');
  log('云数据库（BaaS）：' + (CLOUD_DB ? '已接入，运行历史将落库' : '初始化失败，仅本地日志'));
  tick().catch(e => log('首次tick异常: ' + e.message));
  schedule();
});
