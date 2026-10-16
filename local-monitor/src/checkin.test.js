import test from 'node:test';
import assert from 'node:assert/strict';
import { checkinAccount } from './engine.js';
import { isExpired } from './auth-reader.js';
import { selectValidAuth } from './store.js';

test('unknown or near-future expiry does not disable an account', () => {
  assert.equal(isExpired(null), false);
  assert.equal(isExpired(Date.now() + 60000), false);
  assert.equal(isExpired(Date.now() - 60000), true);
});

test('check-in records only confirmed business success', async () => {
  const original = globalThis.fetch;
  try {
    for (const [http, body, accepted, signed] of [
      [200, {code: 0}, true, false],
      [400, {code: 10001}, true, true],
      [200, {code: 500, msg: '凭证已过期'}, false, false],
      [401, {code: 10001}, false, false],
      [200, 'invalid response', false, false],
    ]) {
      globalThis.fetch = async () => new Response(
        typeof body === 'string' ? body : JSON.stringify(body), {status: http});
      const account = {token: 'synthetic', lastCheckin: null};
      const result = await checkinAccount(account);
      assert.equal(result.ok || result.alreadySigned, accepted);
      assert.equal(result.alreadySigned, signed);
      assert.equal(account.lastCheckin !== null, accepted);
    }
  } finally {
    globalThis.fetch = original;
  }
});

test('revoked newer snapshot cannot replace a valid older login', async () => {
  const selected = await selectValidAuth([
    {token: 'revoked-newer', expiresAt: 200},
    {token: 'valid-older', expiresAt: 100},
  ], async (token) => ({ok: token === 'valid-older', status: token === 'valid-older' ? 200 : 401}));
  assert.equal(selected.candidate.token, 'valid-older');
  assert.equal(selected.result.status, 200);
});

test('no unverified login candidate is selected', async () => {
  const selected = await selectValidAuth(
    [{token: 'revoked', expiresAt: 200}],
    async () => ({ok: false, status: 401}),
  );
  assert.equal(selected, null);
});
