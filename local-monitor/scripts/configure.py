#!/usr/bin/env python3
"""Create the private local configuration used by the bridge and Android app.

No WorkBuddy credentials are read or copied. Generated secrets and local
addresses are written only to gitignored files.
"""
import argparse
import json
import os
import secrets
import socket

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCAL_CONFIG_JAVA = os.path.join(
    ROOT, "mobile", "java", "io", "github", "workbuddymonitor", "LocalConfig.java")
CONFIG = os.path.join(ROOT, "config.local.json")


def load_existing():
    try:
        with open(CONFIG, encoding="utf-8") as f:
            value = json.load(f)
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def detect_lan_ip():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("1.1.1.1", 80))
        return sock.getsockname()[0]
    except OSError:
        return ""
    finally:
        sock.close()


def main():
    parser = argparse.ArgumentParser(description="Configure WorkBuddy Monitor for this computer")
    lan = parser.add_mutually_exclusive_group()
    lan.add_argument("--lan-ip", help="computer LAN IPv4; auto-detected when omitted")
    lan.add_argument("--no-auto-lan", action="store_true",
                     help="leave the APK LAN address empty (for generic public builds)")
    parser.add_argument("--port", type=int, default=8791)
    parser.add_argument("--mqtt-host", default="broker.emqx.io")
    parser.add_argument("--mqtt-port", type=int, default=1883)
    mqtt = parser.add_mutually_exclusive_group()
    mqtt.add_argument("--enable-public-mqtt", action="store_true",
                      help="publish snapshots through the anonymous public MQTT broker")
    mqtt.add_argument("--disable-mqtt", action="store_true",
                      help="disable MQTT even when an earlier local config enabled it")
    parser.add_argument("--no-bridge-auth", action="store_true",
                        help="do not generate a LAN bearer token (only for generic public APKs)")
    args = parser.parse_args()

    existing = load_existing()
    ip = "" if args.no_auto_lan else (args.lan_ip or detect_lan_ip())
    lan_url = "http://%s:%d" % (ip, args.port) if ip else ""
    if args.enable_public_mqtt:
        topic = existing.get("mqttTopic") or "wbmon/" + secrets.token_hex(20)
    elif args.disable_mqtt:
        topic = ""
    else:
        topic = existing.get("mqttTopic") or ""
    bridge_token = "" if args.no_bridge_auth else (
        existing.get("bridgeToken") or secrets.token_urlsafe(32))
    config = {
        "mqttHost": args.mqtt_host,
        "mqttPort": args.mqtt_port,
        "mqttTopic": topic,
        "lanUrl": lan_url,
        "bridgeToken": bridge_token,
    }
    with open(CONFIG, "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)

    java = """package io.github.workbuddymonitor;

/** Generated locally by scripts/configure.py. Never commit this file. */
final class LocalConfig {
    static final String DEFAULT_LAN = %s;
    static final String MQTT_HOST = %s;
    static final int MQTT_PORT = %d;
    static final String MQTT_TOPIC = %s;
    static final String BRIDGE_TOKEN = %s;
    private LocalConfig() {}
}
""" % (json.dumps(lan_url), json.dumps(args.mqtt_host), args.mqtt_port,
       json.dumps(topic), json.dumps(bridge_token))
    with open(LOCAL_CONFIG_JAVA, "w", encoding="utf-8", newline="") as f:
        f.write(java)

    print("Configuration created.")
    print("LAN URL:", lan_url or "not detected; set it later in the Android app")
    print("MQTT:", "enabled with a private random topic" if topic else "disabled (LAN-only privacy default)")
    print("Bridge authentication:", "enabled; token stored in config.local.json" if bridge_token
          else "disabled for generic APK build")
    print("Next: start the panel with `npm start`, import an account, then run the bridge.")


if __name__ == "__main__":
    main()
