#!/usr/bin/env python3
"""一键编译 userspace/ksud（Android 目标），无需安装 cargo-ndk。

通过环境变量把 Android NDK 的 clang/llvm-ar 交给 cargo 的 cc-rs / 链接器，
然后调用系统 cargo 完成构建。

用法:
  python3 scripts/build_ksud.py                 # 默认：arm64-v8a，release
  python3 scripts/build_ksud.py -d              # debug 构建
  python3 scripts/build_ksud.py -a x86_64       # 编译 x86_64 版本
  python3 scripts/build_ksud.py -p com.foo.ksu  # 指定内置包名 KSU_PACKAGE_NAME

产物:
  target/<triple>/<profile>/ksud
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
KSUD_DIR = ROOT / "userspace" / "ksud"

# ABI -> (Rust 目标三元组, Android API level)
ABI_TARGETS = {
    "arm64-v8a": ("aarch64-linux-android", 26),
    "x86_64": ("x86_64-linux-android", 26),
}

HOST_TAGS = {
    "Linux": "linux-x86_64",
    "Darwin": "darwin-x86_64",
}


def version_key(name: str) -> tuple[int, ...]:
    """把 NDK 目录名转换成可比较的数字序列，例如 29.0.14206865。"""
    return tuple(int(part) for part in re.findall(r"\d+", name))


def is_ndk_dir(path: Path) -> bool:
    return (path / "toolchains" / "llvm").is_dir()


def pinned_ndk_version() -> str | None:
    """读取 Manager 固定的 NDK 版本，保证 cargo 与 Gradle 使用同一套工具链。"""
    versions = ROOT / "manager" / "gradle" / "libs.versions.toml"
    if not versions.is_file():
        return None
    match = re.search(r'^ndk\s*=\s*"([^"]+)"', versions.read_text(encoding="utf-8"), re.M)
    return match.group(1) if match else None


def find_ndk() -> Path | None:
    """按环境变量、Android SDK 目录的顺序查找 NDK，优先使用项目固定版本。"""
    # 显式设置的环境变量优先，不再按版本排序。
    for name in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        value = os.environ.get(name)
        if value:
            path = Path(value).expanduser()
            if is_ndk_dir(path):
                return path

    sdk_roots = [
        os.environ.get("ANDROID_SDK_ROOT"),
        os.environ.get("ANDROID_HOME"),
        str(Path.home() / "Android" / "Sdk"),
    ]
    candidates: list[Path] = []
    for sdk in sdk_roots:
        if not sdk:
            continue
        ndk_root = Path(sdk).expanduser() / "ndk"
        if not ndk_root.is_dir():
            continue
        candidates.extend(path for path in ndk_root.iterdir() if is_ndk_dir(path))

    if not candidates:
        return None

    # 与 scripts/build.py 行为一致：有固定版本就用固定版本，否则取最高版本。
    pinned = pinned_ndk_version()
    if pinned:
        for path in candidates:
            if path.name == pinned:
                return path
    candidates.sort(key=lambda path: version_key(path.name))
    return candidates[-1]


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="build_ksud.py",
        description="编译 userspace/ksud 的 Android 版本（无需 cargo-ndk）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例:\n"
            "  python3 scripts/build_ksud.py\n"
            "  python3 scripts/build_ksud.py -d -a x86_64\n"
            "  python3 scripts/build_ksud.py -p com.example.ksu\n"
        ),
    )
    parser.add_argument("-d", "--debug", action="store_true", help="debug 构建")
    parser.add_argument(
        "-a",
        "--abi",
        default="arm64-v8a",
        help="目标 ABI：arm64-v8a 或 x86_64（默认 arm64-v8a）",
    )
    parser.add_argument(
        "-p",
        "--package",
        default="me.weishu.kernelsu",
        help="编译期写入的 KSU_PACKAGE_NAME（默认 me.weishu.kernelsu）",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)

    if args.abi == "riscv64":
        print(
            "错误: riscv64 需要 nightly + -Z build-std，请使用 "
            "python3 scripts/build.py -a riscv64",
            file=sys.stderr,
        )
        return 2
    if args.abi not in ABI_TARGETS:
        print(
            f"错误: 不支持的 ABI '{args.abi}'（可选 arm64-v8a / x86_64）",
            file=sys.stderr,
        )
        return 2

    triple, api = ABI_TARGETS[args.abi]
    profile = "debug" if args.debug else "release"

    ndk = find_ndk()
    if ndk is None:
        print("错误: 找不到 Android NDK，请设置 ANDROID_NDK_HOME", file=sys.stderr)
        return 1

    host_tag = HOST_TAGS.get(os.uname().sysname)
    if host_tag is None:
        print(f"错误: 不支持的主机系统 {os.uname().sysname}", file=sys.stderr)
        return 1

    ndk_bin = ndk / "toolchains" / "llvm" / "prebuilt" / host_tag / "bin"
    clang = ndk_bin / f"{triple}{api}-clang"
    ar = ndk_bin / "llvm-ar"
    if not clang.is_file():
        print(f"错误: 找不到 {clang}", file=sys.stderr)
        return 1

    cargo = shutil.which("cargo")
    if cargo is None:
        print("错误: 找不到 cargo，请先安装 Rust 工具链", file=sys.stderr)
        return 1

    # cc-rs 只认下划线形式的变量名；链接器变量名则必须是大写目标三元组。
    triple_u = triple.replace("-", "_")
    triple_up = triple_u.upper()

    env = os.environ.copy()
    env.update(
        {
            "ANDROID_NDK_HOME": str(ndk),
            "KSU_PACKAGE_NAME": args.package,
            f"CC_{triple_u}": str(clang),
            f"AR_{triple_u}": str(ar),
            f"CARGO_TARGET_{triple_up}_LINKER": str(clang),
        }
    )

    cargo_args = [cargo, "build", "--target", triple]
    if profile == "release":
        cargo_args.append("--release")

    print(f"NDK   : {ndk}")
    print(f"目标  : {args.abi} ({triple}, API {api})")
    print(f"模式  : {profile}")
    print(f"包名  : {args.package}")
    print()

    try:
        result = subprocess.call(cargo_args, cwd=KSUD_DIR, env=env)
    except KeyboardInterrupt:
        return 130
    if result != 0:
        return result

    # 仓库根目录有 workspace，target 目录位于根目录而不是 userspace/ksud。
    output = ROOT / "target" / triple / profile / "ksud"
    print()
    print(f"编译完成: {output}")
    if output.is_file():
        size = output.stat().st_size
        print(f"{size / (1024 * 1024):.1f} MiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
