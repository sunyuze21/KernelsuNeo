#!/usr/bin/env bash
# Install the built KernelSU Neo manager APK and push the matching LKM.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_PACKAGE="com.sunyuze.kernelsuneo"
PACKAGE="${PACKAGE:-$DEFAULT_PACKAGE}"
APK=""
SERIAL="${ANDROID_SERIAL:-}"
LKM_DIR="$ROOT/dist/lkm"
PUSH_LKM=1
LAUNCH=1
FORCE=0
UNINSTALL=0

usage() {
    cat <<'EOF'
Usage: ./install.sh [options]

Installs the newest dist/*.apk and pushes dist/lkm/*.ko for the connected
device's KMI to /sdcard/Download/KernelSU-Neo/.

Options:
  -s, --serial SERIAL   adb device serial (default: $ANDROID_SERIAL)
  -a, --apk PATH        APK to install (default: newest dist/*.apk)
  -p, --package NAME    package name (default: com.sunyuze.kernelsuneo)
      --no-lkm          do not push the kernel module
      --no-launch       do not open the manager after installation
      --force           continue even if the device KMI has no matching LKM;
                        additionally pushes all LKMs
      --uninstall       uninstall the manager package and exit
  -h, --help            show this help

Examples:
  ./install.sh
  ./install.sh -s 54abd35b
  ./install.sh --apk dist/KernelSU_xxx.apk --no-lkm
  ./install.sh --uninstall
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--serial) SERIAL="${2:?missing serial}"; shift 2 ;;
        -a|--apk) APK="${2:?missing apk path}"; shift 2 ;;
        -p|--package) PACKAGE="${2:?missing package name}"; shift 2 ;;
        --no-lkm) PUSH_LKM=0; shift ;;
        --no-launch) LAUNCH=0; shift ;;
        --force) FORCE=1; shift ;;
        --uninstall) UNINSTALL=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
    esac
done

if ! command -v adb >/dev/null 2>&1; then
    echo "error: adb not found in PATH" >&2
    exit 1
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB+=(-s "$SERIAL")
fi

STATE="$("${ADB[@]}" get-state 2>/dev/null || true)"
if [[ "$STATE" != "device" ]]; then
    echo "error: no usable Android device" >&2
    adb devices -l || true
    echo "Connect a device with USB debugging enabled and try again." >&2
    exit 1
fi

MODEL="$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
ABI="$("${ADB[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
KERNEL="$( "${ADB[@]}" shell uname -r 2>/dev/null | tr -d '\r')"
LOCKED="$("${ADB[@]}" shell getprop ro.boot.flash.locked 2>/dev/null | tr -d '\r')"
VBSTATE="$("${ADB[@]}" shell getprop ro.boot.verifiedbootstate 2>/dev/null | tr -d '\r')"

ANDROID_VER="$(printf '%s' "$KERNEL" | sed -n 's/.*android\([0-9]\+\).*/\1/p' | head -n1)"
KERNEL_VER="$(printf '%s' "$KERNEL" | sed -n 's/^\([0-9]\+\.[0-9]\+\).*/\1/p' | head -n1)"
DEVICE_KMI=""
if [[ -n "$ANDROID_VER" && -n "$KERNEL_VER" ]]; then
    DEVICE_KMI="android${ANDROID_VER}-${KERNEL_VER}"
fi

echo "Device : ${MODEL:-unknown}"
echo "ABI    : ${ABI:-unknown}"
echo "SDK    : ${SDK:-unknown}"
echo "Kernel : ${KERNEL:-unknown}"
if [[ -n "$DEVICE_KMI" ]]; then
    echo "KMI    : $DEVICE_KMI"
fi
echo

if [[ "$UNINSTALL" == "1" ]]; then
    echo "Uninstalling $PACKAGE ..."
    "${ADB[@]}" uninstall "$PACKAGE" || true
    exit 0
fi

if [[ -z "$APK" ]]; then
    APK="$(ls -t "$ROOT"/dist/*.apk 2>/dev/null | head -n1 || true)"
fi
if [[ -z "$APK" || ! -f "$APK" ]]; then
    echo "error: APK not found. Build it first with:" >&2
    echo "  python3 build.py --kernel --kmi $DEVICE_KMI -p $PACKAGE" >&2
    exit 1
fi

MATCHING_LKM=""
if [[ -n "$DEVICE_KMI" && -f "$LKM_DIR/${DEVICE_KMI}_kernelsu.ko" ]]; then
    MATCHING_LKM="$LKM_DIR/${DEVICE_KMI}_kernelsu.ko"
fi

if [[ "$PUSH_LKM" == "1" && -z "$MATCHING_LKM" ]]; then
    echo "warning: no LKM built for device KMI ${DEVICE_KMI:-unknown}" >&2
    if [[ -d "$LKM_DIR" ]]; then
        echo "available LKMs:" >&2
        ls -1 "$LKM_DIR"/*.ko 2>/dev/null | sed 's/^/  /' >&2 || true
    fi
    if [[ "$FORCE" != "1" ]]; then
        echo "The APK will still be installed, but the embedded LKM may not match." >&2
        echo "Use --force to also push all available LKMs." >&2
    fi
fi

echo "Installing: $APK"
if ! "${ADB[@]}" install -r "$APK"; then
    # MIUI/HyperOS often blocks streamed installs with USER_RESTRICTED.
    echo "streamed install failed, retrying with pm install ..."
    REMOTE_APK="/data/local/tmp/ksu-neo-install.apk"
    "${ADB[@]}" push "$APK" "$REMOTE_APK"
    if ! "${ADB[@]}" shell pm install -r -d "$REMOTE_APK"; then
        "${ADB[@]}" shell rm -f "$REMOTE_APK" || true
        echo "error: installation failed" >&2
        echo "If this is INSTALL_FAILED_UPDATE_INCOMPATIBLE, uninstall first:" >&2
        echo "  ${ADB[*]} uninstall $PACKAGE" >&2
        echo "If this is INSTALL_FAILED_USER_RESTRICTED (MIUI/HyperOS), enable:" >&2
        echo "  Developer options -> Install via USB" >&2
        echo "  Developer options -> USB debugging (Security settings)" >&2
        echo "Then keep the phone unlocked and run this script again." >&2
        exit 1
    fi
    "${ADB[@]}" shell rm -f "$REMOTE_APK" || true
fi

if ! "${ADB[@]}" shell pm path "$PACKAGE" >/dev/null 2>&1; then
    echo "error: package $PACKAGE is not installed after the install step" >&2
    exit 1
fi

VERSION_LINE="$("${ADB[@]}" shell dumpsys package "$PACKAGE" 2>/dev/null \
    | grep -m1 'versionName=' | sed 's/^ *//' || true)"
echo "Installed : ${VERSION_LINE:-$PACKAGE}"

if [[ "$PUSH_LKM" == "1" ]]; then
    "${ADB[@]}" shell mkdir -p /sdcard/Download/KernelSU-Neo
    if [[ -n "$MATCHING_LKM" ]]; then
        "${ADB[@]}" push "$MATCHING_LKM" /sdcard/Download/KernelSU-Neo/
        echo "LKM       : pushed $(basename "$MATCHING_LKM")"
    elif [[ "$FORCE" == "1" && -d "$LKM_DIR" ]]; then
        for ko in "$LKM_DIR"/*.ko; do
            [[ -f "$ko" ]] || continue
            "${ADB[@]}" push "$ko" /sdcard/Download/KernelSU-Neo/
        done
        echo "LKM       : pushed all available modules"
    else
        echo "LKM       : not pushed (no KMI match)"
    fi
    echo "LKM dir   : /sdcard/Download/KernelSU-Neo/"
fi

if [[ "$LOCKED" == "1" || "$VBSTATE" == "green" ]]; then
    echo
    echo "WARNING: bootloader is locked or verified boot is green."
    echo "Installing the APK does not load the kernel module by itself."
    echo "The matching LKM must be patched/flashed after unlocking the bootloader,"
    echo "or loaded through the manager's LKM installation flow if available."
fi

if [[ "$LAUNCH" == "1" ]]; then
    "${ADB[@]}" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 \
        >/dev/null 2>&1 || true
fi

echo
echo "Done."
echo "APK   : $APK"
echo "Package: $PACKAGE"
if [[ -n "$DEVICE_KMI" ]]; then
    echo "KMI   : $DEVICE_KMI"
fi
