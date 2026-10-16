// 最小 MQTT 3.1.1 客户端验证：连接 -> 订阅 -> 发布 retain -> 收到
import net from 'node:net';

function encStr(s) {
  const b = Buffer.from(s, 'utf8');
  return Buffer.concat([Buffer.from([b.length >> 8, b.length & 0xff]), b]);
}
function remLen(n) {
  const out = [];
  do { let d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 0x80; out.push(d); } while (n > 0);
  return Buffer.from(out);
}
function pkt(type, body) {
  return Buffer.concat([Buffer.from([type]), remLen(body.length), body]);
}
function mqttConnect(clientId) {
  const vh = Buffer.concat([
    Buffer.from([0x00, 0x04]), Buffer.from('MQTT'), Buffer.from([0x04]),
    Buffer.from([0x02]), Buffer.from([0x00, 0x3c])
  ]);
  return pkt(0x10, Buffer.concat([vh, encStr(clientId)]));
}
function mqttSubscribe(topic) {
  const vh = Buffer.from([0x00, 0x01]);
  return pkt(0x82, Buffer.concat([vh, encStr(topic), Buffer.from([0x00])]));
}
function mqttPublish(topic, payload, retain) {
  const flags = retain ? 0x01 : 0x00;
  return pkt(0x30 | flags, Buffer.concat([encStr(topic), Buffer.from(payload, 'utf8')]));
}

const HOST = 'broker.emqx.io';
const PORT = 1883;
const TOPIC = 'wbmon/public-probe/' + Date.now().toString(36);

// ---- 订阅端 ----
const sub = net.createConnection({ host: HOST, port: PORT }, () => {
  console.log('[订阅端] 已连接，发送 CONNECT');
  sub.write(mqttConnect('wbmon-sub-' + Date.now().toString(36)));
});
let subReady = false;
sub.on('data', d => {
  const type = d[0] >> 4;
  if (type === 2) {
    console.log('[订阅端] CONNACK 返回码 =', d[3], d[3] === 0 ? '(接受)' : '(被拒)');
    sub.write(mqttSubscribe(TOPIC));
    console.log('[订阅端] 已订阅:', TOPIC);
    subReady = true;
    // 订阅成功后让发布端发消息
    setTimeout(publishNow, 500);
  } else if (type === 3) {
    // PUBLISH
    let i = 2;
    const tl = (d[i] << 8) | d[i + 1]; i += 2;
    const topic = d.slice(i, i + tl).toString(); i += tl;
    const body = d.slice(i).toString();
    console.log('[订阅端] >>> 收到消息 topic=' + topic);
    console.log('[订阅端] >>> 内容: ' + body.slice(0, 120));
    console.log('  ✓ MQTT 通道验证通过');
    sub.destroy(); pub.destroy(); process.exit(0);
  } else if (type === 9) {
    console.log('[订阅端] SUBACK');
  }
});
sub.on('error', e => { console.log('[订阅端] 错误', e.message); process.exit(1); });

// ---- 发布端 ----
const pub = net.createConnection({ host: HOST, port: PORT }, () => {
  pub.write(mqttConnect('wbmon-pub-' + Date.now().toString(36)));
});
let pubReady = false;
pub.on('data', d => {
  if ((d[0] >> 4) === 2) { pubReady = true; }
});
pub.on('error', e => { console.log('[发布端] 错误', e.message); process.exit(1); });

function publishNow() {
  const snap = JSON.stringify({ balance: 3044.06, usedToday: 1664.89, running: 2, tasks: 8, ts: Date.now() });
  pub.write(mqttPublish(TOPIC, snap, true));
  console.log('[发布端] 已发布 retain 消息 (' + snap.length + ' 字节)');
}

setTimeout(() => { console.log('  ✗ 超时：20 秒内没收到消息'); process.exit(1); }, 20000);
