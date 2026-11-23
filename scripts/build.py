#!/usr/bin/env python3
"""One-click build script for KernelSU Neo (ksud + manager APK).

What it does:
  1. When --kernel is used, builds kernelsu.ko for the requested KMI(s) with
     DDK, copies them into dist/lkm/, and embeds them into ksud's bin/aarch64/.
     The manager certificate size and SHA-256 are derived from the signing
     keystore so the module trusts exactly the manager built here.
  2. Builds userspace/ksud for the requested Android ABIs with
     KSU_PACKAGE_NAME embedded at compile time.
  3. Copies each ksud binary into manager/app/src/main/jniLibs/<abi>/libksud.so.
  4. Builds the manager APK with -PKSU_PACKAGE_NAME / -PKSU_NAME and signs it
     with the given or an auto-generated keystore (v2 signature required).
  5. Copies the final APK into dist/ and verifies its certificate against the
     kernel's expected signature.

If you also build the kernel/LKM yourself, pass the same package name to its
build as KSU_PACKAGE_NAME; the manager and kernel must agree on it. With
--kernel this is handled automatically.

Examples:
  python3 scripts/build.py -p com.example.kernelsu -n "My KernelSU"
  python3 scripts/build.py --kernel --kmi android15-6.6 -K release.jks -A key \\
      -P storepass -S keypass
  python3 scripts/build.py -a arm64-v8a -a x86_64 -b debug
  python3 scripts/build.py -K my.jks -A mykey -P storepass -S keypass
  python3 scripts/build.py --kernel --kmi android15-6.6
  python3 scripts/build.py --kernel --skip-app --skip-ksud
  python3 scripts/build.py --skip-ksud --skip-app
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

ROOT = Path(__file__).resolve().parent.parent
MANAGER_DIR = ROOT / "manager"
KSUD_MANIFEST = ROOT / "userspace" / "ksud" / "Cargo.toml"
JNILIBS_DIR = MANAGER_DIR / "app" / "src" / "main" / "jniLibs"
KSUD_BIN_DIR = ROOT / "userspace" / "ksud" / "bin"
KSUD_BIN_ARCH = {
    "arm64-v8a": "aarch64",
    "x86_64": "x86_64",
    "riscv64": "riscv64",
}
KSUINIT_ARCH_MACHINE = {
    "aarch64": 183,
    "x86_64": 62,
    "riscv64": 243,
}
VERSIONS_TOML = MANAGER_DIR / "gradle" / "libs.versions.toml"
RISCV_CONFIG = ROOT / ".github" / "riscv64-cargo.toml"
RISCV_NIGHTLY = "nightly-2026-09-23"
STATE_DIR = ROOT / ".build"
DEFAULT_KEYSTORE_DIR = Path.home() / ".kernelsu-neo"

# DDK images used by the project's CI to build the LKM for a given KMI.
DEFAULT_DDK_RELEASE = "20260828"
DEFAULT_DDK_IMAGE = "ghcr.io/ylarod/ddk-min"
DDK_IMAGE_MIRRORS = [
    DEFAULT_DDK_IMAGE,
    "docker.cnb.cool/ylarod/ddk/ddk-min",
]
SUPPORTED_KMIS = [
    "android12-5.10",
    "android13-5.10",
    "android13-5.15",
    "android14-5.15",
    "android14-6.1",
    "android15-6.6",
    "android16-6.12",
    "android17-6.18",
]

# Build artifacts and VCS data that must not leak into the temporary kernel
# tree handed to the DDK container.
LKM_COPY_IGNORE = shutil.ignore_patterns(
    ".git",
    "out",
    "out-x64",
    "libs",
    "obj",
    "*.o",
    "*.ko",
    "*.mod",
    "*.mod.c",
    "*.symvers",
    "*.order",
    "*.lds",
    ".*.cmd",
    ".tmp_versions",
    ".cache",
    ".thinlto-cache",
    "compile_commands.json",
    "check_symbol",
    "ksu_uapi.h",
    "*.elf",
)

DEFAULT_PACKAGE_NAME = "me.weishu.kernelsu"
DEFAULT_APP_NAME = "KernelsuNeo"

# Android applicationId rules: at least two dot-separated segments, each
# starting with a letter. Also keep it below KSU_MAX_PACKAGE_NAME (256).
PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")


@dataclass(frozen=True)
class Arch:
    abi: str
    triple: str
    api: int


ARCHES: Dict[str, Arch] = {
    "arm64-v8a": Arch("arm64-v8a", "aarch64-linux-android", 26),
    "x86_64": Arch("x86_64", "x86_64-linux-android", 26),
    # RISC-V needs a nightly toolchain with -Z build-std (see CI workflow).
    "riscv64": Arch("riscv64", "riscv64-linux-android", 35),
}


@dataclass
class SigningConfig:
    keystore: Path
    alias: str
    store_pass: str
    key_pass: str
    source: str


class BuildError(RuntimeError):
    pass


# ---------------------------------------------------------------------------
# Logging / command execution
# ---------------------------------------------------------------------------

_COLOR = sys.stdout.isatty() and os.environ.get("NO_COLOR") is None


def _paint(code: str, text: str) -> str:
    if not _COLOR:
        return text
    return f"\033[{code}m{text}\033[0m"


def info(message: str) -> None:
    print(f"{_paint('36', '[info]')} {message}", flush=True)


def warn(message: str) -> None:
    print(f"{_paint('33', '[warn]')} {message}", flush=True)


def error(message: str) -> None:
    print(f"{_paint('31', '[error]')} {message}", file=sys.stderr, flush=True)


def section(title: str) -> None:
    print(f"\n{_paint('1;34', '==>')} {_paint('1', title)}", flush=True)


class Runner:
    """Run subprocesses, optionally printing commands without executing them."""

    def __init__(self, dry_run: bool = False) -> None:
        self.dry_run = dry_run
        self.secrets: List[str] = []

    def add_secret(self, value: Optional[str]) -> None:
        if value and value not in self.secrets:
            self.secrets.append(value)

    def _redact(self, token: str) -> str:
        if token in ("-storepass", "-keypass", "--ks-pass", "--key-pass"):
            return token
        redacted = token
        for secret in self.secrets:
            if secret and secret in redacted:
                redacted = redacted.replace(secret, "******")
        return redacted

    def _format(self, args: Sequence[str]) -> str:
        parts: List[str] = []
        hide_next = False
        for arg in args:
            if hide_next:
                parts.append("******")
                hide_next = False
                continue
            if arg in ("-storepass", "-keypass", "--ks-pass", "--key-pass"):
                parts.append(arg)
                hide_next = True
                continue
            parts.append(shlex.quote(self._redact(arg)))
        return " ".join(parts)

    def run(
        self,
        cmd: Sequence[object],
        *,
        cwd: Path = ROOT,
        env: Optional[Dict[str, Optional[str]]] = None,
        capture: bool = False,
        check: bool = True,
    ) -> subprocess.CompletedProcess:
        args = [str(item) for item in cmd]
        prefix = "[dry-run] " if self.dry_run else ""
        print(f"{prefix}$ {self._format(args)}", flush=True)
        if self.dry_run:
            return subprocess.CompletedProcess(args, 0, "", "")

        run_env: Optional[Dict[str, str]] = None
        if env is not None:
            run_env = {**os.environ}
            for key, value in env.items():
                if value is None:
                    run_env.pop(key, None)
                else:
                    run_env[key] = str(value)

        try:
            proc = subprocess.run(
                args,
                cwd=str(cwd),
                env=run_env,
                stdout=subprocess.PIPE if capture else None,
                stderr=subprocess.PIPE if capture else None,
                text=True,
            )
        except FileNotFoundError as exc:
            raise BuildError(f"command not found: {args[0]}") from exc

        if check and proc.returncode != 0:
            details = ""
            if capture:
                output = ((proc.stdout or "") + (proc.stderr or "")).strip()
                if output:
                    details = "\n" + output
            raise BuildError(
                f"command failed with exit code {proc.returncode}: "
                f"{self._format(args)}{details}"
            )
        return proc


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------


def find_executable(name: str, *, required: bool = False, hint: str = "") -> Optional[Path]:
    found = shutil.which(name)
    if found is None and os.name == "nt":
        for suffix in (".exe", ".bat", ".cmd"):
            found = shutil.which(name + suffix)
            if found:
                break
    if found is None:
        if required:
            message = f"required tool not found: {name}"
            if hint:
                message += f"\n  hint: {hint}"
            raise BuildError(message)
        return None
    return Path(found)


def parse_properties(path: Path) -> Dict[str, str]:
    if not path.is_file():
        return {}
    result: Dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" in line:
            key, value = line.split("=", 1)
        elif ":" in line:
            key, value = line.split(":", 1)
        else:
            continue
        result[key.strip()] = value.strip().strip('"').strip("'")
    return result


def version_key(value: str) -> Tuple[int, ...]:
    numbers = re.findall(r"\d+", value)
    return tuple(int(number) for number in numbers) if numbers else (0,)


def project_ndk_version() -> Optional[str]:
    if not VERSIONS_TOML.is_file():
        return None
    match = re.search(
        r'^\s*ndk\s*=\s*"([^"]+)"',
        VERSIONS_TOML.read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    return match.group(1) if match else None


def detect_sdk(explicit: Optional[str]) -> Optional[Path]:
    if explicit:
        path = Path(explicit).expanduser()
        if not path.is_dir():
            raise BuildError(f"--sdk is not a directory: {path}")
        return path.resolve()

    candidates: List[Optional[str]] = [
        os.environ.get("ANDROID_SDK_ROOT"),
        os.environ.get("ANDROID_HOME"),
    ]
    for candidate in candidates:
        if not candidate:
            continue
        path = Path(candidate).expanduser()
        if path.is_dir():
            return path.resolve()

    local_properties = MANAGER_DIR / "local.properties"
    properties = parse_properties(local_properties)
    sdk_dir = properties.get("sdk.dir")
    if sdk_dir:
        # Java properties files escape ':' and '\' (common with Windows paths).
        sdk_dir = re.sub(r"\\(.)", r"\1", sdk_dir)
        path = Path(sdk_dir).expanduser()
        if not path.is_absolute():
            path = (MANAGER_DIR / path).resolve()
        if path.is_dir():
            return path

    default = Path.home() / "Android" / "Sdk"
    if default.is_dir():
        return default.resolve()
    return None


def detect_ndk(explicit: Optional[str], sdk: Optional[Path]) -> Path:
    if explicit:
        path = Path(explicit).expanduser()
        if not (path / "toolchains" / "llvm" / "prebuilt").is_dir():
            raise BuildError(f"--ndk does not look like an NDK directory: {path}")
        return path.resolve()

    # Gradle pins the NDK version in libs.versions.toml. Prefer that exact
    # version so cargo and Gradle use the same toolchain; --ndk overrides it.
    if sdk is not None:
        wanted = project_ndk_version()
        if wanted and (sdk / "ndk" / wanted).is_dir():
            return (sdk / "ndk" / wanted).resolve()

    for name in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK"):
        value = os.environ.get(name)
        if value:
            path = Path(value).expanduser()
            if (path / "toolchains" / "llvm" / "prebuilt").is_dir():
                return path.resolve()

    if sdk is not None:
        ndk_root = sdk / "ndk"
        if ndk_root.is_dir():
            versions = sorted(
                (item for item in ndk_root.iterdir() if item.is_dir()),
                key=lambda item: version_key(item.name),
            )
            if versions:
                chosen = versions[-1]
                warn(
                    "project NDK not found; using the newest available NDK: "
                    + chosen.name
                )
                return chosen.resolve()

    raise BuildError(
        "Android NDK not found.\n"
        "  Set ANDROID_NDK_HOME (or install it under $ANDROID_HOME/ndk)."
    )


def prebuilt_dir(ndk: Path) -> Path:
    base = ndk / "toolchains" / "llvm" / "prebuilt"
    if not base.is_dir():
        raise BuildError(f"invalid NDK layout (missing {base})")
    entries = sorted(item for item in base.iterdir() if item.is_dir())
    if not entries:
        raise BuildError(f"no NDK prebuilt toolchain under {base}")
    return entries[0].resolve()


def tool_path(directory: Path, name: str) -> Optional[Path]:
    for suffix in ("", ".exe", ".bat", ".cmd"):
        candidate = directory / f"{name}{suffix}"
        if candidate.is_file():
            return candidate
    return None


def find_clang(bin_dir: Path, triple: str, api: int) -> Path:
    candidates = [
        f"{triple}{api}-clang",
        f"{triple}-clang",
    ]
    for name in candidates:
        found = tool_path(bin_dir, name)
        if found:
            return found
    raise BuildError(
        f"NDK clang wrapper not found for {triple} (API {api}) in {bin_dir}.\n"
        "  Your NDK may be too old or missing support for this ABI."
    )


def find_android_tool(name: str, sdk: Optional[Path]) -> Optional[Path]:
    direct = find_executable(name)
    if direct:
        return direct
    if sdk is None:
        return None
    build_tools = sdk / "build-tools"
    if not build_tools.is_dir():
        return None
    candidates: List[Tuple[Tuple[int, ...], Path]] = []
    for version_dir in build_tools.iterdir():
        if not version_dir.is_dir():
            continue
        found = tool_path(version_dir, name)
        if found:
            candidates.append((version_key(version_dir.name), found))
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0])
    return candidates[-1][1]


def java_major_version() -> Optional[int]:
    java = find_executable("java")
    if java is None:
        return None
    proc = subprocess.run(
        [str(java), "-version"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    output = (proc.stderr or "") + (proc.stdout or "")
    match = re.search(r'version "(\d+)(?:\.(\d+))?', output)
    if not match:
        return None
    major = int(match.group(1))
    if major == 1 and match.group(2):
        major = int(match.group(2))
    return major


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def human_size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024.0 or unit == "GiB":
            return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} B"
        value /= 1024.0
    return f"{size} B"


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


def parse_arches(values: Sequence[str]) -> List[Arch]:
    result: List[Arch] = []
    seen = set()
    for value in values:
        for part in value.split(","):
            abi = part.strip()
            if not abi:
                continue
            if abi not in ARCHES:
                raise BuildError(
                    f"unknown ABI '{abi}'. Supported: {', '.join(ARCHES)}"
                )
            if abi not in seen:
                seen.add(abi)
                result.append(ARCHES[abi])
    if not result:
        result.append(ARCHES["arm64-v8a"])
    return result


def validate_config(args: argparse.Namespace) -> None:
    package = args.package_name.strip()
    if not PACKAGE_RE.match(package):
        raise BuildError(
            f"invalid package name: {args.package_name!r}\n"
            "  Must look like com.example.kernelsu "
            "(dot-separated, each segment starts with a letter)."
        )
    if len(package) >= 255:
        raise BuildError(
            "package name is too long (must be shorter than 255 bytes, "
            "including the trailing NUL used by the kernel)"
        )
    args.package_name = package

    if not args.app_name.strip():
        raise BuildError("--app-name must not be empty")

    if args.build_type not in ("debug", "release"):
        raise BuildError("--build-type must be debug or release")
    if args.ksud_build_type not in ("debug", "release"):
        raise BuildError("--ksud-build-type must be debug or release")
    if args.jobs is not None and args.jobs < 1:
        raise BuildError("--jobs must be a positive integer")

    if args.skip_ksud and args.skip_app and not args.kernel:
        raise BuildError(
            "nothing to do: --skip-ksud and --skip-app were given without "
            "--kernel"
        )
    if args.kmi and not args.kernel:
        raise BuildError("--kmi can only be used together with --kernel")
    if (args.expected_size or args.expected_hash) and not args.kernel:
        raise BuildError(
            "--expected-size/--expected-hash can only be used with --kernel"
        )
    if (
        args.kernel
        and not args.skip_app
        and args.build_type == "release"
        and args.no_sign
    ):
        raise BuildError(
            "--no-sign cannot be used with --kernel when building the APK.\n"
            "  The kernel must trust the manager certificate. Pass a keystore "
            "(-K/-A/-P/-S) or build the APK separately."
        )
    if not args.ddk_release.strip():
        raise BuildError("--ddk-release must not be empty")


# ---------------------------------------------------------------------------
# Rust / ksud
# ---------------------------------------------------------------------------


def rustup_installed_targets() -> Optional[set]:
    try:
        proc = subprocess.run(
            ["rustup", "target", "list", "--installed"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except FileNotFoundError:
        return None
    if proc.returncode != 0:
        return None
    return set(proc.stdout.split())


def ensure_rust_target(
    triple: str, *, auto_install: bool, runner: Runner
) -> None:
    installed = rustup_installed_targets()
    if installed is None:
        warn("rustup not found; cannot verify that the Rust target is installed")
        return
    if triple in installed:
        return
    if not auto_install:
        raise BuildError(
            f"Rust target {triple} is not installed.\n"
            f"  Run: rustup target add {triple}"
        )
    info(f"Rust target {triple} is missing, installing it")
    runner.run(["rustup", "target", "add", triple])


def ensure_riscv_toolchain(runner: Runner) -> None:
    if runner.dry_run:
        return
    try:
        proc = subprocess.run(
            ["rustup", "toolchain", "list"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except FileNotFoundError as exc:
        raise BuildError("rustup is required to build riscv64") from exc
    toolchains = proc.stdout
    if not any(line.startswith(RISCV_NIGHTLY) for line in toolchains.splitlines()):
        raise BuildError(
            f"RISC-V builds need the {RISCV_NIGHTLY} toolchain.\n"
            f"  Run: rustup toolchain install {RISCV_NIGHTLY} "
            "--profile minimal --component rust-src"
        )
    proc = subprocess.run(
        ["rustup", "component", "list", "--toolchain", RISCV_NIGHTLY, "--installed"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if "rust-src" not in proc.stdout:
        raise BuildError(
            f"{RISCV_NIGHTLY} is missing the rust-src component.\n"
            f"  Run: rustup component add rust-src --toolchain {RISCV_NIGHTLY}"
        )


def ksud_build_env(ndk: Path, arch: Arch, package_name: str) -> Dict[str, str]:
    prebuilt = prebuilt_dir(ndk)
    bin_dir = prebuilt / "bin"
    clang = find_clang(bin_dir, arch.triple, arch.api)
    sysroot = prebuilt / "sysroot"

    underscored = arch.triple.replace("-", "_")
    upper = underscored.upper()

    llvm_ar = tool_path(bin_dir, "llvm-ar")
    if llvm_ar is None:
        raise BuildError(f"llvm-ar not found in {bin_dir}")

    env: Dict[str, str] = {
        "ANDROID_NDK_HOME": str(ndk),
        "ANDROID_NDK_ROOT": str(ndk),
        "KSU_PACKAGE_NAME": package_name,
        f"CC_{underscored}": str(clang),
        f"CXX_{underscored}": str(clang) + "++",
        f"AR_{underscored}": str(llvm_ar),
        f"CARGO_TARGET_{upper}_LINKER": str(clang),
        f"BINDGEN_EXTRA_CLANG_ARGS_{underscored}": (
            f"--sysroot={sysroot} -I{sysroot / 'usr' / 'include' / arch.triple}"
        ),
    }
    jobs = os.environ.get("CARGO_BUILD_JOBS")
    if jobs:
        env["CARGO_BUILD_JOBS"] = jobs
    return env


def ksuinit_builtins_path(clang: Path, arch_name: str) -> Path:
    proc = subprocess.run(
        [str(clang), "--print-resource-dir"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if proc.returncode != 0:
        raise BuildError(
            f"failed to query clang resource directory: {proc.stderr.strip()}"
        )
    resource_dir = Path(proc.stdout.strip())
    return (
        resource_dir
        / "lib"
        / "linux"
        / f"libclang_rt.builtins-{arch_name}-android.a"
    )


def build_ksuinit(
    args: argparse.Namespace,
    runner: Runner,
    ndk: Path,
    arches: Sequence[Arch],
) -> None:
    section("Building ksuinit")
    cargo = find_executable(
        "cargo", required=True, hint="Install Rust via https://rustup.rs"
    )
    prebuilt = prebuilt_dir(ndk)

    for arch in arches:
        if arch.abi == "riscv64":
            ensure_riscv_toolchain(runner)
        else:
            ensure_rust_target(
                arch.triple, auto_install=args.auto_install, runner=runner
            )

        arch_name = KSUD_BIN_ARCH[arch.abi]
        clang = find_clang(prebuilt / "bin", arch.triple, arch.api)
        builtins = ksuinit_builtins_path(clang, arch_name)
        if not builtins.is_file():
            raise BuildError(
                f"missing compiler runtime for ksuinit: {builtins}\n"
                "  Check that the NDK matches the project's ndkVersion."
            )

        env = ksud_build_env(ndk, arch, args.package_name)
        # ksuinit runs from the initramfs, so it must be fully static and use
        # 16 KiB page alignment (same flags as the CI ksuinit workflow).
        env["RUSTFLAGS"] = (
            "-C target-feature=+crt-static "
            "-C link-arg=-Wl,-z,max-page-size=16384 "
            "-C link-arg=-Wno-unused-command-line-argument "
            f"-C link-arg={builtins}"
        )

        cmd: List[object] = [cargo]
        if arch.abi == "riscv64":
            cmd.extend(
                [
                    f"+{RISCV_NIGHTLY}",
                    "build",
                    "--config",
                    RISCV_CONFIG,
                    "-Z",
                    "build-std=std,panic_abort",
                ]
            )
        else:
            cmd.append("build")
        cmd.extend(
            ["--package", "ksuinit", "--target", arch.triple, "--release"]
        )

        info(f"building ksuinit for {arch.abi} ({arch.triple})")
        runner.run(cmd, env=env)

        if runner.dry_run:
            continue

        source = ROOT / "target" / arch.triple / "release" / "ksuinit"
        if not source.is_file():
            raise BuildError(f"ksuinit binary was not produced: {source}")
        data = source.read_bytes()
        if not data.startswith(b"\x7fELF"):
            raise BuildError(f"ksuinit is not an ELF binary: {source}")
        machine = int.from_bytes(data[18:20], "little")
        expected = KSUINIT_ARCH_MACHINE[arch_name]
        if machine != expected:
            raise BuildError(
                f"ksuinit for {arch.abi} has unexpected e_machine={machine}"
            )

        destination = KSUD_BIN_DIR / arch_name / "ksuinit"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        destination.chmod(destination.stat().st_mode | 0o111)
        info(
            f"installed {destination.relative_to(ROOT)} "
            f"({human_size(len(data))})"
        )


def check_ksud_assets(arches: Sequence[Arch], dry_run: bool = False) -> None:
    if dry_run:
        return
    required = ("busybox", "waitsys", "ksuinit")
    for arch in arches:
        directory = KSUD_BIN_DIR / KSUD_BIN_ARCH[arch.abi]
        missing = [
            name for name in required if not (directory / name).is_file()
        ]
        if arch.abi == "arm64-v8a" and not (directory / "bootctl").is_file():
            missing.append("bootctl")
        if missing:
            raise BuildError(
                f"missing ksud asset(s) in {directory.relative_to(ROOT)}: "
                + ", ".join(missing)
            )


def ksud_assets_fingerprint() -> str:
    """Fingerprint the files rust-embed bakes into ksud (bin/*/*)."""
    hasher = hashlib.sha256()
    if KSUD_BIN_DIR.is_dir():
        for path in sorted(KSUD_BIN_DIR.rglob("*")):
            if not path.is_file():
                continue
            stat = path.stat()
            relative = path.relative_to(KSUD_BIN_DIR).as_posix()
            hasher.update(
                f"{relative}:{stat.st_size}:{stat.st_mtime_ns}\n".encode()
            )
    return hasher.hexdigest()


def check_embedded_lkms(args: argparse.Namespace, arches: Sequence[Arch]) -> None:
    """Refuse to bake a .ko built for a different manager package name."""
    for arch in arches:
        directory = KSUD_BIN_DIR / KSUD_BIN_ARCH[arch.abi]
        for ko in sorted(directory.glob("*_kernelsu.ko")):
            if args.package_name.encode() not in ko.read_bytes():
                raise BuildError(
                    f"{ko.relative_to(ROOT)} was built for a different package "
                    "name.\n  Rebuild it with --kernel, or remove the file."
                )


def build_ksud(
    args: argparse.Namespace,
    runner: Runner,
    ndk: Path,
    arches: Sequence[Arch],
) -> None:
    section("Building ksud")
    cargo = find_executable(
        "cargo", required=True, hint="Install Rust via https://rustup.rs"
    )

    if args.clean:
        runner.run([cargo, "clean", "-p", "ksud"])

    # build.rs does not emit rerun-if-env-changed for KSU_PACKAGE_NAME, and
    # rust-embed is not guaranteed to notice new files under bin/, so both the
    # package name and the embedded-asset fingerprint must invalidate the cache.
    stamp = STATE_DIR / "ksud-package.txt"
    assets_stamp = STATE_DIR / "ksud-assets.txt"
    previous: Optional[str] = None
    if stamp.is_file():
        previous = stamp.read_text(encoding="utf-8").strip() or None
    previous_assets: Optional[str] = None
    if assets_stamp.is_file():
        previous_assets = assets_stamp.read_text(encoding="utf-8").strip() or None
    assets = ksud_assets_fingerprint()

    reasons: List[str] = []
    if previous != args.package_name:
        reasons.append("package name changed (or unknown)")
    if previous_assets != assets:
        reasons.append("embedded assets changed")
    if reasons and not args.clean:
        info("; ".join(reasons) + "; cleaning cached ksud artifacts")
        runner.run([cargo, "clean", "-p", "ksud"])

    check_ksud_assets(arches, dry_run=runner.dry_run)
    check_embedded_lkms(args, arches)

    # Drop stale libs so the APK cannot pick up a binary built for another
    # package name (or another build type).
    for arch in ARCHES.values():
        stale = JNILIBS_DIR / arch.abi / "libksud.so"
        if stale.exists():
            info(f"removing stale {stale.relative_to(ROOT)}")
            if not runner.dry_run:
                stale.unlink()

    profile = args.ksud_build_type
    for arch in arches:
        if arch.abi == "riscv64":
            ensure_riscv_toolchain(runner)
        else:
            ensure_rust_target(
                arch.triple, auto_install=args.auto_install, runner=runner
            )

        info(f"building ksud for {arch.abi} ({arch.triple}), package={args.package_name}")
        env = ksud_build_env(ndk, arch, args.package_name)

        if arch.abi == "riscv64":
            cmd: List[object] = [
                cargo,
                f"+{RISCV_NIGHTLY}",
                "build",
                "--config",
                RISCV_CONFIG,
                "-Z",
                "build-std=std,panic_abort",
                "--target",
                arch.triple,
                f"--{profile}",
                "--manifest-path",
                KSUD_MANIFEST,
            ]
        else:
            cmd = [
                cargo,
                "build",
                "--target",
                arch.triple,
                f"--{profile}",
                "--manifest-path",
                KSUD_MANIFEST,
            ]
        runner.run(cmd, env=env)

        if runner.dry_run:
            continue

        source = ROOT / "target" / arch.triple / profile / "ksud"
        if not source.is_file():
            raise BuildError(f"ksud binary was not produced: {source}")

        destination = JNILIBS_DIR / arch.abi / "libksud.so"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)

        data = destination.read_bytes()
        if not data.startswith(b"\x7fELF"):
            raise BuildError(f"built ksud is not an ELF binary: {destination}")
        if args.package_name.encode() not in data:
            raise BuildError(
                f"ksud does not contain the expected package name "
                f"{args.package_name!r}. Try again with --clean."
            )
        info(
            f"installed {destination.relative_to(ROOT)} "
            f"({human_size(len(data))})"
        )

    if not runner.dry_run:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        stamp.write_text(args.package_name, encoding="utf-8")
        assets_stamp.write_text(assets, encoding="utf-8")


def check_existing_ksud(args: argparse.Namespace, arches: Sequence[Arch]) -> None:
    missing: List[str] = []
    for arch in arches:
        lib = JNILIBS_DIR / arch.abi / "libksud.so"
        if not lib.is_file():
            missing.append(arch.abi)
            continue
        if args.package_name.encode() not in lib.read_bytes():
            warn(
                f"{lib.relative_to(ROOT)} does not contain "
                f"{args.package_name!r}; skip-ksud may produce a mismatched app"
            )
        else:
            info(f"using existing {lib.relative_to(ROOT)}")
    if missing:
        raise BuildError(
            "no existing ksud binary for: " + ", ".join(missing) + "\n"
            "  Run without --skip-ksud, or build ksud first."
        )


# ---------------------------------------------------------------------------
# Kernel module (LKM) via DDK
# ---------------------------------------------------------------------------


def git_commit_count() -> Optional[int]:
    git = find_executable("git")
    if git is None:
        return None
    proc = subprocess.run(
        [str(git), "-C", str(ROOT), "rev-list", "--count", "HEAD"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    if proc.returncode != 0:
        return None
    try:
        return int(proc.stdout.strip())
    except ValueError:
        return None


def parse_kmis(values: Sequence[str]) -> List[str]:
    result: List[str] = []
    seen = set()
    for value in values:
        for part in value.split(","):
            kmi = part.strip()
            if not kmi:
                continue
            if kmi not in SUPPORTED_KMIS:
                raise BuildError(
                    f"unsupported KMI '{kmi}'.\n"
                    "  Supported: " + ", ".join(SUPPORTED_KMIS)
                )
            if kmi not in seen:
                seen.add(kmi)
                result.append(kmi)
    return result


def detect_kmi_via_adb() -> Optional[str]:
    adb = find_executable("adb")
    if adb is None:
        return None
    try:
        proc = subprocess.run(
            [str(adb), "shell", "uname -r"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0:
        return None

    release = proc.stdout.strip()
    android_match = re.search(r"android(\d+)", release)
    kernel_match = re.search(r"(\d+\.\d+)", release)
    if not android_match or not kernel_match:
        return None
    kmi = f"android{android_match.group(1)}-{kernel_match.group(1)}"
    if kmi not in SUPPORTED_KMIS:
        warn(f"detected KMI {kmi} from adb is not in the supported list")
        return None

    try:
        abi_proc = subprocess.run(
            [str(adb), "shell", "getprop", "ro.product.cpu.abi"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=15,
        )
        abi = abi_proc.stdout.strip()
        if abi and not abi.startswith("arm64"):
            warn(
                f"connected device reports ABI {abi!r}, but DDK builds an "
                "aarch64 kernel module"
            )
    except (OSError, subprocess.TimeoutExpired):
        pass
    return kmi


def resolve_kmis(args: argparse.Namespace) -> List[str]:
    kmis = parse_kmis(args.kmi)
    if kmis:
        return kmis
    detected = detect_kmi_via_adb()
    if detected:
        info(f"detected KMI from adb: {detected}")
        return [detected]
    raise BuildError(
        "no --kmi given and it could not be detected from a connected device.\n"
        "  Supported KMIs: " + ", ".join(SUPPORTED_KMIS) + "\n"
        "  Example: --kernel --kmi android15-6.6"
    )


def resolve_docker(args: argparse.Namespace) -> Path:
    if args.docker:
        direct = Path(args.docker).expanduser()
        if direct.is_file():
            return direct.resolve()
        found = find_executable(args.docker)
        if found is None:
            raise BuildError(f"container engine not found: {args.docker}")
        return found
    for name in ("docker", "podman"):
        found = find_executable(name)
        if found is not None:
            return found
    raise BuildError(
        "Docker (or Podman) is required to build the kernel module with DDK.\n"
        "  Install Docker, or pass --docker /path/to/docker."
    )


def ensure_ddk_image(
    args: argparse.Namespace, runner: Runner, docker: Path, kmi: str
) -> str:
    repositories = [args.ddk_image]
    if args.ddk_image == DEFAULT_DDK_IMAGE:
        repositories.extend(DDK_IMAGE_MIRRORS[1:])

    images = [
        f"{repository}:{kmi}-{args.ddk_release}" for repository in repositories
    ]
    for image in images:
        proc = runner.run(
            [docker, "image", "inspect", image], capture=True, check=False
        )
        if proc.returncode == 0:
            info(f"using DDK image: {image}")
            return image

    if args.no_pull:
        raise BuildError(
            "DDK image is not available locally: " + images[0] + "\n"
            "  Pull it manually or remove --no-pull."
        )

    for image in images:
        warn(f"pulling DDK image (this can be several GB): {image}")
        try:
            runner.run([docker, "pull", image])
            return image
        except BuildError:
            warn(f"failed to pull {image}")
    raise BuildError(
        "could not pull a DDK image for "
        f"{kmi} (release {args.ddk_release}).\n"
        "  Check the KMI/release combination or pass --ddk-image."
    )


def resolve_lkm_output_dir(args: argparse.Namespace) -> Path:
    if args.lkm_output_dir:
        output_dir = Path(args.lkm_output_dir).expanduser()
        if not output_dir.is_absolute():
            output_dir = (Path.cwd() / output_dir).resolve()
        return output_dir
    output_dir = Path(args.output_dir).expanduser()
    if not output_dir.is_absolute():
        output_dir = (Path.cwd() / output_dir).resolve()
    return output_dir / "lkm"


def kernel_cert_limit(size: int) -> int:
    """Smallest 256-byte-aligned limit >= size that fits the kernel stack."""
    limit = max(1024, ((size + 255) // 256) * 256)
    if limit > 1792:
        raise BuildError(
            f"manager certificate is {size} bytes; the kernel build can only "
            "raise its stack buffer to 1792 bytes.\n"
            "  Use a certificate no larger than 1792 bytes (RSA-2048 with a "
            "short subject is recommended)."
        )
    return limit


def build_kernel(
    args: argparse.Namespace,
    runner: Runner,
    kmis: Sequence[str],
    expected_size: Optional[str],
    expected_hash: Optional[str],
) -> List[Path]:
    section("Building kernel module (DDK)")

    docker = resolve_docker(args)
    runner.run([docker, "version"], capture=True)

    output_dir = resolve_lkm_output_dir(args)
    if not runner.dry_run:
        output_dir.mkdir(parents=True, exist_ok=True)

    version = git_commit_count()
    if version is None:
        warn("could not read git commit count; kernel module version will be 16")
    else:
        info(f"kernel module version: {30000 + version}")

    temp_root = STATE_DIR / "lkm-tmp"
    if not runner.dry_run:
        temp_root.mkdir(parents=True, exist_ok=True)

    results: List[Path] = []
    for kmi in kmis:
        image = ensure_ddk_image(args, runner, docker, kmi)
        destination = output_dir / f"{kmi}_kernelsu.ko"
        info(f"building {kmi} kernel module")

        make_args = [
            "CONFIG_KSU=m",
            "CC=clang",
            f"KSU_PACKAGE_NAME={shlex.quote(args.package_name)}",
        ]
        if expected_size and expected_hash:
            make_args.extend(
                [
                    f"KSU_EXPECTED_SIZE={shlex.quote(expected_size)}",
                    f"KSU_EXPECTED_HASH={shlex.quote(expected_hash)}",
                ]
            )
        if version is not None:
            make_args.extend(
                [f"KSU_GIT_VERSION={version}", "KSU_GIT_VERSION_VALID=1"]
            )
        script = (
            "cd kernel && make "
            + " ".join(make_args)
            + " && llvm-strip -d kernelsu.ko"
        )

        cmd_prefix: List[object] = [docker, "run", "--rm"]
        if hasattr(os, "getuid") and hasattr(os, "getgid"):
            # Keep build artifacts owned by the invoking user.
            cmd_prefix.extend(["--user", f"{os.getuid()}:{os.getgid()}"])

        cert_size = 0
        if expected_size:
            try:
                cert_size = int(expected_size, 16)
            except ValueError:
                cert_size = 0

        if runner.dry_run:
            if cert_size > 1024:
                info(
                    "would raise CERT_MAX_LENGTH to "
                    f"{kernel_cert_limit(cert_size)} for a "
                    f"{cert_size}-byte manager certificate"
                )
            runner.run(
                [
                    *cmd_prefix,
                    "-v",
                    "<temporary-kernel-tree>:/work",
                    "-w",
                    "/work",
                    image,
                    "bash",
                    "-lc",
                    script,
                ]
            )
            results.append(destination)
            continue

        temp_dir = Path(tempfile.mkdtemp(prefix=f"{kmi}-", dir=str(temp_root)))
        try:
            shutil.copytree(
                ROOT / "kernel",
                temp_dir / "kernel",
                ignore=LKM_COPY_IGNORE,
            )
            # Some checkouts have kernel/include/uapi as a symlink; the
            # repository-level copy is cheap and keeps both layouts working.
            shutil.copytree(
                ROOT / "uapi",
                temp_dir / "uapi",
                ignore=LKM_COPY_IGNORE,
            )

            if cert_size > 1024:
                limit = kernel_cert_limit(cert_size)
                source = temp_dir / "kernel" / "manager" / "apk_sign.c"
                code = source.read_text(encoding="utf-8")
                code, count = re.subn(
                    r"#define\s+CERT_MAX_LENGTH\s+\d+",
                    f"#define CERT_MAX_LENGTH {limit}",
                    code,
                )
                if count != 1:
                    raise BuildError(
                        f"could not patch CERT_MAX_LENGTH in {source}"
                    )
                source.write_text(code, encoding="utf-8")
                warn(
                    f"raised CERT_MAX_LENGTH to {limit} for a "
                    f"{cert_size}-byte manager certificate (temporary kernel tree)"
                )

            runner.run(
                [
                    *cmd_prefix,
                    "-v",
                    f"{temp_dir}:/work",
                    "-w",
                    "/work",
                    image,
                    "bash",
                    "-lc",
                    script,
                ]
            )

            built = temp_dir / "kernel" / "kernelsu.ko"
            if not built.is_file():
                raise BuildError(
                    f"DDK did not produce kernelsu.ko for {kmi} (looked in {built})"
                )
            data = built.read_bytes()
            if not data.startswith(b"\x7fELF"):
                raise BuildError(f"built kernel module is not an ELF file: {built}")
            machine = int.from_bytes(data[18:20], "little")
            if machine != 183:
                raise BuildError(
                    f"kernel module is not aarch64 (e_machine={machine})"
                )
            if args.package_name.encode() not in data:
                raise BuildError(
                    f"kernel module for {kmi} does not embed package name "
                    f"{args.package_name!r}"
                )

            shutil.copy2(built, destination)
            results.append(destination)
            info(
                f"installed {destination} "
                f"({human_size(destination.stat().st_size)})"
            )
        finally:
            shutil.rmtree(temp_dir, ignore_errors=True)

    return results


def install_lkms_into_ksud(
    args: argparse.Namespace, runner: Runner, lkm_paths: Sequence[Path]
) -> None:
    """Copy built modules into ksud's embedded assets (bin/aarch64)."""
    if not lkm_paths:
        return
    target = KSUD_BIN_DIR / "aarch64"
    if not runner.dry_run:
        target.mkdir(parents=True, exist_ok=True)
        # Never embed a .ko that belongs to a different package name.
        for existing in sorted(target.glob("*_kernelsu.ko")):
            if args.package_name.encode() not in existing.read_bytes():
                warn(f"removing stale {existing.relative_to(ROOT)}")
                existing.unlink()

    for path in lkm_paths:
        destination = target / path.name
        info(f"embedding {path.name} into ksud")
        if not runner.dry_run:
            shutil.copy2(path, destination)


# ---------------------------------------------------------------------------
# Signing
# ---------------------------------------------------------------------------


def signing_from_values(
    *,
    keystore: str,
    alias: str,
    store_pass: str,
    key_pass: str,
    base: Path,
    source: str,
) -> SigningConfig:
    path = Path(keystore).expanduser()
    if not path.is_absolute():
        path = (base / path).resolve()
    if not path.is_file():
        raise BuildError(f"keystore not found ({source}): {path}")
    return SigningConfig(
        keystore=path,
        alias=alias,
        store_pass=store_pass,
        key_pass=key_pass,
        source=source,
    )


def find_keytool() -> Optional[Path]:
    keytool = find_executable("keytool")
    if keytool is not None:
        return keytool
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        name = "keytool.exe" if os.name == "nt" else "keytool"
        candidate = Path(java_home) / "bin" / name
        if candidate.is_file():
            return candidate
    return None


def detect_keystore_type(path: Path) -> Optional[str]:
    try:
        magic = path.read_bytes()[:4]
    except OSError:
        return None
    if magic == b"\xfe\xed\xfe\xed":
        return "JKS"
    if magic == b"\xce\xce\xce\xce":
        return "JCEKS"
    if magic[:1] == b"\x30":
        return "PKCS12"
    return None


def debug_keystore_path() -> Optional[Path]:
    candidates: List[Path] = []
    user_home = os.environ.get("ANDROID_USER_HOME")
    if user_home:
        candidates.append(Path(user_home).expanduser() / "debug.keystore")
    sdk_home = os.environ.get("ANDROID_SDK_HOME")
    if sdk_home:
        candidates.append(
            Path(sdk_home).expanduser() / ".android" / "debug.keystore"
        )
    candidates.append(Path.home() / ".android" / "debug.keystore")
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    return None


def normalize_expected_size(value: str) -> str:
    text = value.strip()
    if text.lower().startswith("0x"):
        text = text[2:]
    if not re.fullmatch(r"[0-9a-fA-F]+", text):
        raise BuildError("--expected-size must be hexadecimal, e.g. 0x033b")
    number = int(text, 16)
    if number <= 0 or number > 4096:
        raise BuildError("--expected-size must be between 1 and 4096 bytes")
    return f"0x{number:04x}"


def normalize_expected_hash(value: str) -> str:
    text = value.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", text):
        raise BuildError(
            "--expected-hash must be a 64-character SHA-256 hex digest"
        )
    return text


def certificate_info(
    signing: SigningConfig, runner: Runner
) -> Tuple[str, str, int]:
    """Return (size_hex, sha256_hex, size_bytes) of the signing certificate."""
    if runner.dry_run and (
        not signing.keystore.is_file() or "******" in signing.store_pass
    ):
        info(f"would derive the expected signature from {signing.keystore}")
        return "0x????", "?" * 64, 0

    keytool = find_keytool()
    if keytool is None:
        raise BuildError(
            "keytool not found; install a JDK to read the manager certificate"
        )

    temp_root = STATE_DIR / "cert-tmp"
    temp_root.mkdir(parents=True, exist_ok=True)
    temp_dir = Path(tempfile.mkdtemp(prefix="cert-", dir=str(temp_root)))
    certificate = temp_dir / "manager.der"
    try:
        cmd: List[str] = [
            str(keytool),
            "-exportcert",
            "-keystore",
            str(signing.keystore),
            "-alias",
            signing.alias,
            "-storepass",
            signing.store_pass,
            "-file",
            str(certificate),
        ]
        store_type = detect_keystore_type(signing.keystore)
        if store_type:
            cmd[2:2] = ["-storetype", store_type]
        if runner.dry_run:
            # Read-only export, safe during --dry-run and gives real values.
            proc = subprocess.run(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            if proc.returncode != 0:
                details = ((proc.stdout or "") + (proc.stderr or "")).strip()
                raise BuildError(f"keytool failed to export the certificate:\n{details}")
        else:
            runner.run(cmd, capture=True)

        data = certificate.read_bytes()
        if not data:
            raise BuildError("keytool produced an empty certificate")
        size = len(data)
        if size > 4096:
            raise BuildError(
                f"manager certificate is {size} bytes; this is too large even "
                "for the raised kernel limit."
            )
        if size > 1024:
            warn(
                f"manager certificate is {size} bytes; the upstream kernel "
                "limit is 1024, so the LKM build will raise CERT_MAX_LENGTH."
            )
        return f"0x{size:04x}", hashlib.sha256(data).hexdigest(), size
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def resolve_signing(
    args: argparse.Namespace, runner: Runner
) -> Optional[SigningConfig]:
    if args.build_type == "debug":
        if not args.kernel:
            return None
        keystore = debug_keystore_path()
        if keystore is None:
            raise BuildError(
                "a debug build with --kernel needs ~/.android/debug.keystore.\n"
                "  Run a debug build once, or use -b release with a keystore."
            )
        info(f"using debug keystore for the kernel signature check: {keystore}")
        return SigningConfig(
            keystore, "androiddebugkey", "android", "android", "debug keystore"
        )
    if args.no_sign:
        return None

    cli_values = {
        "keystore": args.keystore or "",
        "alias": args.key_alias or "",
        "store_pass": args.keystore_pass or "",
        "key_pass": args.key_pass or "",
    }
    if any(cli_values.values()):
        missing = [key for key, value in cli_values.items() if not value]
        if missing:
            raise BuildError(
                "incomplete signing arguments; missing: " + ", ".join(missing)
            )
        return signing_from_values(
            **cli_values, base=Path.cwd(), source="command line"
        )

    env_values = {
        "keystore": os.environ.get("KEYSTORE_FILE", ""),
        "alias": os.environ.get("KEY_ALIAS", ""),
        "store_pass": os.environ.get("KEYSTORE_PASSWORD", ""),
        "key_pass": os.environ.get("KEY_PASSWORD", ""),
    }
    if all(env_values.values()):
        return signing_from_values(
            **env_values, base=Path.cwd(), source="environment"
        )
    if any(env_values.values()):
        warn(
            "ignoring incomplete signing environment variables "
            "(need KEYSTORE_FILE, KEY_ALIAS, KEYSTORE_PASSWORD, KEY_PASSWORD)"
        )

    gradle_props = parse_properties(MANAGER_DIR / "gradle.properties")
    prop_values = {
        "keystore": gradle_props.get("KEYSTORE_FILE", ""),
        "alias": gradle_props.get("KEY_ALIAS", ""),
        "store_pass": gradle_props.get("KEYSTORE_PASSWORD", ""),
        "key_pass": gradle_props.get("KEY_PASSWORD", ""),
    }
    if all(prop_values.values()):
        info("using signing config from manager/gradle.properties")
        return signing_from_values(
            **prop_values, base=MANAGER_DIR, source="manager/gradle.properties"
        )
    if any(prop_values.values()):
        warn("ignoring incomplete signing entries in manager/gradle.properties")

    global_path = Path.home() / ".gradle" / "gradle.properties"
    global_props = parse_properties(global_path)
    global_values = {
        "keystore": global_props.get("KEYSTORE_FILE", ""),
        "alias": global_props.get("KEY_ALIAS", ""),
        "store_pass": global_props.get("KEYSTORE_PASSWORD", ""),
        "key_pass": global_props.get("KEY_PASSWORD", ""),
    }
    if all(global_values.values()):
        info(f"using signing config from {global_path}")
        return signing_from_values(
            **global_values, base=global_path.parent, source=str(global_path)
        )
    if any(global_values.values()):
        warn(f"ignoring incomplete signing entries in {global_path}")

    return auto_signing(args, runner)


def auto_signing(
    args: argparse.Namespace, runner: Runner
) -> SigningConfig:
    keys_dir = (
        Path(args.keystore_dir).expanduser()
        if args.keystore_dir
        else DEFAULT_KEYSTORE_DIR
    )
    if not keys_dir.is_absolute():
        keys_dir = (Path.cwd() / keys_dir).resolve()
    keystore = keys_dir / "kernelsu-neo.jks"
    props_path = keys_dir / "signing.properties"
    alias = "kernelsu-neo"

    if args.regenerate_keystore:
        if not runner.dry_run:
            keystore.unlink(missing_ok=True)
            props_path.unlink(missing_ok=True)

    if keystore.is_file():
        if props_path.is_file():
            props = parse_properties(props_path)
            store_pass = props.get("KEYSTORE_PASSWORD", "")
            key_pass = props.get("KEY_PASSWORD", "")
            alias = props.get("KEY_ALIAS", alias)
            if store_pass and key_pass:
                info(f"reusing local signing key: {keystore}")
                return SigningConfig(
                    keystore, alias, store_pass, key_pass, "local key"
                )
        raise BuildError(
            f"a local keystore exists but its credentials are missing or "
            f"incomplete: {props_path}\n"
            "  Pass --regenerate-keystore to replace it, or supply -K/-A/-P/-S."
        )

    keytool = find_keytool()
    if keytool is None:
        if runner.dry_run:
            info("would generate a local keystore (keytool not found)")
            return SigningConfig(keystore, alias, "******", "******", "local key")
        raise BuildError(
            "no keystore configured and keytool was not found.\n"
            "  Install a JDK, or pass -K/-A/-P/-S explicitly, or use --no-sign."
        )

    if runner.dry_run:
        info(f"would generate a local signing key: {keystore}")
        return SigningConfig(keystore, alias, "******", "******", "local key")

    keys_dir.mkdir(parents=True, exist_ok=True)
    password = secrets.token_hex(16)
    runner.add_secret(password)
    runner.run(
        [
            keytool,
            "-genkeypair",
            "-keystore",
            keystore,
            "-alias",
            alias,
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-dname",
            "CN=KernelSU Neo Build, OU=Local, O=KernelSU Neo, C=CN",
            "-storetype",
            "PKCS12",
            "-storepass",
            password,
            "-keypass",
            password,
            "-noprompt",
        ],
        capture=True,
    )
    props_path.write_text(
        "# Auto-generated by build.py. Keep this file private.\n"
        f"KEYSTORE_FILE={keystore}\n"
        f"KEY_ALIAS={alias}\n"
        f"KEYSTORE_PASSWORD={password}\n"
        f"KEY_PASSWORD={password}\n",
        encoding="utf-8",
    )
    try:
        os.chmod(keystore, 0o600)
        os.chmod(props_path, 0o600)
    except OSError:
        pass
    warn(
        "no keystore supplied; generated a local signing key.\n"
        f"  Keystore: {keystore}\n"
        f"  Secrets : {props_path}\n"
        "  Reuse this key for future updates (uninstalling is required if it changes)."
    )
    return SigningConfig(keystore, alias, password, password, "local key")


# ---------------------------------------------------------------------------
# Gradle / manager
# ---------------------------------------------------------------------------


def build_app(
    args: argparse.Namespace,
    runner: Runner,
    signing: Optional[SigningConfig],
    sdk: Optional[Path],
    ndk: Optional[Path],
) -> float:
    section("Building manager APK")

    gradlew = MANAGER_DIR / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradlew.is_file():
        raise BuildError(f"Gradle wrapper not found: {gradlew}")
    if os.name != "nt" and not os.access(gradlew, os.X_OK):
        raise BuildError(f"Gradle wrapper is not executable: {gradlew}")

    tasks: List[str] = []
    if args.clean:
        tasks.append("clean")
    tasks.append("assembleRelease" if args.build_type == "release" else "assembleDebug")

    properties: List[str] = [
        f"-PKSU_PACKAGE_NAME={args.package_name}",
        f"-PKSU_NAME={args.app_name}",
    ]

    if signing is not None:
        runner.add_secret(signing.store_pass)
        runner.add_secret(signing.key_pass)
        properties.extend(
            [
                f"-PKEYSTORE_FILE={signing.keystore}",
                f"-PKEY_ALIAS={signing.alias}",
                f"-PKEYSTORE_PASSWORD={signing.store_pass}",
                f"-PKEY_PASSWORD={signing.key_pass}",
            ]
        )
    elif args.build_type == "release":
        warn(
            "building a release APK without signing (--no-sign); "
            "the output will not be installable until you sign it"
        )

    cmd: List[object] = [gradlew, *tasks, *properties, "--console=plain"]
    if args.jobs:
        cmd.append(f"--max-workers={args.jobs}")
    if args.stacktrace:
        cmd.append("--stacktrace")

    env: Dict[str, Optional[str]] = {"KSU_PACKAGE_NAME": args.package_name}
    if signing is not None:
        # Also expose the signing values as Gradle project properties via the
        # environment; some plugins only read ORG_GRADLE_PROJECT_* variables.
        env["ORG_GRADLE_PROJECT_KEYSTORE_FILE"] = str(signing.keystore)
        env["ORG_GRADLE_PROJECT_KEY_ALIAS"] = signing.alias
        env["ORG_GRADLE_PROJECT_KEYSTORE_PASSWORD"] = signing.store_pass
        env["ORG_GRADLE_PROJECT_KEY_PASSWORD"] = signing.key_pass
    if sdk is not None:
        env["ANDROID_HOME"] = str(sdk)
        env["ANDROID_SDK_ROOT"] = str(sdk)
    if ndk is not None:
        env["ANDROID_NDK_HOME"] = str(ndk)
        env["ANDROID_NDK_ROOT"] = str(ndk)

    started = time.time()
    runner.run(cmd, cwd=MANAGER_DIR, env=env)
    return started


def find_built_apk(build_type: str, started: float) -> Path:
    apk_dir = MANAGER_DIR / "app" / "build" / "outputs" / "apk" / build_type
    candidates = sorted(apk_dir.glob("*.apk"), key=lambda path: path.stat().st_mtime)
    if not candidates:
        raise BuildError(f"no APK produced under {apk_dir}")
    fresh = [
        path for path in candidates if path.stat().st_mtime >= started - 2.0
    ]
    if fresh:
        return fresh[-1]
    warn("falling back to the newest APK in the output directory")
    return candidates[-1]


def _apksigner_verify(
    apksigner: Path, apk: Path, runner: Runner
) -> Tuple[int, str]:
    proc = runner.run(
        [apksigner, "verify", "-v", "--print-certs", apk],
        capture=True,
        check=False,
    )
    output = (proc.stdout or "") + (proc.stderr or "")
    return proc.returncode, output


def _has_v2_signature(report: str) -> bool:
    return bool(
        re.search(
            r"Verified using v2 scheme[^\n]*:\s*true", report, re.IGNORECASE
        )
    )


def verify_apk_certificate(report: str, expected_hash: str) -> None:
    match = re.search(r"SHA-256 digest:\s*([0-9a-fA-F]{64})", report)
    if not match:
        raise BuildError(
            "could not read the APK signing certificate digest from apksigner"
        )
    actual = match.group(1).lower()
    if actual != expected_hash.lower():
        raise BuildError(
            "the APK was signed with a different certificate than the kernel "
            "expects.\n"
            f"  APK cert   : {actual}\n"
            f"  kernel cert: {expected_hash}\n"
            "  The kernel module would not recognize this manager."
        )
    info(f"APK certificate matches the kernel: {actual}")


def ensure_signed_apk(
    apk: Path,
    signing: SigningConfig,
    sdk: Optional[Path],
    runner: Runner,
) -> Optional[str]:
    apksigner = find_android_tool("apksigner", sdk)
    if apksigner is None:
        warn("apksigner not found; skipping APK signature verification")
        return None

    runner.add_secret(signing.store_pass)
    runner.add_secret(signing.key_pass)

    returncode, report = _apksigner_verify(apksigner, apk, runner)
    if returncode == 0 and _has_v2_signature(report):
        info("APK signature verified (v2)")
        return report

    info(
        "APK is unsigned, has an invalid signature, or lacks a v2 signature; "
        "signing with the selected key"
    )
    temp = apk.with_name(f"{apk.stem}.signed{apk.suffix}")
    try:
        runner.run(
            [
                apksigner,
                "sign",
                # The kernel reads the v2 block only. Disable v3/v4 so
                # apksigner cannot emit a v3-only signature.
                "--v1-signing-enabled",
                "false",
                "--v2-signing-enabled",
                "true",
                "--v3-signing-enabled",
                "false",
                "--v4-signing-enabled",
                "false",
                "--ks",
                signing.keystore,
                "--ks-key-alias",
                signing.alias,
                "--ks-pass",
                f"pass:{signing.store_pass}",
                "--key-pass",
                f"pass:{signing.key_pass}",
                "--out",
                temp,
                apk,
            ],
            capture=True,
        )
        temp.replace(apk)
    finally:
        if temp.exists():
            temp.unlink(missing_ok=True)

    returncode, report = _apksigner_verify(apksigner, apk, runner)
    if returncode != 0 or not _has_v2_signature(report):
        raise BuildError("APK is still not correctly v2-signed after signing")
    info("APK signed and verified (v2)")
    return report


def finalize_apk(
    args: argparse.Namespace,
    runner: Runner,
    apk_started: float,
    sdk: Optional[Path],
    signing: Optional[SigningConfig],
    expected_hash: Optional[str] = None,
) -> Optional[Path]:
    if runner.dry_run:
        info(f"would copy the newest {args.build_type} APK into {args.output_dir}")
        return None

    apk = find_built_apk(args.build_type, apk_started)

    output_dir = Path(args.output_dir).expanduser()
    if not output_dir.is_absolute():
        output_dir = (Path.cwd() / output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    if args.output_name:
        stem = args.output_name
        if stem.lower().endswith(".apk"):
            stem = stem[:-4]
    else:
        stem = f"{apk.stem}_{args.package_name}"

    destination = output_dir / f"{stem}.apk"
    if destination.resolve() != apk.resolve():
        shutil.copy2(apk, destination)

    if signing is not None:
        report = ensure_signed_apk(destination, signing, sdk, runner)
        if expected_hash:
            if report is None:
                raise BuildError(
                    "cannot verify the APK certificate because apksigner was "
                    "not found; refusing to produce a kernel/manager mismatch"
                )
            verify_apk_certificate(report, expected_hash)

    info(f"APK   : {destination} ({human_size(destination.stat().st_size)})")
    info(f"SHA256: {sha256_file(destination)}")
    return destination


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "One-click build for KernelSU Neo: builds ksud, optionally builds "
            "the kernel module (LKM) with DDK, injects ksud into the manager, "
            "and produces a signed APK with an optional custom package name."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "examples:\n"
            "  python3 scripts/build.py -p com.example.kernelsu -n \"My KernelSU\"\n"
            "  python3 scripts/build.py --kernel --kmi android15-6.6 -K release.jks "
            "-A alias -P storepass -S keypass\n"
            "  python3 scripts/build.py -a arm64-v8a -a x86_64 -b debug\n"
            "  python3 scripts/build.py --kernel --kmi android15-6.6\n"
            "  python3 scripts/build.py --kernel --skip-app --skip-ksud\n"
            "  python3 scripts/build.py -K key.jks -A alias -P storepass -S keypass\n"
            "  python3 scripts/build.py --dry-run\n"
        ),
    )

    general = parser.add_argument_group("build options")
    general.add_argument(
        "-p",
        "--package-name",
        default=DEFAULT_PACKAGE_NAME,
        help=f"Android applicationId (default: {DEFAULT_PACKAGE_NAME})",
    )
    general.add_argument(
        "-n",
        "--app-name",
        default=DEFAULT_APP_NAME,
        help=f"display name of the manager app (default: {DEFAULT_APP_NAME})",
    )
    general.add_argument(
        "-a",
        "--arch",
        action="append",
        default=[],
        help=(
            "target ABI, repeatable or comma-separated "
            "(arm64-v8a, x86_64, riscv64; default: arm64-v8a)"
        ),
    )
    general.add_argument(
        "-b",
        "--build-type",
        choices=("debug", "release"),
        default="release",
        help="manager build type (default: release)",
    )
    general.add_argument(
        "-t",
        "--ksud-build-type",
        choices=("debug", "release"),
        default="release",
        help="ksud build profile (default: release)",
    )
    general.add_argument(
        "-j", "--jobs", type=int, default=None, help="parallel jobs / Gradle workers"
    )
    general.add_argument(
        "--clean", action="store_true", help="clean ksud artifacts and Gradle output first"
    )
    general.add_argument(
        "--skip-ksud",
        action="store_true",
        help="reuse existing jniLibs/<abi>/libksud.so instead of building it",
    )
    general.add_argument(
        "--skip-app",
        action="store_true",
        help="skip the manager APK / Gradle build",
    )
    general.add_argument(
        "--output-dir",
        default=str(ROOT / "dist"),
        help="directory for the final APK (default: dist)",
    )
    general.add_argument(
        "--output-name", default=None, help="output APK file name (without .apk)"
    )
    general.add_argument(
        "--dry-run",
        action="store_true",
        help="print the commands that would run, without executing them",
    )
    general.add_argument(
        "--stacktrace", action="store_true", help="pass --stacktrace to Gradle"
    )
    general.add_argument(
        "--no-auto-install",
        dest="auto_install",
        action="store_false",
        help="do not run 'rustup target add' automatically",
    )
    parser.set_defaults(auto_install=True)

    signing = parser.add_argument_group("signing (release builds)")
    signing.add_argument("-K", "--keystore", default=None, help="keystore path")
    signing.add_argument("-A", "--key-alias", default=None, help="key alias")
    signing.add_argument("-P", "--keystore-pass", default=None, help="keystore password")
    signing.add_argument("-S", "--key-pass", default=None, help="private key password")
    signing.add_argument(
        "--keystore-dir",
        default=None,
        help=(
            "directory for an auto-generated local keystore "
            "(default: ~/.kernelsu-neo)"
        ),
    )
    signing.add_argument(
        "--regenerate-keystore",
        action="store_true",
        help="replace the auto-generated local keystore",
    )
    signing.add_argument(
        "--no-sign",
        action="store_true",
        help="build an unsigned release APK (debug builds are always signed by AGP)",
    )

    kernel = parser.add_argument_group("kernel module (DDK)")
    kernel.add_argument(
        "--kernel",
        action="store_true",
        help="also build the aarch64 kernel module (kernelsu.ko) with DDK",
    )
    kernel.add_argument(
        "-k",
        "--kmi",
        action="append",
        default=[],
        help=(
            "KMI to build, repeatable or comma-separated "
            "(default: auto-detect from adb; supported: "
            + ", ".join(SUPPORTED_KMIS)
            + ")"
        ),
    )
    kernel.add_argument(
        "--ddk-release",
        default=DEFAULT_DDK_RELEASE,
        help=f"DDK image release (default: {DEFAULT_DDK_RELEASE})",
    )
    kernel.add_argument(
        "--ddk-image",
        default=DEFAULT_DDK_IMAGE,
        help=f"DDK image repository (default: {DEFAULT_DDK_IMAGE})",
    )
    kernel.add_argument(
        "--docker",
        default=None,
        help="docker/podman executable to use (default: autodetect)",
    )
    kernel.add_argument(
        "--no-pull",
        action="store_true",
        help="do not pull missing DDK images",
    )
    kernel.add_argument(
        "--lkm-output-dir",
        default=None,
        help="directory for built .ko files (default: <output-dir>/lkm)",
    )
    kernel.add_argument(
        "--expected-size",
        default=None,
        help=(
            "manager certificate size the kernel accepts, e.g. 0x033b "
            "(default: derived from the signing keystore)"
        ),
    )
    kernel.add_argument(
        "--expected-hash",
        default=None,
        help=(
            "manager certificate SHA-256 the kernel accepts "
            "(default: derived from the signing keystore)"
        ),
    )

    environment = parser.add_argument_group("environment overrides")
    environment.add_argument("--sdk", default=None, help="Android SDK root")
    environment.add_argument("--ndk", default=None, help="Android NDK root")

    return parser


def print_summary(
    args: argparse.Namespace,
    arches: Sequence[Arch],
    apk: Optional[Path],
    kmis: Sequence[str],
    lkm_paths: Sequence[Path],
    expected_size: Optional[str],
    expected_hash: Optional[str],
    elapsed: float,
) -> None:
    section("Summary")
    print(f"  Package   : {args.package_name}")
    print(f"  App name  : {args.app_name}")
    print(f"  ABIs      : {', '.join(arch.abi for arch in arches)}")
    print(f"  Build type: {args.build_type}")
    if apk is not None:
        print(f"  APK       : {apk}")
    elif args.skip_app:
        print("  APK       : skipped (--skip-app)")
    else:
        print(f"  APK       : dry run (would be in {args.output_dir})")
    if args.kernel:
        print(f"  KMI(s)    : {', '.join(kmis)}")
        if lkm_paths:
            for path in lkm_paths:
                suffix = " (dry run)" if args.dry_run else ""
                print(f"  LKM       : {path}{suffix}")
        else:
            print("  LKM       : not built")
        if expected_size and expected_hash:
            print(f"  LKM cert  : size={expected_size} sha256={expected_hash}")
    else:
        print("  LKM       : skipped (add --kernel to build it)")
    print(f"  Elapsed   : {elapsed:.1f}s")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    runner = Runner(dry_run=args.dry_run)
    started = time.time()

    try:
        validate_config(args)
        arches = parse_arches(args.arch)
        kmis: List[str] = []
        if args.kernel:
            kmis = resolve_kmis(args)

        sdk = detect_sdk(args.sdk)
        if not args.skip_app:
            if sdk is None:
                raise BuildError(
                    "Android SDK not found.\n"
                    "  Set ANDROID_HOME / ANDROID_SDK_ROOT or create "
                    "manager/local.properties with sdk.dir=..."
                )
            java_major = java_major_version()
            if java_major is None:
                raise BuildError("java not found; install JDK 21 and put it on PATH")
            if java_major < 17:
                raise BuildError(
                    f"Java {java_major} is too old; Gradle 9 requires Java 17+ "
                    "(JDK 21 recommended)"
                )
            if java_major < 21:
                warn(f"Java {java_major} detected; the project targets Java 21")

        ndk: Optional[Path] = None
        if not args.skip_ksud:
            ndk = detect_ndk(args.ndk, sdk)
            info(f"using NDK: {ndk}")

        expected_size: Optional[str] = None
        expected_hash: Optional[str] = None
        if args.expected_size or args.expected_hash:
            if not (args.expected_size and args.expected_hash):
                raise BuildError(
                    "--expected-size and --expected-hash must be used together"
                )
            expected_size = normalize_expected_size(args.expected_size)
            expected_hash = normalize_expected_hash(args.expected_hash)
            info(
                f"using explicit manager certificate: size={expected_size}, "
                f"sha256={expected_hash}"
            )

        # The kernel module must know the manager certificate before it is
        # built, so resolve signing even for --skip-app kernel builds.
        need_signing = (not args.skip_app) or (
            args.kernel and expected_hash is None
        )
        signing: Optional[SigningConfig] = None
        if need_signing:
            signing = resolve_signing(args, runner)

        if args.kernel and expected_hash is None:
            if signing is None:
                raise BuildError(
                    "cannot determine the manager signing certificate for "
                    "--kernel.\n"
                    "  Pass -K/-A/-P/-S, or use "
                    "--expected-size/--expected-hash."
                )
            size_hex, hash_hex, size_bytes = certificate_info(signing, runner)
            expected_size, expected_hash = size_hex, hash_hex
            info(
                f"kernel will accept manager certificate: size={size_hex} "
                f"({size_bytes} bytes), sha256={hash_hex}"
            )
        elif (
            args.kernel
            and signing is not None
            and expected_hash is not None
            and not runner.dry_run
        ):
            actual_size, actual_hash, _ = certificate_info(signing, runner)
            if actual_size != expected_size or actual_hash != expected_hash:
                raise BuildError(
                    "the signing certificate does not match "
                    "--expected-size/--expected-hash"
                )
            info("signing certificate matches the explicit expected values")

        lkm_paths: List[Path] = []
        if args.kernel:
            lkm_paths = build_kernel(
                args, runner, kmis, expected_size, expected_hash
            )
            if args.skip_ksud:
                if not args.skip_app:
                    warn(
                        "--skip-ksud: the built kernel module will not be "
                        "embedded into the APK"
                    )
            else:
                install_lkms_into_ksud(args, runner, lkm_paths)

        if not args.skip_ksud:
            build_ksuinit(args, runner, ndk, arches)
            build_ksud(args, runner, ndk, arches)
        elif not args.skip_app:
            check_existing_ksud(args, arches)

        apk: Optional[Path] = None
        if not args.skip_app:
            apk_started = build_app(args, runner, signing, sdk, ndk)
            apk = finalize_apk(
                args, runner, apk_started, sdk, signing, expected_hash
            )

        print_summary(
            args,
            arches,
            apk,
            kmis,
            lkm_paths,
            expected_size,
            expected_hash,
            time.time() - started,
        )
        return 0
    except BuildError as exc:
        error(str(exc))
        return 1
    except KeyboardInterrupt:
        error("interrupted")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
