import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { PROJECT_DIR } from './config.js';
import { load, save, importLocal, remove, applyExpiryPolicy } from './store.js';
import { refreshAccount, checkinAccount, checkinAll } from './engine.js';
import { claimExpertPackage } from './wb-api.js';
import { discover, clientIdFromToken, profileFromIdToken, genHandle, genPkce, buildAuthUrl, exchangeCode } from './oidc.js';
import { listSessions, exportSession, importSession } from './sessions.js';
import { recordDailySnapshot, getUsage } from './usage.js';

// 设备流临时状态（进程内存，重启即失效，可接受）
const deviceFlows = new Map();

// 临时本地回调服务：捕获授权码并换取 token，自动加入凭证池
async function startCallbackServer(handle, cfg, pkce) {
  for (const port of [18765, 18766, 18767, 18768]) {
    try {
      const server = http.createServer(async (req2, res2) => {
        const u = new URL(req2.url, `http://127.0.0.1:${port}`);
        if (u.pathname !== '/cb') { res2.writeHead(404); res2.end(''); return; }
        const code = u.searchParams.get('code');
        const err = u.searchParams.get('error');
        const f = deviceFlows.get(handle);
        try {
          if (err) throw new Error(err);
          if (!code) throw new Error('回调缺少 code');
          const tokens = await exchangeCode(cfg, code, `http://127.0.0.1:${port}/cb`, pkce.verifier);
          const prof = profileFromIdToken(tokens.id_token);
          const store = load();
          const existing = store.accounts.find((a) => a.uid && prof.uid && a.uid === prof.uid);
          const acct = existing || {
            id: Math.random().toString(16).slice(2, 10), name: prof.nickname || prof.phone || '新账号',
            region: 'CN', autoCheckin: true, disabled: false, lastCheckin: null, lastResult: null,
          };
          acct.nickname = prof.nickname || acct.nickname;
          acct.phone = prof.phone || acct.phone;
          acct.uid = prof.uid || acct.uid;
          acct.token = tokens.access_token;
          acct.refreshToken = tokens.refresh_token || acct.refreshToken;
          acct.expiresAt = tokens.expires_in ? Date.now() + tokens.expires_in * 1000 : acct.expiresAt;
          acct.tokenType = 'Bearer';
          if (!existing) store.accounts.push(acct);
          save(store);
          f.status = 'done'; f.account = redact(acct); f.profile = prof;
          res2.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
          res2.end('<h2 style="font-family:sans-serif;padding:40px">✅ WorkBuddy 登录成功，可关闭此页返回面板。</h2>');
        } catch (e) {
          const f2 = deviceFlows.get(handle);
          if (f2) { f2.status = 'error'; f2.error = String(e.message || e); }
          res2.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
          res2.end('<h2 style="font-family:sans-serif;padding:40px;color:#c33">登录失败：' + String(e.message || e) + '。可关闭此页重试。</h2>');
        }
      });
      await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', () => resolve()); });
      deviceFlows.get(handle).server = server;
      return port;
    } catch {
      continue;
    }
  }
  throw new Error('无可用本地端口启动回调服务');
}

const PORT = process.env.PORT || 8765;

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let d = '';
    req.on('data', (c) => (d += c));
    req.on('end', () => {
      try { resolve(d ? JSON.parse(d) : {}); } catch { resolve({}); }
    });
  });
}

function redact(a) {
  return {
    id: a.id, name: a.name, region: a.region, nickname: a.nickname, uin: a.uin, phone: a.phone,
    disabled: a.disabled, disabledReason: a.disabledReason, expiresAt: a.expiresAt,
    autoCheckin: a.autoCheckin, lastCheckin: a.lastCheckin, lastResult: a.lastResult,
    hasToken: Boolean(a.token), tokenLen: a.token ? a.token.length : 0,
  };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const p = url.pathname;

  // 静态面板
  if (req.method === 'GET' && (p === '/' || p === '/index.html')) {
    const html = fs.readFileSync(path.join(PROJECT_DIR, 'public', 'panel.html'));
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(html);
    return;
  }

  if (req.method === 'GET' && (p === '/widget' || p === '/widget.html')) {
    const html = fs.readFileSync(path.join(PROJECT_DIR, 'public', 'widget.html'));
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(html);
    return;
  }

  try {
    // 列表 + 设置（不含 token）
    if (req.method === 'GET' && p === '/api/state') {
      const store = load();
      return send(res, 200, { settings: store.settings, accounts: store.accounts.map(redact), usage: getUsage(store) });
    }

    // 导入本机当前账号
    if (req.method === 'POST' && p === '/api/import-local') {
      const store = load();
      const r = importLocal(store);
      save(store);
      return send(res, 200, { ...r, accounts: store.accounts.map(redact) });
    }

    // 手动添加账号
    if (req.method === 'POST' && p === '/api/add') {
      const b = await readBody(req);
      const store = load();
      if (!b.token) return send(res, 400, { error: 'token 必填' });
      store.accounts.push({
        id: Math.random().toString(16).slice(2, 10),
        name: b.name || b.nickname || '账号',
        region: b.region === 'Global' ? 'Global' : 'CN',
        nickname: b.nickname || null, uin: b.uin || null, phone: b.phone || null, uid: b.uid || null,
        token: b.token, apiBase: b.apiBase || null, claimEndpoint: b.claimEndpoint || null,
        expiresAt: b.expiresAt || null, autoCheckin: b.region === 'Global' ? false : true, disabled: false,
        lastCheckin: null, lastResult: null,
      });
      save(store);
      return send(res, 200, { ok: true, accounts: store.accounts.map(redact) });
    }

    // 删除账号
    if (req.method === 'DELETE' && p.startsWith('/api/account/')) {
      const id = p.split('/').pop();
      const store = load();
      const ok = remove(store, id);
      save(store);
      return send(res, 200, { ok, accounts: store.accounts.map(redact) });
    }

    // 更新设置
    if (req.method === 'POST' && p === '/api/settings') {
      const b = await readBody(req);
      const store = load();
      store.settings = { ...store.settings, ...b };
      save(store);
      return send(res, 200, { ok: true, settings: store.settings });
    }

    // 开关账号自动签到 / 禁用
    if (req.method === 'POST' && p.startsWith('/api/toggle/')) {
      const [_, id] = p.split('/').slice(-1);
      const b = await readBody(req);
      const store = load();
      const a = store.accounts.find((x) => x.id === id);
      if (!a) return send(res, 404, { error: 'not found' });
      if ('autoCheckin' in b) a.autoCheckin = b.autoCheckin;
      if ('disabled' in b) a.disabled = b.disabled;
      save(store);
      return send(res, 200, { ok: true, account: redact(a) });
    }

    // 刷新单个
    if (req.method === 'POST' && p.startsWith('/api/refresh/')) {
      const id = p.split('/').pop();
      const store = load();
      const a = store.accounts.find((x) => x.id === id);
      if (!a) return send(res, 404, { error: 'not found' });
      const data = await refreshAccount(a);
      a.exhausted = data.exhausted;
      if (data.exhausted && store.settings.expireAutoDisable && !a.disabled) { a.disabled = true; a.disabledReason = '额度耗尽'; }
      recordDailySnapshot(store);
      save(store);
      return send(res, 200, data);
    }

    // 刷新全部
    if (req.method === 'POST' && p === '/api/refresh-all') {
      const store = load();
      const data = [];
      for (const a of store.accounts) data.push(await refreshAccount(a));
      const acts = applyExpiryPolicy(store);
      recordDailySnapshot(store);
      save(store);
      return send(res, 200, { data, expiryActions: acts });
    }

    // 签到单个
    if (req.method === 'POST' && p.startsWith('/api/checkin/')) {
      const id = p.split('/').pop();
      const store = load();
      const a = store.accounts.find((x) => x.id === id);
      if (!a) return send(res, 404, { error: 'not found' });
      const r = await checkinAccount(a);
      save(store);
      return send(res, 200, { result: r.alreadySigned ? '已签(跳过)' : r.ok ? '签到成功' : `失败:${r.status}`, raw: r.raw });
    }

    // 签到全部（CN 自动）
    if (req.method === 'POST' && p === '/api/checkin-all') {
      const store = load();
      const results = await checkinAll(store);
      save(store);
      return send(res, 200, { results });
    }

    // 领取专家包（需配置 claimEndpoint）
    if (req.method === 'POST' && p.startsWith('/api/claim/')) {
      const id = p.split('/').pop();
      const store = load();
      const a = store.accounts.find((x) => x.id === id);
      if (!a) return send(res, 404, { error: 'not found' });
      const r = await claimExpertPackage(a.token, a.claimEndpoint, a.apiBase || undefined);
      return send(res, 200, r);
    }

    // ===== 无感登录新账号（OIDC 授权码 + PKCE）=====
    if (req.method === 'POST' && p === '/api/login/start') {
      const store = load();
      const firstTok = store.accounts[0]?.token;
      const { clientId } = firstTok ? clientIdFromToken(firstTok) : { clientId: 'console' };
      const cfg = await discover(clientId);
      const pkce = genPkce();
      const handle = genHandle();
      deviceFlows.set(handle, { status: 'pending', cfg, pkce });
      let port;
      try { port = await startCallbackServer(handle, cfg, pkce); }
      catch (e) { deviceFlows.delete(handle); return send(res, 500, { error: String(e.message || e) }); }
      const redirectUri = `http://127.0.0.1:${port}/cb`;
      const authUrl = buildAuthUrl(cfg, redirectUri, handle, pkce.challenge);
      return send(res, 200, { handle, authUrl });
    }

    if (req.method === 'POST' && p === '/api/login/poll') {
      const b = await readBody(req);
      const f = deviceFlows.get(b.handle);
      if (!f) return send(res, 404, { error: '无效或已过期的登录句柄，请重新发起' });
      if (f.status === 'done') { deviceFlows.delete(b.handle); if (f.server) f.server.close(); return send(res, 200, { status: 'approved', account: f.account, profile: f.profile }); }
      if (f.status === 'error') { if (f.server) f.server.close(); return send(res, 200, { status: 'error', error: f.error }); }
      return send(res, 200, { status: 'pending' });
    }

    // ===== 跨账号会话迁移 =====
    if (req.method === 'GET' && p.startsWith('/api/sessions/')) {
      const uid = p.split('/').pop();
      try {
        return send(res, 200, { uid, sessions: listSessions(uid) });
      } catch (e) {
        return send(res, 500, { error: String(e?.message || e) });
      }
    }
    if (req.method === 'POST' && p === '/api/sessions/export') {
      const b = await readBody(req);
      try {
        const r = exportSession(b.uid, b.sessionId);
        return send(res, 200, { ok: true, ...r });
      } catch (e) {
        return send(res, 400, { error: String(e?.message || e) });
      }
    }
    if (req.method === 'POST' && p === '/api/sessions/import') {
      const b = await readBody(req);
      try {
        const r = importSession(b.uid, b.sessionId, b.targetUid);
        return send(res, 200, { ok: true, ...r });
      } catch (e) {
        return send(res, 400, { error: String(e?.message || e) });
      }
    }

    return send(res, 404, { error: 'not found' });
  } catch (e) {
    return send(res, 500, { error: String(e?.message || e) });
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`WorkBuddy 多账号面板已启动: http://localhost:${PORT}`);
});
