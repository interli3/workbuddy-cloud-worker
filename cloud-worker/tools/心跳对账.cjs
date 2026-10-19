#!/usr/bin/env node
/**
 * 心跳对账 —— 一条命令回答三个问题：
 *   ① 该跑的时间点，有没有跑？（时间线）
 *   ② 是谁执行的？（executorNode = 云端 pod 名 / 本机）
 *   ③ 跑出了什么？（agentOutput = worker 当场回的业务结果）
 *
 * 为什么需要它：
 *   worker 的 /health 只保留「最后一次 tick」，谁查一次就覆盖一次 ⇒ 不能当证据。
 *   真正的留痕在【平台调度日志】里：每条带 executorNode（在哪台机器跑）+ agentOutput（跑出什么）。
 *   本脚本就是把这些日志翻译成人话，避免每次靠人肉慢慢翻。
 *
 * 用法：
 *   node tools/心跳对账.cjs          最近 10 次
 *   node tools/心跳对账.cjs 20       最近 20 次
 *
 * 配置（按优先级）：
 *   WBMON_CONFIG   config.json 路径（默认 ../config.json，即 cloud-worker/config.json）
 *   WBMON_TASK_ID  心跳任务 id（默认读 config 里的 cloud.heartbeatTaskId，再兜底 12707515）
 *
 * 判读要点（别误读）：
 *   · executorNode 是 agentserver-xxxx 这类名字 ⇒ 该次运行在【平台云端】，与本机开关机无关。
 *   · 本机开机时刻只能精确推断「本次开机之后」的时段；更早的时段无法用本机日志还原。
 *   · 所以证明「不依赖电脑」靠的是 executorNode，不是靠「当时电脑关着」。
 */
const fs = require('fs');
const os = require('os');
const path = require('path');

const CFG = process.env.WBMON_CONFIG || path.join(__dirname, '..', 'config.json');
const WB = 'https://www.workbuddy.cn';

const C = { ok: '\x1b[32m', wn: '\x1b[33m', er: '\x1b[31m', dim: '\x1b[2m', rst: '\x1b[0m' };
const fmt = (s) => new Date(s).toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-');
const hoursAgo = (s) => (Date.now() - new Date(s).getTime()) / 3600000;
const short = (t, n) => String(t || '').replace(/\s+/g, ' ').slice(0, n || 110);

function parseResult(agentOutput) {
  if (!agentOutput) return '';
  try {
    const o = JSON.parse(agentOutput);
    return String((o && o.data && o.data.result) || '').replace(/\s+/g, ' ').trim();
  } catch (e) {
    return String(agentOutput).replace(/\s+/g, ' ').slice(0, 300);
  }
}

(async () => {
  const bootAt = new Date(Date.now() - os.uptime() * 1000);
  console.log(`\n════════ 心跳对账 ${new Date().toLocaleString('zh-CN')} ════════`);
  console.log(`${C.dim}本机本次开机：${fmt(bootAt)}（已运行 ${Math.round(os.uptime() / 60)} 分钟）${C.rst}\n`);

  if (!fs.existsSync(CFG)) {
    console.log(`${C.er}找不到配置 ${CFG}${C.rst}\n把 WBMON_CONFIG 指到 config.json 即可。\n`);
    process.exit(1);
  }
  const cfg = JSON.parse(fs.readFileSync(CFG, 'utf8'));
  const H = {
    'Authorization': 'Bearer ' + cfg.accounts[0].accessToken,
    'Accept': 'application/json', 'Origin': WB, 'Referer': WB + '/',
    'x-codebuddy-request': '1', 'x-client-platform': 'web',
  };
  const TASK_ID = process.env.WBMON_TASK_ID || (cfg.cloud && cfg.cloud.heartbeatTaskId) || 12707515;
  const N = Math.min(Math.max(Number(process.argv[2] || 10), 1), 50);

  let task = {};
  try {
    task = (await (await fetch(`${WB}/v2/as/scheduler/tasks/${TASK_ID}`, { headers: H })).json()).data || {};
    const cf = String(task.cronExpr || '').split(' ');
    const hh = (cf[2] || '?').split(',').map((h) => `${h}:${(cf[1] || '0').padStart(2, '0')}`).join(' / ');
    console.log(`【任务】${task.name || TASK_ID}｜${task.status === 1 ? C.ok + 'ACTIVE' : C.er + '非活跃'}${C.rst}｜每天 ${hh}｜下次 ${task.nextRunAt || '?'}`);
  } catch (e) {
    console.log(`${C.er}任务信息读取失败：${e.message}${C.rst}`);
  }

  let logs = [];
  try {
    logs = ((await (await fetch(`${WB}/v2/as/scheduler/tasks/${TASK_ID}/logs?page=1&page_size=${N}`, { headers: H })).json()).data || {}).logs || [];
  } catch (e) {
    console.log(`${C.er}日志读取失败：${e.message}${C.rst}\n`);
    process.exit(1);
  }
  if (!logs.length) {
    console.log(`\n${C.wn}⚠️ 没有任何运行记录 —— 这才是真该修的问题。${C.rst}\n`);
    process.exit(0);
  }

  console.log(`\n【最近 ${logs.length} 次运行】`);
  const rows = [];
  for (const x of logs) {
    let node = x.executorNode || '?', out = '';
    try {
      const d = (await (await fetch(`${WB}/v2/as/scheduler/tasks/${TASK_ID}/logs/${x.id}`, { headers: H })).json()).data || {};
      node = d.executorNode || node;
      out = parseResult(d.agentOutput);
    } catch (e) { /* 详情拿不到就不填，不编 */ }
    rows.push({ ...x, node, out });
  }

  for (const r of rows) {
    const auto = r.triggerType === 'scheduled';
    const cloud = /agentserver|server|pod|cloud/i.test(r.node);
    const tag = auto ? (r.status === 'success' ? C.ok + '自动·成功' + C.rst : C.er + '自动·失败' + C.rst)
                     : (C.wn + '手动' + C.rst);
    const where = cloud ? C.ok + '云端节点' + C.rst : C.wn + '非云端' + C.rst;
    console.log(`  ${tag}  ${fmt(r.startTime)}  ${where}(${r.node})  ${Math.round((r.durationMs || 0) / 1000)}s`);
    if (r.out) console.log(`      ${C.dim}└─ ${short(r.out, 120)}${C.rst}`);
  }

  console.log(`\n════════ 结论 ════════`);
  const autoOk = rows.filter((r) => r.triggerType === 'scheduled' && r.status === 'success');
  const cloudAll = rows.every((r) => /agentserver|server|pod|cloud/i.test(r.node));
  const latest = rows[0];
  const h = hoursAgo(latest.startTime);

  console.log(`  · 最近一次运行：${fmt(latest.startTime)}（${h.toFixed(1)} 小时前）｜${latest.status}`);
  console.log(`  · 自动触发成功次数：${autoOk.length} / 最近 ${rows.length} 次`);
  console.log(`  · 执行位置：${cloudAll ? C.ok + '全部在平台云端节点（与本机开关机无关）' + C.rst : C.wn + '存在非云端节点，需细看' + C.rst}`);

  const cf = String(task.cronExpr || '').split(' ');
  const perDay = (cf[2] || '').split(',').filter(Boolean).length || 1;
  if (h > (24 / perDay) * 1.6) {
    console.log(`  ${C.er}❌ 最近一次已超过预期间隔（每天 ${perDay} 次），心跳可能断了。${C.rst}`);
  } else {
    console.log(`  ${C.ok}✅ 节奏正常，最近一次在预期间隔内。${C.rst}`);
  }
  const failed = rows.filter((r) => r.status !== 'success');
  if (failed.length) console.log(`  ${C.wn}⚠️ 有 ${failed.length} 次失败：${failed.map((f) => fmt(f.startTime)).join('、')}${C.rst}`);

  if (task.nextRunAt && new Date(task.nextRunAt).getTime() < Date.now() - 3600000) {
    console.log(`  ${C.er}❌ nextRunAt 已过期，调度器可能停了。${C.rst}`);
  }
  console.log('');
})();
