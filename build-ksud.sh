#!/usr/bin/env bash
# 一键编译 userspace/ksud（Android 目标），无需安装 cargo-ndk。
#
# 用法:
#   ./build-ksud.sh                 # 默认：arm64-v8a，release
#   ./build-ksud.sh -d              # debug 构建
#   ./build-ksud.sh -a x86_64       # 编译 x86_64 版本
#   ./build-ksud.sh -p com.foo.ksu  # 指定内置包名 KSU_PACKAGE_NAME
#
# 产物:
#   target/<triple>/<profile>/ksud
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KSUD_DIR="$ROOT/userspace/ksud"

ABI="arm64-v8a"
PROFILE="release"
PACKAGE_NAME="me.weishu.kernelsu"

usage() {
    sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -d|--debug) PROFILE="debug"; shift ;;
        -a|--abi) ABI="${2:?missing ABI}"; shift 2 ;;
        -p|--package) PACKAGE_NAME="${2:?missing package name}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
    esac
done

case "$ABI" in
    arm64-v8a) TRIPLE="aarch64-linux-android"; API=26 ;;
    x86_64)    TRIPLE="x86_64-linux-android";  API=26 ;;
    riscv64)
        echo "错误: riscv64 需要 nightly + -Z build-std，请使用 ./build.py -a riscv64" >&2
        exit 2
        ;;
    *) echo "错误: 不支持的 ABI '$ABI'（可选 arm64-v8a / x86_64）" >&2; exit 2 ;;
esac

# 定位 NDK：优先环境变量，其次常见 SDK 目录，版本号取最高。
find_ndk() {
    local candidates=() sdk d
    # 显式设置的环境变量优先，不再按版本排序。
    for d in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
        [[ -n "$d" && -d "$d/toolchains/llvm" ]] && { printf '%s\n' "$d"; return 0; }
    done
    for sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
        [[ -n "$sdk" ]] || continue
        for d in "$sdk"/ndk/*; do
            [[ -d "$d" ]] && candidates+=("$d")
        done
    done
    [[ ${#candidates[@]} -gt 0 ]] || return 1
    printf '%s\n' "${candidates[@]}" | sort -V | tail -n 1
}

NDK="$(find_ndk || true)"
if [[ -z "$NDK" ]]; then
    echo "错误: 找不到 Android NDK，请设置 ANDROID_NDK_HOME" >&2
    exit 1
fi

case "$(uname -s)" in
    Linux)  HOST_TAG="linux-x86_64" ;;
    Darwin) HOST_TAG="darwin-x86_64" ;;
    *) echo "错误: 不支持的主机系统 $(uname -s)" >&2; exit 1 ;;
esac

NDK_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CLANG="$NDK_BIN/${TRIPLE}${API}-clang"
AR="$NDK_BIN/llvm-ar"
if [[ ! -x "$CLANG" ]]; then
    echo "错误: 找不到 $CLANG" >&2
    exit 1
fi

# cc-rs 只认下划线形式的变量名；链接器变量名则必须是大写目标三元组。
TRIPLE_U="${TRIPLE//-/_}"
TRIPLE_UP="$(printf '%s' "$TRIPLE" | tr '[:lower:]-' '[:upper:]_')"

CARGO_ARGS=(build --target "$TRIPLE")
[[ "$PROFILE" == "release" ]] && CARGO_ARGS+=(--release)

echo "NDK   : $NDK"
echo "目标  : $ABI ($TRIPLE, API $API)"
echo "模式  : $PROFILE"
echo "包名  : $PACKAGE_NAME"
echo

cd "$KSUD_DIR"
env \
    "ANDROID_NDK_HOME=$NDK" \
    "KSU_PACKAGE_NAME=$PACKAGE_NAME" \
    "CC_$TRIPLE_U=$CLANG" \
    "AR_$TRIPLE_U=$AR" \
    "CARGO_TARGET_${TRIPLE_UP}_LINKER=$CLANG" \
    cargo "${CARGO_ARGS[@]}"

# 仓库根目录有 workspace，target 目录位于根目录而不是 userspace/ksud。
OUT="$ROOT/target/$TRIPLE/$PROFILE/ksud"
echo
echo "编译完成: $OUT"
ls -lh "$OUT"
