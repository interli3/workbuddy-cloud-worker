import { load, save, importLocal, importKnownLocal, applyExpiryPolicy } from './store.js';
import { refreshAccount, checkinAll } from './engine.js';
import { claimExpertPackage } from './wb-api.js';
import { discover, clientIdFromToken, profileFromIdToken, genPkce, buildAuthUrl, exchangeCode } from './oidc.js';
import http from 'node:http';

const cmd = process.argv[2];

let cliLoginResolve = null;
async function startCliCallback(cfg, pkce) {
  for (const port of [18765, 18766, 18767]) {
    try {
      const server = http.createServer(async (req2, res2) => {
        const u = new URL(req2.url, `http://127.0.0.1:${port}`);
        if (u.pathname !== '/cb') { res2.writeHead(404); res2.end(''); return; }
        try {
          const code = u.searchParams.get('code');
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
          console.log('✅ 登录成功，已加入凭证池：' + (prof.nickname || prof.phone || acct.id));
          res2.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
          res2.end('<h2 style="font-family:sans-serif;padding:40px">✅ WorkBuddy 登录成功，可关闭此页。</h2>');
        } catch (e) {
          console.log('❌ 登录失败: ' + String(e.message || e));
          res2.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
          res2.end('<h2 style="font-family:sans-serif;padding:40px;color:#c33">登录失败：' + String(e.message || e) + '</h2>');
        }
        if (cliLoginResolve) { cliLoginResolve(); cliLoginResolve = null; }
        setTimeout(() => server.close(), 500);
      });
      await new Promise((res, rej) => { server.once('error', rej); server.listen(port, '127.0.0.1', () => res()); });
      return port;
    } catch { continue; }
  }
  throw new Error('无可用本地端口启动回调服务');
}

async function loginDevice() {
  const store = load();
  const firstTok = store.accounts[0]?.token;
  const { clientId } = firstTok ? clientIdFromToken(firstTok) : { clientId: 'console' };
  const cfg = await discover(clientId);
  const pkce = genPkce();
  const port = await startCliCallback(cfg, pkce);
  const authUrl = buildAuthUrl(cfg, `http://127.0.0.1:${port}/cb`, 'cli', pkce.challenge);
  console.log('\n=== 无感登录新账号 ===');
  console.log('请在浏览器打开并扫码/登录：\n  ' + authUrl);
  try { require('child_process').exec(`start "" "${authUrl}"`); } catch {}
  console.log('（登录授权后自动加入凭证池，无需退出当前账号）\n等待授权…');
  await new Promise((resolve) => { cliLoginResolve = resolve; });
}

async function main() {
  switch (cmd) {
    case 'login': {
      await loginDevice();
      break;
    }
    case 'import-local': {
      const store = load();
      const r = importLocal(store);
      save(store);
      console.log(JSON.stringify(r, null, 2));
      break;
    }
    case 'import-known':
    case 'sync-auth':
    case 'refresh-tokens': {
      const store = load();
      const synced = await importKnownLocal(store);
      save(store);
      const results = synced.accounts.map((account) => ({
        id: account.id, name: account.name,
        result: account.ok ? '有效:官方接口验证通过' : '失败:没有可用的官方登录凭证',
      }));
      console.log(JSON.stringify({ synced, accounts: results }, null, 2));
      if (results.some((x) => x.result.startsWith('失败:'))) process.exitCode = 1;
      break;
    }
    case 'list': {
      const store = load();
      console.log(store.accounts.map((a) => ({ id: a.id, name: a.name, region: a.region, disabled: a.disabled, lastCheckin: a.lastCheckin })));
      break;
    }
    case 'refresh-all': {
      const store = load();
      const data = [];
      for (const a of store.accounts) data.push(await refreshAccount(a));
      applyExpiryPolicy(store);
      const { recordDailySnapshot } = await import('./usage.js');
      recordDailySnapshot(store);
      save(store);
      console.log(JSON.stringify(data, null, 2));
      break;
    }
    case 'checkin-all': {
      const store = load();
      const results = await checkinAll(store);
      save(store);
      console.log(JSON.stringify(results, null, 2));
      if (results.some((x) => x.result.startsWith('失败:') || x.result.startsWith('异常:'))) {
        process.exitCode = 1;
      }
      break;
    }
    case 'claim-all': {
      const store = load();
      const out = [];
      for (const a of store.accounts) {
        if (!a.claimEndpoint) continue;
        out.push({ id: a.id, name: a.name, r: await claimExpertPackage(a.token, a.claimEndpoint, a.apiBase || undefined) });
      }
      console.log(JSON.stringify(out, null, 2));
      break;
    }
    case 'sessions': {
      const uid = process.argv[3];
      if (!uid) return console.log('用法: node src/cli.js sessions <uid>');
      const { listSessions } = await import('./sessions.js');
      console.log(JSON.stringify(listSessions(uid), null, 2));
      break;
    }
    case 'session-export': {
      const uid = process.argv[3], sid = process.argv[4];
      const { exportSession } = await import('./sessions.js');
      console.log(JSON.stringify(exportSession(uid, sid), null, 2));
      break;
    }
    case 'session-import': {
      const uid = process.argv[3], sid = process.argv[4], tuid = process.argv[5];
      const { importSession } = await import('./sessions.js');
      console.log(JSON.stringify(importSession(uid, sid, tuid), null, 2));
      break;
    }
    default:
      console.log('用法: node src/cli.js <login|import-local|sync-auth|list|refresh-all|checkin-all|claim-all|sessions <uid>|session-export <uid> <sid>|session-import <uid> <sid> <targetUid>>');
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
