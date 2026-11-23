#!/usr/bin/env python3
"""安装构建好的 KernelSU Neo Manager APK，并推送匹配的 LKM。

所有设备操作都通过系统 adb 完成：
  - 读取设备型号 / ABI / SDK / 内核版本，推导 KMI；
  - 安装 dist/ 下最新的 APK（或 -a 指定的 APK）；
  - 推送 dist/lkm/<kmi>_kernelsu.ko 到 /sdcard/Download/KernelSU-Neo/。

用法:
  python3 scripts/install.py
  python3 scripts/install.py -s 54abd35b
  python3 scripts/install.py --apk dist/KernelSU_xxx.apk --no-lkm
  python3 scripts/install.py --uninstall
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PACKAGE = "com.sunyuze.kernelsuneo"
LKM_DIR = ROOT / "dist" / "lkm"
REMOTE_DIR = "/sdcard/Download/KernelSU-Neo"
REMOTE_APK = "/data/local/tmp/ksu-neo-install.apk"


def run(cmd: list[str], *, capture: bool = False) -> subprocess.CompletedProcess[str]:
    """执行系统命令；capture 时合并 stdout/stderr 便于解析。"""
    kwargs: dict[str, object] = {"text": True}
    if capture:
        kwargs["stdout"] = subprocess.PIPE
        kwargs["stderr"] = subprocess.STDOUT
    return subprocess.run(cmd, **kwargs)  # noqa: S603 - 参数均为列表，无 shell 注入


def adb_shell(adb: list[str], *args: str) -> str:
    """执行 adb shell 并去掉 Windows 风格换行。"""
    result = run([*adb, "shell", *args], capture=True)
    return result.stdout.replace("\r", "").strip() if result.stdout else ""


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="install.py",
        description="Install the built KernelSU Neo manager APK and push the matching LKM.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Examples:\n"
            "  python3 scripts/install.py\n"
            "  python3 scripts/install.py -s 54abd35b\n"
            "  python3 scripts/install.py --apk dist/KernelSU_xxx.apk --no-lkm\n"
            "  python3 scripts/install.py --uninstall\n"
        ),
    )
    parser.add_argument(
        "-s",
        "--serial",
        default=os.environ.get("ANDROID_SERIAL", ""),
        help="adb device serial (default: $ANDROID_SERIAL)",
    )
    parser.add_argument(
        "-a",
        "--apk",
        default="",
        help="APK to install (default: newest dist/*.apk)",
    )
    parser.add_argument(
        "-p",
        "--package",
        default=os.environ.get("PACKAGE", DEFAULT_PACKAGE),
        help=f"package name (default: {DEFAULT_PACKAGE})",
    )
    parser.add_argument(
        "--no-lkm", action="store_true", help="do not push the kernel module"
    )
    parser.add_argument(
        "--no-launch",
        action="store_true",
        help="do not open the manager after installation",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="continue even if the device KMI has no matching LKM; also push all LKMs",
    )
    parser.add_argument(
        "--uninstall", action="store_true", help="uninstall the manager package and exit"
    )
    return parser.parse_args(argv)


def newest_apk() -> Path | None:
    """按修改时间取 dist/ 下最新的 APK。"""
    candidates = [path for path in (ROOT / "dist").glob("*.apk") if path.is_file()]
    if not candidates:
        return None
    return max(candidates, key=lambda path: path.stat().st_mtime)


def device_kmi(kernel_release: str) -> str:
    """从 uname -r 推导 KMI，例如 android15-6.6。"""
    android = re.search(r"android(\d+)", kernel_release)
    version = re.match(r"(\d+\.\d+)", kernel_release)
    if not android or not version:
        return ""
    return f"android{android.group(1)}-{version.group(1)}"


def main(argv: list[str]) -> int:
    args = parse_args(argv)

    adb_path = shutil.which("adb")
    if adb_path is None:
        print("error: adb not found in PATH", file=sys.stderr)
        return 1
    adb = [adb_path]
    if args.serial:
        adb.extend(["-s", args.serial])

    state = run([*adb, "get-state"], capture=True).stdout
    state = state.strip() if state else ""
    if state != "device":
        print("error: no usable Android device", file=sys.stderr)
        run([adb_path, "devices", "-l"])
        print(
            "Connect a device with USB debugging enabled and try again.",
            file=sys.stderr,
        )
        return 1

    model = adb_shell(adb, "getprop", "ro.product.model")
    abi = adb_shell(adb, "getprop", "ro.product.cpu.abi")
    sdk = adb_shell(adb, "getprop", "ro.build.version.sdk")
    kernel = adb_shell(adb, "uname", "-r")
    locked = adb_shell(adb, "getprop", "ro.boot.flash.locked")
    vbstate = adb_shell(adb, "getprop", "ro.boot.verifiedbootstate")
    kmi = device_kmi(kernel)

    print(f"Device : {model or 'unknown'}")
    print(f"ABI    : {abi or 'unknown'}")
    print(f"SDK    : {sdk or 'unknown'}")
    print(f"Kernel : {kernel or 'unknown'}")
    if kmi:
        print(f"KMI    : {kmi}")
    print()

    if args.uninstall:
        print(f"Uninstalling {args.package} ...")
        run([*adb, "uninstall", args.package])
        return 0

    apk = Path(args.apk).expanduser() if args.apk else newest_apk()
    if apk is None or not apk.is_file():
        print("error: APK not found. Build it first with:", file=sys.stderr)
        print(
            f"  python3 scripts/build.py --kernel --kmi {kmi or '<kmi>'} "
            f"-p {args.package}",
            file=sys.stderr,
        )
        return 1

    matching_lkm: Path | None = None
    if kmi:
        candidate = LKM_DIR / f"{kmi}_kernelsu.ko"
        if candidate.is_file():
            matching_lkm = candidate

    if not args.no_lkm and matching_lkm is None:
        print(f"warning: no LKM built for device KMI {kmi or 'unknown'}", file=sys.stderr)
        if LKM_DIR.is_dir():
            print("available LKMs:", file=sys.stderr)
            for ko in sorted(LKM_DIR.glob("*.ko")):
                print(f"  {ko.name}", file=sys.stderr)
        if not args.force:
            print(
                "The APK will still be installed, but the embedded LKM may not match.",
                file=sys.stderr,
            )
            print("Use --force to also push all available LKMs.", file=sys.stderr)

    print(f"Installing: {apk}")
    install = run([*adb, "install", "-r", str(apk)])
    if install.returncode != 0:
        # MIUI/HyperOS often blocks streamed installs with USER_RESTRICTED.
        print("streamed install failed, retrying with pm install ...")
        run([*adb, "push", str(apk), REMOTE_APK])
        retry = run([*adb, "shell", "pm", "install", "-r", "-d", REMOTE_APK])
        run([*adb, "shell", "rm", "-f", REMOTE_APK])
        if retry.returncode != 0:
            print("error: installation failed", file=sys.stderr)
            print(
                "If this is INSTALL_FAILED_UPDATE_INCOMPATIBLE, uninstall first:",
                file=sys.stderr,
            )
            print(f"  {' '.join(adb)} uninstall {args.package}", file=sys.stderr)
            print(
                "If this is INSTALL_FAILED_USER_RESTRICTED (MIUI/HyperOS), enable:",
                file=sys.stderr,
            )
            print("  Developer options -> Install via USB", file=sys.stderr)
            print(
                "  Developer options -> USB debugging (Security settings)",
                file=sys.stderr,
            )
            print(
                "Then keep the phone unlocked and run this script again.",
                file=sys.stderr,
            )
            return 1

    if run([*adb, "shell", "pm", "path", args.package], capture=True).returncode != 0:
        print(
            f"error: package {args.package} is not installed after the install step",
            file=sys.stderr,
        )
        return 1

    dumpsys = run([*adb, "shell", "dumpsys", "package", args.package], capture=True)
    version_line = ""
    for line in (dumpsys.stdout or "").splitlines():
        if "versionName=" in line:
            version_line = line.strip()
            break
    print(f"Installed : {version_line or args.package}")

    if not args.no_lkm:
        run([*adb, "shell", "mkdir", "-p", REMOTE_DIR])
        if matching_lkm is not None:
            run([*adb, "push", str(matching_lkm), f"{REMOTE_DIR}/"])
            print(f"LKM       : pushed {matching_lkm.name}")
        elif args.force and LKM_DIR.is_dir():
            for ko in sorted(LKM_DIR.glob("*.ko")):
                if ko.is_file():
                    run([*adb, "push", str(ko), f"{REMOTE_DIR}/"])
            print("LKM       : pushed all available modules")
        else:
            print("LKM       : not pushed (no KMI match)")
        print(f"LKM dir   : {REMOTE_DIR}/")

    if locked == "1" or vbstate == "green":
        print()
        print("WARNING: bootloader is locked or verified boot is green.")
        print("Installing the APK does not load the kernel module by itself.")
        print(
            "The matching LKM must be patched/flashed after unlocking the bootloader,"
        )
        print("or loaded through the manager's LKM installation flow if available.")

    if not args.no_launch:
        run(
            [
                *adb,
                "shell",
                "monkey",
                "-p",
                args.package,
                "-c",
                "android.intent.category.LAUNCHER",
                "1",
            ],
            capture=True,
        )

    print()
    print("Done.")
    print(f"APK   : {apk}")
    print(f"Package: {args.package}")
    if kmi:
        print(f"KMI   : {kmi}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        raise SystemExit(130)
