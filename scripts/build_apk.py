#!/usr/bin/env python3
"""一键编译 KernelSU Neo 并打包 APK。

流程由 scripts/build.py 完成：ksuinit + ksud + 内嵌 kernelsu.ko + Manager APK。
本脚本只负责补齐默认包名，然后把其余参数原样转交给 build.py。

用法:
  python3 scripts/build_apk.py                    # 默认 arm64-v8a / release，产物在 dist/
  python3 scripts/build_apk.py -b debug -t debug  # Manager/ksud 都用 debug
  python3 scripts/build_apk.py -a x86_64          # 额外/指定 ABI
  python3 scripts/build_apk.py --kernel -k android15-6.6
                                                  # 用 DDK 重新编译内核模块（需要 docker）
  python3 scripts/build_apk.py --help             # 透传 build.py 的完整帮助
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD_SCRIPT = ROOT / "scripts" / "build.py"
DEFAULT_PACKAGE = "com.sunyuze.kernelsuneo"


def has_package_arg(argv: list[str]) -> bool:
    """判断调用方是否已经显式指定了包名，避免覆盖。"""
    for arg in argv:
        if arg in ("-p", "--package-name"):
            return True
        if arg.startswith("--package-name="):
            return True
        # 兼容 -pcom.foo 这种紧凑写法；-P 是密钥密码，不能误判。
        if arg.startswith("-p") and len(arg) > 2:
            return True
    return False


def main(argv: list[str]) -> int:
    if not BUILD_SCRIPT.is_file():
        print(f"错误: 找不到 {BUILD_SCRIPT}", file=sys.stderr)
        return 1

    args = list(argv)
    if not has_package_arg(args):
        package = os.environ.get("KSU_PACKAGE_NAME", DEFAULT_PACKAGE)
        args = ["-p", package, *args]

    # 交给 build.py 处理；sys.executable 保证使用当前 Python 解释器。
    cmd = [sys.executable, str(BUILD_SCRIPT), *args]
    try:
        return subprocess.call(cmd)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
