# -*- coding: utf-8 -*-
"""
验证 apply_daymark()：今日消耗只累加「余额下降」，签到/赠送导致的上涨不能算消耗。
直接加载 bridge_multi.py 的真实代码跑，不是复制一份逻辑来测。
"""
import importlib.util
import json
import os
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("bm", os.path.join(HERE, "bridge_multi.py"))
bm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bm)

TMP = os.path.join(tempfile.gettempdir(), "_daymark_selftest.json")
if os.path.exists(TMP):
    os.remove(TMP)
bm.DAYMARK = TMP

results = []


def T(bal, expect, note):
    got = bm.apply_daymark("testacc", bal)
    ok = abs(got - expect) < 0.01
    results.append(ok)
    print(" %-4s %-34s 余额=%-6s -> 今日消耗=%-6s (期望 %s)" %
          ("PASS" if ok else "FAIL", note, bal, got, expect))


def TC(bal, cyc, expect, note):
    """带「周期已用」口径的用例"""
    got = bm.apply_daymark("cycacc", bal, cyc)
    ok = abs(got - expect) < 0.01
    results.append(ok)
    print(" %-4s %-34s 余额=%-6s 周期已用=%-6s -> 今日消耗=%-6s (期望 %s)" %
          ("PASS" if ok else "FAIL", note, bal, cyc, got, expect))


print("\n=== 场景1：签到涨分不能被算成消耗（本次修复的核心 bug）===")
T(1000, 0, "当天首次，建立基线")
T(1100, 0, "签到 +100，余额上涨不算消耗")
T(1200, 0, "再赠送 +100，仍不算消耗")

print("\n=== 场景2：真实消耗必须被累加 ===")
T(1150, 50, "真实消耗 50")
T(1120, 80, "再消耗 30，累计应为 80")

print("\n=== 场景3：签到收益不能冲抵已发生的消耗 ===")
T(1220, 80, "签到 +100，消耗累计应保持 80")

print("\n=== 场景4：继续消耗继续累加 ===")
T(1000, 300, "再消耗 220，累计应为 300")

print("\n=== 场景5：跨天要重置 ===")
try:
    d = json.load(open(TMP, encoding="utf-8"))
    d["testacc"]["date"] = "2000-01-01"  # 伪造成昨天，模拟跨天
    json.dump(d, open(TMP, "w", encoding="utf-8"))
except Exception as e:
    print("  伪造跨天失败:", e)
T(1000, 0, "新的一天从 0 重新开始")

print("\n=== 场景6：余额不动、但周期已用上涨（兜底口径，不能漏报）===")
TC(2000, 0, 0, "当天首次，建立双基线")
TC(2000, 30, 30, "余额未变但周期已用 +30")
TC(2000, 75, 75, "周期已用再 +45，累计 75")
TC(2000, 75, 75, "无变化，保持 75（不能重复累加）")
TC(1900, 175, 175, "余额 -100 且周期 +100，只算 100")

print("\n=== 场景7：周期重置不能倒扣 ===")
TC(2000, 0, 175, "额度周期重置（已用归零），消耗累计保持")

print("\n" + "=" * 62)
passed = sum(1 for r in results if r)
print("结果: %d/%d 通过" % (passed, len(results)))
if passed == len(results):
    print("✅ 今日消耗计算逻辑正确")
else:
    print("❌ 存在失败用例")
os.path.exists(TMP) and os.remove(TMP)
