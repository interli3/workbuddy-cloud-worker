#!/usr/bin/env node
/**
 * get-token.cjs —— 从本机 WorkBuddy 客户端的登录态里，取出明文的 access / refresh 令牌
 *
 * 用法：
 *   node get-token.cjs                    # 自动探测客户端与登录态，生成 tokens.json
 *   node get-token.cjs --exe "D:\\Apps\\WorkBuddy\\WorkBuddy.exe"
 *   node get-token.cjs --info "C:\\Users\\me\\AppData\\Local\\CodeBuddyExtension\\Data\\Public\\auth\\workbuddy-desktop.info"
 *   node get-token.cjs --print            # 顺带把令牌打到终端（会留在命令历史里，慎用）
 *   node get-token.cjs --out ./tokens.json
 *
 * 它做什么（全程本机、离线，不联网）：
 *   客户端把登录态加密存在本地（{"$wbEncrypted":1,...} 信封，AES-256-GCM）。
 *   1) 借客户端自带的 Electron 运行时（ELECTRON_RUN_AS_NODE=1）调原生绑定
 *      electron_browser_workbuddy_storage.loggerGet()，拿到本机 atRestSecretKey；
 *   2) 对它 sha256 → 32 字节 AES 密钥（只经管道，不落盘）；
 *   3) 解开 .info 里的信封，得到明文 accessToken / refreshToken。
 *   客户端 < 5.6 时令牌本来就是明文，脚本也能直接读。
 *
 * 安全：令牌只写到本地 tokens.json（权限 600）。别把它提交进 git、别贴到聊天里。
 */
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const args = process.argv.slice(2);
const argOf = (n) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : null; };
const WANT_PRINT = args.includes('--print');
const OUT = path.resolve(argOf('--out') || path.join(process.cwd(), 'tokens.json'));

/* ---------------- 1. 找客户端可执行文件 ---------------- */
function exeCandidates() {
  const list = [];
  if (process.env.WORKBUDDY_EXE) list.push(process.env.WORKBUDDY_EXE);
  if (process.platform === 'win32') {
    const local = process.env.LOCALAPPDATA || '';
    list.push(
      path.join(local, 'Programs', 'WorkBuddy', 'WorkBuddy.exe'),
      path.join(local, 'Programs', 'workbuddy', 'WorkBuddy.exe'),
      'C:\\Program Files\\WorkBuddy\\WorkBuddy.exe',
      'C:\\Program Files (x86)\\WorkBuddy\\WorkBuddy.exe',
    );
  } else if (process.platform === 'darwin') {
    list.push('/Applications/WorkBuddy.app/Contents/MacOS/WorkBuddy');
  } else {
    list.push('/usr/bin/workbuddy', '/opt/WorkBuddy/workbuddy');
  }
  return list;
}

// 装到非默认目录时，从 Windows 卸载表项里读回安装位置
function exeFromRegistry() {
  if (process.platform !== 'win32') return null;
  const ps = [
    "$ErrorActionPreference='SilentlyContinue';",
    "$p = Get-ItemProperty 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',",
    "'HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',",
    "'HKCU:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'",
    "| Where-Object { $_.DisplayName -match 'WorkBuddy' }",
    "| Select-Object -First 1 -ExpandProperty DisplayIcon;",
    "if ($p) { Write-Output ($p -replace ',\\d+$','') }",
  ].join(' ');
  const r = spawnSync('powershell', ['-NoProfile', '-NonInteractive', '-Command', ps], {
    encoding: 'utf8', timeout: 15000, windowsHide: true,
  });
  const p = (r.stdout || '').trim().split(/\r?\n/).pop();
  return p && fs.existsSync(p) ? p : null;
}

function findExe() {
  const given = argOf('--exe');
  if (given) return fs.existsSync(given) ? given : null;
  for (const p of exeCandidates()) { try { if (p && fs.existsSync(p)) return p; } catch (_) {} }
  return exeFromRegistry();
}

/* ---------------- 2. 找登录态文件 ---------------- */
function findInfo() {
  const given = argOf('--info');
  if (given) return fs.existsSync(given) ? given : null;
  const tail = ['CodeBuddyExtension', 'Data', 'Public', 'auth', 'workbuddy-desktop.info'];
  const roots = [];
  if (process.platform === 'win32') roots.push(process.env.LOCALAPPDATA || '');
  else if (process.platform === 'darwin') roots.push(path.join(os.homedir(), 'Library', 'Application Support'));
  else roots.push(path.join(os.homedir(), '.config'));
  for (const r of roots) {
    const p = r && path.join(r, ...tail);
    if (p && fs.existsSync(p)) return p;
  }
  return null;
}

/* ---------------- 3. 取钥：在客户端 Electron 运行时里跑 ---------------- */
const KEY_SNIPPET = `
try {
  const binding = process._linkedBinding('electron_browser_workbuddy_storage');
  const payload = JSON.parse(binding.loggerGet());
  if (payload && payload.version === 1 && typeof payload.atRestSecretKey === 'string') {
    const key = require('crypto').createHash('sha256').update(payload.atRestSecretKey, 'utf8').digest();
    process.stdout.write('WD_COMPAT_KEY ' + key.toString('base64') + '\\n');
    process.exit(0);
  }
  process.stderr.write('payload schema mismatch: ' + JSON.stringify(payload).slice(0,120)); process.exit(4);
} catch (e) { process.stderr.write(String((e && e.message) || e).slice(0, 300)); process.exit(3); }
`;

function deriveKey(exe) {
  const tmp = path.join(os.tmpdir(), `wb-key-${process.pid}-${Date.now()}.cjs`);
  fs.writeFileSync(tmp, KEY_SNIPPET, 'utf8');
  try {
    const r = spawnSync(exe, [tmp], {
      env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' },
      timeout: 30000, encoding: 'utf8', windowsHide: true,
    });
    const m = (r.stdout || '').match(/WD_COMPAT_KEY ([A-Za-z0-9+/=]+)/);
    if (!m) {
      const why = ((r.stderr || '') + ' (exit=' + r.status + ')').trim().slice(0, 300);
      throw new Error(why || '客户端没有返回密钥');
    }
    return Buffer.from(m[1], 'base64');
  } finally {
    try { fs.unlinkSync(tmp); } catch (_) {}
  }
}

/* ---------------- 4. 解信封：AES-256-GCM / sym-v1 帧 ---------------- */
const lp = (s) => { const b = Buffer.from(s, 'utf8'); const l = Buffer.alloc(4); l.writeUInt32BE(b.length); return Buffer.concat([l, b]); };
const u32 = (n) => { const b = Buffer.alloc(4); b.writeUInt32BE(n); return b; };

function openEnvelope(v, key) {
  const env = JSON.parse(Buffer.from(v.envelope, 'base64').toString('utf8'));
  const aad = Buffer.concat([
    Buffer.from('WB-AAD\0', 'ascii'), Buffer.from([1]),
    lp('WBEV1'), lp('sym-v1'), u32(env.suite || 1),
    lp(env.keyId || ''), Buffer.from([2]), Buffer.from([0]), Buffer.from([0]),
  ]);
  const d = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(env.nonce, 'base64'), { authTagLength: 16 });
  d.setAAD(aad);
  d.setAuthTag(Buffer.from(env.authTag, 'base64'));
  return Buffer.concat([d.update(Buffer.from(env.ciphertext, 'base64')), d.final()]).toString('utf8');
}

function plain(v, key) {
  if (typeof v === 'string') return v;
  if (v && typeof v === 'object' && v.$wbEncrypted === 1 && typeof v.envelope === 'string') {
    try { return openEnvelope(v, key); } catch (_) { return null; }
  }
  return null;
}

/* ---------------- 5. 采集所有账号 ---------------- */
function main() {
  if (typeof crypto.createDecipheriv !== 'function') {
    console.error('✗ Node 版本太低，请用 Node 18 或更高版本。');
    process.exit(2);
  }

  const exe = findExe();
  if (!exe) {
    console.error('✗ 没找到 WorkBuddy 客户端。请手动指定，例如：');
    console.error('  node get-token.cjs --exe "D:\\你的安装目录\\WorkBuddy\\WorkBuddy.exe"');
    console.error('  找法：任务管理器里右键 WorkBuddy 进程 → 打开文件所在的位置。');
    process.exit(2);
  }
  const info = findInfo();
  if (!info) {
    console.error('✗ 没找到登录态文件 workbuddy-desktop.info。');
    console.error('  请先打开客户端并确认已登录，然后完全退出、重新打开一次，再跑本脚本。');
    process.exit(2);
  }

  console.log('· 客户端：' + exe);
  console.log('· 登录态：' + info);

  let key = null;
  let keyErr = null;
  try {
    key = deriveKey(exe);
    console.log('· 密钥：已取得（256 bit，只在内存里）');
  } catch (e) {
    keyErr = e;
    console.log('· 密钥：取不到 —— ' + e.message);
    console.log('  若你的客户端是 5.6 之前的版本，登录态本来就是明文，先按明文试一次。');
  }

  const found = new Map();
  const harvest = (entry) => {
    if (!entry || typeof entry !== 'object') return;
    const acc = entry.account || entry;
    const auth = entry.auth || entry;
    const at = plain(auth.accessToken ?? auth.access_token ?? auth.token, key);
    const rt = plain(auth.refreshToken ?? auth.refresh_token, key);
    if (!at || !acc || !acc.uid) return;
    const exp = auth.expiresAt || 0;
    const prev = found.get(acc.uid);
    const nb = plain(acc.nickname, key);
    const name = (typeof nb === 'string' && nb.trim()) ? nb.trim() : String(acc.uid).slice(0, 8);
    if (!prev || exp > prev.expiresAt) {
      found.set(acc.uid, {
        name, uid: acc.uid,
        accessToken: at,
        refreshToken: rt || '',
        expiresAt: auth.expiresAt || null,
        refreshExpiresAt: auth.refreshExpiresAt || null,
      });
    }
  };

  const readOne = (file) => {
    try {
      const o = JSON.parse(fs.readFileSync(file, 'utf8'));
      harvest({ account: o.account, auth: o.auth });
      for (const a of o.accounts || []) harvest(a);
      for (const a of o.allAccounts || []) harvest(a);
    } catch (e) { console.error('  跳过 ' + path.basename(file) + '：' + e.message); }
  };

  readOne(info);
  // 切号会留下历史快照，第二个账号可能只在这里
  const dir = path.dirname(info);
  for (const n of fs.readdirSync(dir)) {
    if (n === path.basename(info)) continue;
    if (n.startsWith('workbuddy-desktop.') && n.includes('.info')) readOne(path.join(dir, n));
  }

  if (!found.size) {
    console.error('\n✗ 登录态里没有解出任何令牌。逐条排查：');
    console.error('  1) 客户端是否真的登录了（能正常用 AI 对话）？');
    console.error('  2) 完全退出客户端再打开，让它重写一次登录态；');
    console.error('  3) 是不是 5.6+ 的加密信封而我们取钥失败？把上面「密钥」那行报错发出来。');
    if (keyErr) console.error('  取钥报错原文：' + keyErr.message);
    process.exit(3);
  }

  const out = [...found.values()];
  fs.writeFileSync(OUT, JSON.stringify(out, null, 2), { mode: 0o600 });
  console.log('\n✓ 解出 ' + out.length + ' 个账号 → ' + OUT);
  for (const v of out) {
    const d = v.expiresAt ? ((v.expiresAt - Date.now()) / 86400000).toFixed(1) : '?';
    const rd = v.refreshExpiresAt ? ((v.refreshExpiresAt - Date.now()) / 86400000).toFixed(1) : '?';
    console.log(`  - ${v.name}：access 剩 ${d} 天，refresh 剩 ${rd} 天，refreshToken ${v.refreshToken ? '有' : '无'}`);
  }

  if (WANT_PRINT) {
    console.log('\n--- 完整令牌（填进 config.json 后请清屏 / 清命令历史）---');
    for (const v of out) console.log(`${v.name}\n  accessToken:  ${v.accessToken}\n  refreshToken: ${v.refreshToken}\n`);
  } else {
    console.log('\n下一步：把上面这些字段填进 config.json 的 accounts[]；');
    console.log('或者一条命令直接填（与本脚本同目录、已有 config.json 时）：');
    console.log('  node -e "const t=require(\'./tokens.json\'),c=require(\'./config.json\');c.accounts=t.map(x=>({name:x.name,uid:x.uid,accessToken:x.accessToken,refreshToken:x.refreshToken,expiresAt:x.expiresAt||0,refreshExpiresAt:x.refreshExpiresAt||0}));require(\'fs\').writeFileSync(\'config.json\',JSON.stringify(c,null,2));console.log(\'config.json 已填好\')"');
    console.log('\n填完就可以删掉 tokens.json 了（config.json 里已经有副本）。');
  }
}

main();
