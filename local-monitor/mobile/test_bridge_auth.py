# -*- coding: utf-8 -*-
"""Regression checks for the bridge's optional LAN bearer-token boundary."""
import importlib.util
import os

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("bridge_multi", os.path.join(HERE, "bridge_multi.py"))
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


def main():
    check = bridge.is_request_authorized
    token = "test-secret"
    cases = [
        (check("192.168.1.20", "", ""), True, "empty token keeps compatibility"),
        (check("127.0.0.1", "", token), True, "loopback diagnostics stay available"),
        (check("::1", "", token), True, "IPv6 loopback stays available"),
        (check("192.168.1.20", "", token), False, "missing remote token is rejected"),
        (check("192.168.1.20", "Bearer wrong", token), False, "wrong remote token is rejected"),
        (check("192.168.1.20", "Bearer " + token, token), True, "correct remote token is accepted"),
    ]
    for actual, expected, label in cases:
        assert actual is expected, label
    print("Bridge authorization tests: %d/%d passed" % (len(cases), len(cases)))


if __name__ == "__main__":
    main()
