import crypto from 'node:crypto';

// 从已登录账号的 JWT 推导 OIDC 配置（iss / client_id），避免硬编码 realm。
// 注意：iss 必须用 token 里真实的值（现在是 www.workbuddy.cn），
// 旧的 www.codebuddy.cn 虽然也能解析，但会拿到不一致的端点，调令牌接口会 401。
export async function discover(clientId, issuer) {
  const iss = issuer || 'https://www.workbuddy.cn/auth/realms/copilot';
  const cfg = await (await fetch(`${iss}/.well-known/openid-configuration`)).json();
  return {
    authorizationEndpoint: cfg.authorization_endpoint,
    tokenEndpoint: cfg.token_endpoint,
    deviceEndpoint: cfg.device_authorization_endpoint,
    userinfoEndpoint: cfg.userinfo_endpoint,
    clientId: clientId || 'console',
    issuer: iss,
  };
}

// 解码 JWT payload（不校验签名，仅读声明）
export function decodeJwt(t) {
  try {
    const p = String(t).split('.')[1];
    return JSON.parse(Buffer.from(p, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
}

// 从已存 token 取 client_id（azp）与 iss
export function clientIdFromToken(token) {
  const p = decodeJwt(token);
  return { clientId: p?.azp || 'console', iss: p?.iss };
}

// 发起设备流：返回 user_code / verification_uri + device_code（服务端需暂存用于轮询）
export async function startDevice(cfg, scope = 'openid profile email offline_access') {
  const r = await fetch(cfg.deviceEndpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: cfg.clientId, scope }),
  });
  const j = await r.json();
  if (!r.ok) throw new Error(j.error_description || j.error || 'device flow start failed');
  return {
    deviceCode: j.device_code,
    userCode: j.user_code,
    verificationUri: j.verification_uri,
    verificationUriComplete: j.verification_uri_complete,
    interval: j.interval || 5,
    expiresIn: j.expires_in,
  };
}

// 轮询令牌端点
export async function pollDevice(cfg, deviceCode) {
  const r = await fetch(cfg.tokenEndpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
      device_code: deviceCode,
      client_id: cfg.clientId,
    }),
  });
  const j = await r.json();
  if (r.ok && j.access_token) {
    return { status: 'approved', tokens: j };
  }
  if (j.error === 'authorization_pending') return { status: 'pending' };
  if (j.error === 'slow_down') return { status: 'slow_down' };
  if (j.error === 'expired_token' || j.error === 'access_denied') return { status: 'error', error: j.error };
  return { status: 'error', error: j.error || 'unknown' };
}

// 从 id_token 取用户资料（昵称/手机）
export function profileFromIdToken(idToken) {
  const p = decodeJwt(idToken);
  if (!p) return {};
  return {
    nickname: p.preferred_username || p.name || p.nickname || null,
    phone: p.phone_number || null,
    email: p.email || null,
    uid: p.sub || null,
  };
}

export function genHandle() {
  return crypto.randomBytes(8).toString('hex');
}

// ===== 授权码 + PKCE（公开/原生客户端标准，无需 client_secret）=====
export function genPkce() {
  const verifier = crypto.randomBytes(32).toString('base64url');
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
  return { verifier, challenge };
}

export function buildAuthUrl(cfg, redirectUri, state, challenge, scope = 'openid profile email offline_access') {
  const u = new URL(cfg.authorizationEndpoint);
  u.searchParams.set('client_id', cfg.clientId);
  u.searchParams.set('response_type', 'code');
  u.searchParams.set('scope', scope);
  u.searchParams.set('redirect_uri', redirectUri);
  u.searchParams.set('state', state);
  u.searchParams.set('code_challenge', challenge);
  u.searchParams.set('code_challenge_method', 'S256');
  return u.toString();
}

export async function exchangeCode(cfg, code, redirectUri, verifier) {
  const r = await fetch(cfg.tokenEndpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: redirectUri,
      client_id: cfg.clientId,
      code_verifier: verifier,
    }),
  });
  const j = await r.json();
  if (!r.ok) throw new Error(j.error_description || j.error || 'token exchange failed');
  return j;
}
