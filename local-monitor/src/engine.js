import { getResources, checkinStatus, dailyCheckin } from './wb-api.js';
import { todayStr } from './store.js';
import { isExpired } from './auth-reader.js';

const baseOf = (a) => a.apiBase || undefined;

// 刷新单个账号：拉额度 + 签到状态，聚合展示字段。失败隔离（不抛异常）。
export async function refreshAccount(a) {
  const out = {
    id: a.id,
    name: a.name,
    region: a.region,
    nickname: a.nickname,
    uin: a.uin,
    phone: a.phone,
    disabled: a.disabled,
    expired: isExpired(a.expiresAt),
    expiresAt: a.expiresAt,
    resources: [],
    totalBalance: 0,
    todaySigned: false,
    exhausted: false,
    error: null,
  };
  try {
    const res = await getResources(a.token, baseOf(a));
    out.resources = res;
    out.totalBalance = res.filter((r) => !r.expired).reduce((s, r) => s + (Number(r.balance) || 0), 0);
    a.lastBalance = out.totalBalance;
    // 签到状态接口已失效（官方改版 404），改用本机持久化记录判断今日是否已签；
    // 实际签到走幂等 daily-checkin，已签返回 code=10001 自动跳过并更新记录。
    out.todaySigned = a.lastCheckin === todayStr();
    if (res.length && out.totalBalance <= 0) out.exhausted = true;
  } catch (e) {
    out.error = String(e?.message || e);
  }
  return out;
}

// 对单个账号执行今日签到（幂等）
export async function checkinAccount(a) {
  const r = await dailyCheckin(a.token, baseOf(a));
  if (r.alreadySigned || r.ok) a.lastCheckin = todayStr();
  a.lastResult = r.alreadySigned ? '已签(跳过)' : r.ok ? '签到成功' : `失败:${r.code ?? r.status}`;
  return r;
}

// 自动签到：遍历所有 CN 且开启且未禁用、今日未签的账号
export async function checkinAll(store) {
  const results = [];
  for (const a of store.accounts) {
    if (a.region !== 'CN') continue;
    if (a.disabled) continue;
    if (a.autoCheckin === false) continue;
    if (a.lastCheckin === todayStr()) { results.push({ id: a.id, name: a.name, result: '今日已签(跳过)' }); continue; }
    try {
      const r = await checkinAccount(a);
      results.push({ id: a.id, name: a.name, result: r.alreadySigned ? '已签(跳过)' : r.ok ? '签到成功' : `失败:${r.code ?? r.status}` });
    } catch (e) {
      results.push({ id: a.id, name: a.name, result: `异常:${String(e?.message || e)}` });
    }
  }
  return results;
}
