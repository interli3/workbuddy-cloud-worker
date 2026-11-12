#!/usr/bin/env node
/**
 * 心跳告警 —— 定期体检一次，出问题就留一份「看得见」的记录
 *
 * 用法：
 *   node tools/心跳告警.cjs              检查（退出码 0=正常 1=异常 2=配置缺失）
 *   node tools/心跳告警.cjs --selftest   强制造一条假告警，验证告警链路（产物带 -selftest 后缀）
 *
 * 建议挂在系统计划任务里（每天一次，错过则开机后补跑），例如 Windows：
 *   schtasks /create /tn "WB心跳每日体检" /sc daily /st 12:00 /tr "node \"<路径>\tools\心跳告警.cjs\""
 *
 * 判异规则（任一命中即告警）：
 *   1) 最近一次心跳不是 success
 *   2) 距最近一次成功心跳 > 26 小时
 *   3) nextRunAt 已过期超过 1 小时
 *   4) 今天有账号没签到
 *   5) 令牌 access < 7 天
 *
 * 环境变量：
 *   WBMON_CONFIG         config.json 路径（默认 ../config.json）
 *   WBMON_TASK_ID        心跳任务 id（默认 config.cloud.heartbeatTaskId，再兜底 12707515）
 *   WBMON_ALERT_DIR      告警文件目录（默认 ../alerts）
 *   WBMON_ALERT_DESKTOP  设为 1 时，额外在桌面放一个 ⚠️ 文本，方便一眼看见
 */
const fs = require('fs');
const os = require('os');
const path = require('path');

const CFG = process.env.WBMON_CONFIG || path.join(__dirname, '..', 'config.json');
const OUT_DIR = process.env.WBMON_ALERT_DIR || path.join(__dirname, '..', 'alerts');
const DESKTOP_ON = process.env.WBMON_ALERT_DESKTOP === '1';
const DESKTOP = path.join(os.homedir(), 'Desktop');
const WB = 'https://www.workbuddy.cn';

const cnDate = () => new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10);
const SELFTEST = process.argv.includes('--selftest');
const SUFFIX = SELFTEST ? '-selftest' : '';
const fmt = (s) => new Date(s).toLocaleString('zh-CN', { hour12: false });

(async () => {
  const issues = [];
  const facts = [];
  if (!fs.existsSync(CFG)) {
    console.error('找不到配置：' + CFG);
    process.exit(2);
  }
  const cfg = JSON.parse(fs.readFileSync(CFG, 'utf8'));
  const TASK_ID = process.env.WBMON_TASK_ID || (cfg.cloud && cfg.cloud.heartbeatTaskId) || 12707515;
  const H = {
    'Authorization': 'Bearer ' + cfg.accounts[0].accessToken,
    'Accept': 'application/json', 'Origin': WB, 'Referer': WB + '/',
    'x-codebuddy-request': '1', 'x-client-platform': 'web',
  };

  // 1) 任务与最近一次运行
  let task = {};
  try {
    task = (await (await fetch(`${WB}/v2/as/scheduler/tasks/${TASK_ID}`, { headers: H, signal: AbortSignal.timeout(20000) })).json()).data || {};
    const logs = ((await (await fetch(`${WB}/v2/as/scheduler/tasks/${TASK_ID}/logs?page=1&page_size=5`, { headers: H, signal: AbortSignal.timeout(20000) })).json()).data || {}).logs || [];
    if (!logs.length) {
      issues.push('查不到任何心跳运行记录');
    } else {
      const last = logs[0];
      facts.push(`最近一次：${fmt(last.startTime)}｜${last.status}｜${last.triggerType}｜${last.executorNode}`);
      if (last.status !== 'success') issues.push(`最近一次心跳不是成功（${last.status}）`);
      const okRun = logs.find((x) => x.status === 'success');
      if (!okRun) issues.push('最近 5 次里没有一次成功');
      else {
        const h = (Date.now() - new Date(okRun.startTime).getTime()) / 3600e3;
        if (h > 26) issues.push(`距最近一次成功心跳已 ${h.toFixed(1)} 小时（>26 小时，视为断了）`);
      }
    }
    facts.push(`任务状态：${task.status === 1 ? 'ACTIVE' : '非活跃'}｜cron ${task.cronExpr || '?'}｜下次 ${task.nextRunAt || '?'}`);
    if (task.status !== 1) issues.push('心跳任务不是 ACTIVE');
    if (task.nextRunAt && new Date(task.nextRunAt).getTime() < Date.now() - 3600e3) {
      issues.push('nextRunAt 已过期超过 1 小时，调度器可能停了');
    }
  } catch (e) {
    issues.push('读取心跳任务失败：' + String(e.message || e).slice(0, 80));
  }

  // 2) 今日业务与令牌
  //    ⚠️ 托管链接必须带 ?sharecode=（对外分享链接里那串），缺了会被网关拦成
  //       403 + 一张「链接不完整」的网页，而不是报连不上。别把网页当 JSON 解析。
  const ep = (cfg.cloud && cfg.cloud.endpoint) || '';
  const share = (cfg.cloud && cfg.cloud.sharecode) || '';
  const today = cnDate();
  let st = null;
  try {
    if (!ep) throw new Error('配置文件缺 cloud.endpoint');
    const qs = '?key=' + encodeURIComponent(cfg.access_key) + (share ? '&sharecode=' + encodeURIComponent(share) : '');
    const r = await fetch(`${ep}/status${qs}`, { signal: AbortSignal.timeout(20000) });
    const ct = r.headers.get('content-type') || '';
    if (!r.ok || !/json/i.test(ct)) {
      throw new Error(`HTTP ${r.status}，返回 ${(ct.split(';')[0] || '空类型')} 网页而非数据` +
        (share ? '（sharecode 可能已失效）' : '（cloud.sharecode 未配置，托管链接缺它必被拦成 403）'));
    }
    st = (await r.json()).state || {};
  } catch (e) {
    issues.push('云端服务连不上：' + String(e.message || e).slice(0, 90));
  }

  if (st) {
    for (const [name, v] of Object.entries(st.accounts || {})) {
      const c = v.checkin || {};
      const ok = c.day === today && c.done;
      facts.push(`签到 ${name}：${ok ? '已完成 +' + (c.credit ?? '?') + '｜连续 ' + (c.streak ?? '?') + ' 天' : '未完成（' + (c.note || '无记录') + '）'}`);
      if (!ok) issues.push(`${name} 今天未签到（${c.note || '无记录'}）`);
    }
    for (const t of st.tokenInfo || []) {
      facts.push(`令牌 ${t.name}：剩 ${t.daysLeft} 天`);
      if (t.daysLeft < 7) issues.push(`${t.name} 令牌 ${t.daysLeft} 天后到期`);
    }
  } else {
    // 云端读不到 → 直连平台只读接口，确认今天到底有没有漏签（避免"服务挂了"被误报成"漏签"）
    for (const a of cfg.accounts || []) {
      const nm = a.name || '?';
      try {
        const r = await fetch(`${WB}/v2/billing/meter/checkin-activity-status`, {
          method: 'POST',
          headers: { ...H, 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + a.accessToken },
          body: '{}', signal: AbortSignal.timeout(15000),
        });
        const d = (await r.json()).data || {};
        const ok = d.today_checked_in === true;
        facts.push(`签到 ${nm}（平台直连）：${ok ? '已完成 +' + (d.today_credit ?? '?') + '｜连续 ' + (d.streak_days ?? '?') + ' 天' : '未完成'}`);
        if (!ok) issues.push(`${nm} 今天未签到`);
      } catch (e2) {
        facts.push(`签到 ${nm}（平台直连）：没查到（${String(e2.message || e2).slice(0, 40)}）`);
      }
    }
    // config.json 是部署时快照、云端会自动续期，拿它估令牌天数必然误报 → 不判断
    facts.push('令牌剩余天数：云端读不到，本机配置不是最新，不做判断');
  }

  const stamp = new Date().toLocaleString('zh-CN', { hour12: false });
  if (SELFTEST) issues.push('自检：故意造一条测试告警，验证告警链路');
  if (!issues.length) {
    console.log(`[OK] ${stamp} 心跳正常｜${facts[0] || ''}`);
    process.exit(0);
  }

  try { fs.mkdirSync(OUT_DIR, { recursive: true }); } catch (e) { /* 忽略 */ }
  const md = [
    `# 心跳异常告警 ${stamp}`,
    '',
    '## 命中的问题',
    ...issues.map((x, i) => `${i + 1}. ${x}`),
    '',
    '## 现场事实（未加工）',
    ...facts.map((x) => `- ${x}`),
    '',
    '## 怎么处理',
    '1. 先跑 `node tools/心跳对账.cjs 8` 看完整时间线',
    '2. 【常见】云端连不上且报 403 / 返回网页 → 查 `config.cloud.endpoint` 的链接是否缺 `?sharecode=`：' +
      '平台给的对外分享链接形如 `https://xxx.app.workbuddy.host/?sharecode=XXXXXXXX`，' +
      '程序调用接口时也要带上这串（写成 `?key=...&sharecode=...`）。缺了不会报"应用已停"，只会返回一张「链接不完整」的网页',
    '3. 任务非 ACTIVE → 需要重新激活心跳',
    '4. 只是今天漏签 → 手动触发一次：`POST /v2/as/scheduler/tasks/<id>/trigger`',
    '5. 令牌到期 → 重新登录取新令牌（见 docs/获取令牌.md）',
    '',
  ].join('\n');
  const mdPath = path.join(OUT_DIR, `心跳异常-${cnDate()}${SUFFIX}.md`);
  fs.writeFileSync(mdPath, md, 'utf8');

  if (DESKTOP_ON) {
    try {
      fs.writeFileSync(path.join(DESKTOP, `⚠️心跳异常-${cnDate()}${SUFFIX}.txt`),
        `心跳出问题了（${stamp}）\n\n` + issues.map((x) => '· ' + x).join('\n') + `\n\n详情：${mdPath}\n`, 'utf8');
    } catch (e) { /* 桌面写不了就算了 */ }
  }

  console.log(`[ALERT] ${stamp} ${issues.length} 个问题：${issues.join('；')}`);
  console.log(`  详情：${mdPath}`);
  process.exit(1);
})();
