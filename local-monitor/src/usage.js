import fs from 'node:fs';
import path from 'node:path';
import { DATA_DIR } from './config.js';

function file() { return path.join(DATA_DIR, 'usage.json'); }
function today() { return new Date().toISOString().slice(0, 10); }

function readAll() {
  try { return JSON.parse(fs.readFileSync(file(), 'utf8')); } catch { return {}; }
}

// 记录今日各账号余额快照（每天首次刷新时覆盖为当日开局余额）
export function recordDailySnapshot(store) {
  const all = readAll();
  const d = all[today()] || {};
  for (const a of store.accounts) {
    if (a.uid && typeof a.lastBalance === 'number') {
      if (!(a.uid in d)) d[a.uid] = a.lastBalance; // 仅记录首次（当日开局）
    }
  }
  all[today()] = d;
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.writeFileSync(file(), JSON.stringify(all, null, 2));
}

// 计算当日消耗（当前余额 − 晨间快照；负值代表当日有入账）
export function getUsage(store) {
  const all = readAll();
  const d = all[today()] || {};
  const byUid = {};
  let totalConsumed = 0;
  for (const a of store.accounts) {
    if (!a.uid) continue;
    const morning = d[a.uid];
    const cur = typeof a.lastBalance === 'number' ? a.lastBalance : null;
    if (morning == null || cur == null) { byUid[a.uid] = null; continue; }
    const delta = cur - morning; // 负=消耗
    byUid[a.uid] = { morning, current: cur, delta };
    if (delta < 0) totalConsumed += -delta;
  }
  return { date: today(), byUid, totalConsumed };
}
