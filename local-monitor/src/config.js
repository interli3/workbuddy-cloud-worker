import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const PROJECT_DIR = path.resolve(__dirname, '..');
export const CRED_FILE = path.join(PROJECT_DIR, 'credentials.json');
export const DATA_DIR = path.join(PROJECT_DIR, 'data');

// 默认 API host（腾讯官方计费/签到接口）。可按账号用 apiBase 覆盖（如国际版）。
export const DEFAULT_API_BASE = process.env.WB_API_BASE || 'https://copilot.tencent.com';

// 各平台本机登录态文件路径候选（用于“导入本机当前账号”）。
export function authCandidates() {
  const home = os.homedir();
  const u = process.env.USERNAME || process.env.USER || '';
  return [
    process.env.WB_AUTH_FILE,
    path.join(home, 'AppData', 'Local', 'CodeBuddyExtension', 'Data', 'Public', 'auth', 'workbuddy-desktop.info'),
    path.join(home, 'AppData', 'Roaming', 'CodeBuddyExtension', 'Data', 'Public', 'auth', 'workbuddy-desktop.info'),
    path.join(home, 'Library', 'Application Support', 'CodeBuddyExtension', 'Data', 'Public', 'auth', 'workbuddy-desktop.info'),
    path.join(home, '.config', 'CodeBuddyExtension', 'Data', 'Public', 'auth', 'workbuddy-desktop.info'),
    path.join('/home', u, '.config', 'CodeBuddyExtension', 'Data', 'Public', 'auth', 'workbuddy-desktop.info'),
  ].filter(Boolean);
}

// CodeBuddyExtension 数据根目录（会话/历史按账号 UID 隔离于此）。
export function codeBuddyDataDir() {
  const home = os.homedir();
  const u = process.env.USERNAME || process.env.USER || '';
  const candidates = [
    path.join(home, 'AppData', 'Local', 'CodeBuddyExtension', 'Data'),
    path.join(home, 'AppData', 'Roaming', 'CodeBuddyExtension', 'Data'),
    path.join(home, 'Library', 'Application Support', 'CodeBuddyExtension', 'Data'),
    path.join(home, '.config', 'CodeBuddyExtension', 'Data'),
    path.join('/home', u, '.config', 'CodeBuddyExtension', 'Data'),
  ];
  for (const c of candidates) if (fs.existsSync(c)) return c;
  return candidates[0];
}

// 某账号 UID 的 history 目录（结构: Data/<uid>/CodeBuddyIDE/<uid>/history）
export function accountHistoryDir(uid) {
  const base = codeBuddyDataDir();
  if (!uid) return null;
  const direct = path.join(base, uid, 'CodeBuddyIDE', uid, 'history');
  if (fs.existsSync(direct)) return direct;
  const root = path.join(base, uid);
  if (fs.existsSync(root)) {
    let found = null;
    const walk = (d) => {
      if (found) return;
      for (const e of fs.readdirSync(d, { withFileTypes: true })) {
        const p = path.join(d, e.name);
        if (e.isDirectory()) {
          if (e.name === 'history') { found = p; return; }
          walk(p);
        }
      }
    };
    try { walk(root); } catch {}
    return found;
  }
  return null;
}

export const EXPORT_DIR = path.join(PROJECT_DIR, 'exports');
