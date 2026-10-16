package io.github.workbuddymonitor;

import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * 最小 MQTT 3.1.1 客户端（拉模式，零第三方依赖）。
 * 只实现：CONNECT → SUBSCRIBE → 收 retained PUBLISH → 断开。
 * 用于从公共 broker 取电脑端推上来的最新快照，实现"无地址"会合。
 */
public class Mqtt {

    private static final String TAG = "wbmon";

    /** 拉取 topic 上最新一条 retain 消息，失败返回 null */
    public static String pull(String host, int port, String topic, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), Math.min(timeoutMs, 6000));
            s.setSoTimeout(timeoutMs);
            OutputStream os = s.getOutputStream();
            InputStream is = s.getInputStream();

            // CONNECT
            os.write(connectPacket("wbmon-app-" + System.currentTimeMillis() % 1000000));
            os.flush();
            readPacket(is);   // CONNACK

            // SUBSCRIBE
            os.write(subscribePacket(topic));
            os.flush();

            // 不能先"假定下一包就是 SUBACK"：MQTT 允许 broker 在 SUBACK 之前就下发 retained
            // PUBLISH。若把那一包当 SUBACK 读走，retained 快照就被吃掉，只剩超时返回 null，
            // 手机会平白回退到过期缓存。这里按报文类型分发，谁先到都不丢。

            // 等待 retained PUBLISH（QoS0）
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                int h = readByte(is);
                int type = (h >> 4) & 0x0F;
                int len = readRemLen(is);
                byte[] body = readN(is, len);
                if (type == 3) {
                    int tl = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
                    return new String(body, 2 + tl, body.length - 2 - tl, StandardCharsets.UTF_8);
                }
                // type 2/9 等其它报文忽略，继续等 PUBLISH
            }
            return null;
        } catch (SocketTimeoutException e) {
            return null;
        } catch (IOException e) {
            Log.w(TAG, "mqtt pull failed: " + e.getMessage());
            return null;
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 发布一条 QoS0、不 retain 的消息（手机向电脑端回报自身状态用）。
     * 失败一律静默返回 false，绝不影响主流程。
     */
    public static boolean publish(String host, int port, String topic, String payload) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 6000);
            s.setSoTimeout(6000);
            OutputStream os = s.getOutputStream();
            InputStream is = s.getInputStream();

            os.write(connectPacket("wbmon-rep-" + System.currentTimeMillis() % 1000000));
            os.flush();
            readPacket(is);   // CONNACK（CONNECT 之后第一包必为 CONNACK，无竞态）

            byte[] tb = topic.getBytes(StandardCharsets.UTF_8);
            byte[] pb = payload.getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[2 + tb.length + pb.length];
            body[0] = (byte) (tb.length >> 8);
            body[1] = (byte) (tb.length & 0xff);
            System.arraycopy(tb, 0, body, 2, tb.length);
            System.arraycopy(pb, 0, body, 2 + tb.length, pb.length);

            os.write(frame(0x30, body));   // PUBLISH QoS0, retain=0
            os.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "mqtt publish failed: " + e.getMessage());
            return false;
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    // ---- 报文构造 ----

    private static byte[] connectPacket(String clientId) {
        byte[] proto = {0x00, 0x04, 'M', 'Q', 'T', 'T', 0x04, 0x02, 0x00, 0x3c};
        byte[] payload = encStr(clientId);
        byte[] body = new byte[proto.length + payload.length];
        System.arraycopy(proto, 0, body, 0, proto.length);
        System.arraycopy(payload, 0, body, proto.length, payload.length);
        return frame(0x10, body);
    }

    private static byte[] subscribePacket(String topic) {
        byte[] vh = {0x00, 0x01};                 // packet id
        byte[] sub = encStr(topic);              // topic filter
        byte[] qos = {0x00};                     // QoS 0
        byte[] body = new byte[2 + sub.length + 1];
        System.arraycopy(vh, 0, body, 0, 2);
        System.arraycopy(sub, 0, body, 2, sub.length);
        body[body.length - 1] = 0x00;
        return frame(0x82, body);
    }

    private static byte[] frame(int type, byte[] body) {
        byte[] rl = remLen(body.length);
        byte[] out = new byte[1 + rl.length + body.length];
        out[0] = (byte) type;
        System.arraycopy(rl, 0, out, 1, rl.length);
        System.arraycopy(body, 0, out, 1 + rl.length, body.length);
        return out;
    }

    private static byte[] encStr(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[2 + b.length];
        out[0] = (byte) (b.length >> 8);
        out[1] = (byte) (b.length & 0xff);
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }

    private static byte[] remLen(int n) {
        int[] tmp = new int[4];
        int i = 0;
        do { int d = n % 128; n /= 128; if (n > 0) d |= 0x80; tmp[i++] = d; } while (n > 0);
        byte[] out = new byte[i];
        for (int k = 0; k < i; k++) out[k] = (byte) tmp[k];
        return out;
    }

    // ---- 报文解析 ----

    private static byte[] readPacket(InputStream in) throws IOException {
        int h = readByte(in);
        int len = readRemLen(in);
        return readN(in, len);
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("eof");
        return b;
    }

    private static int readRemLen(InputStream in) throws IOException {
        int mult = 1, val = 0, b;
        do {
            b = readByte(in);
            val += (b & 127) * mult;
            mult *= 128;
        } while ((b & 128) != 0);
        return val;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("eof");
            off += r;
        }
        return buf;
    }
}
