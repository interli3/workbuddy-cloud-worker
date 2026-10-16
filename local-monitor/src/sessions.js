import fs from 'node:fs';
import path from 'node:path';
import { accountHistoryDir, EXPORT_DIR } from './config.js';

function copyDir(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, e.name);
    const d = path.join(dest, e.name);
    if (e.isDirectory()) copyDir(s, d);
    else fs.copyFileSync(s, d);
  }
}

// 列出某账号本机会话
export function listSessions(uid) {
  const hdir = accountHistoryDir(uid);
  if (!hdir) return [];
  const out = [];
  for (const e of fs.readdirSync(hdir, { withFileTypes: true })) {
    if (!e.isDirectory() || e.name.startsWith('.')) continue;
    const sessionId = e.name;
    const sessIdx = path.join(hdir, sessionId, 'index.json');
    let name = sessionId;
    let lastMessageAt = null;
    let convCount = 0;
    try {
      const si = JSON.parse(fs.readFileSync(sessIdx, 'utf8'));
      convCount = (si.conversations || []).length;
      const cur = (si.conversations || []).find((c) => c.id === si.current) || si.conversations?.[0];
      if (cur?.name) name = cur.name;
      lastMessageAt = cur?.lastMessageAt || null;
    } catch {}
    // 统计消息数（取 current 对话）
    let msgCount = 0;
    try {
      const si = JSON.parse(fs.readFileSync(sessIdx, 'utf8'));
      const cur = (si.conversations || []).find((c) => c.id === si.current) || si.conversations?.[0];
      if (cur?.id) {
        const cidx = path.join(hdir, sessionId, cur.id, 'index.json');
        if (fs.existsSync(cidx)) msgCount = (JSON.parse(fs.readFileSync(cidx, 'utf8')).messages || []).length;
      }
    } catch {}
    out.push({ sessionId, name, msgCount, convCount, lastMessageAt });
  }
  return out.sort((a, b) => (b.lastMessageAt || '').localeCompare(a.lastMessageAt || ''));
}

// 导出会话为可移植副本（整文件夹）
export function exportSession(uid, sessionId) {
  const hdir = accountHistoryDir(uid);
  if (!hdir) throw new Error('源账号无本地数据目录');
  const src = path.join(hdir, sessionId);
  if (!fs.existsSync(src)) throw new Error('会话不存在: ' + sessionId);
  const meta = listSessions(uid).find((s) => s.sessionId === sessionId);
  const safe = (meta?.name || 'session').replace(/[^\w一-龥-]+/g, '_').slice(0, 30);
  const dest = path.join(EXPORT_DIR, `${safe}-${sessionId.slice(0, 8)}`);
  fs.mkdirSync(EXPORT_DIR, { recursive: true });
  copyDir(src, dest);
  return { dest, name: meta?.name, sessionId };
}

// 迁移会话到另一账号（整文件夹拷贝，先备份目标已存在的同名会话）
export function importSession(uid, sessionId, targetUid) {
  const srcDir = accountHistoryDir(uid);
  const tgtDir = accountHistoryDir(targetUid);
  if (!srcDir) throw new Error('源账号无本地数据目录');
  if (!tgtDir) throw new Error('目标账号在本机无数据目录（请先在 WorkBuddy 客户端登录该账号一次）');
  const src = path.join(srcDir, sessionId);
  if (!fs.existsSync(src)) throw new Error('会话不存在: ' + sessionId);
  const dest = path.join(tgtDir, sessionId);
  if (fs.existsSync(dest)) {
    const bak = `${dest}.bak-${Date.now()}`;
    fs.renameSync(dest, bak);
    // 备份后仍需把原 dest 让出来
  }
  copyDir(src, dest);
  return { dest, targetUid, sessionId };
}
