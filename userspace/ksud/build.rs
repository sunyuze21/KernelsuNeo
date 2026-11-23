use std::env;
use std::ffi::OsString;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;

/// AArch64 汇编源文件（LKM 镜像自举代码）相对 manifest 的路径。
const BOOTSTRAP_SOURCE: &str = "src/lkm_image_bootstrap.S";
/// 汇编产物在 `OUT_DIR` 中的文件名，随后由 `include_bytes!` 嵌入二进制。
const BOOTSTRAP_OBJECT: &str = "lkm_image_bootstrap.o";
/// 宿主机预置的自举目标文件；交叉构建时在进容器前生成，用来跳过本机汇编。
const PREPARED_BOOTSTRAP_OBJECT: &str = ".lkm_image_bootstrap.o";

/// 从 git 仓库推导版本号与版本名。
///
/// 版本号 = 30000 + `git rev-list --count HEAD`，版本名取 `git describe --tags --always`，
/// 并去掉前缀 `v`。git 不可用或输出不可解析时返回 `io::Error`，由 `main` 降级处理。
fn get_git_version() -> Result<(u32, String), std::io::Error> {
    let output = Command::new("git")
        .args(["rev-list", "--count", "HEAD"])
        .output()?;

    let output = output.stdout;
    let version_code = String::from_utf8(output).expect("Failed to read git count stdout");
    let version_code: u32 = version_code
        .trim()
        .parse()
        .map_err(|_| std::io::Error::other("Failed to parse git count"))?;
    let version_code = 30000 + version_code;

    let version_name = String::from_utf8(
        Command::new("git")
            .args(["describe", "--tags", "--always"])
            .output()?
            .stdout,
    )
    .map_err(|_| std::io::Error::other("Failed to read git describe stdout"))?;
    let version_name = version_name.trim_start_matches('v').to_string();
    Ok((version_code, version_name))
}

/// 用 bindgen 从 `src/ksu_uapi.h` 生成 UAPI 绑定，写入 `OUT_DIR/bindings.rs`。
///
/// 以 C++ 模式传入 include 路径，使头文件可用 C++ 写法；RISC-V 目标额外注入
/// `<sys/ucontext.h>`，因为 libc crate 尚未暴露 Android RISC-V 的信号上下文布局。
fn configure_bindgen() {
    // bindgen::Builder 是 bindgen 的主要入口，
    // 用于逐步构建生成绑定所需的选项。
    let mut builder = bindgen::Builder::default()
        // 需要为其生成绑定的输入头文件。
        .header("src/ksu_uapi.h")
        .clang_args(["-x", "c++", "-I../../"])
        // 当任何被包含的头文件发生变化时，通知 cargo 使已构建的 crate 失效。
        .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()));
    if env::var("CARGO_CFG_TARGET_ARCH").as_deref() == Ok("riscv64") {
        // libc 尚未暴露 Android 的 RISC-V 信号上下文，因此改为从目标 NDK
        // 生成，而不是假定为其他 libc 的布局。
        builder = builder.header_contents("ksu_signal_context.h", "#include <sys/ucontext.h>");
    }
    let bindings = builder
        // 完成 builder 并生成绑定。
        .generate()
        // 解包 Result，失败时 panic。
        .expect("Unable to generate bindings");

    // 将绑定写入 $OUT_DIR/bindings.rs 文件。
    let out_path = std::path::PathBuf::from(env::var("OUT_DIR").unwrap());
    // 调试时取消下面一行的注释
    // let out_path = std::path::PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    bindings
        .write_to_file(out_path.join("bindings.rs"))
        .expect("Couldn't write bindings!");
}

/// 校验汇编产物确实是小端 AArch64 的 ELF64 可重定位目标文件（ET_REL）。
///
/// `include_bytes!` 只接受二进制 blob，若把错误文本或别的架构产物嵌进去，要到
/// 运行期才会暴露；这里在构建期就拒绝。字段偏移取自 ELF 规范：魔数 4 字节、
/// EI_CLASS=2（64 位）、EI_DATA=1（小端）、e_type=1（ET_REL）、e_machine=183（AArch64）。
fn validate_bootstrap_object(path: &Path) -> io::Result<()> {
    let object = fs::read(path)?;
    let valid = object.len() >= 64
        && object.starts_with(b"\x7fELF")
        && object[4] == 2
        && object[5] == 1
        && u16::from_le_bytes([object[16], object[17]]) == 1
        && u16::from_le_bytes([object[18], object[19]]) == 183;
    if valid {
        Ok(())
    } else {
        Err(io::Error::other(
            "bootstrap object must be a little-endian AArch64 ELF64 ET_REL",
        ))
    }
}

/// 校验后把自举目标文件复制到 `OUT_DIR`。
///
/// 复制前先校验，避免来源文件被污染时静默产出坏 blob。
fn copy_bootstrap_object(source: &Path, output: &Path) -> io::Result<()> {
    validate_bootstrap_object(source)?;
    fs::copy(source, output)?;
    Ok(())
}

/// 在 Android NDK 中定位宿主机可用的 clang。
///
/// 依次读取 `ANDROID_NDK_HOME`、`ANDROID_NDK_ROOT`，在
/// `toolchains/llvm/prebuilt/<host>/bin` 下寻找 `clang`/`clang.exe`。
/// 找不到 NDK 或编译器时返回 `None`，由调用方回退到系统编译器。
fn ndk_clang() -> Option<PathBuf> {
    let ndk = env::var_os("ANDROID_NDK_HOME")
        .or_else(|| env::var_os("ANDROID_NDK_ROOT"))
        .map(PathBuf::from)?;
    let prebuilt = ndk.join("toolchains/llvm/prebuilt");
    let mut hosts = fs::read_dir(prebuilt)
        .ok()?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .collect::<Vec<_>>();
    hosts.sort();
    hosts.into_iter().find_map(|host| {
        ["clang", "clang.exe"]
            .into_iter()
            .map(|name| host.join("bin").join(name))
            .find(|path| path.is_file())
    })
}

/// 执行一次汇编命令并收集失败信息。
///
/// 成功返回 `Ok(())`；失败时把编译器名和 stderr（优先）或 stdout 拼成错误字符串，
/// 交由调用方汇总后决定是否 panic。
fn run_assembler(program: &Path, arguments: &[OsString]) -> Result<(), String> {
    let output = Command::new(program)
        .args(arguments)
        .output()
        .map_err(|error| format!("{}: {error}", program.display()))?;
    if output.status.success() {
        return Ok(());
    }
    let details = if output.stderr.is_empty() {
        &output.stdout
    } else {
        &output.stderr
    };
    Err(format!(
        "{}: {}",
        program.display(),
        String::from_utf8_lossy(details).trim()
    ))
}

/// 生成 LKM 自举目标文件，输出到 `OUT_DIR/lkm_image_bootstrap.o`。
///
/// 获取顺序（前者优先）：
/// 1. 环境变量 `KSU_LKM_BOOTSTRAP_OBJECT` 指定的现成目标文件；
/// 2. manifest 下预置的 `.lkm_image_bootstrap.o`（交叉构建入口在宿主机生成）；
/// 3. 用 `KSU_LKM_BOOTSTRAP_CC`、`aarch64-linux-gnu-gcc`、NDK clang、系统 clang 依次汇编；
/// 4. 最后尝试 `llvm-mc`。
///
/// 每一步都校验产物的 ELF 头，确保嵌入的是 AArch64 可重定位目标文件。所有编译器
/// 都失败时 panic，避免产出无法启动的二进制。同时向 cargo 声明输入/环境依赖，
/// 保证源文件或相关变量变化时重新运行本脚本。
fn assemble_bootstrap() {
    println!("cargo:rerun-if-changed={BOOTSTRAP_SOURCE}");
    println!("cargo:rerun-if-env-changed=KSU_LKM_BOOTSTRAP_OBJECT");
    println!("cargo:rerun-if-env-changed=KSU_LKM_BOOTSTRAP_CC");
    println!("cargo:rerun-if-env-changed=ANDROID_NDK_HOME");
    println!("cargo:rerun-if-env-changed=ANDROID_NDK_ROOT");

    let manifest = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let source = manifest.join(BOOTSTRAP_SOURCE);
    let output = PathBuf::from(env::var_os("OUT_DIR").unwrap()).join(BOOTSTRAP_OBJECT);

    if let Some(prebuilt) = env::var_os("KSU_LKM_BOOTSTRAP_OBJECT") {
        let prebuilt = PathBuf::from(prebuilt);
        copy_bootstrap_object(&prebuilt, &output).unwrap_or_else(|error| {
            panic!(
                "cannot use KSU_LKM_BOOTSTRAP_OBJECT {}: {error}",
                prebuilt.display()
            )
        });
        return;
    }

    // 交叉构建会在进入容器之前于宿主机上准备好此文件。
    let prepared = manifest.join(PREPARED_BOOTSTRAP_OBJECT);
    println!("cargo:rerun-if-changed={}", prepared.display());
    if prepared.is_file() {
        copy_bootstrap_object(&prepared, &output).unwrap_or_else(|error| {
            panic!(
                "cannot use prepared bootstrap object {}: {error}",
                prepared.display()
            )
        });
        return;
    }

    // 按可用性从高到低排列编译器候选：显式指定的优先，其次交叉 gcc、NDK clang、
    // 系统 clang；每个候选失败都会记录错误，最后统一报告。
    let mut errors = Vec::new();
    let mut drivers = Vec::<PathBuf>::new();
    if let Some(compiler) = env::var_os("KSU_LKM_BOOTSTRAP_CC") {
        drivers.push(PathBuf::from(compiler));
    }
    drivers.push(PathBuf::from("aarch64-linux-gnu-gcc"));
    if let Some(clang) = ndk_clang() {
        drivers.push(clang);
    }
    drivers.push(PathBuf::from("clang"));

    for driver in drivers {
        let mut arguments = Vec::<OsString>::new();
        if driver
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.contains("clang"))
        {
            // clang 需要显式目标三元组；gcc 由自身前缀已经是 AArch64 交叉编译器。
            arguments.push("--target=aarch64-linux-gnu".into());
        }
        arguments.extend([
            "-c".into(),
            "-nostdlib".into(),
            "-o".into(),
            output.as_os_str().to_owned(),
            source.as_os_str().to_owned(),
        ]);
        match run_assembler(&driver, &arguments) {
            Ok(()) => {
                validate_bootstrap_object(&output)
                    .expect("assembler produced an invalid bootstrap object");
                return;
            }
            Err(error) => errors.push(error),
        }
    }

    // 最后尝试 llvm-mc；它直接接收汇编源并产出目标文件，不经过 C 编译器驱动。
    let llvm_mc = PathBuf::from("llvm-mc");
    let llvm_arguments = [
        "-triple=aarch64-linux-gnu".into(),
        "-filetype=obj".into(),
        "-o".into(),
        output.as_os_str().to_owned(),
        source.as_os_str().to_owned(),
    ];
    match run_assembler(&llvm_mc, &llvm_arguments) {
        Ok(()) => {
            validate_bootstrap_object(&output)
                .expect("llvm-mc produced an invalid bootstrap object");
        }
        Err(error) => {
            errors.push(error);
            panic!(
                "cannot assemble the AArch64 LKM bootstrap; install an AArch64 GNU compiler, clang, or llvm-mc, or set KSU_LKM_BOOTSTRAP_OBJECT:\n{}",
                errors.join("\n")
            );
        }
    }
}

/// 构建脚本入口：生成自举目标文件、注入版本信息，并在 Android 目标上生成 UAPI 绑定。
///
/// 通过 `cargo:rustc-env` 把 `VERSION_CODE`/`VERSION_NAME`/`KSU_PACKAGE_NAME` 传给主 crate。
/// git 不可用时降级为 `0.0.0` 并打印 cargo 警告，保证离线构建仍可完成。
fn main() {
    assemble_bootstrap();

    // 版本号来自 git 提交计数；无 git 环境时退回 0，避免构建直接失败
    let (code, name) = match get_git_version() {
        Ok((code, name)) => (code, name),
        Err(_) => {
            // 未安装 git 时输出警告
            println!("cargo:warning=Failed to get git version, using 0.0.0");
            (0, "0.0.0".to_string())
        }
    };
    if env::var("KSU_PACKAGE_NAME").is_err() {
        // 允许构建系统覆盖 Manager 包名；默认使用官方包名。
        println!("cargo:rustc-env=KSU_PACKAGE_NAME=me.weishu.kernelsu");
    }
    println!("cargo:rustc-env=VERSION_CODE={code}");
    println!("cargo:rustc-env=VERSION_NAME={name}");

    let target_os = env::var("CARGO_CFG_TARGET_OS").expect("CARGO_CFG_TARGET_OS not set");
    // 只有 Android 目标需要 bindgen；宿主机构建（如工具/测试）跳过，避免依赖 clang。
    if target_os == "android" {
        configure_bindgen();
    }
}
