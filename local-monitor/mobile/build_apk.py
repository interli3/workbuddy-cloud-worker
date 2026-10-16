# -*- coding: utf-8 -*-
"""WB Monitor APK 构建（Windows 原生，零第三方依赖）

等价于 build.sh，但不依赖 bash/git-bash —— 本机没有 Git Bash，
导致"改完代码却构建不了"。步骤与原脚本一致：
    aapt2 compile -> aapt2 link -> javac -> d8 -> 注入 dex -> zipalign -> apksigner

用法：
    python build_apk.py            # 构建并校验签名
    python build_apk.py --clean    # 先清理中间产物
"""
import argparse
import glob
import os
import secrets
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SDK = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or r"C:\Android"
JDK = os.environ.get("JAVA_HOME") or os.path.join(SDK, "jdk17")
if not os.path.exists(os.path.join(JDK, "bin", "javac.exe")):
    _javac_candidates = glob.glob(os.path.join(SDK, "jdk*", "**", "bin", "javac.exe"), recursive=True)
    if _javac_candidates:
        JDK = os.path.dirname(os.path.dirname(_javac_candidates[0]))
if os.path.isdir(os.path.join(SDK, "build-tools")):
    _bt_versions = sorted(glob.glob(os.path.join(SDK, "build-tools", "*")), reverse=True)
else:
    _bt_versions = []
BT = os.environ.get("ANDROID_BUILD_TOOLS") or (_bt_versions[0] if _bt_versions else "")
AJ = os.environ.get("ANDROID_JAR") or os.path.join(SDK, "platforms", "android-34", "android.jar")
KS = os.path.join(HERE, "wbmon.jks")
KS_PASS_FILE = os.path.join(HERE, ".keystore-password")


def keystore_password():
    if os.path.exists(KS_PASS_FILE):
        return open(KS_PASS_FILE, encoding="utf-8").read().strip()
    value = secrets.token_urlsafe(24)
    with open(KS_PASS_FILE, "w", encoding="utf-8") as f:
        f.write(value)
    return value


def run(cmd, cwd=HERE, label=""):
    print("  > %s" % (label or " ".join(os.path.basename(c) if i == 0 else c
                                       for i, c in enumerate(cmd))[:150]))
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    out = ((p.stdout or "") + (p.stderr or "")).strip()
    if p.returncode != 0:
        print(out[-2500:])
        raise SystemExit("[失败] %s rc=%s" % (label or cmd[0], p.returncode))
    if out:
        print("    " + out[-600:].replace("\n", "\n    "))
    return out


def class_files():
    return [p.replace("\\", "/") for p in glob.glob(os.path.join(HERE, "obj", "**", "*.class"),
                                                    recursive=True)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clean", action="store_true")
    a = ap.parse_args()

    # LocalConfig 由 scripts/configure.py 生成，包目录可能随版本调整，这里递归查找而不写死路径
    hits = glob.glob(os.path.join(HERE, "java", "**", "LocalConfig.java"), recursive=True)
    if not hits:
        raise SystemExit("[未配置] 请先在仓库根目录运行: python scripts/configure.py")

    ks_pass = keystore_password()

    for p in (("gen", "obj", "dex", "out") if a.clean else ()):
        shutil.rmtree(os.path.join(HERE, p), ignore_errors=True)
    for p in ("gen", "obj", "dex", "out"):
        os.makedirs(os.path.join(HERE, p), exist_ok=True)

    for need in (os.path.join(JDK, "bin", "javac.exe"), os.path.join(BT, "aapt2.exe"), AJ):
        if not os.path.exists(need):
            raise SystemExit("[缺失] %s" % need)

    print("[1/7] aapt2 compile 资源")
    run([os.path.join(BT, "aapt2.exe"), "compile", "--dir", "res", "-o", "res.zip"])

    print("[2/7] aapt2 link")
    run([os.path.join(BT, "aapt2.exe"), "link", "-I", AJ,
         "--manifest", "AndroidManifest.xml", "-o", "out/base.apk",
         "--java", "gen", "--min-sdk-version", "26", "--target-sdk-version", "34",
         "--auto-add-overlay", "res.zip"])

    print("[3/7] javac")
    srcs = []
    for d in ("java", "gen"):
        srcs += [p.replace("\\", "/") for p in glob.glob(os.path.join(HERE, d, "**", "*.java"),
                                                         recursive=True)]
    with open(os.path.join(HERE, "sources.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(os.path.relpath(s, HERE).replace("\\", "/") for s in srcs))
    run([os.path.join(JDK, "bin", "javac.exe"), "-source", "17", "-target", "17", "-nowarn",
         "-encoding", "UTF-8",
         # 强制英文报错：Windows 控制台多为 GBK，javac 的中文报错会被编码问题吞成乱码，
         # 反而看不到真正错在哪一行（2026-10-04 实测踩到）。
         "-J-Duser.language=en", "-J-Duser.country=US",
         "-classpath", AJ, "-d", "obj", "@sources.txt"])

    print("[4/7] d8 转 dex")
    run([os.path.join(JDK, "bin", "java.exe"), "-cp", os.path.join(BT, "lib", "d8.jar"),
         "com.android.tools.r8.D8", "--lib", AJ, "--output", "dex"] + class_files())

    print("[5/7] 注入 dex 并对齐")
    shutil.copy(os.path.join(HERE, "out", "base.apk"), os.path.join(HERE, "out", "unsigned.apk"))
    z = zipfile.ZipFile(os.path.join(HERE, "out", "unsigned.apk"), "a", zipfile.ZIP_DEFLATED)
    z.write(os.path.join(HERE, "dex", "classes.dex"), "classes.dex")
    z.close()
    run([os.path.join(BT, "zipalign.exe"), "-f", "-p", "4",
         "out/unsigned.apk", "out/aligned.apk"])

    print("[6/7] 签名")
    if not os.path.exists(KS):
        run([os.path.join(JDK, "bin", "keytool.exe"), "-genkeypair", "-v", "-keystore", "wbmon.jks",
             "-alias", "wbmon", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
             "-storepass", ks_pass, "-keypass", ks_pass,
             "-dname", "CN=WorkBuddy Monitor, OU=Open Source, O=Community"], label="generate signing key")
    run([os.path.join(JDK, "bin", "java.exe"), "-jar", os.path.join(BT, "lib", "apksigner.jar"),
         "sign", "--ks", "wbmon.jks", "--ks-key-alias", "wbmon",
         "--ks-pass", "pass:" + ks_pass, "--key-pass", "pass:" + ks_pass,
         "--out", "out/wbmon.apk", "out/aligned.apk"], label="sign APK")

    print("[7/7] 校验签名")
    run([os.path.join(JDK, "bin", "java.exe"), "-jar", os.path.join(BT, "lib", "apksigner.jar"),
         "verify", "--verbose", "out/wbmon.apk"])

    apk = os.path.join(HERE, "out", "wbmon.apk")
    import hashlib
    payload = open(apk, "rb").read()
    md5 = hashlib.md5(payload).hexdigest()
    sha256 = hashlib.sha256(payload).hexdigest()
    print("\n=== 构建完成 ===")
    print("  APK : %s" % apk)
    print("  大小: %d 字节" % os.path.getsize(apk))
    print("  MD5 : %s" % md5)
    print("  SHA-256: %s" % sha256)
    return 0


if __name__ == "__main__":
    sys.exit(main())
