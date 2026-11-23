# 项目依赖

本文列出构建、打包、安装 KernelSU Neo 所需的系统工具和版本。`scripts/` 下的
Python 脚本只使用标准库，本身不需要 `pip install`；它们通过调用 `cargo`、
Gradle、`adb`、`docker` 等系统工具完成实际工作。

版本号以仓库文件为准，升级依赖时请同步更新本表：

- Android SDK / NDK / JDK：`manager/build.gradle.kts`、`manager/gradle/libs.versions.toml`
- Gradle：`manager/gradle/wrapper/gradle-wrapper.properties`
- Rust edition：`Cargo.toml`
- DDK 镜像与 riscv64 nightly：`scripts/build.py`

## 快速对照

| 目标 | 必需 | 可选 |
| --- | --- | --- |
| 运行构建/安装脚本 | Python 3.10+、Git | — |
| 编译 ksud / ksuinit | Rust 1.85+、Android NDK | `cross`（`justfile`） |
| 编译 Manager APK | JDK 21、Android SDK 37、NDK 29 | — |
| 编译内核模块（LKM） | Docker 或 Podman | 本地 DDK |
| 安装到设备 | `adb` | — |
| 网站 | Node.js LTS 或 Bun | — |

## 1. 通用

- **Python 3.10+**：`scripts/build.py`、`scripts/build_apk.py`、
  `scripts/build_ksud.py`、`scripts/install.py`、`scripts/repack_apk.py`
  仅依赖标准库。
- **Git**：Manager 的 `versionCode` 来自 `git rev-list --count HEAD`；
  `scripts/build.py` 也用它生成版本信息，必须在 Git 仓库内构建。
- **网络**：首次构建会下载 Gradle 发行版、Maven/crates.io 依赖和 DDK 镜像。

## 2. Rust 用户态（`ksud`、`ksuinit`）

- **Rust 工具链**：stable，且支持 edition 2024（Rust **1.85+**）。
- **Android 目标**：

  ```bash
  rustup target add aarch64-linux-android x86_64-linux-android
  ```

  riscv64 需要 nightly（CI 使用 `nightly-2026-09-23`）和 `rust-src`：

  ```bash
  rustup toolchain install nightly-2026-09-23 --profile minimal --component rust-src
  ```

- **Android NDK**：版本 **29.0.14206865**（`libs.versions.toml` 中的 `ndk`）。
  脚本会优先使用该版本，未安装时回退到 SDK 下的最高版本；也可通过
  `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` 指定其他版本。
- **环境变量**：`ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 指向 Android SDK；
  `scripts/build.py` 也支持 `--sdk` / `--ndk` 显式覆盖。
- 构建命令：

  ```bash
  python3 scripts/build_ksud.py                 # arm64-v8a release
  python3 scripts/build_ksud.py -d -a x86_64    # debug x86_64
  ```

- `justfile` 中的 `build_ksud` 仍使用 [`cross`](https://github.com/cross-rs/cross)，
  需要额外安装：`cargo install cross`。

## 3. Manager APK

- **JDK 21**（`androidSourceCompatibility` / `androidTargetCompatibility`）。
- **Android SDK**：
  - compileSdk **37**（minor 0）
  - build-tools **37.0.0**
  - platform-tools（含 `adb`）
- **Android NDK 29.0.14206865**：Manager 的 JNI 与 `scripts/build.py` 共用。
- **Gradle 9.7.1**：由 `gradlew` 自动下载，无需手动安装；AGP 版本为 9.4.1。
- **`apksigner` / `zipalign`**：来自 build-tools，`scripts/build.py` 和
  `scripts/repack_apk.py` 用它校验/重签 APK。
- **`keytool`**：随 JDK 提供；未指定密钥库时脚本会自动生成调试签名。
- 构建命令：

  ```bash
  python3 scripts/build.py          # ksud + Manager APK
  python3 scripts/build_apk.py      # 同上，并补默认包名 com.sunyuze.kernelsuneo
  ```

## 4. 内核模块 / LKM（`--kernel`，可选）

- **Docker** 或 **Podman**，用于运行 DDK 容器：

  ```bash
  python3 scripts/build.py --kernel --kmi android15-6.6
  ```

- 默认镜像：`ghcr.io/ylarod/ddk-min:<kmi>-20260828`（镜像会按 KMI 自动选择），
  可用 `--docker` 指定容器引擎、`--ddk-image` 覆盖镜像仓库。
- 已支持的 KMI 列表见 `scripts/build.py` 中的 `SUPPORTED_KMIS`。
- 不构建内核时，`scripts/build.py` 会复用 `userspace/ksud/bin/` 下已内嵌的
  `kernelsu.ko`。

## 5. 安装到设备

- **adb**（Android platform-tools），并开启 USB 调试。
- 命令：

  ```bash
  python3 scripts/install.py            # 安装 dist/ 最新 APK 并推送匹配 LKM
  python3 scripts/install.py --uninstall
  ```

## 6. 网站与 JS

- **Node.js LTS**，或 **Bun**（仓库推荐）：

  ```bash
  cd website
  bun install && bun run docs:build
  # 或：npm install && npm run docs:build
  ```

- `js/` 是模块 WebUI 使用的 JS 库，无第三方运行时依赖；测试命令为
  `npm test`。

## 7. 辅助脚本

- `scripts/ksubot.py`（CI 通知）：需要第三方包
  [`telethon`](https://pypi.org/project/Telethon/)：

  ```bash
  pip install telethon
  ```

- `scripts/repack_apk.py`：默认只用标准库；若 `repack-config.json` 含注释，
  需要可选依赖 `json-with-comments`：

  ```bash
  pip install json-with-comments
  ```

- `scripts/prepare-ddk-x64.sh`：DDK x86_64 本地准备脚本，仍是 Bash，需要
  `bash`、`make`、`sed`、`realpath` 和 x86_64 交叉工具链。
- `scripts/setup_cargo_config.py`：只依赖标准库，用于生成 `.cargo/config.toml`。

## 8. 开发与 CI 检查（可选）

- `cargo fmt` / `cargo clippy`（`rustfmt`、`clippy` 组件）。
- `shellcheck`、`clang-format`：与 `.github/workflows/` 中的检查保持一致。
- `cargo ndk`：`AGENTS.md` 中推荐的本地检查方式，需要：

  ```bash
  cargo install cargo-ndk
  ```

## 9. 最小安装示例（Ubuntu）

```bash
sudo apt update
sudo apt install -y python3 git openjdk-21-jdk adb unzip zip

# Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
rustup target add aarch64-linux-android x86_64-linux-android

# Android SDK/NDK：用 Android Studio 或 cmdline-tools 安装
#   platform 37、build-tools 37.0.0、NDK 29.0.14206865
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/29.0.14206865"

# 仅构建内核模块时需要
sudo apt install -y docker.io
```
