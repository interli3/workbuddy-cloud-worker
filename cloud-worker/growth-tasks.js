// growth-tasks.js —— 成长任务自动化模块（纯接口配方，移植自社区验证方案 purepoorx/WorkBuddy-Daily）
// 由 server.js 每 20 分钟调用一次：查缺口 → 补埋点/真实对话 → 领奖
'use strict';

const crypto = require('crypto');

const BASE = 'https://www.workbuddy.cn';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) WorkBuddy/5.5.4 Chrome/138.0.7204.251 Electron/37.10.3 Safari/537.36';

// 🔴 抱豹指令：换主题任务跳过不做（Hp_Appearance 有真实副作用）
const SKIP_TASKS = new Set(['Hp_Appearance']);

function deriveId(uid, salt) {
  return crypto.createHash('md5').update(salt + ':' + uid).digest('hex').slice(0, 36);
}

function uidOf(tok) {
  try {
    const p = tok.split('.')[1];
    const j = JSON.parse(Buffer.from(p, 'base64url').toString('utf8'));
    return j.sub || '';
  } catch { return ''; }
}

function nickOf(tok) {
  try {
    const p = tok.split('.')[1];
    const j = JSON.parse(Buffer.from(p, 'base64url').toString('utf8'));
    return j.nickname || j.preferred_username || '';
  } catch { return ''; }
}

// ---------- 请求 ----------
async function req(token, method, path, body, opts = {}) {
  const uid = uidOf(token);
  const headers = {
    'Authorization': 'Bearer ' + token,
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/plain, */*',
    'Origin': BASE, 'Referer': BASE + '/profile/growth-center',
    'User-Agent': UA, 'X-User-Id': uid,
    'x-codebuddy-request': '1', 'x-client-platform': 'web',
  };
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), opts.timeoutMs || 25000);
  try {
    const init = { method, headers, signal: ctrl.signal };
    if (body !== undefined) init.body = JSON.stringify(body);
    const r = await fetch(BASE + path, init);
    const text = await r.text();
    let j = null; try { j = JSON.parse(text); } catch {}
    return { status: r.status, json: j, raw: text };
  } catch (e) {
    return { status: -1, json: null, raw: String(e && e.message || e) };
  } finally { clearTimeout(t); }
}

// ---------- 遥测上报（进度判定的核心） ----------
async function report(token, events) {
  const uid = uidOf(token);
  const nick = nickOf(token);
  const now = Date.now();
  const out = events.map(e => Object.assign({
    timestamp: now, reportDelay: 0,
    userId: uid, userNickname: nick,
    ideName: 'WorkBuddy', ideType: 'WorkBuddy', ideVersion: '5.5.6',
    machineId: deriveId(uid, 'machine'), sessionId: deriveId(uid, 'session'),
    mode: 'CLOUD', userAgent: UA, os: 'Win32', arch: 'x64',
    osVersion: '10.0.26220', timezone: 'Asia/Shanghai',
    product: 'SaaS', releaseDate: 1789036585355,
    commit: '5f9692923c93033111c51ad7b003eb80204a9b75',
    extName: 'workbuddy-desktop', extVersion: '5.5.6',
    cpuCores: 20, memorySize: 24,
  }, e));
  return req(token, 'POST', '/v2/report', out);
}

// ---------- 任务查询 / 领奖 ----------
async function taskProgress(token, code) {
  const r = await req(token, 'GET', '/v2/activity/growth/tasks');
  const tasks = (r.json && r.json.data && r.json.data.tasks) || [];
  for (const t of tasks) {
    if (t.task_code === code) {
      const p = t.progress || {};
      return { status: t.accept_status, cur: p.current, tgt: p.target };
    }
  }
  return null;
}

async function allTasks(token) {
  const r = await req(token, 'GET', '/v2/activity/growth/tasks');
  return (r.json && r.json.data && r.json.data.tasks) || [];
}

async function claim(token, code) {
  let r = await req(token, 'POST', '/activity/growth/tasks/' + code + '/claim', {});
  if (r.status === 400) {
    r = await req(token, 'POST', '/activity/growth/tasks/' + code + '/claim', {}, {
      headers: { 'Origin': BASE, 'Referer': BASE + '/profile/growth-center', 'x-client-platform': 'web' },
    });
  }
  return r;
}

async function acceptMissing(token) {
  const tasks = await allTasks(token);
  const todo = tasks.filter(t => t.accept_status === 'not_accepted' && !SKIP_TASKS.has(t.task_code))
    .map(t => t.task_code);
  if (!todo.length) return [];
  const r = await req(token, 'POST', '/activity/growth/tasks/accept', { task_codes: todo });
  const results = (r.json && r.json.data && r.json.data.results) || [];
  // 🔴 顶层 code=0 不代表逐条成功（prerequisite not met 会整体 error）——只认 status=accepted
  return results.filter(x => x.status === 'accepted');
}

// ---------- 真实对话（chat 类任务用：web 端 glm-5.2，零成本） ----------
async function webchat(token, prompt, opts = {}) {
  const conv = await req(token, 'POST', '/console/webchat/conversations',
    { name: (opts.convName || 'growth') + '-' + crypto.randomUUID().slice(0, 8) });
  const convId = (conv.json && conv.json.data && conv.json.data.conversationId) || '';
  if (!convId) return { convId: '', txt: '' };
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), 90000);
  let txt = '';
  const payload = { messages: [{ role: 'user', content: prompt }], model: 'glm-5.2', stream: true, conversationId: convId };
  // 🔴 专家团/专家必须带 _meta，服务端才把这次会话记为「专家(团)实际使用」
  if (opts.meta) payload._meta = opts.meta;
  try {
    const r = await fetch(BASE + '/console/chat/completions', {
      method: 'POST', signal: ctrl.signal,
      headers: {
        'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
        'Accept': 'text/event-stream', 'Origin': BASE, 'Referer': BASE + '/',
        'User-Agent': UA, 'x-codebuddy-request': '1', 'x-client-platform': 'web',
      },
      body: JSON.stringify(payload),
    });
    const reader = r.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      buf += dec.decode(part.value, { stream: true });
      const lines = buf.split('\n'); buf = lines.pop() || '';
      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        const d = line.slice(6).trim();
        if (d === '[DONE]' || d === '[完成]') { }
        try {
          const jj = JSON.parse(d);
          for (const c of jj.choices || []) {
            const cp = c.delta && c.delta.content;
            if (cp) txt += cp;
          }
        } catch {}
      }
    }
  } catch {} finally { clearTimeout(t); }
  return { convId, txt };
}

function chatEvents(token, convId, prompt, txt) {
  const uid = uidOf(token), nick = nickOf(token);
  const now = Date.now();
  const rid = 'cmb-' + crypto.randomUUID();
  const common = { userId: uid, userNickname: nick, ideName: 'web-Agents', ideType: 'web-Agents',
    machineId: deriveId(uid, 'machine'), mode: 'CLOUD', userAgent: UA, os: 'Win32', timezone: 'Asia/Shanghai' };
  return [
    { eventCode: 'chat_request_send', conversationId: convId, requestId: rid, requestModelId: 'glm-5.2',
      requestModelName: 'GLM-5.2', inputLength: prompt.length, customAgentName: '', ...common },
    { eventCode: 'chat_request_response', timestamp: now + 100, conversationId: convId, requestId: rid,
      requestModelId: 'glm-5.2', requestModelName: 'GLM-5.2', toolCallCount: 0,
      inputToken: Math.max(1, Math.floor(prompt.length / 4)), outputToken: Math.max(1, Math.floor(txt.length / 4)),
      totalToken: Math.max(2, Math.floor((prompt.length + txt.length) / 4)), ...common },
    { eventCode: 'chat_message_send', timestamp: now + 50, conversationId: convId, requestId: rid,
      messageId: 'cmb-' + crypto.randomUUID(), requestModelId: 'glm-5.2', requestModelName: 'GLM-5.2',
      historyCount: 1, isContextTruncated: false, currentStepCount: 1, traceId: rid, rootRequestId: rid,
      parentConversationId: convId, agentName: 'cli', agentType: 'main', ...common },
  ];
}

// ---------- 专家名单（从官方市场真实拉取） ----------
async function getExperts(token, count) {
  const r = await req(token, 'GET', '/api/v2/open-platform/experts/search?q=&page=1&page_size=' + (count || 20));
  const d = (r.json && r.json.data) || {};
  const list = d.experts || d.list || d.items || [];
  return list.map(e => ({ id: e.id || e.expert_id, name: e.name || e.title || 'Expert', profession: e.profession || e.industry || '' }));
}

// ---------- 专家团名单（官方市场公开配置） ----------
const EXPERT_MARKET = 'https://acc-1258344699.cos.accelerate.myqcloud.com/workbuddy/expert-marketplace/expert_center.json';
let _teamCache = null;
function cn(v) { if (v && typeof v === 'object') return v.zh || v.en || ''; return v || ''; }
async function getTeams(count) {
  if (!_teamCache) {
    try {
      const r = await fetch(EXPERT_MARKET, { headers: { 'User-Agent': UA, 'Accept': 'application/json' } });
      const j = await r.json();
      _teamCache = (j.experts || [])
        .filter(e => (e._meta && e._meta.expertType === 'team') || e.expertType === 'team')
        .map(e => ({
          id: e.id,
          name: cn(e.displayName) || cn(e.name) || 'Expert Team',
          profession: cn(e.profession),
          industryId: (e._meta && e._meta.industryId) || e.industryId || '',
        }));
    } catch { _teamCache = []; }
  }
  return _teamCache.slice(0, count || 10);
}

let _expertCache = null;
async function getNormalExperts(count) {
  if (!_expertCache) {
    try {
      const r = await fetch(EXPERT_MARKET, { headers: { 'User-Agent': UA, 'Accept': 'application/json' } });
      const j = await r.json();
      _expertCache = (j.experts || []).map(e => ({
        id: e.id, name: cn(e.displayName) || cn(e.name) || 'Expert', profession: cn(e.profession),
      }));
    } catch { _expertCache = []; }
  }
  return _expertCache.slice(0, count || 20);
}

// 桌面事件信封（{common, events}，与 /v2/report 的数组信封不同）
function desktopFingerprint(token) {
  const uid = uidOf(token), nick = nickOf(token), now = Date.now();
  return {
    timezone: 'Asia/Shanghai', reportDelay: 2000, userId: uid, username: nick, userNickname: nick,
    product: 'SaaS', releaseDate: 1789036585355, commit: '5f9692923c93033111c51ad7b003eb80204a9b75',
    ideName: 'WorkBuddy', ideType: 'WorkBuddy', ideVersion: '5.5.6',
    machineId: deriveId(uid, 'machine'), sessionId: deriveId(uid, 'session'),
    extName: 'workbuddy-desktop', extVersion: '5.5.6',
    os: 'win32', arch: 'x64', osVersion: '10.0.26220', cpuCores: 20, memorySize: 24,
    timestamp: now, presentAt: now,
  };
}

async function reportDesktop(token, events) {
  const fp = desktopFingerprint(token);
  const arr = events.map(e => Object.assign({}, e, fp));
  const out = {
    common: { userId: fp.userId, userNickname: fp.userNickname, ideName: 'WorkBuddy', ideType: 'WorkBuddy',
      machineId: fp.machineId, mode: 'LOCAL', userAgent: UA, os: 'win32', timezone: 'Asia/Shanghai' },
    events: arr,
  };
  return req(token, 'POST', '/v2/report', out);
}

// ---------- 逐任务配方 ----------
const uuid = () => crypto.randomUUID();

async function tExpert5(token, log) {
  for (let i = 0; i < 5; i++) {
    const p = await taskProgress(token, 'expert_5');
    if (!p || p.status === 'completed' || p.status === 'claimed' || (p.cur || 0) >= (p.tgt || 5)) break;
    let experts = [];
    try { experts = await getExperts(token, 20); } catch {}
    const e = experts[i % Math.max(1, experts.length)] || { id: 'expert-' + uuid().slice(0, 8), name: 'Expert', profession: '' };
    await report(token, [
      { eventCode: 'expert_summoned', id: e.id, name: e.name, type: 'agent', expertTitle: e.profession, expertType: 'agent' },
      { eventCode: 'expert_actual_use', id: e.id, name: e.name, type: '', expertType: 'agent', source: 'builtin',
        version: '', cost: 0, characterCount: 12, conversationId: 'conv-' + uuid(), requestId: uuid(),
        messageId: 'msg-' + uuid(), requestModelId: 'deepseek-v4-flash', requestModelName: 'DeepSeek V4 Flash' },
    ]);
    await sleep(3000);
  }
  return taskProgress(token, 'expert_5');
}

async function tExpertTeam3(token, log) {
  const uid = uidOf(token), nick = nickOf(token);
  let teams = [];
  try { teams = await getTeams(12); } catch {}
  if (!teams.length) { log('  ⚠️ 专家团列表为空，跳过'); return taskProgress(token, 'Expert_team_use_3'); }
  for (let rd = 0; rd < 3; rd++) {
    const p = await taskProgress(token, 'Expert_team_use_3');
    if (!p || p.status === 'completed' || p.status === 'claimed' || (p.cur || 0) >= (p.tgt || 3)) break;
    const team = teams[rd % teams.length];
    const prompt = '你好，请简单介绍一下你们团队能帮我做什么，回答OK即可';
    const reqId = uuid(), msgId = 'cmb-' + uuid();
    // ① 会话内嵌 growthEvent（服务端在对话完成时读取，把本次算作「专家团实际使用」）
    const ge = [{
      eventCode: 'ExpertActualUse', id: team.id, expertType: 'team',
      extra: {
        name: team.name, expertTitle: team.profession, type: team.industryId || '',
        expertType: 'team', source: 'builtin', version: '', cost: 8, characterCount: prompt.length,
        requestId: reqId, messageId: msgId, requestModelId: 'glm-5.2', requestModelName: 'GLM-5.2',
      },
    }];
    const meta = {
      'codebuddy.ai': {
        growthEvent: JSON.stringify(ge), promptRequestId: reqId, clientSendTime: Date.now(),
        userId: uid, mode: 'craft', model: 'glm-5.2', expertId: team.id,
        expert: { id: team.id, name: team.name, profession: team.profession, prompt: prompt.slice(0, 50) },
        tags: ['expert:' + team.id],
      },
    };
    // ② 真实发起一次团队对话
    const w = await webchat(token, prompt, { meta, convName: 'team' });
    // ③ 常规遥测：expert_actual_use（expertType=team）
    await report(token, [{
      eventCode: 'expert_actual_use', id: team.id, name: team.name, expertTitle: team.profession,
      type: team.industryId || '', expertType: 'team', source: 'builtin', version: '', cost: 8,
      characterCount: prompt.length, conversationId: (w && w.convId) || '', requestId: reqId,
      messageId: msgId, requestModelId: 'glm-5.2', requestModelName: 'GLM-5.2',
    }]);
    log('  专家团[' + (rd + 1) + '/3] ' + team.name + (w && w.txt ? ' ✓' : ' (空响应)'));
    await sleep(5000);
  }
  return taskProgress(token, 'Expert_team_use_3');
}

async function tExpertLighthouse(token, log) {
  const p = await taskProgress(token, 'Expert_lighthouse');
  if (!p || p.status === 'completed' || p.status === 'claimed') return p;
  let lh = null;
  try {
    const list = await getNormalExperts(80);
    lh = list.find(e => /轻量|lighthouse/i.test(e.name) || /lighthouse/i.test(e.id));
  } catch {}
  if (!lh) lh = { id: 'expert-lh-' + uuid().slice(0, 8), name: '轻量云专家', profession: '' };
  const rid = uuid(), cid = 'conv-' + uuid();
  await report(token, [
    { eventCode: 'expert_summoned', id: lh.id, name: lh.name, type: 'agent', expertTitle: lh.profession, expertType: 'agent', source: 'builtin' },
    { eventCode: 'expert_actual_use', id: lh.id, name: lh.name, expertTitle: lh.profession, type: 'agent', expertType: 'agent',
      source: 'builtin', version: '', cost: 0, characterCount: 12, conversationId: cid, requestId: rid, messageId: rid,
      requestModelId: 'deepseek-v4-flash', requestModelName: 'DeepSeek V4 Flash' },
  ]);
  await sleep(3000);
  return taskProgress(token, 'Expert_lighthouse');
}

async function tBuddyApp(token, log) {
  const p = await taskProgress(token, 'Buddy_App');
  if (!p || p.status === 'completed' || p.status === 'claimed') return p;
  const id = 'buddy-app-default', name = '发现应用';
  const mk = (code, extra) => Object.assign({ eventCode: code, mode: 'LOCAL', buddyId: id, buddyName: name }, extra || {});
  await reportDesktop(token, [
    mk('buddyapp_discover_click', {}),
    mk('buddyapp_show', { elementId: id, elementName: name, position: 2 }),
    mk('buddyapp_enter_click', { elementId: id, elementName: name, position: 2, isFirstPage: '1' }),
    mk('buddyapp_auth_confirm_click', { elementId: id, elementName: name }),
    mk('buddyapp_bindaccount_skip_click', { elementId: id, elementName: name }),
  ]);
  await sleep(4000);
  return taskProgress(token, 'Buddy_App');
}

async function tTemplate5(token, log) {
  const scenes = [
    { id: '01-ProductDesign', name: '产品设计' }, { id: '02-Marketing', name: '营销文案' },
    { id: '03-DataAnalysis', name: '数据分析' }, { id: '04-CodeReview', name: '代码审查' },
    { id: '05-Report', name: '报告撰写' },
  ];
  for (const sc of scenes) {
    const p = await taskProgress(token, 'template_5');
    if (!p || p.status === 'completed' || p.status === 'claimed' || (p.cur || 0) >= (p.tgt || 5)) break;
    await report(token, [
      { eventCode: 'agent_task_created', source: 'CLOUD', name: '', mode: 'craft', requestModelId: 'default', action: sc.id, has_template: true, template_id: sc.id, template_name: sc.name },
      { eventCode: 'agent_task_created_with_template', templateId: sc.id, templateName: sc.name, isCustomModel: true, id: sc.id, name: sc.name },
      { eventCode: 'playbook_prompt_send', ext1: uuid(), requestId: uuid(), id: sc.id, name: sc.name, type: 'other', promptLength: 30, isOfficial: 1, source: 'growth-center' },
    ]);
    await sleep(2000);
  }
  return taskProgress(token, 'template_5');
}

async function tCanvasAutomationPlaybook(token, log) {
  // create_canvas
  let p = await taskProgress(token, 'create_canvas');
  if (p && p.status !== 'completed' && p.status !== 'claimed') {
    await report(token, [
      { eventCode: 'agent_task_created', source: 'CLOUD', name: '', mode: 'craft', requestModelId: 'default', task_mode: 'design' },
      { eventCode: 'wbx_design_canvas_task_create' },
    ]);
    await sleep(3000);
  }
  // automation_1
  p = await taskProgress(token, 'automation_1');
  if (p && p.status !== 'completed' && p.status !== 'claimed') {
    await report(token, [
      { eventCode: 'agent_task_created', source: 'CLOUD', name: '', mode: 'craft', requestModelId: 'default', task_mode: 'automation', isAutomationBackground: true },
      { eventCode: 'automated_task_create_suc', action: 'create' },
      { eventCode: 'automated_task_execute', action: 'execute' },
    ]);
    await sleep(3000);
  }
  // playbook_prompt
  p = await taskProgress(token, 'playbook_prompt');
  if (p && p.status !== 'completed' && p.status !== 'claimed') {
    await report(token, [
      { eventCode: 'playbook_prompt_send', ext1: uuid(), requestId: uuid(), id: '01-ProductDesign', name: '产品设计', type: 'other', promptLength: 30, isOfficial: 1, source: 'growth-center' },
    ]);
    await sleep(3000);
  }
  return Promise.all([taskProgress(token, 'create_canvas'), taskProgress(token, 'automation_1'), taskProgress(token, 'playbook_prompt')]);
}

async function tLibrary(token, log) {
  const p = await taskProgress(token, 'Library_read');
  if (!p || p.status === 'completed' || p.status === 'claimed') return p;
  const uid = uidOf(token), nick = nickOf(token);
  await report(token, [{ eventCode: 'web_element_click', page_url: 'https://www.workbuddy.cn/library',
    element_id: 'library_doc_intro_click', element_name: 'WorkBuddy资料库介绍' }]);
  await sleep(6000);
  return taskProgress(token, 'Library_read');
}

async function tSkill1(token, log) {
  const p = await taskProgress(token, 'skill_1');
  if (!p || p.status === 'completed' || p.status === 'claimed') return p;
  // API 安装一个技能（真实动作）
  try {
    const src = await req(token, 'GET', '/console/as/marketplace/sources');
    const sources = (src.json && src.json.data && src.json.data.sources) || [];
    const mid = sources[0] && sources[0].id;
    if (mid) {
      await req(token, 'POST', '/console/as/user/plugins/install',
        { plugin_name: 'qq-mail', marketplace_id: mid, version: 'latest' }, { timeoutMs: 30000 });
    }
  } catch {}
  await report(token, [{ eventCode: 'skill_info', skillId: 'skill-' + deriveId(uidOf(token), 'skill').slice(0, 12),
    skillName: 'QQ邮箱', mode: 'LOCAL', source: 'builtin' }]);
  await sleep(5000);
  return taskProgress(token, 'skill_1');
}

async function tRichMeow(token, log) {
  const p = await taskProgress(token, 'RichMeow_Chat');
  if (!p || p.status === 'completed' || p.status === 'claimed') return p;
  const uid = uidOf(token), nick = nickOf(token);
  const conv = 'fp-rm-' + deriveId(uid, 'rm-conv');
  const reqId = 'fp-rm-req-' + deriveId(uid, 'rm-req');
  const msg = 'fp-rm-msg-' + deriveId(uid, 'rm-msg');
  const now = Date.now();
  await report(token, [
    { eventCode: 'desktop_chat_sequence', conversationId: conv, requestId: reqId, messageId: msg, step: 1, action: 'open_app' },
    { eventCode: 'desktop_chat_sequence', conversationId: conv, requestId: reqId, messageId: msg, step: 2, action: 'new_conversation' },
    { eventCode: 'desktop_chat_sequence', conversationId: conv, requestId: reqId, messageId: msg, step: 3, action: 'send_message' },
    { eventCode: 'desktop_chat_sequence', conversationId: conv, requestId: reqId, messageId: msg, step: 4, action: 'receive_response' },
    { eventCode: 'desktop_chat_sequence', conversationId: conv, requestId: reqId, messageId: msg, step: 5, action: 'conversation_end' },
    { eventCode: 'desktop_chat_sequence', conversationId: conv, requestId: reqId, messageId: msg, step: 6, action: 'close' },
  ]);
  await sleep(5000);
  return taskProgress(token, 'RichMeow_Chat');
}

async function tChatN(token, code, n, prompts) {
  for (let i = 0; i < n; i++) {
    const p = await taskProgress(token, code);
    if (!p || p.status === 'completed' || p.status === 'claimed' || (p.cur || 0) >= (p.tgt || n)) break;
    const w = await webchat(token, prompts[i % prompts.length]);
    if (w && w.txt) {
      await report(token, chatEvents(token, w.convId, prompts[i % prompts.length], w.txt));
    }
    await sleep(4000);
  }
  return taskProgress(token, code);
}

// ---------- 主入口：每轮调用 ----------
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function runGrowthTasks(token, log, dbEvent, opts = {}) {
  const out = [];
  const tries = opts.tries || null;          // { task_code: 当日已尝试次数 }（由 server.js 持久化）
  const maxTries = opts.maxTries || 3;

  // 配方表：codes = 该配方会推进的全部 task_code（tries/输出以 codes[0] 为键）
  const recipes = [
    { codes: ['Library_read'], fn: () => tLibrary(token, log) },
    { codes: ['Expert_lighthouse'], fn: () => tExpertLighthouse(token, log) },
    { codes: ['Buddy_App'], fn: () => tBuddyApp(token, log) },
    { codes: ['create_canvas', 'automation_1', 'playbook_prompt'], fn: () => tCanvasAutomationPlaybook(token, log) },
    { codes: ['skill_1'], fn: () => tSkill1(token, log) },
    { codes: ['RichMeow_Chat'], fn: () => tRichMeow(token, log) },
    { codes: ['expert_5'], fn: () => tExpert5(token, log) },
    { codes: ['Expert_team_use_3'], fn: () => tExpertTeam3(token, log) },
    { codes: ['template_5'], fn: () => tTemplate5(token, log) },
    { codes: ['chat_5'], fn: () => tChatN(token, 'chat_5', 5, ['你好', '今天天气怎么样？', '1+1等于几？', 'Python是什么？', '推荐一本好书']) },
    { codes: ['Model_chat_GLM5.2'], fn: () => tChatN(token, 'Model_chat_GLM5.2', 1, ['你好，请介绍一下你自己']) },
  ];
  const covered = new Set();
  for (const r of recipes) for (const c of r.codes) covered.add(c);

  // 1) 接取全部未接取任务（跳过 SKIP_TASKS）
  const accepted = await acceptMissing(token);
  if (accepted.length) log('  接取 ' + accepted.length + ' 条');

  // 2) 先取一次全量：有活干才继续（无缺口 → 极轻量返回，省调用）
  const tasks = await allTasks(token);
  const actionable = tasks.filter(t => !SKIP_TASKS.has(t.task_code)
    && t.accept_status !== 'completed' && t.accept_status !== 'claimed'
    && t.accept_status !== 'not_accepted');
  const workable = actionable.filter(t => covered.has(t.task_code));
  if (!workable.length) {
    if (actionable.length) {
      // 有未完成任务但配方表未覆盖（如需本人动作/活动类），如实标出但不空跑
      out.push('待人工/未覆盖: ' + actionable.map(t => t.task_code).join(','));
    }
    return { results: out, didWork: false, tasks };
  }

  // 3) 逐任务补缺口
  for (const rec of recipes) {
    const key = rec.codes[0];
    try {
      if (SKIP_TASKS.has(key)) { out.push(key + ':跳过'); continue; }
      if (!rec.codes.some(c => workable.some(t => t.task_code === c))) {
        out.push(key + ':已完/未接取'); continue;
      }
      if (tries && (tries[key] || 0) >= maxTries) { out.push(key + ':今日已试' + tries[key] + '次'); continue; }
      await rec.fn();
      if (tries) tries[key] = (tries[key] || 0) + 1;
      const after = await taskProgress(token, key);
      out.push(key + ':' + (after ? after.status + ' ' + after.cur + '/' + after.tgt : '未找到'));
      // 4) 完成即领奖
      if (after && after.status === 'completed') {
        const c = await claim(token, key);
        const cd = (c.json && c.json.data) || {};
        if (c.json && c.json.code === 0) {
          log('  🎁 ' + key + ' 领奖: +' + (cd.credit || '?') + '积分 +' + (cd.energy || '?') + '能量');
          if (dbEvent) dbEvent('growth-claim', key + ' 领奖 +' + (cd.credit || '?') + '积分', cd);
        }
      }
    } catch (e) {
      out.push(key + ':异常(' + String(e && e.message || e).slice(0, 40) + ')');
    }
  }
  return { results: out, didWork: true, tasks };
}

module.exports = { runGrowthTasks, allTasks, taskProgress, SKIP_TASKS, tExpertTeam3, getTeams };
