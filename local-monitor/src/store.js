import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { CRED_FILE } from './config.js';
import { readLocalAuth, readKnownLocalAuth, isExpired } from './auth-reader.js';
import { validateAccessToken } from './wb-api.js';

const EMPTY = { settings: { autoCheckinCN: true, autoCheckinTimes: ['09:00', '21:00'], expireAutoDisable: true, expireAutoDelete: false }, accounts: [] };

export function load() {
  try {
    const o = JSON.parse(fs.readFileSync(CRED_FILE, 'utf8'));
    o.settings = { ...EMPTY.settings, ...(o.settings || {}) };
    o.accounts = Array.isArray(o.accounts) ? o.accounts : [];
    return o;
  } catch {
    return structuredClone(EMPTY);
  }
}

export function save(store) {
  fs.mkdirSync(path.dirname(CRED_FILE), { recursive: true });
  fs.writeFileSync(CRED_FILE, JSON.stringify(store, null, 2));
}

export function genId() {
  return crypto.randomBytes(4).toString('hex');
}

// 导入本机当前登录账号；若已存在同 uid 则更新 token。
export function importLocal(store) {
  const local = readLocalAuth();
  if (!local) return { added: false, reason: '未找到本机登录态' };
  return upsert(store, {
    nickname: local.nickname,
    uin: local.uin,
    phone: local.phone,
    uid: local.uid,
    token: local.token,
    expiresAt: local.expiresAt,
    refreshToken: local.refreshToken,
    refreshExpiresAt: local.refreshExpiresAt,
    tokenType: local.tokenType,
    region: 'CN',
    source: 'local',
  });
}

function upsert(store, info) {
  const existing = store.accounts.find((a) => a.uid && info.uid && a.uid === info.uid);
  if (existing) {
    existing.token = info.token;
    existing.refreshToken = info.refreshToken || existing.refreshToken;
    existing.refreshExpiresAt = info.refreshExpiresAt || existing.refreshExpiresAt;
    existing.expiresAt = info.expiresAt || existing.expiresAt;
    existing.nickname = info.nickname || existing.nickname;
    existing.phone = info.phone || existing.phone;
    existing.region = info.region || existing.region || 'CN';
    existing.disabled = false;
    return { added: false, id: existing.id, merged: true };
  }
  const acc = {
    id: genId(),
    name: info.nickname || info.phone || info.uid || '账号',
    region: info.region || 'CN',
    nickname: info.nickname || null,
    uin: info.uin || null,
    phone: info.phone || null,
    uid: info.uid || null,
    token: info.token,
    refreshToken: info.refreshToken || null,
    refreshExpiresAt: info.refreshExpiresAt || null,
    apiBase: info.apiBase || null,
    claimEndpoint: info.claimEndpoint || null,
    expiresAt: info.expiresAt || null,
    autoCheckin: info.region ? info.region === 'CN' : true,
    disabled: false,
    lastCheckin: null,
    lastResult: null,
  };
  store.accounts.push(acc);
  return { added: true, id: acc.id };
}

export async function selectValidAuth(candidates, validate = validateAccessToken) {
  const ordered = [...candidates].sort((a, b) => Number(b.expiresAt || 0) - Number(a.expiresAt || 0));
  for (const candidate of ordered) {
    const result = await validate(candidate.token, candidate.apiBase || undefined);
    if (result.ok) return { candidate, result };
  }
  return null;
}

// 只更新凭证池中已有账号。历史候选必须先通过官方接口验证，已删除账号不会复活。
export async function importKnownLocal(store, validate = validateAccessToken) {
  const known = readKnownLocalAuth();
  let updated = 0;
  const accounts = [];
  for (const existing of store.accounts) {
    const candidates = known.filter((info) => existing.uid && info.uid === existing.uid);
    candidates.push({
      uid: existing.uid, token: existing.token, expiresAt: existing.expiresAt,
      refreshToken: existing.refreshToken, refreshExpiresAt: existing.refreshExpiresAt,
      apiBase: existing.apiBase,
    });
    const selected = await selectValidAuth(candidates, validate);
    if (!selected) {
      accounts.push({ id: existing.id, name: existing.name, ok: false, status: null, code: null });
      continue;
    }
    const info = selected.candidate;
    if (existing.token !== info.token || existing.refreshToken !== (info.refreshToken || existing.refreshToken)) {
      existing.token = info.token;
      existing.expiresAt = info.expiresAt || existing.expiresAt;
      existing.refreshToken = info.refreshToken || existing.refreshToken;
      existing.refreshExpiresAt = info.refreshExpiresAt || existing.refreshExpiresAt;
      updated++;
    }
    existing.disabled = false;
    delete existing.disabledReason;
    accounts.push({ id: existing.id, name: existing.name, ok: true,
      status: selected.result.status, code: selected.result.code });
  }
  return { scanned: known.length, updated, accounts };
}

export function remove(store, id) {
  const i = store.accounts.findIndex((a) => a.id === id);
  if (i < 0) return false;
  store.accounts.splice(i, 1);
  return true;
}

export function todayStr() {
  // 用本地日期（与 bridge_multi.py 的 date.today() 保持一致），
  // 避免中国时区跨零点时 UTC 与本地相差一天导致“今日已签”误判。
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

// “耗尽自动关/删”：积分耗尽或 token 过期时按设置处理。
export function applyExpiryPolicy(store) {
  const actions = [];
  for (const a of store.accounts) {
    const exhausted = a.disabled === 'exhausted' || a.disabled === true && a.disabledReason === 'exhausted';
    const expired = isExpired(a.expiresAt);
    if (expired || a.exhausted) {
      if (store.settings.expireAutoDelete) {
        // 删除（仅当非本机唯一）
        actions.push({ id: a.id, action: 'delete', reason: expired ? 'token过期' : '额度耗尽' });
      } else if (store.settings.expireAutoDisable) {
        if (!a.disabled) {
          a.disabled = true;
          a.disabledReason = expired ? 'token过期' : '额度耗尽';
          actions.push({ id: a.id, action: 'disable', reason: a.disabledReason });
        }
      }
    }
  }
  if (actions.length) save(store);
  return actions;
}
