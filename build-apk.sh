#!/usr/bin/env bash
# 一键编译 KernelSU Neo 并打包 APK：
#   ksuinit + ksud + 内嵌 kernelsu.ko + Manager APK。
#
# 用法:
#   ./build-apk.sh                    # 默认 arm64-v8a / release，产物在 dist/
#   ./build-apk.sh -b debug -t debug  # Manager/ksud 都用 debug
#   ./build-apk.sh -a x86_64          # 额外/指定 ABI
#   ./build-apk.sh --kernel -k android15-6.6
#                                     # 用 DDK 重新编译内核模块（需要 docker）
#   ./build-apk.sh --help             # 透传 build.py 的完整帮助
#
# 其余参数原样传给 build.py；默认包名使用 com.sunyuze.kernelsuneo，
# 与仓库内嵌的 bin/aarch64/android15-6.6_kernelsu.ko 保持一致。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_PACKAGE="com.sunyuze.kernelsuneo"

if ! command -v python3 >/dev/null 2>&1; then
    echo "错误: 未找到 python3" >&2
    exit 1
fi

# 若调用方自己指定了 -p/--package-name，则不再追加默认值。
package_args=()
has_package=0
for arg in "$@"; do
    case "$arg" in
        -p|--package-name|--package-name=*|-p*) has_package=1 ;;
    esac
done
if [[ $has_package -eq 0 ]]; then
    package_args=(-p "${KSU_PACKAGE_NAME:-$DEFAULT_PACKAGE}")
fi

exec python3 "$ROOT/build.py" "${package_args[@]}" "$@"
