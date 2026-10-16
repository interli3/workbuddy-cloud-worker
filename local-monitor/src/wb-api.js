import { DEFAULT_API_BASE } from './config.js';

function authHeader(token) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

async function jpost(base, path, token, body) {
  const r = await fetch(base + path, { method: 'POST', headers: authHeader(token), body: JSON.stringify(body ?? {}) });
  const text = await r.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { _raw: text }; }
  return { ok: r.ok, status: r.status, json };
}

async function jget(base, path, token) {
  const r = await fetch(base + path, { method: 'GET', headers: authHeader(token) });
  const text = await r.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { _raw: text }; }
  return { ok: r.ok, status: r.status, json };
}

// 积分套餐明细。返回结构：{data:{Response:{Data:{Accounts:[...]}}}
// 每项含 PackageName / CapacityType / CapacityRemain / CapacitySize / ExpiredTime / CapacityUnit
export async function getResources(token, base = DEFAULT_API_BASE) {
  const { ok, status, json } = await jpost(base, '/billing/meter/get-user-resource', token, {});
  if (!ok || json?._raw || (json?.code != null && ![0, 200].includes(Number(json.code))))
    throw new Error('额度查询失败: HTTP ' + status);
  const data = json?.data?.Response?.Data || json?.data?.Data || json?.data || json;
  const list = data?.Accounts || data?.list || data?.resources || (Array.isArray(data) ? data : []);
  const now = Date.now();
  const resources = (Array.isArray(list) ? list : []).map((it) => {
    const cap = Number(it.CapacityRemain ?? it.capacityRemain ?? it.balance ?? it.remain ?? 0);
    const size = Number(it.CapacitySize ?? it.capacitySize ?? it.total ?? 0);
    const ctype = it.CapacityType ?? it.capacityType ?? null;
    const expStr = it.ExpiredTime ?? it.expireTime ?? it.expireAt ?? null;
    let expired = false;
    if (expStr) {
      const ms = Date.parse(String(expStr).replace(' ', 'T'));
      if (!Number.isNaN(ms)) expired = ms < now;
    }
    return {
      name: it.PackageName || it.packageName || it.resourceName || it.name || '套餐',
      capacityType: ctype,
      kind: ctype === 4 ? '体验版' : '赠送包',
      balance: cap,
      total: size,
      expired,
      expireAt: expStr,
      unit: it.CapacityUnit || it.capacityUnit || it.unit || 'credits',
    };
  });
  return resources;
}

// 历史登录态只有在官方接口仍接受时才可覆盖凭证池。
export async function validateAccessToken(token, base = DEFAULT_API_BASE) {
  const { ok, status, json } = await jpost(base, '/billing/meter/get-user-resource', token, {});
  const code = json?.code ?? json?.status ?? null;
  const businessOk = code == null || Number(code) === 0 || Number(code) === 200;
  return { ok: Boolean(ok && !json?._raw && businessOk), status, code };
}

// 签到状态（GET，只读）
export async function checkinStatus(token, base = DEFAULT_API_BASE) {
  const { json } = await jget(base, '/v2/billing/meter/checkin-activity-status', token);
  return json;
}

// 执行今日签到（幂等：已签返回 code=10001 视为成功跳过）
export async function dailyCheckin(token, base = DEFAULT_API_BASE) {
  const { ok, status, json } = await jpost(base, '/v2/billing/meter/daily-checkin', token, {});
  const code = json?.code ?? json?.status ?? status;
  const alreadySigned = (Number(code) === 10001 && (ok || status === 400))
    || (ok && json?.data?.checked === true);
  const success = ok && !json?._raw && (Number(code) === 0 || Number(code) === 200);
  return {
    ok: Boolean(success),
    status,
    code,
    alreadySigned: Boolean(alreadySigned),
    raw: json,
  };
}

// 专家包领取（端点非官方公开，需用户按客户端实际抓包填充 claimEndpoint；默认不打调用）
export async function claimExpertPackage(token, claimEndpoint, base = DEFAULT_API_BASE) {
  if (!claimEndpoint) {
    return { ok: false, skipped: true, reason: '未配置领取端点' };
  }
  const { ok, status, json } = await jpost(base, claimEndpoint, token, {});
  return { ok, status, json };
}

// AI 记忆画像（GET，只读）
export async function memoryProfile(token, base = DEFAULT_API_BASE) {
  const { json } = await jget(base, '/api/memory/profile', token);
  return json;
}
