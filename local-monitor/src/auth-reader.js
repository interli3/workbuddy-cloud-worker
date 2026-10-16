import fs from 'node:fs';
import path from 'node:path';
import { authCandidates } from './config.js';

function readAuthFile(p) {
  try {
    if (!fs.existsSync(p)) return null;
    const o = JSON.parse(fs.readFileSync(p, 'utf8'));
    const acc = o.account || o.accounts?.[0] || o.allAccounts?.[0] || {};
    const auth = o.auth || {};
    // WorkBuddy/WorkDaddy may persist DPAPI/envelope-wrapped values. They are
    // not usable Bearer strings; ignore them instead of poisoning the pool.
    if (typeof auth.accessToken !== 'string' || !auth.accessToken || !acc.uid) return null;
    return {
      source: p,
      uid: acc.uid,
      nickname: acc.nickname || null,
      uin: acc.uin || null,
      phone: acc.phoneNumber || null,
      accountType: acc.type || 'personal',
      token: auth.accessToken,
      tokenType: auth.tokenType || 'Bearer',
      expiresAt: auth.expiresAt || null,
      refreshToken: typeof auth.refreshToken === 'string' ? auth.refreshToken : null,
      refreshExpiresAt: auth.refreshExpiresAt || null,
    };
  } catch {
    return null;
  }
}

// 读取本机当前登录态文件，提取关键信息。失败返回 null。
export function readLocalAuth() {
  for (const p of authCandidates()) {
    const value = readAuthFile(p);
    if (value) return value;
  }
  return null;
}

// WorkBuddy 会在同目录保留切换账号前的登录态快照。返回全部候选，
// 由调用方在线验证后选择；不能仅凭到期时间判断 token 是否仍被服务端接受。
export function readKnownLocalAuth() {
  const files = new Set(authCandidates());
  for (const current of authCandidates()) {
    try {
      const dir = path.dirname(current);
      for (const name of fs.readdirSync(dir)) {
        if (name.startsWith('workbuddy-desktop.') && name.endsWith('.info'))
          files.add(path.join(dir, name));
      }
    } catch {}
  }
  const values = [];
  const seen = new Set();
  for (const file of files) {
    const value = readAuthFile(file);
    if (!value) continue;
    const key = `${value.uid}\n${value.token}`;
    if (seen.has(key)) continue;
    seen.add(key);
    values.push(value);
  }
  return values.sort((a, b) => Number(b.expiresAt || 0) - Number(a.expiresAt || 0));
}

// 未知到期时间不等于已过期；实际 API 失败会单独显示。
export function isExpired(expiresAt) {
  if (!expiresAt) return false;
  const ms = Number(expiresAt);
  if (!Number.isFinite(ms)) return false;
  return ms < Date.now();
}
