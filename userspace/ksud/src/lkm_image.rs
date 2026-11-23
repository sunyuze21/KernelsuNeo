// SPDX-License-Identifier: GPL-2.0-only

//! `boot-patch-v2`：把一个预编译好的 `kernelsu.ko` 注入 boot 镜像中未压缩的
//! ARM64 内核 `Image`。
//!
//! 这是与 `boot_patch` 中基于 ramdisk 的流程并列的第二套启动修补方案。原来的
//! `boot_patch` 会解包 ramdisk、加入一小段 userspace/init 钩子，再由 Android 的
//! `init` 尽早加载 KernelSU。`boot-patch-v2` 完全不碰用户态：它把 KernelSU 当作
//! 可加载内核模块，让内核自己在启动早期加载。对于 ramdisk 难以修改（AVB、
//! vendor_boot、GKI ramdisk 分片）以及模块必须在 `init` 之前运行的设备很有用。
//!
//! `patch_boot` 与 `inject_image` 的完整流程：
//!
//! 1. 用 `android_bootimg` crate 解析 boot 镜像（boot header v3/v4 与 vendor_boot
//!    布局由该 crate 处理），并解压内核块。结果必须是未压缩的原始 ARM64 `Image`
//!    （magic `ARM\x64` 位于 0x38，大小位于 0x10）；`zImage` 或压缩的 `Image.*`
//!    会被拒绝。
//! 2. 扫描镜像内嵌的 kallsyms 数据恢复符号表（兼容 6.4 之前和 6.4+ 两种布局），
//!    如果存在 vmlinux BTF，则用 `__start_BTF`/`__stop_BTF` 符号确认是哪一块 BTF。
//!    此流程依赖 CONFIG_KALLSYMS_ALL，否则会直接报错退出。
//! 3. 通过解码 AArch64 分支指令定位三处内核代码补丁点：`kernel_init` 中的
//!    `async_synchronize_full()` 调用、`load_module` 中的 `strndup_user()` 调用，
//!    以及 `arm64_memblock_init` 中的 `memblock_reserve()` 调用。每一处都必须
//!    证明唯一，否则宁可停止也不猜测。
//! 4. 用 kallsyms 解析模块的所有未定义符号并生成 fixup 表；随后在内核 `Image`
//!    之后追加一个自描述的 capsule，保存模块字节、fixup 表、模块参数和 SHA-256。
//! 5. 把一段小的 bootstrap 对象（编译期生成的 AArch64 ET_REL blob）重定位到
//!    `.text` 尾部经过验证的零填充区，再把三处调用点重定向过去。启动时
//!    memblock 包装函数先把保留区扩展到 capsule，bootstrap 再校验 capsule、
//!    应用模块 fixup 并调用 `load_module()`。
//!
//! 支持的镜像布局：只要能由 `android_bootimg` crate 解析并重新打包，且内核块是
//! 原始 ARM64 `Image` 的 boot 镜像均可。这里刻意不修改 ramdisk 压缩格式、cpio
//! 归档、bootconfig/cmdline 和 AVB footer：只替换内核，其余容器原样重新打包。
//! 需要重新签名 AVB 或关闭校验的调用方必须另行处理。
//!
//! 全流程维持的不变量：capsule 位于原始 `image_size` 之后，bootstrap 只写入
//! `.text` 内已证明全零的填充区；新的镜像大小按 4096 字节对齐；capsule 可通过
//! 内核已有的线性映射和 PAGE_OFFSET 访问。

#![allow(
    clippy::many_single_char_names,
    clippy::similar_names,
    clippy::struct_field_names
)]

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::fs;
use std::io::{Cursor, Write};
use std::path::{Path, PathBuf};

use android_bootimg::parser::BootImage;
use android_bootimg::patcher::BootImagePatchOption;
use anyhow::{Context, Result, anyhow, bail, ensure};

use crate::lkm_image_btf::{KernelBtf, find_btf_candidates};
use crate::{assets, boot_patch};

// 镜像、capsule、ELF 与 kallsyms 的线上格式常量。

// `struct arm64_image_header` 中 `image_size` 位于 0x10，小端 magic
// "ARM\x64" 位于 0x38；这些偏移由 arm64 启动协议固定。
/// ARM64 启动协议中镜像头 magic 字段的固定文件偏移。
const ARM64_IMAGE_MAGIC_OFFSET: usize = 0x38;
/// 未压缩 ARM64 Image 的 magic 字节；用于拒绝 zImage 与压缩载荷。
const ARM64_IMAGE_MAGIC: &[u8; 4] = b"ARM\x64";
/// ARM64 镜像头中 image_size（可加载字节数）的固定偏移。
const ARM64_IMAGE_SIZE_OFFSET: usize = 0x10;
// capsule 头 magic 为 "KSULKM1"，末尾补 NUL，使该值作为 u64 传给
// bootstrap 的编译期定义时保持稳定。
/// capsule 头 magic；按小端解释为 u64 后作为 bootstrap 的编译期常量。
const CAPSULE_MAGIC: &[u8; 8] = b"KSULKM1\0";
/// capsule 线上格式版本；布局变更时必须递增，否则 bootstrap 会拒绝旧/新格式。
const CAPSULE_VERSION: u32 = 2;
// capsule 按页对齐，内核可以用映射 Image 的同一页粒度映射它；
// 最终镜像大小也按此对齐。
/// capsule 与最终镜像的对齐粒度，保证内核可按页映射整段 capsule。
const CAPSULE_ALIGNMENT: usize = 4096;
/// capsule 固定头长度；模块字节与 fixup 表都相对 capsule 起点布局。
const CAPSULE_HEADER_SIZE: usize = 112;
/// 模块参数字符串长度上限，不含 NUL 终止符
const MAX_MODULE_PARAMS_SIZE: usize = 4096;
// 置位时，capsule 的 fixup 表携带绝对内核地址（SHN_ABS 风格）；
// bootstrap 只需改写每个符号的 st_value。
/// 置位表示 fixup 表携带 SHN_ABS 风格绝对地址，而非内核文件偏移。
const CAPSULE_FLAG_SHN_ABS_FIXUPS: u64 = 1;
// 接受的 ELF machine/type 编码；这里全部面向 AArch64。
/// ELF machine 值，表示 AArch64。
const EM_AARCH64: u16 = 183;
/// ELF 类型：可重定位目标文件（内核模块即此类型）。
const ET_REL: u16 = 1;
/// ELF 节类型：带文件内容的程序数据。
const SHT_PROGBITS: u32 = 1;
/// ELF 节类型：符号表。
const SHT_SYMTAB: u32 = 2;
/// ELF 节类型：字符串表。
const SHT_STRTAB: u32 = 3;
/// ELF 节类型：带显式 addend 的重定位表。
const SHT_RELA: u32 = 4;
/// ELF 节类型：仅占地址空间、文件内无内容（如 .bss）。
const SHT_NOBITS: u32 = 8;
/// ELF 节类型：不带 addend 的重定位表；本实现明确拒绝。
const SHT_REL: u32 = 9;
/// 节标志：运行期可写。
const SHF_WRITE: u64 = 1;
/// 节标志：运行期需要装载到内存。
const SHF_ALLOC: u64 = 2;
/// 节标志：包含可执行指令。
const SHF_EXECINSTR: u64 = 4;
/// 符号节索引：未定义，必须由内核导出符号来解析。
const SHN_UNDEF: u16 = 0;
/// 符号节索引：绝对值，不再参与重定位。
const SHN_ABS: u16 = 0xfff1;
/// AArch64 重定位：写入 64 位绝对地址。
const R_AARCH64_ABS64: u32 = 257;
/// AArch64 重定位：写入 32 位绝对地址。
const R_AARCH64_ABS32: u32 = 258;
/// AArch64 重定位：26 位 PC 相对无条件跳转（B）。
const R_AARCH64_JUMP26: u32 = 282;
/// AArch64 重定位：26 位 PC 相对带链接跳转（BL）。
const R_AARCH64_CALL26: u32 = 283;
/// AArch64 重定位：ADRP 的 21 位页相对高位。
const R_AARCH64_ADR_PREL_PG_HI21: u32 = 275;
/// AArch64 重定位：ADD 立即数的低 12 位。
const R_AARCH64_ADD_ABS_LO12_NC: u32 = 277;
/// ELF64 节头固定大小；链接器按此步进解析节表。
const ELF64_SECTION_SIZE: usize = 64;
/// ELF64 符号表项固定大小。
const ELF64_SYMBOL_SIZE: usize = 24;
/// ELF64 RELA 重定位项固定大小。
const ELF64_RELA_SIZE: usize = 24;
// kallsyms 表按 8 字节对齐构建；token 表固定 256 项，每项可用一个
// 压缩字节索引。
/// kallsyms 各表之间的对齐要求；恢复逻辑依赖它按对齐边界定位表。
const KALLSYMS_ALIGNMENT: usize = 8;
/// kallsyms token 表项数固定为 256，因为索引是单字节。
const KALLSYMS_TOKEN_COUNT: usize = 256;
/// token 索引表总字节数：每项存放一个 u16 表内偏移。
const KALLSYMS_TOKEN_INDEX_SIZE: usize = KALLSYMS_TOKEN_COUNT * 2;
// 单个 kallsyms token 不会合法地超过该长度；更长的可打印字节串
// 说明候选表是误报。
/// 单个 token 可接受的最大长度；更长说明候选表是误报。
const KALLSYMS_MAX_TOKEN_LENGTH: usize = 512;
// token 表附近的搜索范围有上限，避免恶意或损坏的 Image 把恢复过程
// 变成二次方扫描。
/// 在 token 表之前搜索 markers 的最大窗口，避免二次方扫描。
const KALLSYMS_MARKER_SEARCH_WINDOW: usize = 4 * 1024 * 1024;
/// 从估算的 names 起点向前搜索 num_syms 的最大距离。
const KALLSYMS_NAME_TAIL_SEARCH: usize = 0x40000;
/// markers 最少条数，用于排除偶然单调递增的数据。
const KALLSYMS_MIN_MARKERS: usize = 8;
/// markers 最多读取条数，防止异常镜像导致无界增长。
const KALLSYMS_MAX_MARKERS: usize = 4096;
// code cave 必须按指令对齐；优先 4096 对齐，使 cave 从新页开始，
// 也更不容易与活跃代码共享缓存行。
/// code cave 起点的最小对齐（与 AArch64 指令宽度一致）。
const TEXT_CAVE_ALIGNMENT: usize = 16;
/// code cave 首选页对齐，便于隔离且减少与活跃代码共享缓存行。
const TEXT_CAVE_PREFERRED_ALIGNMENT: usize = 4096;
// `struct load_info` 会随 GKI 版本增长；这些值限定 bootstrap 在栈上
// 分配存储时的范围（类似 alloca 的用法）。
/// bootstrap 在栈上为 load_info 预留的最小字节数。
const MINIMUM_LOAD_INFO_STORAGE_SIZE: u64 = 256;
/// bootstrap 允许预留的最大字节数，防止异常 BTF 导致栈溢出。
const MAXIMUM_LOAD_INFO_STORAGE_SIZE: u64 = 4096;
// 预编译的 AArch64 ET_REL bootstrap，由构建脚本生成；打补丁时由下面的
// 极简链接器重定位。
/// 构建期生成的 AArch64 ET_REL bootstrap；补丁阶段重定位后写入 code cave。
const BOOTSTRAP_OBJECT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/lkm_image_bootstrap.o"));

// CLI 及顶层结果类型。

/// `boot-patch-v2` 子命令的参数。
///
/// boot 镜像是必填项；`--module` 可选，因为 ksud 随包提供按 KMI 构建的
/// `kernelsu.ko`，可以根据 boot 镜像内核里的 release 字符串自动选择。
#[derive(clap::Args, Debug)]
pub struct BootPatchV2Args {
    /// 源 boot 镜像
    #[arg(short, long)]
    pub boot: PathBuf,

    /// 指定确切的 kernelsu.ko；省略时自动选择内嵌的 KMI 构建
    #[arg(short, long)]
    pub module: Option<PathBuf>,

    /// 打补丁后的 boot 镜像输出路径
    #[arg(short, long)]
    pub output: PathBuf,

    /// 覆盖已存在的输出文件
    #[arg(long, default_value = "false")]
    pub force: bool,

    /// 透传的 KernelSU 配置参数（模块参数等）。
    #[command(flatten)]
    pub config: boot_patch::KsuConfigArgs,
}

/// 一条恢复出来的 kallsyms 记录：链接期虚拟地址和名称。
#[derive(Clone, Debug)]
struct MapSymbol {
    /// 链接期内核虚拟地址；地址为零的占位项会在建索引时被丢弃。
    address: u64,
    /// kallsyms 中的原始符号名，可能带 `.cold` 等编译器后缀。
    name: String,
}

/// 按地址排序的 kallsyms 索引。
///
/// 查找以名称为准，并做两级归一化：优先精确名称匹配，其次才使用去掉
/// 编译器后缀的形式。所有使用方都要求地址唯一，因为给歧义符号打补丁会
/// 静默破坏无关的内核代码。
#[derive(Debug)]
struct SymbolMap {
    /// 按 (地址, 名称) 排序后的全部符号；地址查找与范围界定都基于它。
    entries: Vec<MapSymbol>,
    /// 精确名称到 entries 下标的索引，同一名称可能对应多个地址。
    by_name: HashMap<String, Vec<usize>>,
    /// 去掉编译器装饰后的名称索引，用于匹配克隆或别名。
    by_normalized_name: HashMap<String, Vec<usize>>,
}

impl SymbolMap {
    /// 构建索引：丢弃占位的零地址，并要求至少有一个真实符号，保证后续
    /// 地址运算有意义。
    fn new(mut entries: Vec<MapSymbol>) -> Result<Self> {
        entries.retain(|entry| entry.address != 0);
        ensure!(
            !entries.is_empty(),
            "symbol map has no non-zero kernel addresses"
        );
        entries.sort_by(|left, right| {
            (left.address, left.name.as_str()).cmp(&(right.address, right.name.as_str()))
        });
        let mut by_name: HashMap<String, Vec<usize>> = HashMap::new();
        let mut by_normalized_name: HashMap<String, Vec<usize>> = HashMap::new();
        for (index, entry) in entries.iter().enumerate() {
            by_name.entry(entry.name.clone()).or_default().push(index);
            by_normalized_name
                .entry(normalize_symbol(&entry.name).to_owned())
                .or_default()
                .push(index);
        }
        Ok(Self {
            entries,
            by_name,
            by_normalized_name,
        })
    }

    /// 返回归一化名称等于 `requested_name` 的所有不同地址。
    ///
    /// 编译器为同一符号生成克隆（`.cold`、`___cfi_*`）时出现多个地址是
    /// 正常的；需要单一目标的调用方必须把多于一个结果视为错误。
    fn variants(&self, requested_name: &str) -> Vec<MapSymbol> {
        let mut unique = BTreeMap::<u64, MapSymbol>::new();
        if let Some(indices) = self
            .by_normalized_name
            .get(normalize_symbol(requested_name))
        {
            for &index in indices {
                let candidate = &self.entries[index];
                unique
                    .entry(candidate.address)
                    .or_insert_with(|| candidate.clone());
            }
        }
        unique.into_values().collect()
    }

    /// 把符号解析为唯一地址，否则带诊断信息失败。
    ///
    /// 即使存在归一化变体，精确名称匹配也优先；否则归一化后的集合必须
    /// 收敛到唯一地址。
    fn resolve(&self, requested_name: &str) -> Result<MapSymbol> {
        if let Some(indices) = self.by_name.get(requested_name) {
            let mut exact = BTreeMap::<u64, MapSymbol>::new();
            for &index in indices {
                let candidate = &self.entries[index];
                exact
                    .entry(candidate.address)
                    .or_insert_with(|| candidate.clone());
            }
            if exact.len() == 1 {
                return Ok(exact.into_values().next().expect("one exact symbol"));
            }
        }
        let candidates = self.variants(requested_name);
        if candidates.len() == 1 {
            return Ok(candidates[0].clone());
        }
        let rendered = candidates
            .iter()
            .take(8)
            .map(|candidate| format!("{}@0x{:x}", candidate.name, candidate.address))
            .collect::<Vec<_>>()
            .join(", ");
        if rendered.is_empty() {
            bail!("symbol {requested_name:?} was not found")
        }
        bail!("symbol {requested_name:?} is not unique: {rendered}")
    }

    /// 模块符号解析的宽松版本：歧义或缺失时返回 `None`，由调用方记入
    /// unresolved 并留给内核模块加载器自行解析，而不是让整个注入失败。
    fn resolve_module_symbol(&self, requested_name: &str) -> Option<MapSymbol> {
        self.resolve(requested_name).ok()
    }

    /// 返回严格大于 `address` 的下一个符号地址，用于界定函数扫描范围。
    fn next_address(&self, address: u64) -> Option<u64> {
        self.entries
            .iter()
            .find(|entry| entry.address > address)
            .map(|entry| entry.address)
    }
}

/// 注入流程必需的符号。
///
/// 每个符号的用途：
/// - `_text`/`_end` 建立文件偏移到地址的映射，并校验 image_size。
/// - `_stext`/`_etext` 界定搜索 code cave 的可执行区域。
/// - `linux_banner` 提供内核 release，用于选择对应 KMI 的模块。
/// - 三个调用点（`kernel_init`、`load_module`、`arm64_memblock_init` 及其
///   被调用者）是启动 bootstrap 和解析镜像保留区的钩子。
/// - `memstart_addr`/`kimage_voffset` 用于在 bootstrap 内转换物理地址和
///   虚拟地址。
/// - `vmalloc`、`memcpy`、`kstrdup` 供 bootstrap 辅助函数使用。
#[derive(Debug)]
struct RequiredSymbols {
    /// `_text`：镜像文件偏移与虚拟地址换算的基准。
    image_base: MapSymbol,
    /// `_end`：镜像结束地址，用于交叉校验头部 image_size。
    image_end: MapSymbol,
    /// `_stext`：永久可执行区起点。
    text_start: MapSymbol,
    /// `_etext`：code cave 搜索范围的终点。
    text_end: MapSymbol,
    /// 内核 banner 字符串，用于提取 release 并选择对应 KMI。
    linux_banner: MapSymbol,
    /// `arm64_memblock_init`：memblock 保留区补丁的宿主函数。
    arm64_memblock_init: MapSymbol,
    /// `memblock_reserve`：被包装的保留内存函数。
    memblock_reserve: MapSymbol,
    /// `memstart_addr`：物理地址到虚拟地址转换所需的全局变量。
    memstart_addr: MapSymbol,
    /// `kimage_voffset`：内核镜像虚拟偏移全局变量。
    kimage_voffset: MapSymbol,
    /// `kernel_init`：async 调用补丁的宿主函数。
    kernel_init: MapSymbol,
    /// `async_synchronize_full`：被重定向的启动后期同步调用。
    async_synchronize_full: MapSymbol,
    /// `load_module`：模块加载入口，bootstrap 最终调用它。
    load_module: MapSymbol,
    /// `strndup_user`：模块参数复制调用，被适配器替换。
    strndup_user: MapSymbol,
    /// `vmalloc`：bootstrap 分配模块缓冲区的内核函数。
    vmalloc: MapSymbol,
    /// `memcpy`：bootstrap 复制模块字节所用的内核函数。
    memcpy: MapSymbol,
    /// `kstrdup`：bootstrap 复制字符串所用的内核函数。
    kstrdup: MapSymbol,
}

impl RequiredSymbols {
    /// 解析全部必需符号；`vmalloc_noprof` 可作为 `vmalloc` 的新拼写，
    /// 以兼容 fault injection 重构后加上的 `_noprof` 后缀。
    fn resolve(symbols: &SymbolMap) -> Result<Self> {
        let vmalloc = symbols.resolve("vmalloc").or_else(|primary_error| {
            symbols
                .resolve("vmalloc_noprof")
                .with_context(|| primary_error.to_string())
        })?;
        Ok(Self {
            image_base: symbols.resolve("_text")?,
            image_end: symbols.resolve("_end")?,
            text_start: symbols.resolve("_stext")?,
            text_end: symbols.resolve("_etext")?,
            linux_banner: symbols.resolve("linux_banner")?,
            arm64_memblock_init: symbols.resolve("arm64_memblock_init")?,
            memblock_reserve: symbols.resolve("memblock_reserve")?,
            memstart_addr: symbols.resolve("memstart_addr")?,
            kimage_voffset: symbols.resolve("kimage_voffset")?,
            kernel_init: symbols.resolve("kernel_init")?,
            async_synchronize_full: symbols.resolve("async_synchronize_full")?,
            load_module: symbols.resolve("load_module")?,
            strndup_user: symbols.resolve("strndup_user")?,
            vmalloc,
            memcpy: symbols.resolve("memcpy")?,
            kstrdup: symbols.resolve("kstrdup")?,
        })
    }

    /// 返回 `_text` 地址，作为文件偏移与虚拟地址换算的基准。
    const fn image_base(&self) -> u64 {
        self.image_base.address
    }

    /// 用头部 `image_size` 交叉校验 `_end - _text`，并确认每个必需符号都
    /// 落在文件镜像内。
    ///
    /// 这是防止解析到截断或重定位镜像的主要防线：两个独立大小来源一旦
    /// 不一致，后续结果都不可信。
    fn validate_image_bounds(&self, image_size: usize) -> Result<()> {
        let image_base = self.image_base();
        let image_end_offset = self
            .image_end
            .address
            .checked_sub(image_base)
            .context("_end is below _text")?;
        ensure!(
            image_end_offset == image_size as u64,
            "image_size=0x{image_size:x} does not match {}-{}=0x{image_end_offset:x}",
            self.image_end.name,
            self.image_base.name
        );

        for (key, symbol, allow_end) in self.entries() {
            let offset = symbol.address.checked_sub(image_base).ok_or_else(|| {
                anyhow!(
                    "required symbol {key}={}@0x{:x} is below the Image base",
                    symbol.name,
                    symbol.address
                )
            })?;
            let maximum_offset = if allow_end {
                image_size as u64
            } else {
                image_size.saturating_sub(1) as u64
            };
            ensure!(
                offset <= maximum_offset,
                "required symbol {key}={}@0x{:x} is outside image_size",
                symbol.name,
                symbol.address
            );
        }
        Ok(())
    }

    /// 列出全部必需符号及其诊断名；bool 表示是否允许落在镜像末尾。
    const fn entries(&self) -> [(&'static str, &MapSymbol, bool); 16] {
        [
            ("image_base", &self.image_base, false),
            ("image_end", &self.image_end, true),
            ("text_start", &self.text_start, false),
            ("text_end", &self.text_end, false),
            ("linux_banner", &self.linux_banner, false),
            ("arm64_memblock_init", &self.arm64_memblock_init, false),
            ("memblock_reserve", &self.memblock_reserve, false),
            ("memstart_addr", &self.memstart_addr, false),
            ("kimage_voffset", &self.kimage_voffset, false),
            ("kernel_init", &self.kernel_init, false),
            (
                "async_synchronize_full",
                &self.async_synchronize_full,
                false,
            ),
            ("load_module", &self.load_module, false),
            ("strndup_user", &self.strndup_user, false),
            ("vmalloc", &self.vmalloc, false),
            ("memcpy", &self.memcpy, false),
            ("kstrdup", &self.kstrdup, false),
        ]
    }
}

/// 一条被改写的 `BL` 指令：文件偏移、虚拟地址（重编码分支需要）以及它
/// 原本调用的地址。
#[derive(Clone, Copy, Debug)]
struct CallSite {
    /// 该 BL 在 Image 文件中的字节偏移，写回补丁时按此定位。
    file_offset: usize,
    /// 指令自身的虚拟地址，重编码分支立即数时需要。
    address: u64,
    /// 原 BL 指向的地址，用于确认补丁目标与 bootstrap 定义一致。
    target: u64,
}

/// `inject_image` 改写的三处代码位置，外加 bootstrap 转换 capsule 所需的
/// PAGE_OFFSET。
#[derive(Clone, Copy, Debug)]
struct PatchSites {
    /// `kernel_init` 中 async_synchronize_full 调用点。
    async_call: CallSite,
    /// `load_module` 中 strndup_user 调用点。
    strndup_call: CallSite,
    /// `arm64_memblock_init` 中为内核镜像保留内存的调用点。
    memblock_reserve_call: CallSite,
    /// 从代码推断出的 PAGE_OFFSET，bootstrap 用它换算 capsule 虚拟地址。
    page_offset: u64,
}

/// 一个已解析到内核文件偏移的模块未定义符号。
///
/// `symbol_file_offset` 指向模块内 24 字节的 ELF64 符号记录；bootstrap 会
/// 改写该记录的 `st_value` 和 `st_shndx`，让内核模块加载器看到 SHN_ABS
/// 地址。
#[derive(Debug)]
struct Fixup {
    /// 模块 ELF 内 24 字节符号记录的文件偏移；bootstrap 原地改写该记录。
    symbol_file_offset: usize,
    /// 目标符号相对镜像基址的偏移，必须小于 image_size。
    kernel_offset: u64,
}

/// 追加在内核镜像末尾、携带模块及元数据的尾部数据。
///
/// `data` 是写入 `file_offset` 的精确字节；其余字段是绝对位置，用于生成
/// bootstrap 的编译期定义并校验最终镜像布局。
#[derive(Debug)]
struct Capsule {
    /// 要追加到镜像末尾的完整 capsule 字节（含尾部零填充）。
    data: Vec<u8>,
    /// capsule 在最终镜像中的文件偏移，即原始 image_size 向上对齐后。
    file_offset: usize,
    /// 追加 capsule 后的镜像总大小，按 CAPSULE_ALIGNMENT 对齐。
    image_size: usize,
    /// 模块字节的绝对文件偏移。
    module_offset: usize,
    /// fixup 表的绝对文件偏移。
    fixup_offset: usize,
    /// 模块参数字符串的绝对文件偏移。
    args_offset: usize,
    /// 参数字节数，包含结尾 NUL。
    args_size: usize,
}

/// 扫描原始 Image 得到的 kallsyms 结果。
#[derive(Debug)]
struct RecoveredKallsyms {
    /// 恢复出的符号索引，后续所有地址解析都通过它完成。
    symbols: SymbolMap,
    /// "pre-6.4" 或 "6.4+"，仅用于诊断输出。
    layout: &'static str,
    /// 恢复出的符号总数，用于诊断与 markers 一致性检查。
    count: usize,
}

/// kallsyms 以及可恢复时对应的 vmlinux BTF。
#[derive(Debug)]
struct RecoveredKernelMetadata {
    /// 恢复出的 kallsyms 及其布局信息。
    kallsyms: RecoveredKallsyms,
    /// 由 `__start_BTF`/`__stop_BTF` 界定的 vmlinux BTF；不存在时为 None。
    btf: Option<KernelBtf>,
}

/// 未作为符号导出的内核 ABI 事实。
///
/// GKI 模块加载器会在栈上分配 `struct load_info`，并从其中的固定偏移读取
/// ELF 头和长度；这些偏移随结构体增长而变化。有 BTF 时以真实布局为准，
/// 这些值是经过验证的回退值。
#[derive(Clone, Copy, Debug)]
struct GkiAbi {
    /// 若有 BTF 能确定，则为真实的 `sizeof(struct load_info)`。
    load_info_structure_size: Option<u64>,
    /// 栈上预留的字节数，由结构体大小向上对齐得到。
    load_info_storage_size: u64,
    /// `struct load_info` 内保存 ELF 头的字段偏移。
    load_info_hdr_offset: u64,
    /// `struct load_info` 内保存模块长度的字段偏移。
    load_info_len_offset: u64,
    /// 传给模块分配/加载路径的 GFP 标志（arm64 GKI 上 GFP_KERNEL 为 0xcc0）。
    gfp_kernel: u64,
}

// 没有 BTF 或 BTF 无法解析时的回退 ABI。0xcc0 是 arm64 GKI 构建中
// GFP_KERNEL 的数值。
/// 内置回退 ABI：没有 BTF 或 BTF 无法解析 load_info 布局时使用。
const GKI_ABI: GkiAbi = GkiAbi {
    load_info_structure_size: None,
    load_info_storage_size: MINIMUM_LOAD_INFO_STORAGE_SIZE,
    load_info_hdr_offset: 16,
    load_info_len_offset: 24,
    gfp_kernel: 0xcc0,
};

impl GkiAbi {
    /// 用 BTF 推导的 `load_info` 布局覆盖内置回退值。
    ///
    /// 存储大小向上取整到 16 字节，保证栈分配自然对齐；再由 `validate`
    /// 限制到可接受范围。
    fn apply_btf(mut self, btf: &KernelBtf) -> Result<Self> {
        if let Some(layout) = btf.load_info {
            self.load_info_structure_size = Some(layout.structure_size);
            self.load_info_storage_size = align_up_u64(
                layout.structure_size.max(MINIMUM_LOAD_INFO_STORAGE_SIZE),
                16,
            )
            .context("BTF load_info storage size overflow")?;
            self.load_info_hdr_offset = layout.hdr_offset;
            self.load_info_len_offset = layout.len_offset;
        }
        self.validate()?;
        Ok(self)
    }

    /// 拒绝 bootstrap 无法安全使用的布局：存储必须是 16 字节对齐且范围
    /// 合理，结构体必须能放进存储，两个被访问字段必须 8 字节对齐并完全
    /// 位于结构体内。
    fn validate(self) -> Result<()> {
        ensure!(
            self.load_info_storage_size > 0
                && self.load_info_storage_size <= MAXIMUM_LOAD_INFO_STORAGE_SIZE
                && self.load_info_storage_size.is_multiple_of(16),
            "invalid GKI load_info storage size"
        );
        let layout_size = self
            .load_info_structure_size
            .unwrap_or(self.load_info_storage_size);
        ensure!(
            layout_size > 0 && layout_size <= self.load_info_storage_size,
            "invalid GKI load_info structure size"
        );
        for (field, offset) in [
            ("hdr_offset", self.load_info_hdr_offset),
            ("len_offset", self.load_info_len_offset),
        ] {
            ensure!(
                offset.is_multiple_of(8) && offset.saturating_add(8) <= layout_size,
                "invalid GKI load_info {field}"
            );
        }
        Ok(())
    }
}

/// 恢复过程中选中的 BTF blob 的可读摘要。
#[derive(Clone, Copy, Debug)]
struct BtfReport {
    /// BTF blob 在 Image 文件中的偏移。
    file_offset: usize,
    /// BTF blob 的字节长度。
    size: usize,
    /// BTF 类型段中的类型数量，仅用于输出诊断。
    type_count: usize,
}

/// `patch_boot` 在注入成功后打印的全部信息；与补丁字节分离，使
/// `inject_image` 保持纯粹、可测试。
#[derive(Debug)]
struct ImageInjectionReport {
    /// 从 linux_banner 提取的内核 release 字符串。
    kernel_release: String,
    /// 选中的 vmlinux BTF 摘要；无 BTF 时为 None。
    btf: Option<BtfReport>,
    /// kallsyms 布局："pre-6.4" 或 "6.4+"。
    kallsyms_layout: &'static str,
    /// 恢复出的 kallsyms 符号数量。
    kallsyms_count: usize,
    /// BTF 给出的真实 `sizeof(struct load_info)`；未知时为 None。
    load_info_structure_size: Option<u64>,
    /// bootstrap 在栈上预留的 load_info 字节数。
    load_info_storage_size: u64,
    /// load_info 内 ELF 头字段的偏移。
    load_info_hdr_offset: u64,
    /// load_info 内模块长度字段的偏移。
    load_info_len_offset: u64,
    /// bootstrap 在 Image 文件中的落点偏移。
    code_offset: usize,
    /// bootstrap 实际写入的字节数。
    code_size: usize,
    /// memblock_reserve 补丁指令的文件偏移。
    memblock_call_offset: usize,
    /// 推断出的 PAGE_OFFSET。
    page_offset: u64,
    /// 成功解析的模块未定义符号数量。
    fixup_count: usize,
    /// 留给内核模块加载器自行解析的符号名。
    unresolved: Vec<String>,
    /// 注入并补齐后的最终镜像大小。
    image_size: usize,
}

// 小端字节序缓冲区辅助函数。

/// 去掉编译器给符号名添加的装饰。
///
/// kallsyms 中可能出现 `sym.cold`、`sym.llvm.123`、`sym$...` 以及 CFI
/// 跳转表别名；它们都不是我们查找意义上的独立函数。
fn normalize_symbol(name: &str) -> &str {
    let mut end = name.len();
    for marker in ["$", ".llvm."] {
        if let Some(position) = name.find(marker) {
            end = end.min(position);
        }
    }
    let name = &name[..end];
    name.strip_suffix(".cfi_jt").unwrap_or(name)
}

/// 向上取整到 2 的幂对齐。调用方必须传入 2 的幂；这是用于已校验对齐值
/// 的未检查版本。
const fn align_up(value: usize, alignment: usize) -> usize {
    (value + alignment - 1) & !(alignment - 1)
}

/// `align_up` 的 u64 检查版本，溢出时返回 `None`。
const fn align_up_u64(value: u64, alignment: u64) -> Option<u64> {
    match value.checked_add(alignment - 1) {
        Some(value) => Some(value & !(alignment - 1)),
        None => None,
    }
}

/// 带边界检查的不可变切片访问；所有解析路径都经过它，畸形偏移会返回
/// 错误而不是 panic。
fn get_slice(data: &[u8], offset: usize, size: usize) -> Result<&[u8]> {
    data.get(offset..offset.saturating_add(size))
        .ok_or_else(|| anyhow!("offset 0x{offset:x} size 0x{size:x} is outside input"))
}

/// ARM64 Image 头、ELF 对象和 capsule 中的多字节值都是小端，与
/// arm64/kernel ABI 和 AArch64 ELF 一致。
fn read_u16(data: &[u8], offset: usize) -> Result<u16> {
    Ok(u16::from_le_bytes(get_slice(data, offset, 2)?.try_into()?))
}

/// 读取小端 u32；偏移越界时返回错误而不是 panic。
fn read_u32(data: &[u8], offset: usize) -> Result<u32> {
    Ok(u32::from_le_bytes(get_slice(data, offset, 4)?.try_into()?))
}

/// 读取小端 i32；用于 kallsyms 的有符号相对地址。
fn read_i32(data: &[u8], offset: usize) -> Result<i32> {
    Ok(i32::from_le_bytes(get_slice(data, offset, 4)?.try_into()?))
}

/// 读取小端 u64；用于 ELF 头、节表和 capsule 字段。
fn read_u64(data: &[u8], offset: usize) -> Result<u64> {
    Ok(u64::from_le_bytes(get_slice(data, offset, 8)?.try_into()?))
}

/// 读取小端 i64；用于 RELA 的显式 addend（可为负）。
fn read_i64(data: &[u8], offset: usize) -> Result<i64> {
    Ok(i64::from_le_bytes(get_slice(data, offset, 8)?.try_into()?))
}

/// 写入小端 u32；越界时返回错误，避免污染相邻字段。
fn write_u32(data: &mut [u8], offset: usize, value: u32) -> Result<()> {
    get_slice_mut(data, offset, 4)?.copy_from_slice(&value.to_le_bytes());
    Ok(())
}

/// 写入小端 u64；越界时返回错误，避免污染相邻字段。
fn write_u64(data: &mut [u8], offset: usize, value: u64) -> Result<()> {
    get_slice_mut(data, offset, 8)?.copy_from_slice(&value.to_le_bytes());
    Ok(())
}

/// 带边界检查的可变切片访问；offset + size 的加法也要检查，因为两者都
/// 可能来自不可信的 capsule/布局运算。
fn get_slice_mut(data: &mut [u8], offset: usize, size: usize) -> Result<&mut [u8]> {
    let end = offset
        .checked_add(size)
        .ok_or_else(|| anyhow!("offset overflow"))?;
    data.get_mut(offset..end)
        .ok_or_else(|| anyhow!("offset 0x{offset:x} size 0x{size:x} is outside output"))
}

/// 从 `start` 起查找子串并返回绝对偏移；空 needle 或越界起点返回 None。
fn find_subslice(data: &[u8], needle: &[u8], start: usize) -> Option<usize> {
    if needle.is_empty() || start > data.len() {
        return None;
    }
    data[start..]
        .windows(needle.len())
        .position(|window| window == needle)
        .map(|position| position + start)
}

// kallsyms 恢复。

/// 尝试解析位于 `start` 的 kallsyms token 表。
///
/// token 表由 256 个连续的、以 NUL 结尾的 ASCII token 组成。`digit_offset`
/// 是单字符 token `"0"` 所在的文件偏移；由于第 48 个 token 恒为 `"0"`，
/// 它可作为表起点的锚点。随后的 256 个 u16 索引项必须逐个精确指回对应
/// token。
///
/// 返回 token 列表和 u16 索引的文件偏移。
fn parse_kallsyms_token_table_at(
    image: &[u8],
    start: usize,
    digit_offset: usize,
) -> Option<(Vec<Vec<u8>>, usize)> {
    if !start.is_multiple_of(KALLSYMS_ALIGNMENT) {
        return None;
    }

    // 展开后的 256 个 token；顺序即索引，不能重排。
    let mut tokens = Vec::with_capacity(KALLSYMS_TOKEN_COUNT);
    // 每个 token 相对表起点的偏移，稍后与 u16 索引表逐项比对。
    let mut token_offsets = Vec::with_capacity(KALLSYMS_TOKEN_COUNT);
    // 每个 token 的绝对偏移，用于验证 "0" 恰好落在 digit_offset 上。
    let mut token_starts = Vec::with_capacity(KALLSYMS_TOKEN_COUNT);
    // 游标：始终指向下一个 token 的起始字节。
    let mut position = start;
    for _ in 0..KALLSYMS_TOKEN_COUNT {
        token_starts.push(position);
        let search_end = position
            .checked_add(KALLSYMS_MAX_TOKEN_LENGTH + 1)?
            .min(image.len());
        let length = image
            .get(position..search_end)?
            .iter()
            .position(|byte| *byte == 0)?;
        let end = position.checked_add(length)?;
        let token = image.get(position..end)?;
        if token.iter().any(|byte| !(0x20..=0x7e).contains(byte)) {
            return None;
        }
        token_offsets.push(position.checked_sub(start)?);
        tokens.push(token.to_vec());
        position = end.checked_add(1)?;
    }

    if *token_starts.get(b'0' as usize)? != digit_offset {
        return None;
    }
    for &value in b"0123456789_abcdefghijklmnopqrstuvwxyzT" {
        if tokens.get(value as usize)?.as_slice() != [value] {
            return None;
        }
    }

    let token_index_offset = align_up(position, KALLSYMS_ALIGNMENT);
    if image
        .get(position..token_index_offset)?
        .iter()
        .any(|byte| *byte != 0)
    {
        return None;
    }
    let token_index_end = token_index_offset.checked_add(KALLSYMS_TOKEN_INDEX_SIZE)?;
    if token_index_end > image.len() || *token_offsets.last()? > u16::MAX as usize {
        return None;
    }
    for (index, &expected) in token_offsets.iter().enumerate() {
        let actual = read_u16(image, token_index_offset + index * 2).ok()?;
        if actual as usize != expected {
            return None;
        }
    }
    Some((tokens, token_index_offset))
}

/// 在 Image 中定位所有可能的 token 表。
///
/// 搜索以必然出现的 `"0\0" ... "9\0"` 字节串为线索，然后在候选起点附近
/// 按对齐向后回退，寻找真正的表头。BTreeMap 按起点去重，并保证扫描顺序
/// 确定。
fn find_kallsyms_token_tables(image: &[u8]) -> Vec<(Vec<Vec<u8>>, usize, usize)> {
    // 线索字节串 "0\0"..."9\0"：真实 token 表中必然连续出现。
    let mut digit_tokens = Vec::with_capacity(20);
    for value in b'0'..=b'9' {
        digit_tokens.extend_from_slice(&[value, 0]);
    }

    // 以表起点为键去重，同时保证输出顺序确定。
    let mut candidates = BTreeMap::<usize, (Vec<Vec<u8>>, usize, usize)>::new();
    // 下一轮线索搜索起点，避免重复命中同一处。
    let mut search_from = 0;
    while let Some(digit_offset) = find_subslice(image, &digit_tokens, search_from) {
        let earliest = digit_offset.saturating_sub(b'0' as usize * (KALLSYMS_MAX_TOKEN_LENGTH + 1));
        let mut start = align_up(earliest, KALLSYMS_ALIGNMENT);
        while start <= digit_offset {
            if let Some((tokens, token_index_offset)) =
                parse_kallsyms_token_table_at(image, start, digit_offset)
            {
                candidates.insert(start, (tokens, start, token_index_offset));
            }
            start += KALLSYMS_ALIGNMENT;
        }
        search_from = digit_offset.saturating_add(1);
        if search_from >= image.len() {
            break;
        }
    }

    candidates.into_values().collect()
}

/// 读取 names 表之前的 markers 数组。
///
/// markers 是每 256 个符号一个的累计字节偏移，必须严格递增且差值合理
/// （0x200..=0x40000）。要求最少条数可以避免误匹配到其他单调递增数据。
fn read_kallsyms_markers(image: &[u8], start: usize, limit: usize) -> Vec<u32> {
    if !start.is_multiple_of(KALLSYMS_ALIGNMENT) || start.saturating_add(8) > limit {
        return Vec::new();
    }
    let Ok(first) = read_u32(image, start) else {
        return Vec::new();
    };
    let Ok(second) = read_u32(image, start + 4) else {
        return Vec::new();
    };
    if first != 0 || !(0x200..=0x40000).contains(&second) {
        return Vec::new();
    }

    // 累计偏移序列：首项为 0，之后每项对应第 256 个符号。
    let mut markers = vec![first, second];
    // 读取游标，始终按 4 字节步进。
    let mut position = start + 8;
    while markers.len() < KALLSYMS_MAX_MARKERS && position.saturating_add(4) <= limit {
        let Ok(value) = read_u32(image, position) else {
            break;
        };
        let Some(delta) = value.checked_sub(*markers.last().expect("non-empty markers")) else {
            break;
        };
        if !(0x200..=0x40000).contains(&delta) {
            break;
        }
        markers.push(value);
        position += 4;
    }
    if markers.len() < KALLSYMS_MIN_MARKERS {
        Vec::new()
    } else {
        markers
    }
}

/// 把 names blob 切分成 `count` 个符号的 `(偏移, 长度)` 区间。
///
/// 6.4 之前用一个字节存长度；6.4+ 为支持超过 127 的长度改用 ULEB128，
/// 因此需要 `uleb128_lengths`。函数会校验不变量：每第 256 个符号必须从
/// 对应 marker 开始，且表必须恰好结束在（零填充的）markers 偏移处。
fn parse_kallsyms_name_spans(
    image: &[u8],
    names_offset: usize,
    count: usize,
    markers_offset: usize,
    markers: &[u32],
    uleb128_lengths: bool,
) -> Option<Vec<(usize, usize)>> {
    if count == 0 || markers.len() != count.div_ceil(256) {
        return None;
    }

    // 每个符号的 (名称数据偏移, 编码长度)。
    let mut spans = Vec::with_capacity(count);
    // 名称 blob 内的解码游标，必须始终落在 markers 之前。
    let mut position = names_offset;
    for index in 0..count {
        if index.is_multiple_of(256)
            && position.checked_sub(names_offset)? != markers[index / 256] as usize
        {
            return None;
        }
        if position >= markers_offset {
            return None;
        }
        // 编码长度：旧格式一字节，6.4+ 可能为两字节 ULEB128。
        let mut length = *image.get(position)? as usize;
        position += 1;
        if uleb128_lengths && length & 0x80 != 0 {
            if position >= markers_offset {
                return None;
            }
            let high = *image.get(position)? as usize;
            if high & 0x80 != 0 {
                return None;
            }
            length = (length & 0x7f) | (high << 7);
            position += 1;
        }
        if length == 0 || length > 0x3fff || position.checked_add(length)? > markers_offset {
            return None;
        }
        spans.push((position, length));
        position += length;
    }

    if align_up(position, KALLSYMS_ALIGNMENT) != markers_offset
        || image
            .get(position..markers_offset)?
            .iter()
            .any(|byte| *byte != 0)
    {
        return None;
    }
    Some(spans)
}

/// 用 token 表展开压缩的 kallsyms 名称。
///
/// 每个名称是若干 token 索引；展开后的第一个字节是符号类型，其余是名称。
/// 要求 `_text`、`_end` 和 `load_module` 必须出现，是一个廉价的一致性
/// 检查，可排除偶然匹配的数据。
fn decode_kallsyms_names(
    image: &[u8],
    spans: &[(usize, usize)],
    tokens: &[Vec<u8>],
) -> Option<Vec<(u8, String)>> {
    // kallsyms 名称首字节允许的符号类型集合；不在其中的字节说明解码错位。
    const SYMBOL_TYPES: &[u8] = b"aAbBcCdDeEfFgGiInNpPrRsStTuUvVwW?-";

    // 解码结果：(符号类型字节, 名称)，顺序与 spans 一致。
    let mut decoded = Vec::with_capacity(spans.len());
    for &(position, length) in spans {
        let encoded = image.get(position..position.checked_add(length)?)?;
        let expanded_length = encoded.iter().try_fold(0usize, |total, &index| {
            total.checked_add(tokens.get(index as usize)?.len())
        })?;
        if expanded_length > 4096 {
            return None;
        }
        // 由 token 拼接出的完整记录，首字节是符号类型。
        let mut expanded = Vec::with_capacity(expanded_length);
        for &index in encoded {
            expanded.extend_from_slice(tokens.get(index as usize)?);
        }
        let (&kind, name_bytes) = expanded.split_first()?;
        if name_bytes.is_empty()
            || !SYMBOL_TYPES.contains(&kind)
            || name_bytes.iter().any(|byte| !(0x21..=0x7e).contains(byte))
        {
            return None;
        }
        decoded.push((kind, String::from_utf8(name_bytes.to_vec()).ok()?));
    }

    let names = decoded
        .iter()
        .map(|(_, name)| name.as_str())
        .collect::<HashSet<_>>();
    if ["_text", "_end", "load_module"]
        .iter()
        .any(|required| !names.contains(required))
    {
        return None;
    }
    Some(decoded)
}

/// (num_syms 偏移, names 偏移, markers 偏移, 解码后的名称)
type KallsymsNameTable = (usize, usize, usize, Vec<(u8, String)>);

/// 查找与给定 token 表一致的 names 表。
///
/// markers 数组在 token 表之前的有界窗口内搜索。根据最后一个 marker 可以
/// 估算 names blob 的起点，然后按对齐向前回退，寻找合理的 `num_syms` u32
/// 以及紧随其后的四个零字节。新、旧两种名称编码都会尝试，因为仅凭布局
/// 无法判断镜像来自哪个内核系列。
fn find_kallsyms_names(
    image: &[u8],
    tokens: &[Vec<u8>],
    token_table_offset: usize,
) -> Vec<KallsymsNameTable> {
    let lower = token_table_offset.saturating_sub(KALLSYMS_MARKER_SEARCH_WINDOW);
    // 所有通过递增性校验的 markers 位置及其内容。
    let mut marker_candidates = Vec::new();
    let mut position = align_up(lower, KALLSYMS_ALIGNMENT);
    while position.saturating_add(8) <= token_table_offset {
        let markers = read_kallsyms_markers(image, position, token_table_offset);
        if !markers.is_empty() {
            marker_candidates.push((position, markers));
        }
        position += KALLSYMS_ALIGNMENT;
    }

    let mut recovered = Vec::new();
    for (markers_offset, markers) in marker_candidates {
        let minimum_count = (markers.len() - 1) * 256 + 1;
        let maximum_count = markers.len() * 256;
        // 由最后一个 marker 反推的 names 起点上界。
        let Some(approximate_names) =
            markers_offset.checked_sub(*markers.last().expect("non-empty markers") as usize)
        else {
            continue;
        };
        let search_start = approximate_names.saturating_sub(KALLSYMS_NAME_TAIL_SEARCH);
        // 从估算位置按对齐向前回退，寻找 num_syms u32。
        let mut num_syms_offset = approximate_names - approximate_names % KALLSYMS_ALIGNMENT;
        loop {
            if num_syms_offset.saturating_add(8) <= image.len() {
                let count = read_u32(image, num_syms_offset).unwrap_or(0) as usize;
                let padding_is_zero = image
                    .get(num_syms_offset + 4..num_syms_offset + 8)
                    .is_some_and(|padding| padding == [0; 4]);
                if (minimum_count..=maximum_count).contains(&count) && padding_is_zero {
                    let names_offset = num_syms_offset + KALLSYMS_ALIGNMENT;
                    let mut seen_spans: Vec<Vec<(usize, usize)>> = Vec::new();
                    for uleb128_lengths in [true, false] {
                        let Some(spans) = parse_kallsyms_name_spans(
                            image,
                            names_offset,
                            count,
                            markers_offset,
                            &markers,
                            uleb128_lengths,
                        ) else {
                            continue;
                        };
                        if seen_spans.contains(&spans) {
                            continue;
                        }
                        seen_spans.push(spans.clone());
                        if let Some(decoded) = decode_kallsyms_names(image, &spans, tokens) {
                            recovered.push((
                                num_syms_offset,
                                names_offset,
                                markers_offset,
                                decoded,
                            ));
                        }
                    }
                }
            }
            if num_syms_offset < search_start.saturating_add(KALLSYMS_ALIGNMENT) {
                break;
            }
            num_syms_offset -= KALLSYMS_ALIGNMENT;
        }
    }
    recovered
}

/// 判断是否为高半区的规范 arm64 内核虚拟地址：高 16 位为 0xffff 且按页
/// 对齐。用于排除随机 u64 值。
const fn is_arm64_kernel_address(address: u64) -> bool {
    address >> 48 == 0xffff && address.is_multiple_of(4096)
}

/// 把最终的地址数组解码成符号。
///
/// 存在两种编码。较老的内核直接存 u64 绝对地址；5.10+ 存相对基地址的
/// 有符号 32 位偏移，基地址位于 offsets 数组之后；启用
/// CONFIG_KALLSYMS_ABSOLUTE_PERCPU 时，非负项是绝对地址（percpu 符号），
/// 负值项解码为 `base - 1 - offset`。下面的符号判断会选择占多数的编码。
///
/// 校验刻意严格：地址必须单调，`_text` 必须等于相对基地址，
/// `_end - _text` 必须等于 `image_size`，且至少 90% 的非绝对符号必须落在
/// 镜像内。正是这些条件让恢复在没有符号表的情况下仍然安全。
fn decode_kallsyms_addresses(
    image: &[u8],
    image_size: usize,
    names: &[(u8, String)],
    offsets_offset: usize,
    relative_base_offset: usize,
) -> Option<Vec<MapSymbol>> {
    let count = names.len();
    let offsets_end = offsets_offset.checked_add(count.checked_mul(4)?)?;
    if offsets_end > image.len()
        || relative_base_offset < offsets_end
        || relative_base_offset.checked_add(8)? > image.len()
        || image
            .get(offsets_end..relative_base_offset)?
            .iter()
            .any(|byte| *byte != 0)
    {
        return None;
    }
    // 相对编码的基地址，必须等于 _text 且位于高半区。
    let relative_base = read_u64(image, relative_base_offset).ok()?;
    if !is_arm64_kernel_address(relative_base) {
        return None;
    }

    // 先全部读成 i32，再根据负值比例判断采用哪种编码。
    let signed_offsets = (0..count)
        .map(|index| read_i32(image, offsets_offset + index * 4).ok())
        .collect::<Option<Vec<_>>>()?;
    // 负值占比决定是“相对基址”还是“绝对 percpu”编码。
    let negative_count = signed_offsets.iter().filter(|offset| **offset < 0).count();
    // 统一展开后的绝对地址，必须单调不减。
    let addresses = if negative_count * 2 >= count {
        signed_offsets
            .iter()
            .map(|&offset| {
                let address = if offset < 0 {
                    i128::from(relative_base) - 1 - i128::from(offset)
                } else {
                    i128::from(offset)
                };
                u64::try_from(address).ok()
            })
            .collect::<Option<Vec<_>>>()?
    } else {
        (0..count)
            .map(|index| {
                relative_base
                    .checked_add(u64::from(read_u32(image, offsets_offset + index * 4).ok()?))
            })
            .collect::<Option<Vec<_>>>()?
    };

    if addresses.windows(2).any(|pair| pair[0] > pair[1]) {
        return None;
    }
    let text_addresses = addresses
        .iter()
        .zip(names)
        .filter_map(|(&address, (_, name))| (name == "_text").then_some(address))
        .collect::<Vec<_>>();
    let end_addresses = addresses
        .iter()
        .zip(names)
        .filter_map(|(&address, (_, name))| (name == "_end").then_some(address))
        .collect::<Vec<_>>();
    if text_addresses.len() != 1
        || end_addresses.len() != 1
        || text_addresses[0] != relative_base
        || end_addresses[0].checked_sub(text_addresses[0])? != image_size as u64
    {
        return None;
    }

    // 排除绝对/percpu 符号后的普通地址，用于比例校验。
    let ordinary_addresses = addresses
        .iter()
        .zip(names)
        .filter_map(|(&address, (kind, _))| {
            (!b"Aa".contains(kind) && address != 0).then_some(address)
        })
        .collect::<Vec<_>>();
    // 落在 [_text, _end] 内的普通符号数。
    let in_image = ordinary_addresses
        .iter()
        .filter(|&&address| text_addresses[0] <= address && address <= end_addresses[0])
        .count();
    if ordinary_addresses.is_empty() || in_image * 100 < ordinary_addresses.len() * 90 {
        return None;
    }

    Some(
        addresses
            .into_iter()
            .zip(names)
            .map(|(address, (_, name))| MapSymbol {
                address,
                name: name.clone(),
            })
            .collect(),
    )
}

/// 确认 BTF blob 的文件范围与恢复出的 `__start_BTF` / `__stop_BTF` 符号
/// 一致。
///
/// Image 中可能嵌入多个无关的 BTF blob（例如 BPF skeleton）；只有被上述
/// kallsyms 符号界定的那个才是 vmlinux BTF。
fn validate_kallsyms_btf_boundaries(
    symbols: &SymbolMap,
    btf_file_offset: usize,
    btf_size: usize,
) -> Result<()> {
    let image_base = symbols.resolve("_text")?;
    let btf_start = symbols.resolve("__start_BTF")?;
    let btf_stop = symbols.resolve("__stop_BTF")?;
    let recovered_offset = btf_start
        .address
        .checked_sub(image_base.address)
        .context("__start_BTF is below _text")?;
    let recovered_size = btf_stop
        .address
        .checked_sub(btf_start.address)
        .context("__stop_BTF is below __start_BTF")?;
    ensure!(
        recovered_offset == btf_file_offset as u64,
        "kallsyms __start_BTF offset 0x{recovered_offset:x} does not match BTF file offset 0x{btf_file_offset:x}"
    );
    ensure!(
        recovered_size == btf_size as u64,
        "kallsyms BTF size 0x{recovered_size:x} does not match parsed BTF size 0x{btf_size:x}"
    );
    Ok(())
}

/// 从原始 ARM64 Image 恢复内核符号表（以及可选的 BTF）。
///
/// 策略：枚举所有可能的 token 表，再枚举与其一致的 names 表，然后尝试
/// 两种地址布局。若存在 BTF 候选，用 `__start_BTF`/`__stop_BTF` 边界检查
/// 选择正确的一个；若没有候选匹配，则仅在 kallsyms 候选恰好唯一时使用
/// BTF。任何歧义都会报错：选错会把模块符号解析到错误地址。
fn recover_arm64_kernel_metadata(image: &[u8]) -> Result<RecoveredKernelMetadata> {
    let image_size = parse_arm64_image_size(image)?;
    let btf_candidates = find_btf_candidates(image);
    let token_tables = find_kallsyms_token_tables(image);
    let token_table_count = token_tables.len();
    // 所有通过地址布局校验的完整候选。
    let mut candidates = Vec::new();
    // 诊断计数：完整候选总数，即使最终未被选中。
    let mut complete_candidates = 0usize;

    for (tokens, token_table_offset, token_index_offset) in token_tables {
        let name_tables = find_kallsyms_names(image, &tokens, token_table_offset);
        for (num_syms_offset, _, _, names) in name_tables {
            let count = names.len();
            // 旧布局把 offsets/基址放在 num_syms 之前，新布局放在 token 索引之后。
            let old_relative_base_offset = num_syms_offset.saturating_sub(8);
            let old_offsets_offset =
                old_relative_base_offset.saturating_sub(align_up(count * 4, 8));
            let new_offsets_offset = align_up(
                token_index_offset + KALLSYMS_TOKEN_INDEX_SIZE,
                KALLSYMS_ALIGNMENT,
            );
            let new_relative_base_offset =
                align_up(new_offsets_offset + count * 4, KALLSYMS_ALIGNMENT);
            for (layout, offsets_offset, relative_base_offset) in [
                ("pre-6.4", old_offsets_offset, old_relative_base_offset),
                ("6.4+", new_offsets_offset, new_relative_base_offset),
            ] {
                let Some(entries) = decode_kallsyms_addresses(
                    image,
                    image_size,
                    &names,
                    offsets_offset,
                    relative_base_offset,
                ) else {
                    continue;
                };
                complete_candidates += 1;
                let recovered = RecoveredKallsyms {
                    symbols: SymbolMap::new(entries)?,
                    layout,
                    count,
                };
                candidates.push(recovered);
            }
        }
    }

    // 内建的 BPF skeleton 可能嵌入彼此独立的原始 BTF blob。只有被
    // 这张 kallsyms 表限定在同一范围内的候选项才属于 vmlinux。
    // (kallsyms 候选, BTF 候选) 的边界匹配对。
    let mut boundary_matches = Vec::new();
    for (kallsyms_index, kallsyms) in candidates.iter().enumerate() {
        for (btf_index, btf) in btf_candidates.iter().enumerate() {
            if validate_kallsyms_btf_boundaries(&kallsyms.symbols, btf.file_offset(), btf.size())
                .is_ok()
            {
                boundary_matches.push((kallsyms_index, btf_index));
            }
        }
    }

    if boundary_matches.len() > 1 {
        bail!(
            "cannot uniquely match vmlinux BTF to GKI kallsyms in ARM64 Image ({} token tables, {complete_candidates} complete kallsyms candidates, {} valid BTF blobs, {} boundary matches)",
            token_table_count,
            btf_candidates.len(),
            boundary_matches.len()
        );
    }

    if let Some((kallsyms_index, btf_index)) = boundary_matches.pop() {
        let kallsyms = candidates.swap_remove(kallsyms_index);
        let btf = btf_candidates[btf_index]
            .to_kernel_btf()
            .context("cannot parse vmlinux BTF selected by kallsyms boundaries")?;
        return Ok(RecoveredKernelMetadata {
            kallsyms,
            btf: Some(btf),
        });
    }

    if candidates.len() != 1 {
        bail!(
            "cannot uniquely recover GKI kallsyms from ARM64 Image ({} token tables, {complete_candidates} complete candidates, {} valid BTF blobs, no BTF boundary match); CONFIG_KALLSYMS_ALL is required",
            token_table_count,
            btf_candidates.len()
        );
    }
    Ok(RecoveredKernelMetadata {
        kallsyms: candidates.pop().expect("one kallsyms candidate"),
        btf: None,
    })
}

/// 读取并校验 ARM64 Image 的大小字段。
///
/// 头部至少 64 字节，且 0x38 处必须有 magic，这也会拒绝 zImage/压缩载荷。
/// `image_size` 是可加载大小，具有权威性：其后的字节是追加的元数据
/// （DTB 等），本补丁器拒绝保留它们。
fn parse_arm64_image_size(image: &[u8]) -> Result<usize> {
    ensure!(
        image.len() >= 64,
        "kernel Image is smaller than the ARM64 header"
    );
    ensure!(
        get_slice(image, ARM64_IMAGE_MAGIC_OFFSET, ARM64_IMAGE_MAGIC.len())? == ARM64_IMAGE_MAGIC,
        "kernel input is not an uncompressed ARM64 Image"
    );
    let image_size = read_u64(image, ARM64_IMAGE_SIZE_OFFSET)?;
    ensure!(
        image_size != 0,
        "ARM64 Image header has image_size=0; unsupported input"
    );
    usize::try_from(image_size).context("ARM64 Image image_size does not fit this host")
}

// GKI ABI 与 ARM64 补丁点分析。

/// 恢复内核 release 字符串和 GKI `load_info` ABI。
///
/// `linux_banner` 是形如 "Linux version 6.1.75-android14-..." 的
/// NUL 结尾 C 字符串；前缀之后第一个以空白分隔的 token 就是 release。
/// 只接受经过验证的 GKI 系列，因为下面依赖的调用点特征仅在那些版本已知。
fn recover_gki_abi(
    image: &[u8],
    image_base: u64,
    linux_banner: &MapSymbol,
    btf: Option<&KernelBtf>,
) -> Result<(String, GkiAbi)> {
    let offset = address_to_offset(
        linux_banner.address,
        image_base,
        image.len(),
        &linux_banner.name,
    )?;
    // banner 最多读取 1 KiB，避免跨越到无关数据。
    let bounded = image
        .get(offset..offset.saturating_add(1024).min(image.len()))
        .context("linux_banner is outside the Image")?;
    let end = bounded
        .iter()
        .position(|byte| *byte == 0)
        .context("linux_banner is not a bounded C string in the Image")?;
    // banner 必须是镜像内的有界 ASCII C 字符串。
    let banner = std::str::from_utf8(&bounded[..end]).context("linux_banner is not ASCII")?;
    // release 取前缀之后的第一个空白分隔 token。
    let release = banner
        .strip_prefix("Linux version ")
        .and_then(|suffix| suffix.split_whitespace().next())
        .context("cannot recover the kernel release from linux_banner")?;
    let mut parts = release.splitn(3, '.');
    let major = parts
        .next()
        .context("kernel release has no major version")?
        .parse::<u32>()
        .context("kernel major version is invalid")?;
    let minor = parts
        .next()
        .context("kernel release has no minor version")?
        .parse::<u32>()
        .context("kernel minor version is invalid")?;
    ensure!(
        parts.next().is_some_and(|tail| !tail.is_empty()),
        "kernel release has no patch version"
    );
    ensure!(
        matches!((major, minor), (5, 10 | 15) | (6, 1 | 6 | 12)),
        "unsupported GKI kernel series {major}.{minor}; validated series: 5.10, 5.15, 6.1, 6.6, 6.12"
    );
    // 有 BTF 时用真实 load_info 布局覆盖内置回退值。
    let abi = match btf {
        Some(btf) => GKI_ABI.apply_btf(btf)?,
        None => GKI_ABI,
    };
    abi.validate()?;
    Ok((release.to_owned(), abi))
}

/// 把 `BL` 指令解码为绝对目标地址。
///
/// AArch64 `BL` 在 bit 31..26 为操作码 `0b100101`，bit 25..0 是 26 位有符号
/// 字位移。立即数从 bit 25 符号扩展后乘以 4。
fn decode_bl_target(instruction: u32, source_address: u64) -> Option<u64> {
    if instruction & 0xfc00_0000 != 0x9400_0000 {
        return None;
    }
    let immediate = i64::from((instruction << 6) as i32) >> 4;
    source_address.checked_add_signed(immediate)
}

/// 从 `source_address` 到 `target_address` 编码 `BL`，并强制 26 位位移的
/// 4 字节对齐和 ±128 MiB 范围。
fn encode_bl(source_address: u64, target_address: u64) -> Result<u32> {
    let displacement = i128::from(target_address) - i128::from(source_address);
    ensure!(
        displacement % 4 == 0,
        "ARM64 BL target is not 4-byte aligned"
    );
    let immediate = displacement >> 2;
    ensure!(
        (-(1i128 << 25)..(1i128 << 25)).contains(&immediate),
        "ARM64 BL from 0x{source_address:x} to 0x{target_address:x} is out of range"
    );
    Ok(0x9400_0000 | (u32::try_from(immediate & 0x03ff_ffff)?))
}

/// 把内核虚拟地址转换为文件偏移，并要求至少能容纳 4 字节指令。
fn address_to_offset(
    address: u64,
    image_base: u64,
    file_size: usize,
    label: &str,
) -> Result<usize> {
    let offset = address
        .checked_sub(image_base)
        .and_then(|value| usize::try_from(value).ok())
        .filter(|offset| offset.saturating_add(4) <= file_size)
        .ok_or_else(|| anyhow!("{label} address 0x{address:x} is outside the ARM64 Image file"))?;
    Ok(offset)
}

/// 通过取大小上限和下一个符号地址的较小值确定函数的文件范围。
/// 下一个符号边界可以防止扫描游走到无关（但可能相似）的代码里。
fn function_scan_range(
    image: &[u8],
    symbols: &SymbolMap,
    image_base: u64,
    function: &MapSymbol,
    maximum_scan_size: usize,
) -> Result<(usize, usize)> {
    let start = address_to_offset(function.address, image_base, image.len(), &function.name)?;
    let maximum_scan_size = u64::try_from(maximum_scan_size)?;
    let mut end_address = function
        .address
        .checked_add(maximum_scan_size)
        .context("function scan range overflow")?;
    if let Some(next_address) = symbols.next_address(function.address) {
        end_address = end_address.min(next_address);
    }
    let end = end_address
        .checked_sub(image_base)
        .and_then(|value| usize::try_from(value).ok())
        .unwrap_or(image.len())
        .min(image.len());
    ensure!(
        end > start,
        "cannot establish a scan range for {}",
        function.name
    );
    Ok((start, end))
}

/// 在 `function` 内查找唯一一条目标属于 `accepted_targets` 的 `BL`。
///
/// 刻意要求唯一：如果编译器内联或复制了该调用，给错误的一处打补丁可能
/// 破坏无关的内核行为，因此调用方会收到列出所有候选的错误。
fn find_unique_direct_call(
    image: &[u8],
    symbols: &SymbolMap,
    image_base: u64,
    function: &MapSymbol,
    accepted_targets: &HashSet<u64>,
    label: &str,
    maximum_scan_size: usize,
) -> Result<CallSite> {
    let (start, end) =
        function_scan_range(image, symbols, image_base, function, maximum_scan_size)?;
    // 收集全部候选；数量不等于 1 时直接报错，而不是猜测。
    let mut matches = Vec::new();
    for offset in (start..end.saturating_sub(3)).step_by(4) {
        let instruction = read_u32(image, offset)?;
        let address = image_base + offset as u64;
        if let Some(target) = decode_bl_target(instruction, address)
            && accepted_targets.contains(&target)
        {
            matches.push(CallSite {
                file_offset: offset,
                address,
                target,
            });
        }
    }
    ensure!(
        matches.len() == 1,
        "{label}: expected one direct BL in {}, found {}{}",
        function.name,
        matches.len(),
        if matches.is_empty() {
            String::new()
        } else {
            format!(
                " at {}",
                matches
                    .iter()
                    .map(|call| format!("0x{:x}", call.file_offset))
                    .collect::<Vec<_>>()
                    .join(", ")
            )
        }
    );
    Ok(matches[0])
}

/// 把 `SUB (shifted register)` 解码为 `(Rd, Rn, Rm)`；其他编码返回 `None`。
/// 仅用于识别 GCC/Clang 在内核镜像 `memblock_reserve()` 调用前紧邻生成的
/// 两条减法。
fn decode_sub_shifted_register(instruction: u32) -> Option<(u32, u32, u32)> {
    (instruction & 0xffe0_fc00 == 0xcb00_0000).then_some((
        instruction & 31,
        (instruction >> 5) & 31,
        (instruction >> 16) & 31,
    ))
}

/// 定位为内核镜像保留内存的那次 `memblock_reserve()` 调用。
///
/// `arm64_memblock_init()` 中有多个 `memblock_reserve` 调用，仅凭目标地址
/// 不够。内核镜像调用的特征是：它前面紧跟两条减法，分别从同一个内核符号
/// 寄存器导出 `x1`（大小）和 `x0`（起始地址），例如
/// `sub x1, xN, xM` 后接 `sub x0, xN, xP`。共享的源寄存器以及固定的目标
/// 寄存器（`x1`、`x0`）可唯一确定它。
fn find_kernel_image_memblock_reserve_call(
    image: &[u8],
    symbols: &SymbolMap,
    image_base: u64,
    caller: &MapSymbol,
    accepted_targets: &HashSet<u64>,
    maximum_scan_size: usize,
) -> Result<CallSite> {
    let (start, end) = function_scan_range(image, symbols, image_base, caller, maximum_scan_size)?;
    // 所有指向 memblock_reserve 的直接调用，仅用于错误诊断。
    let mut direct_calls = Vec::new();
    // 满足 x0/x1 构造约束的调用，必须唯一。
    let mut semantic_matches = Vec::new();
    for offset in (start..end.saturating_sub(3)).step_by(4) {
        let instruction = read_u32(image, offset)?;
        let address = image_base + offset as u64;
        let Some(target) = decode_bl_target(instruction, address) else {
            continue;
        };
        if !accepted_targets.contains(&target) {
            continue;
        }
        let call = CallSite {
            file_offset: offset,
            address,
            target,
        };
        direct_calls.push(call);
        if offset < start + 8 {
            continue;
        }
        let Some((size_destination, _, kernel_start)) =
            decode_sub_shifted_register(read_u32(image, offset - 8)?)
        else {
            continue;
        };
        let Some((start_destination, start_left, _)) =
            decode_sub_shifted_register(read_u32(image, offset - 4)?)
        else {
            continue;
        };
        if size_destination == 1
            && start_destination == 0
            && kernel_start == start_left
            && kernel_start != 31
        {
            semantic_matches.push(call);
        }
    }

    let calls = direct_calls
        .iter()
        .map(|call| format!("0x{:x}", call.file_offset))
        .collect::<Vec<_>>()
        .join(", ");
    let matches = semantic_matches
        .iter()
        .map(|call| format!("0x{:x}", call.file_offset))
        .collect::<Vec<_>>()
        .join(", ");
    ensure!(
        semantic_matches.len() == 1,
        "cannot uniquely identify the kernel-image memblock_reserve() call from x0/x1 construction ({} matches){}{}",
        semantic_matches.len(),
        if calls.is_empty() {
            String::new()
        } else {
            format!("; direct calls: {calls}")
        },
        if matches.is_empty() {
            String::new()
        } else {
            format!("; semantic matches: {matches}")
        }
    );
    Ok(semantic_matches[0])
}

/// 在 `width` 位元素内把 `value` 循环右移 `shift` 位，行为同 AArch64
/// `ROR`。用于展开 `decode_orr_immediate` 的位掩码立即数。
const fn rotate_right_width(value: u64, shift: u32, width: u32) -> u64 {
    let mask = if width == 64 {
        u64::MAX
    } else {
        (1u64 << width) - 1
    };
    let shift = shift % width;
    if shift == 0 {
        value & mask
    } else {
        ((value >> shift) | (value << (width - shift))) & mask
    }
}

/// 解码 `ORR`/`MOV` 使用的 AArch64 "logical immediate" 位掩码，返回
/// `(immediate, Rn, Rd)`。
///
/// 编码为 N:immr:imms；算法会在 32 或 64 位元素内复制一段循环旋转的全 1。
/// 对保留编码返回 `None`，保证调用方的模式扫描可靠。
fn decode_orr_immediate(instruction: u32) -> Option<(u64, u32, u32)> {
    if instruction >> 31 != 1 || instruction & 0x7f80_0000 != 0x3200_0000 {
        return None;
    }
    // 逻辑立即数编码的三段：N、immr、imms。
    let n = (instruction >> 22) & 1;
    let immr = (instruction >> 16) & 0x3f;
    let imms = (instruction >> 10) & 0x3f;
    let length_source = (n << 6) | ((!imms) & 0x3f);
    if length_source == 0 {
        return None;
    }
    // 元素宽度的对数；小于 1 表示保留编码。
    let length = length_source.ilog2();
    if length < 1 {
        return None;
    }
    let levels = (1u32 << length) - 1;
    let size = imms & levels;
    let rotate = immr & levels;
    if size == levels {
        return None;
    }
    let element_width = 1u32 << length;
    // 在元素宽度内循环旋转出的全 1 片段。
    let element = rotate_right_width((1u64 << (size + 1)) - 1, rotate, element_width);
    // 把元素片段复制到 64 位，得到最终位掩码。
    let mut immediate = 0u64;
    for bit in (0..64).step_by(element_width as usize) {
        immediate |= element << bit;
    }
    Some((immediate, (instruction >> 5) & 31, instruction & 31))
}

/// 从 `arm64_memblock_init` 函数体推断 PAGE_OFFSET。
///
/// `__pa_symbol()` 会被编译成从一个常量中减去符号地址，该常量是符号扩展
/// 后的 PAGE_OFFSET，由 `ORR` 生成。我们接受同时满足以下条件的立即数：
/// (a) 最高位为 1；(b) 其负的 2 的幂大小的置位落在 arm64 VA 范围
/// （36..=52）；(c) 已覆盖内核基址中置位的所有 bit。必须恰好剩一个候选，
/// 否则视为不支持的镜像，而不是猜测。
fn infer_page_offset(
    image: &[u8],
    symbols: &SymbolMap,
    image_base: u64,
    function: &MapSymbol,
    maximum_scan_size: usize,
) -> Result<u64> {
    let (start, end) =
        function_scan_range(image, symbols, image_base, function, maximum_scan_size)?;
    // 所有满足符号扩展与 VA 范围约束的立即数，必须唯一。
    let mut candidates = BTreeSet::<u64>::new();
    for offset in (start..end.saturating_sub(3)).step_by(4) {
        let Some((immediate, source, destination)) = decode_orr_immediate(read_u32(image, offset)?)
        else {
            continue;
        };
        // 立即数的相反数，应是 2 的幂以表示 PAGE_OFFSET 的大小。
        let magnitude = 0u64.wrapping_sub(immediate);
        if source == destination
            && immediate >> 63 == 1
            && magnitude.is_power_of_two()
            && (36..=52).contains(&magnitude.trailing_zeros())
            && image_base & immediate == immediate
        {
            candidates.insert(immediate);
        }
    }
    ensure!(
        candidates.len() == 1,
        "cannot uniquely recover PAGE_OFFSET from {} ({} candidates){}",
        function.name,
        candidates.len(),
        if candidates.is_empty() {
            String::new()
        } else {
            format!(
                ": {}",
                candidates
                    .iter()
                    .map(|value| format!("0x{value:x}"))
                    .collect::<Vec<_>>()
                    .join(", ")
            )
        }
    );
    Ok(*candidates.iter().next().expect("one PAGE_OFFSET"))
}

/// 收集可通过 `names` 中任一名称（含归一化别名）到达的所有地址，用作可
/// 接受的分支目标集合。
fn symbol_addresses(symbols: &SymbolMap, names: &[&str]) -> HashSet<u64> {
    names
        .iter()
        .flat_map(|name| symbols.variants(name))
        .map(|symbol| symbol.address)
        .collect()
}

/// 查找并校验所有将被改写的代码位置。
///
/// bootstrap 由三个钩子驱动：
/// - `kernel_init` 在启动后期调用 `async_synchronize_full()`；该调用被
///   重定向到 bootstrap 入口，从而在 `init` 之前运行。
/// - `load_module` 为模块参数调用 `strndup_user()`；该调用被重定向到一个
///   适配器，把 capsule 中保存的 args 复制成内核可用的字符串。
/// - `arm64_memblock_init` 为内核镜像调用 `memblock_reserve()`；包装函数
///   扩展这段保留区以覆盖追加的 capsule，避免内核复用该内存。
///
/// 这里同时恢复 PAGE_OFFSET，因为 bootstrap 需要它把 capsule 文件偏移
/// 转换为映射后的虚拟地址。
fn analyze_patch_sites(
    image: &[u8],
    symbols: &SymbolMap,
    required: &RequiredSymbols,
) -> Result<PatchSites> {
    let image_base = required.image_base();
    // 接受 async_synchronize_full 及其 cookie 变体作为分支目标。
    let mut async_targets = symbol_addresses(
        symbols,
        &["async_synchronize_full", "async_synchronize_cookie_domain"],
    );
    async_targets.insert(required.async_synchronize_full.address);
    // 只接受 strndup_user 本身。
    let mut strndup_targets = symbol_addresses(symbols, &["strndup_user"]);
    strndup_targets.insert(required.strndup_user.address);
    // 只接受 memblock_reserve 本身。
    let mut memblock_targets = symbol_addresses(symbols, &["memblock_reserve"]);
    memblock_targets.insert(required.memblock_reserve.address);

    let async_call = find_unique_direct_call(
        image,
        symbols,
        image_base,
        &required.kernel_init,
        &async_targets,
        "kernel_init async call",
        0x4000,
    )?;
    let strndup_call = find_unique_direct_call(
        image,
        symbols,
        image_base,
        &required.load_module,
        &strndup_targets,
        "load_module strndup_user call",
        0x10000,
    )?;
    let memblock_reserve_call = find_kernel_image_memblock_reserve_call(
        image,
        symbols,
        image_base,
        &required.arm64_memblock_init,
        &memblock_targets,
        0x8000,
    )?;
    let page_offset = infer_page_offset(
        image,
        symbols,
        image_base,
        &required.arm64_memblock_init,
        0x8000,
    )?;
    ensure!(
        page_offset >= 1 << 63 && page_offset.is_multiple_of(4096),
        "recovered page_offset is not a page-aligned high-half u64"
    );

    Ok(PatchSites {
        async_call,
        strndup_call,
        memblock_reserve_call,
        page_offset,
    })
}

/// 允许位于 `_etext` 处或之后的符号：它们只是链接期标签而非真实函数，
/// 不会使填充区失效。
fn is_text_boundary_symbol(name: &str) -> bool {
    name == "_etext"
        || name.starts_with("__stop_")
        || name.contains("___stop_")
        || name.ends_with("_text_end")
}

/// 在 `.text` 尾部寻找 `required_size` 字节可证明未使用且全零的空间。
///
/// cave 必须位于 `[_stext, _etext)` 内，这样在 `free_initmem` 之后仍处于
/// 可执行映射；并且不能与该范围内的任何真实符号重叠。会尝试两个候选
/// 起点：优先页对齐（使 cave 隔离），退而求其次指令对齐。只有整个尾部
/// 全为零时才接受候选，这证明那里没有活跃代码。
fn find_text_tail_cave(
    image: &[u8],
    image_size: usize,
    symbols: &SymbolMap,
    image_base: u64,
    text_start: &MapSymbol,
    text_end: &MapSymbol,
    required_size: usize,
) -> Result<(usize, usize)> {
    let start = text_start
        .address
        .checked_sub(image_base)
        .and_then(|value| usize::try_from(value).ok())
        .context("_stext is below the Image base")?;
    let end = text_end
        .address
        .checked_sub(image_base)
        .and_then(|value| usize::try_from(value).ok())
        .context("_etext is below the Image base")?;
    ensure!(
        end > start && end <= image_size && end <= image.len() && required_size > 0,
        "cannot establish a file-backed permanent-text range"
    );

    // 从 _etext 向前跨过连续零字节，得到零区起点。
    let mut zero_start = end;
    // 从 `.text` 末尾向前跨过零填充；cave 只能从这段连续零区内部开始。
    while zero_start > start && image[zero_start - 1] == 0 {
        zero_start -= 1;
    }
    // 两个候选起点：页对齐优先，指令对齐兜底。
    let aligned_start = align_up(zero_start, TEXT_CAVE_ALIGNMENT);
    let preferred_start = align_up(zero_start, TEXT_CAVE_PREFERRED_ALIGNMENT);
    // 按优先级排列的候选起点。
    let mut starts = Vec::with_capacity(2);
    if preferred_start < end {
        starts.push(preferred_start);
    }
    if !starts.contains(&aligned_start) {
        starts.push(aligned_start);
    }

    // 记录占用填充区的符号，用于失败诊断。
    let mut rejected_symbols = Vec::new();
    for candidate_start in starts {
        if candidate_start.saturating_add(required_size) > end {
            continue;
        }
        let occupants = symbols
            .entries
            .iter()
            .filter(|entry| {
                entry
                    .address
                    .checked_sub(image_base)
                    .and_then(|value| usize::try_from(value).ok())
                    .is_some_and(|offset| {
                        candidate_start <= offset
                            && offset < end
                            && !is_text_boundary_symbol(&entry.name)
                    })
            })
            .take(8)
            .collect::<Vec<_>>();
        if !occupants.is_empty() {
            rejected_symbols.extend(occupants.into_iter().map(|entry| entry.name.clone()));
            continue;
        }
        if image[candidate_start..end].iter().any(|byte| *byte != 0) {
            continue;
        }
        return Ok((candidate_start, end - candidate_start));
    }

    rejected_symbols.sort();
    rejected_symbols.dedup();
    bail!(
        "cannot find 0x{required_size:x} bytes of proven zero padding before {}{}",
        text_end.name,
        if rejected_symbols.is_empty() {
            String::new()
        } else {
            format!("; symbols in padding: {}", rejected_symbols.join(", "))
        }
    )
}

/// 确保所有带标签的字节区间互不重叠。用于在写入任何字节之前证明 code
/// cave 不会与三处被改写的指令冲突。
fn check_non_overlapping(ranges: &[(usize, usize, &str)]) -> Result<()> {
    for (index, &(start, end, label)) in ranges.iter().enumerate() {
        for &(other_start, other_end, other_label) in &ranges[index + 1..] {
            ensure!(
                start.max(other_start) >= end.min(other_end),
                "{label} overlaps {other_label}"
            );
        }
    }
    Ok(())
}

// 模块 ELF fixup 与 capsule 构造。

/// ELF64 节的极简视图，只保留 fixup 过程需要的字段。
#[derive(Clone, Copy)]
struct ElfSection {
    /// `sh_type`：决定该节是否被当作符号表/字符串表处理。
    section_type: u32,
    /// `sh_offset`：节内容在模块文件中的偏移。
    offset: usize,
    /// `sh_size`：节内容字节数。
    size: usize,
    /// `sh_link`：关联节下标，符号表用它指向字符串表。
    link: usize,
    /// `sh_entsize`：表项的固定大小；0 表示非表节。
    entry_size: usize,
}

/// 从 `data` 读取 NUL 结尾字符串，并以有损方式解码 UTF-8。用于模块符号
/// 名；只有畸形文件才可能出现非 UTF-8。
fn read_c_string(data: &[u8], offset: usize) -> Option<String> {
    let tail = data.get(offset..)?;
    let end = tail.iter().position(|byte| *byte == 0)?;
    Some(String::from_utf8_lossy(&tail[..end]).into_owned())
}

/// 遍历模块的 ELF 符号表，把每个未定义符号解析到恢复出的内核 kallsyms。
///
/// 只有 `SHN_UNDEF` 符号需要 fixup；已定义和绝对符号本身就带有可用值。
/// 每个 fixup 记录符号记录的文件偏移（而不是名称），因为 bootstrap 会
/// 原地改写 `st_value`。无法解析的符号单独返回，让 bootstrap 留给内核
/// 自带的解析器处理。
fn collect_module_fixups(
    module: &[u8],
    symbols: &SymbolMap,
    image_base: u64,
    image_size: usize,
) -> Result<(Vec<Fixup>, Vec<String>)> {
    ensure!(
        module.len() >= 64 && module.starts_with(b"\x7fELF"),
        "module is not an ELF file"
    );
    // EI_CLASS=ELFCLASS64 (2)，EI_DATA=ELFDATA2LSB (1)。
    ensure!(
        module[4] == 2 && module[5] == 1,
        "module must be little-endian ELF64"
    );
    ensure!(
        read_u16(module, 16)? == ET_REL && read_u16(module, 18)? == EM_AARCH64,
        "module must be an ARM64 ET_REL object"
    );

    // ELF64 头偏移：e_shoff=40、e_shentsize=58、e_shnum=60。节头布局：
    // sh_type=+4、sh_offset=+24、sh_size=+32、sh_link=+40、sh_entsize=+56
    // （除注明外均为 u64 字段）。
    // 节头表起点与步长均来自模块自身，必须先做完整边界校验。
    let section_offset = usize::try_from(read_u64(module, 40)?)
        .context("ELF section offset does not fit this host")?;
    let section_entry_size = usize::from(read_u16(module, 58)?);
    let section_count = usize::from(read_u16(module, 60)?);
    ensure!(
        section_count > 0 && section_entry_size >= 64,
        "extended or malformed ELF section tables are unsupported"
    );
    let section_end = section_offset
        .checked_add(
            section_entry_size
                .checked_mul(section_count)
                .context("ELF section table size overflow")?,
        )
        .context("ELF section table offset overflow")?;
    ensure!(
        section_offset <= module.len() && section_end <= module.len(),
        "ELF section table is outside the module"
    );

    // 解析后的节视图：后续符号表、字符串表都通过下标访问。
    let mut sections = Vec::with_capacity(section_count);
    for index in 0..section_count {
        let offset = section_offset + index * section_entry_size;
        let data_offset = usize::try_from(read_u64(module, offset + 24)?)
            .context("ELF section data offset does not fit this host")?;
        let size = usize::try_from(read_u64(module, offset + 32)?)
            .context("ELF section size does not fit this host")?;
        sections.push(ElfSection {
            section_type: read_u32(module, offset + 4)?,
            offset: data_offset,
            size,
            link: usize::try_from(read_u32(module, offset + 40)?)?,
            entry_size: usize::try_from(read_u64(module, offset + 56)?)
                .context("ELF section entry size does not fit this host")?,
        });
    }

    // 待写入 capsule 的符号 fixup。
    let mut fixups = Vec::new();
    // 交给内核模块加载器自行解析的符号名。
    let mut unresolved = HashSet::new();
    // 防止同一符号记录被重复 fixup。
    let mut seen_offsets = HashSet::new();
    for section in sections
        .iter()
        .filter(|section| section.section_type == SHT_SYMTAB)
    {
        ensure!(
            section.entry_size >= 24 && section.size % section.entry_size == 0,
            "malformed ELF symbol table"
        );
        let strings_section = sections
            .get(section.link)
            .context("ELF symbol table has an invalid string table")?;
        let strings_end = strings_section
            .offset
            .checked_add(strings_section.size)
            .context("ELF string table size overflow")?;
        ensure!(
            strings_end <= module.len(),
            "ELF string table is outside the module"
        );
        // 符号名所在的字符串表切片，已校验落在模块内。
        let strings = &module[strings_section.offset..strings_end];

        for index in 1..section.size / section.entry_size {
            let symbol_file_offset = section
                .offset
                .checked_add(index * section.entry_size)
                .context("ELF symbol offset overflow")?;
            ensure!(
                symbol_file_offset.saturating_add(24) <= module.len(),
                "ELF symbol is outside the module"
            );
            // ELF64 符号：st_name=+0 (u32)、st_shndx=+6 (u16)、
            // st_value=+8。
            if read_u16(module, symbol_file_offset + 6)? != SHN_UNDEF {
                continue;
            }
            let name_offset = usize::try_from(read_u32(module, symbol_file_offset)?)?;
            let Some(name) = read_c_string(strings, name_offset).filter(|name| !name.is_empty())
            else {
                continue;
            };
            let Some(target) = symbols.resolve_module_symbol(&name) else {
                unresolved.insert(name);
                continue;
            };
            let Some(kernel_offset) = target.address.checked_sub(image_base) else {
                unresolved.insert(name);
                continue;
            };
            if kernel_offset >= image_size as u64 {
                unresolved.insert(name);
                continue;
            }
            ensure!(
                u32::try_from(symbol_file_offset).is_ok(),
                "module symbol offset does not fit the capsule fixup format"
            );
            if seen_offsets.insert(symbol_file_offset) {
                fixups.push(Fixup {
                    symbol_file_offset,
                    kernel_offset,
                });
            }
        }
    }

    ensure!(
        !fixups.is_empty() || !unresolved.is_empty(),
        "module has no undefined symbols; unexpected kernel module format"
    );
    let mut unresolved = unresolved.into_iter().collect::<Vec<_>>();
    unresolved.sort();
    Ok((fixups, unresolved))
}

/// 序列化供 capsule 使用的 fixup 表。
///
/// 每项 16 字节、小端：u32 符号文件偏移、u32 保留（必须为零）、u64 内核
/// 文件偏移。保留字让 u64 自然对齐，也为将来加入标志位留出空间。
fn pack_fixups(fixups: &[Fixup]) -> Result<Vec<u8>> {
    let mut output = Vec::with_capacity(fixups.len() * 16);
    for fixup in fixups {
        output.extend_from_slice(&u32::try_from(fixup.symbol_file_offset)?.to_le_bytes());
        output.extend_from_slice(&0u32.to_le_bytes());
        output.extend_from_slice(&fixup.kernel_offset.to_le_bytes());
    }
    Ok(output)
}

/// 计算模块字节的 SHA-256，返回原始 32 字节数组。
///
/// `sha256` crate 返回小写十六进制；这里解码回字节，因为 capsule 线上格式
/// 存的是原始摘要。
fn module_sha256(module: &[u8]) -> Result<[u8; 32]> {
    let digest = sha256::digest(module);
    let mut output = [0u8; 32];
    base16ct::lower::decode(digest.as_bytes(), &mut output)
        .map_err(|error| anyhow!("cannot decode module SHA-256: {error}"))?;
    Ok(output)
}

/// 构造追加在内核镜像之后的 capsule。
///
/// 布局（偏移相对 capsule 起点，均为小端）：
///
/// ```text
/// 0x00  magic "KSULKM1\0"（8 字节）
/// 0x08  version (u32)
/// 0x0c  header size (u32, 112)
/// 0x10  capsule size (u64，4096 对齐)
/// 0x18  module offset (u64)
/// 0x20  module size (u64)
/// 0x28  fixup offset (u64)
/// 0x30  fixup count (u64)
/// 0x38  flags (u64)
/// 0x40  module SHA-256（32 字节）
/// 0x60  args offset (u64)
/// 0x68  args size (u64)
/// 0x70  module 字节，随后是 16 字节对齐的 fixup 表，最后是 args
/// ```
///
/// capsule 从原始 `image_size` 之后 16 字节边界开始，最终镜像按
/// `CAPSULE_ALIGNMENT` 填充，以便按页映射。
fn build_capsule(
    image_size: usize,
    module: &[u8],
    fixup_bytes: &[u8],
    fixup_count: usize,
    params: &str,
) -> Result<Capsule> {
    ensure!(
        fixup_bytes.len() == fixup_count.saturating_mul(16),
        "fixup table length does not match its entry count"
    );
    ensure!(
        params.len() <= MAX_MODULE_PARAMS_SIZE && !params.contains('\0'),
        "invalid module params"
    );
    // capsule 起点：紧跟原始 image_size，并按 16 字节对齐。
    let capsule_offset = align_up(image_size, 16);
    // 以下偏移均相对 capsule 起点，与 bootstrap 读取的线上格式一致。
    let module_relative_offset = CAPSULE_HEADER_SIZE;
    // fixup 表紧随模块，并按 16 字节对齐。
    let fixup_relative_offset = module_relative_offset
        .checked_add(align_up(module.len(), 16))
        .context("capsule module offset overflow")?;
    // 参数字符串放在 fixup 表之后。
    let args_relative_offset = align_up(
        fixup_relative_offset
            .checked_add(fixup_bytes.len())
            .context("capsule fixup offset overflow")?,
        16,
    );
    // 以 NUL 结尾，作为 uargs 传给 load_module()
    // 含结尾 NUL，bootstrap 以 C 字符串形式传给 load_module。
    let args_size = params.len() + 1;
    let content_end = args_relative_offset
        .checked_add(args_size)
        .context("capsule params offset overflow")?;
    // 最终镜像大小按页对齐，保证 capsule 可按页映射。
    let new_image_size = align_up(
        capsule_offset
            .checked_add(content_end)
            .context("capsule image size overflow")?,
        CAPSULE_ALIGNMENT,
    );
    // capsule 自身长度包含尾部对齐填充。
    let capsule_size = new_image_size - capsule_offset;
    // 只有存在 fixup 时才声明 SHN_ABS 语义。
    let flags = if fixup_count == 0 {
        0
    } else {
        CAPSULE_FLAG_SHN_ABS_FIXUPS
    };

    // 先在零填充缓冲上写入，未使用区域自然保持为零。
    let mut data = vec![0u8; capsule_size];
    data[..8].copy_from_slice(CAPSULE_MAGIC);
    write_u32(&mut data, 8, CAPSULE_VERSION)?;
    write_u32(&mut data, 12, u32::try_from(CAPSULE_HEADER_SIZE)?)?;
    write_u64(&mut data, 16, u64::try_from(capsule_size)?)?;
    write_u64(&mut data, 24, u64::try_from(module_relative_offset)?)?;
    write_u64(&mut data, 32, u64::try_from(module.len())?)?;
    write_u64(&mut data, 40, u64::try_from(fixup_relative_offset)?)?;
    write_u64(&mut data, 48, u64::try_from(fixup_count)?)?;
    write_u64(&mut data, 56, flags)?;
    data[64..96].copy_from_slice(&module_sha256(module)?);
    write_u64(&mut data, 96, u64::try_from(args_relative_offset)?)?;
    write_u64(&mut data, 104, u64::try_from(args_size)?)?;
    data[module_relative_offset..module_relative_offset + module.len()].copy_from_slice(module);
    data[fixup_relative_offset..fixup_relative_offset + fixup_bytes.len()]
        .copy_from_slice(fixup_bytes);
    // 结尾的 NUL 来自全零填充的 capsule
    data[args_relative_offset..args_relative_offset + params.len()]
        .copy_from_slice(params.as_bytes());

    Ok(Capsule {
        data,
        file_offset: capsule_offset,
        image_size: new_image_size,
        module_offset: capsule_offset + module_relative_offset,
        fixup_offset: capsule_offset + fixup_relative_offset,
        args_offset: capsule_offset + args_relative_offset,
        args_size,
    })
}

// 构建期嵌入的 bootstrap 与运行期极简 ET_REL 链接器。

/// bootstrap 对象中一个节的解析结果。
///
/// `output_offset` 是该节在最终重定位镜像中的偏移；只有可加载节
/// （SHF_ALLOC）才会被赋值。
#[derive(Clone, Debug)]
struct BootstrapSection {
    /// 节名，用于识别 `.text.ksu_bootstrap` 等约定节。
    name: String,
    /// `sh_type`：PROGBITS/NOBITS/SYMTAB/STRTAB/RELA 等。
    section_type: u32,
    /// `sh_flags`：用于确认可加载、可执行、不可写等安全属性。
    flags: u64,
    /// 节内容在嵌入对象中的偏移。
    offset: usize,
    /// 节内容字节数。
    size: usize,
    /// `sh_link`：关联的字符串表或符号表下标。
    link: usize,
    /// `sh_info`：RELA 目标节下标或符号表局部符号数。
    info: usize,
    /// `sh_addralign`：运行期对齐要求，必须为 2 的幂。
    alignment: usize,
    /// `sh_entsize`：表项大小。
    entry_size: usize,
    /// 该节在最终重定位镜像中的偏移；仅 SHF_ALLOC 节有值。
    output_offset: Option<usize>,
}

/// bootstrap 符号表中的一项。
#[derive(Debug)]
struct BootstrapSymbol {
    /// 符号名，重定位时用于匹配内核侧定义。
    name: String,
    /// `st_value`：节内偏移（SHN_ABS 时是绝对值）。
    value: u64,
    /// `st_shndx`：所属节索引或 SHN_UNDEF/SHN_ABS。
    section_index: u16,
}

/// 解析后的嵌入 bootstrap；`data` 仍指向编译期嵌入的只读字节。
///
/// bootstrap 是随 ksud 一起构建的极小 AArch64 ET_REL 对象。内核里没有用户态
/// 动态链接器，所以补丁阶段必须在这里完成重定位，并把结果直接写进内核
/// `.text` 的零填充区。
#[derive(Debug)]
struct BootstrapObject<'a> {
    /// 编译期嵌入的只读 ELF 字节，解析期间不复制。
    data: &'a [u8],
    /// 解析后的节表，包含输出的相对布局。
    sections: Vec<BootstrapSection>,
    /// 解析后的符号表。
    symbols: Vec<BootstrapSymbol>,
    /// 唯一符号表节的下标，重定位必须引用它。
    symbol_table_index: usize,
    /// 所有可加载节连续布局后的总字节数。
    image_size: usize,
}

/// 完成重定位后的 bootstrap 代码及其三个关键入口地址。
///
/// `entry_address` 接管 `kernel_init` 中的 async 调用；另外两个地址分别用于
/// 替换 `memblock_reserve` 调用和 `strndup_user` 调用。
#[derive(Debug)]
struct LinkedBootstrap {
    /// 重定位完成的机器码，将原样写入 code cave。
    data: Vec<u8>,
    /// 接管 `kernel_init` async 调用的入口地址。
    entry_address: u64,
    /// 包装 `memblock_reserve` 的入口地址。
    reserve_wrapper_address: u64,
    /// 适配 `strndup_user` 的入口地址。
    strndup_adapter_address: u64,
}

/// 带溢出检查的 2 的幂对齐；节对齐来自 ELF 文件，必须先验证。
fn checked_align_up(value: usize, alignment: usize) -> Result<usize> {
    ensure!(
        alignment.is_power_of_two(),
        "ELF section alignment is invalid"
    );
    value
        .checked_add(alignment - 1)
        .map(|aligned| aligned & !(alignment - 1))
        .context("ELF section layout overflow")
}

/// 读取 ELF 字符串表中的一个 UTF-8 字符串。bootstrap 由本项目自己构建，
/// 因此非 UTF-8 直接视为错误，而不是有损解码。
fn read_elf_string(data: &[u8], offset: usize) -> Result<&str> {
    let tail = data
        .get(offset..)
        .with_context(|| format!("ELF string offset 0x{offset:x} is outside its table"))?;
    let end = tail
        .iter()
        .position(|byte| *byte == 0)
        .context("unterminated ELF string")?;
    std::str::from_utf8(&tail[..end]).context("ELF string is not UTF-8")
}

impl<'a> BootstrapObject<'a> {
    /// 解析并严格校验嵌入的 bootstrap。
    ///
    /// 只接受由本项目构建脚本产出的形状：ELF64、小端、AArch64 ET_REL、恰好
    /// 一个 `.text.ksu_bootstrap`、至多一个 `.rodata.ksu_bootstrap`，且除这两
    /// 个节外不允许其他可加载节。其余情况一律拒绝，避免把任意对象代码写进
    /// 内核。
    fn parse(data: &'a [u8]) -> Result<Self> {
        ensure!(
            data.len() >= 64 && data.starts_with(b"\x7fELF"),
            "embedded bootstrap is not an ELF file"
        );
        ensure!(
            data[4] == 2 && data[5] == 1 && data[6] == 1,
            "embedded bootstrap must be little-endian ELF64"
        );
        ensure!(
            read_u16(data, 16)? == ET_REL && read_u16(data, 18)? == EM_AARCH64,
            "embedded bootstrap must be an AArch64 ET_REL object"
        );
        ensure!(read_u32(data, 20)? == 1, "unsupported ELF version");
        ensure!(
            usize::from(read_u16(data, 52)?) >= 64 && read_u16(data, 56)? == 0,
            "malformed bootstrap ELF header"
        );

        // 节头表位置与项大小来自嵌入对象，仍需完整边界校验。
        let section_offset = usize::try_from(read_u64(data, 40)?)
            .context("bootstrap section offset does not fit this host")?;
        let section_entry_size = usize::from(read_u16(data, 58)?);
        let section_count = usize::from(read_u16(data, 60)?);
        let section_names_index = usize::from(read_u16(data, 62)?);
        ensure!(
            section_count > 0
                && section_entry_size == ELF64_SECTION_SIZE
                && section_names_index < section_count,
            "extended or malformed bootstrap section tables are unsupported"
        );
        let section_table_size = section_entry_size
            .checked_mul(section_count)
            .context("bootstrap section table size overflow")?;
        ensure!(
            section_offset
                .checked_add(section_table_size)
                .is_some_and(|end| end <= data.len()),
            "bootstrap section table is outside the object"
        );

        // 所有节的解析结果；名称稍后从字符串表回填。
        let mut sections = Vec::with_capacity(section_count);
        // 暂存 sh_name 偏移，等节名字符串表定位后统一解析。
        let mut name_offsets = Vec::with_capacity(section_count);
        for index in 0..section_count {
            let offset = section_offset + index * section_entry_size;
            let section_type = read_u32(data, offset + 4)?;
            let data_offset = usize::try_from(read_u64(data, offset + 24)?)
                .context("bootstrap section data offset does not fit this host")?;
            let size = usize::try_from(read_u64(data, offset + 32)?)
                .context("bootstrap section size does not fit this host")?;
            let alignment = usize::try_from(read_u64(data, offset + 48)?)
                .context("bootstrap section alignment does not fit this host")?
                .max(1);
            ensure!(
                alignment.is_power_of_two(),
                "bootstrap section {index} has invalid alignment {alignment}"
            );
            if section_type != SHT_NOBITS {
                ensure!(
                    data_offset
                        .checked_add(size)
                        .is_some_and(|end| end <= data.len()),
                    "bootstrap section {index} is outside the object"
                );
            }
            name_offsets.push(usize::try_from(read_u32(data, offset)?)?);
            sections.push(BootstrapSection {
                name: String::new(),
                section_type,
                flags: read_u64(data, offset + 8)?,
                offset: data_offset,
                size,
                link: usize::try_from(read_u32(data, offset + 40)?)?,
                info: usize::try_from(read_u32(data, offset + 44)?)?,
                alignment,
                entry_size: usize::try_from(read_u64(data, offset + 56)?)
                    .context("bootstrap section entry size does not fit this host")?,
                output_offset: None,
            });
        }

        // e_shstrndx 指向的节名字符串表。
        let section_names = sections
            .get(section_names_index)
            .context("invalid bootstrap section-name table")?;
        ensure!(
            section_names.section_type == SHT_STRTAB,
            "bootstrap section-name table is not a string table"
        );
        let section_name_data = get_slice(data, section_names.offset, section_names.size)?;
        for (section, name_offset) in sections.iter_mut().zip(name_offsets) {
            read_elf_string(section_name_data, name_offset)?.clone_into(&mut section.name);
        }

        let find_section = |name: &str| -> Result<Option<usize>> {
            let matches = sections
                .iter()
                .enumerate()
                .filter(|(_, section)| section.name == name)
                .map(|(index, _)| index)
                .collect::<Vec<_>>();
            ensure!(
                matches.len() <= 1,
                "bootstrap must contain at most one {name} section"
            );
            Ok(matches.first().copied())
        };
        let text_index = find_section(".text.ksu_bootstrap")?
            .context("bootstrap must contain exactly one .text.ksu_bootstrap section")?;
        // 可选；bootstrap 目前把所有常量都保存在字面量池中
        let rodata_index = find_section(".rodata.ksu_bootstrap")?;
        let text = &sections[text_index];
        ensure!(
            text.section_type == SHT_PROGBITS
                && text.flags & (SHF_ALLOC | SHF_EXECINSTR) == (SHF_ALLOC | SHF_EXECINSTR)
                && text.flags & SHF_WRITE == 0,
            "bootstrap text section has unsafe flags or type"
        );
        if let Some(rodata_index) = rodata_index {
            let rodata = &sections[rodata_index];
            ensure!(
                rodata.section_type == SHT_PROGBITS
                    && rodata.flags & SHF_ALLOC != 0
                    && rodata.flags & (SHF_WRITE | SHF_EXECINSTR) == 0,
                "bootstrap rodata section has unsafe flags or type"
            );
        }
        for (index, section) in sections.iter().enumerate() {
            ensure!(
                section.size == 0
                    || section.flags & SHF_ALLOC == 0
                    || index == text_index
                    || Some(index) == rodata_index,
                "unsupported allocatable bootstrap section {}",
                section.name
            );
        }

        let text_size = text.size;
        // 可加载节的连续布局大小；.text 从偏移 0 开始。
        let mut image_size = text_size;
        sections[text_index].output_offset = Some(0);
        if let Some(rodata_index) = rodata_index {
            let rodata = &sections[rodata_index];
            let rodata_output = checked_align_up(text_size, rodata.alignment)?;
            image_size = rodata_output
                .checked_add(rodata.size)
                .context("bootstrap image size overflow")?;
            sections[rodata_index].output_offset = Some(rodata_output);
        }
        ensure!(image_size > 0, "bootstrap has no loadable bytes");

        // 只允许一个符号表，避免重定位引用产生歧义。
        let symbol_tables = sections
            .iter()
            .enumerate()
            .filter(|(_, section)| section.section_type == SHT_SYMTAB)
            .map(|(index, _)| index)
            .collect::<Vec<_>>();
        ensure!(
            symbol_tables.len() == 1,
            "bootstrap must contain exactly one symbol table"
        );
        let symbol_table_index = symbol_tables[0];
        let symbol_table = &sections[symbol_table_index];
        ensure!(
            symbol_table.entry_size == ELF64_SYMBOL_SIZE
                && symbol_table.size.is_multiple_of(ELF64_SYMBOL_SIZE),
            "malformed bootstrap symbol table"
        );
        let symbol_strings = sections
            .get(symbol_table.link)
            .context("bootstrap symbol table has an invalid string table")?;
        ensure!(
            symbol_strings.section_type == SHT_STRTAB,
            "bootstrap symbol names are not in a string table"
        );
        let symbol_string_data = get_slice(data, symbol_strings.offset, symbol_strings.size)?;
        let symbol_count = symbol_table.size / ELF64_SYMBOL_SIZE;
        ensure!(
            symbol_table.info <= symbol_count,
            "bootstrap symbol table has invalid local symbol count"
        );
        // 解析后的符号项，供重定位按索引取值。
        let mut symbols = Vec::with_capacity(symbol_count);
        for index in 0..symbol_count {
            let offset = symbol_table.offset + index * ELF64_SYMBOL_SIZE;
            let name = read_elf_string(
                symbol_string_data,
                usize::try_from(read_u32(data, offset)?)?,
            )?
            .to_owned();
            let section_index = read_u16(data, offset + 6)?;
            ensure!(
                section_index == SHN_UNDEF
                    || section_index == SHN_ABS
                    || usize::from(section_index) < sections.len(),
                "bootstrap symbol {name:?} uses an unsupported section index"
            );
            let value = read_u64(data, offset + 8)?;
            if section_index != SHN_UNDEF && section_index != SHN_ABS {
                ensure!(
                    value <= sections[usize::from(section_index)].size as u64,
                    "bootstrap symbol {name:?} is outside its section"
                );
            }
            symbols.push(BootstrapSymbol {
                name,
                value,
                section_index,
            });
        }

        // 统计 RELA 条数；没有重定位的 bootstrap 没有意义。
        let mut relocation_count = 0usize;
        ensure!(
            sections
                .iter()
                .all(|section| section.section_type != SHT_REL),
            "bootstrap uses unsupported REL relocations"
        );
        for section in sections
            .iter()
            .filter(|section| section.section_type == SHT_RELA)
        {
            ensure!(
                section.entry_size == ELF64_RELA_SIZE
                    && section.size.is_multiple_of(ELF64_RELA_SIZE)
                    && section.link == symbol_table_index,
                "malformed bootstrap relocation section {}",
                section.name
            );
            let target = sections
                .get(section.info)
                .context("bootstrap relocation section has an invalid target")?;
            ensure!(
                target.output_offset.is_some(),
                "bootstrap relocation section {} targets non-loadable section {}",
                section.name,
                target.name
            );
            relocation_count = relocation_count
                .checked_add(section.size / ELF64_RELA_SIZE)
                .context("bootstrap relocation count overflow")?;
        }
        ensure!(relocation_count > 0, "bootstrap has no relocations");

        Ok(Self {
            data,
            sections,
            symbols,
            symbol_table_index,
            image_size,
        })
    }

    /// 返回可加载节的连续布局大小。
    const fn image_size(&self) -> usize {
        self.image_size
    }

    /// 计算可加载节在最终代码区中的虚拟地址。
    fn section_address(&self, section_index: usize, code_address: u64) -> Result<u64> {
        let section = self
            .sections
            .get(section_index)
            .context("bootstrap symbol has an invalid section")?;
        let output_offset = section.output_offset.with_context(|| {
            format!(
                "bootstrap symbol refers to non-loadable section {}",
                section.name
            )
        })?;
        code_address
            .checked_add(output_offset as u64)
            .context("bootstrap section address overflow")
    }

    /// 求一个符号在最终地址空间中的值。
    ///
    /// - `SHN_UNDEF`：必须能在 `definitions` 中找到内核侧地址。
    /// - `SHN_ABS`：直接使用符号自带的绝对值。
    /// - 其他节索引：节基址加上符号在节内的偏移。
    fn symbol_value(
        &self,
        symbol_index: usize,
        code_address: u64,
        definitions: &BTreeMap<&str, u64>,
    ) -> Result<u64> {
        let symbol = self
            .symbols
            .get(symbol_index)
            .context("bootstrap relocation has an invalid symbol index")?;
        match symbol.section_index {
            SHN_UNDEF => definitions
                .get(symbol.name.as_str())
                .copied()
                .with_context(|| {
                    format!("bootstrap requires missing definition {:?}", symbol.name)
                }),
            SHN_ABS => Ok(symbol.value),
            section_index => self
                .section_address(usize::from(section_index), code_address)?
                .checked_add(symbol.value)
                .context("bootstrap symbol address overflow"),
        }
    }

    /// 按名称查找唯一定义的符号并求值；重复或缺失都视为错误，因为三个入口
    /// 地址不允许有歧义。
    fn named_symbol_value(
        &self,
        name: &str,
        code_address: u64,
        definitions: &BTreeMap<&str, u64>,
    ) -> Result<u64> {
        let matches = self
            .symbols
            .iter()
            .enumerate()
            .filter(|(_, symbol)| symbol.name == name)
            .map(|(index, _)| index)
            .collect::<Vec<_>>();
        ensure!(
            matches.len() == 1,
            "bootstrap must define exactly one symbol {name}"
        );
        let symbol = &self.symbols[matches[0]];
        ensure!(
            symbol.section_index != SHN_UNDEF,
            "bootstrap symbol {name} is undefined"
        );
        self.symbol_value(matches[0], code_address, definitions)
    }

    /// 把解析后的 bootstrap 链接到 `code_address`。
    ///
    /// 步骤：按 `output_offset` 布局可加载节并校验运行期对齐；复制节内容；
    /// 逐条应用 RELA 重定位（`definitions` 提供内核侧符号地址）；最后解析
    /// 三个导出入口。所有重定位都在补丁阶段完成，运行期不再有链接开销。
    fn link(
        &self,
        code_address: u64,
        definitions: &BTreeMap<&str, u64>,
    ) -> Result<LinkedBootstrap> {
        for section in self
            .sections
            .iter()
            .filter(|section| section.output_offset.is_some())
        {
            let address = code_address
                .checked_add(section.output_offset.expect("checked above") as u64)
                .context("bootstrap section address overflow")?;
            ensure!(
                address.is_multiple_of(section.alignment as u64),
                "bootstrap section {} is not aligned at 0x{address:x}",
                section.name
            );
        }

        // 最终代码缓冲区，大小与可加载节布局一致。
        let mut output = vec![0u8; self.image_size];
        for section in self
            .sections
            .iter()
            .filter(|section| section.output_offset.is_some())
        {
            let output_offset = section.output_offset.expect("checked above");
            get_slice_mut(&mut output, output_offset, section.size)?.copy_from_slice(get_slice(
                self.data,
                section.offset,
                section.size,
            )?);
        }

        for relocation_section in self
            .sections
            .iter()
            .filter(|section| section.section_type == SHT_RELA)
        {
            ensure!(
                relocation_section.link == self.symbol_table_index,
                "bootstrap relocation uses an unexpected symbol table"
            );
            let target_section = &self.sections[relocation_section.info];
            // 重定位写入点在目标节内的偏移。
            let target_output = target_section
                .output_offset
                .context("bootstrap relocation target is not loadable")?;
            // 目标节在最终地址空间中的基址。
            let target_address = self.section_address(relocation_section.info, code_address)?;
            for index in 0..relocation_section.size / ELF64_RELA_SIZE {
                let offset = relocation_section.offset + index * ELF64_RELA_SIZE;
                let target_offset = usize::try_from(read_u64(self.data, offset)?)
                    .context("bootstrap relocation offset does not fit this host")?;
                let info = read_u64(self.data, offset + 8)?;
                let relocation_type = info as u32;
                let symbol_index = usize::try_from(info >> 32)?;
                // RELA 显式 addend，可为负。
                let addend = read_i64(self.data, offset + 16)?;
                // 写入宽度由重定位类型决定，用于越界检查。
                let width = match relocation_type {
                    R_AARCH64_ABS64 => 8,
                    R_AARCH64_ABS32
                    | R_AARCH64_CALL26
                    | R_AARCH64_JUMP26
                    | R_AARCH64_ADR_PREL_PG_HI21
                    | R_AARCH64_ADD_ABS_LO12_NC => 4,
                    _ => bail!("unsupported AArch64 bootstrap relocation {relocation_type}"),
                };
                ensure!(
                    target_offset
                        .checked_add(width)
                        .is_some_and(|end| end <= target_section.size),
                    "bootstrap relocation is outside section {}",
                    target_section.name
                );
                let output_offset = target_output
                    .checked_add(target_offset)
                    .context("bootstrap relocation output offset overflow")?;
                let place_address = target_address
                    .checked_add(target_offset as u64)
                    .context("bootstrap relocation address overflow")?;
                // 符号在最终地址空间中的值。
                let symbol_value = self.symbol_value(symbol_index, code_address, definitions)?;
                let symbol_name = &self.symbols[symbol_index].name;
                apply_bootstrap_relocation(
                    &mut output,
                    output_offset,
                    place_address,
                    relocation_type,
                    symbol_value,
                    addend,
                )
                .with_context(|| {
                    format!(
                        "cannot relocate {}+0x{target_offset:x} against {symbol_name:?}",
                        target_section.name
                    )
                })?;
            }
        }

        Ok(LinkedBootstrap {
            entry_address: self.named_symbol_value("ksu_bootstrap", code_address, definitions)?,
            reserve_wrapper_address: self.named_symbol_value(
                "ksu_memblock_reserve_wrapper",
                code_address,
                definitions,
            )?,
            strndup_adapter_address: self.named_symbol_value(
                "ksu_strndup_user_adapter",
                code_address,
                definitions,
            )?,
            data: output,
        })
    }
}

/// 应用一条 AArch64 RELA 重定位。
///
/// 只支持 bootstrap 实际用到的几种类型：绝对地址、26 位分支和 ADRP/ADD
/// 页地址对。每种类型都会先校验写入位置的对齐以及目标指令的操作码，防止
/// 在错误的指令上写入，导致内核在启动时执行非法代码。
fn apply_bootstrap_relocation(
    output: &mut [u8],
    output_offset: usize,
    place_address: u64,
    relocation_type: u32,
    symbol_value: u64,
    addend: i64,
) -> Result<()> {
    // 用 i128 求和，避免 64 位地址加上负 addend 时溢出。
    // 用 i128 中间值避免 64 位地址与负 addend 相加时溢出。
    let value = i128::from(symbol_value) + i128::from(addend);
    match relocation_type {
        R_AARCH64_ABS64 => {
            ensure!(
                output_offset.is_multiple_of(8),
                "unaligned ABS64 relocation"
            );
            write_u64(
                output,
                output_offset,
                u64::try_from(value).context("ABS64 relocation overflow")?,
            )
        }
        R_AARCH64_ABS32 => {
            ensure!(
                output_offset.is_multiple_of(4),
                "unaligned ABS32 relocation"
            );
            write_u32(
                output,
                output_offset,
                u32::try_from(value).context("ABS32 relocation overflow")?,
            )
        }
        R_AARCH64_CALL26 | R_AARCH64_JUMP26 => {
            // bit 31..26 是操作码（BL=0b100101，B=0b000101），低 26 位是
            // 以 4 字节为单位的 PC 相对位移。
            // 26 位字段以 4 字节为单位存储 PC 相对位移。
            let delta = value - i128::from(place_address);
            ensure!(delta % 4 == 0, "branch target is not instruction-aligned");
            let immediate = delta / 4;
            ensure!(
                (-(1i128 << 25)..(1i128 << 25)).contains(&immediate),
                "branch target is outside the AArch64 26-bit range"
            );
            let instruction = read_u32(output, output_offset)?;
            let opcode = if relocation_type == R_AARCH64_CALL26 {
                0x9400_0000
            } else {
                0x1400_0000
            };
            ensure!(
                instruction & 0xfc00_0000 == opcode,
                "branch relocation does not target the expected instruction"
            );
            let encoded = u32::try_from((immediate as i64 as u64) & 0x03ff_ffff)?;
            write_u32(output, output_offset, opcode | encoded)
        }
        R_AARCH64_ADR_PREL_PG_HI21 => {
            let target = u64::try_from(value).context("ADRP relocation overflow")?;
            // ADRP 以页为单位编码相对位移。
            let page_delta =
                (i128::from(target & !0xfff) - i128::from(place_address & !0xfff)) / 4096;
            ensure!(
                (-(1i128 << 20)..(1i128 << 20)).contains(&page_delta),
                "ADRP target is outside the AArch64 21-bit page range"
            );
            let instruction = read_u32(output, output_offset)?;
            ensure!(
                instruction & 0x9f00_0000 == 0x9000_0000,
                "ADRP relocation does not target an ADRP instruction"
            );
            let immediate = (page_delta as i64 as u64) & 0x1f_ffff;
            // ADRP 把 21 位页位移拆成 immlo（bit 30:29）和 immhi（bit 23:5）。
            let immlo = u32::try_from((immediate & 0x3) << 29)?;
            let immhi = u32::try_from(((immediate >> 2) & 0x7_ffff) << 5)?;
            write_u32(
                output,
                output_offset,
                (instruction & !0x60ff_ffe0) | immlo | immhi,
            )
        }
        R_AARCH64_ADD_ABS_LO12_NC => {
            let target = u64::try_from(value).context("ADD relocation overflow")?;
            let instruction = read_u32(output, output_offset)?;
            ensure!(
                instruction & 0x7f40_0000 == 0x1100_0000,
                "ADD relocation does not target an unshifted ADD-immediate instruction"
            );
            let immediate = u32::try_from((target & 0xfff) << 10)?;
            // ADD-immediate 的 12 位立即数位于 bit 21:10。
            write_u32(
                output,
                output_offset,
                (instruction & !0x003f_fc00) | immediate,
            )
        }
        _ => bail!("unsupported AArch64 bootstrap relocation {relocation_type}"),
    }
}

// 原始 ARM64 Image 注入。

/// 把模块注入原始 ARM64 Image，返回新的镜像字节和诊断报告。
///
/// 前置条件：`original_image` 是未压缩的 ARM64 Image，且 `len() <= image_size`
/// （其后的追加元数据不受支持）。流程分三阶段：
///
/// 1. 分析：恢复 kallsyms/BTF、解析必需符号、识别 GKI ABI、定位三处补丁点，
///    并把模块的未定义符号解析成 fixup。
/// 2. 构造：在 `image_size` 之后生成 capsule，并在 `.text` 尾部找到 code cave，
///    把 bootstrap 重定位到该地址。
/// 3. 写入：复制 bootstrap、改写三处 `BL`、扩展镜像到页对齐的新大小并更新
///    头部的 `image_size`。
///
/// capsule 位于原始 `image_size` 之后，bootstrap 只写入 `.text` 内已证明全零的
/// 填充区；写入前还会证明 code cave 与三处补丁点互不重叠，因此不会破坏原有
/// 内核内容。
fn inject_image(
    original_image: &[u8],
    module: &[u8],
    params: &str,
) -> Result<(Vec<u8>, ImageInjectionReport)> {
    // 在副本上修改，失败时不会污染调用方数据。
    let mut image = original_image.to_vec();
    // 头部声明的可加载大小，是 capsule 与 cave 的布局基准。
    let image_size = parse_arm64_image_size(&image)?;
    // 超出 image_size 的字节通常是追加的 DTB/元数据，重新打包会丢失，直接拒绝。
    ensure!(
        image.len() <= image_size,
        "input has bytes beyond ARM64 image_size; appended DTB/metadata is unsupported"
    );

    let RecoveredKernelMetadata {
        kallsyms:
            RecoveredKallsyms {
                symbols,
                layout: kallsyms_layout,
                count: kallsyms_count,
            },
        btf: kernel_btf,
    } = recover_arm64_kernel_metadata(&image)?;
    let required = RequiredSymbols::resolve(&symbols)?;
    required.validate_image_bounds(image_size)?;
    let image_base = required.image_base();
    let (kernel_release, gki_abi) = recover_gki_abi(
        &image,
        image_base,
        &required.linux_banner,
        kernel_btf.as_ref(),
    )?;
    let sites = analyze_patch_sites(&image, &symbols, &required)?;

    let (fixups, unresolved) = collect_module_fixups(module, &symbols, image_base, image_size)?;
    let fixup_bytes = pack_fixups(&fixups)?;
    // capsule 布局在写代码前确定，后续地址都由它推导。
    let capsule = build_capsule(image_size, module, &fixup_bytes, fixups.len(), params)?;
    let reserve_extension = capsule
        .image_size
        .checked_sub(image_size)
        .context("capsule did not extend the Image")?;

    // 键名必须与 bootstrap 的 extern 符号完全一致。
    let mut definitions = BTreeMap::<&str, u64>::new();
    // bootstrap 通过符号名读取这些编译期常量；键名必须与 bootstrap 源码中的
    // extern 名称严格一致。capsule 内的偏移都是 capsule 相对偏移。
    definitions.insert(
        "ksu_ext_memblock_reserve",
        sites.memblock_reserve_call.target,
    );
    definitions.insert("ksu_ext_memstart_addr", required.memstart_addr.address);
    definitions.insert("ksu_ext_kimage_voffset", required.kimage_voffset.address);
    definitions.insert("ksu_ext_async_synchronize_full", sites.async_call.target);
    definitions.insert("ksu_ext_vmalloc", required.vmalloc.address);
    definitions.insert("ksu_ext_memcpy", required.memcpy.address);
    definitions.insert("ksu_ext_load_module", required.load_module.address);
    definitions.insert("ksu_ext_kstrdup", required.kstrdup.address);
    definitions.insert("ksu_ext_strndup_user", sites.strndup_call.target);
    definitions.insert("ksu_image_base", image_base);
    definitions.insert("ksu_capsule_magic", u64::from_le_bytes(*CAPSULE_MAGIC));
    definitions.insert("ksu_capsule_version", u64::from(CAPSULE_VERSION));
    definitions.insert("ksu_capsule_header_size", CAPSULE_HEADER_SIZE as u64);
    definitions.insert("ksu_capsule_image_offset", capsule.file_offset as u64);
    definitions.insert("ksu_capsule_size", capsule.data.len() as u64);
    definitions.insert(
        "ksu_module_capsule_offset",
        capsule.module_offset.saturating_sub(capsule.file_offset) as u64,
    );
    definitions.insert(
        "ksu_fixup_capsule_offset",
        capsule.fixup_offset.saturating_sub(capsule.file_offset) as u64,
    );
    definitions.insert(
        "ksu_args_capsule_offset",
        capsule.args_offset.saturating_sub(capsule.file_offset) as u64,
    );
    definitions.insert("ksu_args_size", capsule.args_size as u64);
    definitions.insert("ksu_reserve_extension", reserve_extension as u64);
    definitions.insert("ksu_page_offset", sites.page_offset);
    definitions.insert("ksu_module_size", module.len() as u64);
    definitions.insert("ksu_fixup_count", fixups.len() as u64);
    definitions.insert("ksu_load_info_size", gki_abi.load_info_storage_size);
    definitions.insert("ksu_load_info_hdr_offset", gki_abi.load_info_hdr_offset);
    definitions.insert("ksu_load_info_len_offset", gki_abi.load_info_len_offset);
    definitions.insert("ksu_gfp_kernel", gki_abi.gfp_kernel);

    let bootstrap_object = BootstrapObject::parse(BOOTSTRAP_OBJECT)
        .context("cannot parse the embedded LKM bootstrap")?;
    // cave 在 Image 文件中的偏移，也是 bootstrap 的落点。
    let (code_offset, code_cave_size) = find_text_tail_cave(
        &image,
        image_size,
        &symbols,
        image_base,
        &required.text_start,
        &required.text_end,
        bootstrap_object.image_size(),
    )?;
    // cave 的虚拟地址，链接器据此计算所有重定位。
    let code_address = image_base
        .checked_add(code_offset as u64)
        .context("bootstrap address overflow")?;
    // 已完成重定位的 bootstrap 字节与三个入口地址。
    let bootstrap = bootstrap_object
        .link(code_address, &definitions)
        .context("cannot relocate the embedded LKM bootstrap")?;
    // 入口必须正好落在 cave 起点：bootstrap 的首条指令就是接管 async 调用后
    // 的执行流。
    let bootstrap_address = bootstrap.entry_address;
    let adapter_address = bootstrap.strndup_adapter_address;
    let reserve_wrapper_address = bootstrap.reserve_wrapper_address;
    ensure!(
        bootstrap_address == code_address,
        "linked bootstrap address does not match the selected text cave"
    );
    ensure!(
        bootstrap.data.len() <= code_cave_size,
        "code cave needs 0x{:x} bytes, cave has 0x{code_cave_size:x}",
        bootstrap.data.len()
    );
    // 写入前先证明 cave 与三处补丁指令互不重叠，避免自覆盖。
    check_non_overlapping(&[
        (code_offset, code_offset + code_cave_size, "code cave"),
        (
            sites.async_call.file_offset,
            sites.async_call.file_offset + 4,
            "kernel_init patch",
        ),
        (
            sites.strndup_call.file_offset,
            sites.strndup_call.file_offset + 4,
            "load_module patch",
        ),
        (
            sites.memblock_reserve_call.file_offset,
            sites.memblock_reserve_call.file_offset + 4,
            "memblock reserve patch",
        ),
    ])?;

    get_slice_mut(&mut image, code_offset, bootstrap.data.len())?.copy_from_slice(&bootstrap.data);
    // 三处 BL 分别重定向到 bootstrap 入口、strndup 适配器和
    // memblock_reserve 包装函数。
    write_u32(
        &mut image,
        sites.async_call.file_offset,
        encode_bl(sites.async_call.address, bootstrap_address)?,
    )?;
    write_u32(
        &mut image,
        sites.strndup_call.file_offset,
        encode_bl(sites.strndup_call.address, adapter_address)?,
    )?;
    write_u32(
        &mut image,
        sites.memblock_reserve_call.file_offset,
        encode_bl(sites.memblock_reserve_call.address, reserve_wrapper_address)?,
    )?;
    // 先补齐到原始 image_size，再补齐到 capsule 起始的 16 字节边界，最后追加
    // capsule。中间的零填充保证 capsule 对齐且不会覆盖原有内容。
    image.resize(image_size, 0);
    image.resize(capsule.file_offset, 0);
    image.extend_from_slice(&capsule.data);
    ensure!(
        image.len() == capsule.image_size,
        "internal error: capsule length does not match new image_size"
    );
    // 更新头部的 image_size；这是内核计算可加载范围的唯一依据。
    write_u64(
        &mut image,
        ARM64_IMAGE_SIZE_OFFSET,
        capsule.image_size as u64,
    )?;

    Ok((
        image,
        ImageInjectionReport {
            kernel_release,
            btf: kernel_btf.map(|btf| BtfReport {
                file_offset: btf.file_offset,
                size: btf.size,
                type_count: btf.type_count,
            }),
            kallsyms_layout,
            kallsyms_count,
            load_info_structure_size: gki_abi.load_info_structure_size,
            load_info_storage_size: gki_abi.load_info_storage_size,
            load_info_hdr_offset: gki_abi.load_info_hdr_offset,
            load_info_len_offset: gki_abi.load_info_len_offset,
            code_offset,
            code_size: bootstrap.data.len(),
            memblock_call_offset: sites.memblock_reserve_call.file_offset,
            page_offset: sites.page_offset,
            fixup_count: fixups.len(),
            unresolved,
            image_size: capsule.image_size,
        },
    ))
}

// boot-patch-v2 顶层编排。

/// 根据 KMI（例如 "android12-5.10"）拼出内置模块的资源名。
///
/// Android 构建中资源直接位于 assets 根目录；主机侧（测试/开发）则放在
/// `aarch64/` 子目录，与发布产物的布局一致。
fn embedded_module_name(kmi: &str) -> String {
    #[cfg(target_os = "android")]
    {
        format!("{kmi}_kernelsu.ko")
    }
    #[cfg(not(target_os = "android"))]
    {
        format!("aarch64/{kmi}_kernelsu.ko")
    }
}

/// 执行 `boot-patch-v2`：解析 boot 镜像、注入模块、重新打包并原子写出。
///
/// 安全约束：输出路径不能与任何输入（boot 镜像或模块）相同；若输出已存在，
/// 必须显式传入 `--force`。写出时先在同目录创建临时文件，成功后再
/// `persist`，避免中途失败留下半个 boot 镜像。
///
/// 只有内核块会被替换；boot header、ramdisk、vendor_boot 分片和 AVB footer
/// 结构由 `android_bootimg` 保留并重新打包。AVB 签名不会重新生成，改动内核后
/// 原签名即失效，需要调用方另行签名或关闭校验。
pub fn patch_boot(args: &BootPatchV2Args) -> Result<()> {
    ensure!(
        args.boot.is_file(),
        "boot image does not exist: {}",
        args.boot.display()
    );
    if let Some(module) = &args.module {
        ensure!(
            module.is_file(),
            "kernel module does not exist: {}",
            module.display()
        );
    }
    if args.output.exists() {
        // 先规范化路径，防止通过相对路径或符号链接绕过“不得覆盖输入”的检查。
        let output = fs::canonicalize(&args.output)
            .with_context(|| format!("cannot resolve output {}", args.output.display()))?;
        let mut inputs = vec![("boot image", &args.boot)];
        if let Some(module) = &args.module {
            inputs.push(("kernel module", module));
        }
        for (label, input) in inputs {
            let input = fs::canonicalize(input)
                .with_context(|| format!("cannot resolve {label} {}", input.display()))?;
            ensure!(output != input, "refusing to overwrite the input {label}");
        }
        ensure!(
            args.force,
            "output already exists: {}; use --force",
            args.output.display()
        );
    }

    println!("- Reading boot image");
    // 原始 boot 镜像字节，重新打包时用于保留未修改的部分。
    let boot_data = fs::read(&args.boot)
        .with_context(|| format!("cannot read boot image {}", args.boot.display()))?;
    // 只解析容器并取出内核块；其余内容由 crate 原样保留。
    // 解析后的容器视图；这里只替换内核块。
    let boot_image = BootImage::parse(&boot_data).context("cannot parse boot image")?;
    let kernel = boot_image
        .get_blocks()
        .get_kernel()
        .context("boot image does not contain a kernel")?;
    println!("- Decompressing kernel");
    // 解压后的内核块，必须是原始 ARM64 Image。
    let mut raw_kernel = Vec::new();
    // 解压后必须是原始 ARM64 Image；压缩格式的识别由 crate 完成。
    kernel
        .dump(&mut raw_kernel, false)
        .context("cannot decompress boot kernel")?;

    // 显式指定或按 KMI 选出的 kernelsu.ko 字节。
    let module = if let Some(module) = &args.module {
        println!("- Module: {}", module.display());
        fs::read(module)
            .with_context(|| format!("cannot read kernel module {}", module.display()))?
    } else {
        // 未显式指定模块时，从内核 banner 推断 KMI，再取随包内置的对应构建。
        let kmi = boot_patch::parse_kmi(&raw_kernel)
            .context("cannot detect KMI from the boot image kernel")?;
        let name = embedded_module_name(&kmi);
        println!("- KMI: {kmi}");
        println!("- Embedded module: {name}");
        assets::get_asset_data(&name)
            .with_context(|| format!("no embedded KernelSU module for KMI {kmi}: {name}"))?
            .into_owned()
    };

    // 模块参数最终写入 capsule 的 args 区，由 bootstrap 交给 load_module。
    let mut params = Vec::new();
    args.config.validate()?;
    args.config.apply(&mut params);
    // 参数最终写入 capsule 的 args 区，由 bootstrap 作为 uargs 交给 load_module。
    let params = params.join(" ");
    if !params.is_empty() {
        println!("- Module params: {params}");
    }

    println!("- Recovering BTF/kallsyms and injecting module");
    // 注入结果与新镜像诊断信息。
    let (patched_kernel, report) = inject_image(&raw_kernel, &module, &params)?;

    println!("- Repacking boot image");
    // 复用原容器结构，仅替换内核块。
    let mut patcher = BootImagePatchOption::new(&boot_image);
    // patched_kernel 已是未压缩的原始 ARM64 Image，因此 compressed=false。
    patcher.replace_kernel(Box::new(Cursor::new(patched_kernel)), false);
    // 重新打包后的完整 boot 镜像字节。
    let mut repacked = Cursor::new(Vec::with_capacity(boot_data.len()));
    patcher
        .patch(&mut repacked)
        .context("cannot repack boot image")?;
    let repacked = repacked.into_inner();

    let output_parent = args
        .output
        .parent()
        .filter(|path| !path.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(output_parent)
        .with_context(|| format!("cannot create output directory {}", output_parent.display()))?;
    // 先写同目录临时文件，成功后再 persist，避免失败时留下残缺的 boot 镜像。
    // 同目录临时文件，persist 成功后才替换目标。
    let mut temporary = tempfile::NamedTempFile::new_in(output_parent)
        .context("cannot create temporary boot image")?;
    temporary
        .write_all(&repacked)
        .context("cannot write patched boot image")?;
    temporary
        .flush()
        .context("cannot flush patched boot image")?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;

        // 沿用源 boot 镜像的权限位，保证产物可直接被 fastboot 等工具读取。
        let mode = fs::metadata(&args.boot)
            .with_context(|| format!("cannot stat boot image {}", args.boot.display()))?
            .permissions()
            .mode();
        temporary
            .as_file()
            .set_permissions(fs::Permissions::from_mode(mode))
            .context("cannot copy boot image permissions")?;
    }
    temporary.persist(&args.output).map_err(|error| {
        anyhow!(
            "cannot persist patched boot image {}: {}",
            args.output.display(),
            error.error
        )
    })?;

    println!("- Kernel: {}", report.kernel_release);
    if let Some(btf) = report.btf {
        println!(
            "- BTF: v1 at 0x{:x}, {} bytes, {} types",
            btf.file_offset, btf.size, btf.type_count
        );
    } else {
        println!("- BTF: unavailable; using built-in GKI ABI");
    }
    println!(
        "- Kallsyms: {} ({} symbols)",
        report.kallsyms_layout, report.kallsyms_count
    );
    println!(
        "- Load ABI: load_info={}{} storage={} hdr={} len={}",
        report
            .load_info_structure_size
            .map_or_else(|| "unknown".to_owned(), |size| size.to_string()),
        if report.load_info_structure_size.is_some() {
            " (BTF)"
        } else {
            " (built-in)"
        },
        report.load_info_storage_size,
        report.load_info_hdr_offset,
        report.load_info_len_offset,
    );
    println!(
        "- Bootstrap: offset 0x{:x}, {} bytes",
        report.code_offset, report.code_size
    );
    println!(
        "- Memblock patch: offset 0x{:x}",
        report.memblock_call_offset
    );
    println!("- PAGE_OFFSET: 0x{:x}", report.page_offset);
    println!(
        "- Module fixups: {}, unresolved: {}",
        report.fixup_count,
        report.unresolved.len()
    );
    if !report.unresolved.is_empty() {
        println!(
            "- Native resolver symbols: {}",
            report.unresolved.join(", ")
        );
    }
    println!("- Patched Image size: 0x{:x}", report.image_size);
    println!("- Output: {}", args.output.display());
    Ok(())
}

// 针对格式与分析的单元测试。

#[cfg(test)]
mod tests {
    use super::*;

    /// 为 bootstrap 的未定义符号构造测试用地址：`ksu_ext_*` 与
    /// `ksu_image_base` 落在镜像基址之后，其余符号用小的递增值。
    fn bootstrap_test_definitions<'a>(
        object: &'a BootstrapObject<'a>,
        image_base: u64,
    ) -> BTreeMap<&'a str, u64> {
        let mut definitions = BTreeMap::new();
        for (index, symbol) in object
            .symbols
            .iter()
            .filter(|symbol| symbol.section_index == SHN_UNDEF && !symbol.name.is_empty())
            .enumerate()
        {
            let value = if symbol.name.starts_with("ksu_ext_") || symbol.name == "ksu_image_base" {
                image_base + 0x10_000 + index as u64 * 0x100
            } else {
                index as u64 + 1
            };
            definitions.insert(symbol.name.as_str(), value);
        }
        definitions
    }

    /// 在测试镜像中按 kallsyms 对齐追加一段数据，返回写入偏移。
    fn append_aligned(image: &mut [u8], position: &mut usize, data: &[u8]) -> usize {
        *position = align_up(*position, KALLSYMS_ALIGNMENT);
        let offset = *position;
        image[offset..offset + data.len()].copy_from_slice(data);
        *position += data.len();
        offset
    }

    /// 构造最小可解析 BTF：固定头 + 类型段 + 字符串段。
    fn build_test_btf(types: &[u8], strings: &[u8]) -> Vec<u8> {
        assert!(!strings.is_empty());
        let mut output = Vec::new();
        // BTF 头 magic 0xeb9f（小端），版本 1。
        output.extend_from_slice(&0xeb9fu16.to_le_bytes());
        output.extend_from_slice(&[1, 0]);
        for value in [
            24u32,
            0,
            u32::try_from(types.len()).unwrap(),
            u32::try_from(types.len()).unwrap(),
            u32::try_from(strings.len()).unwrap(),
        ] {
            output.extend_from_slice(&value.to_le_bytes());
        }
        output.extend_from_slice(types);
        output.extend_from_slice(strings);
        output
    }

    /// 只有空类型段和空字符串的合法 BTF，用于边界测试。
    fn minimal_test_btf() -> Vec<u8> {
        build_test_btf(&[], &[0])
    }

    /// 构造带 load_info 名称但布局不可用的 BTF，用于验证回退逻辑。
    fn unusable_load_info_test_btf() -> Vec<u8> {
        let mut types = Vec::new();
        types.extend_from_slice(&1u32.to_le_bytes());
        types.extend_from_slice(&(4u32 << 24).to_le_bytes());
        types.extend_from_slice(&8u32.to_le_bytes());
        build_test_btf(&types, b"\0load_info\0")
    }

    /// 构造不含自定义 token 的合成镜像，便于只关心 BTF 边界的测试。
    fn build_kallsyms_fixture_with_btf(
        layout: &str,
        btf_range: Option<(usize, usize)>,
    ) -> (Vec<u8>, u64) {
        build_kallsyms_fixture_with_btf_and_token(layout, btf_range, None)
    }

    /// 手工构造一个包含 ARM64 头、kallsyms、可选 BTF 边界的合成镜像。
    ///
    /// 符号数特意取 2049（大于 8*256），以覆盖多组 markers；`layout` 决定按
    /// 6.4 之前还是 6.4+ 的顺序摆放各表。返回镜像和 `_text` 基址。
    fn build_kallsyms_fixture_with_btf_and_token(
        layout: &str,
        btf_range: Option<(usize, usize)>,
        first_token: Option<&[u8]>,
    ) -> (Vec<u8>, u64) {
        let base = 0xffff_ffc0_0800_0000;
        let image_size = 0x20_0000usize;
        let count = 2049usize;
        // 前两项固定为 _text/load_module，恢复逻辑强依赖它们存在。
        let mut entries = vec![
            (base, b'T', "_text".to_owned()),
            (base + 0x1000, b't', "load_module".to_owned()),
        ];
        let boundary_symbol_count = usize::from(btf_range.is_some()) * 2;
        for index in 0..count - 3 - boundary_symbol_count {
            entries.push((
                base + 0x2000 + index as u64 * 0x20,
                b't',
                format!("fixture_symbol_{index:04}"),
            ));
        }
        if let Some((btf_offset, btf_size)) = btf_range {
            let btf_end = btf_offset.checked_add(btf_size).unwrap();
            assert!(btf_end < image_size);
            assert!(base + btf_offset as u64 > entries.last().unwrap().0);
            entries.push((base + btf_offset as u64, b'R', "__start_BTF".to_owned()));
            entries.push((base + btf_end as u64, b'R', "__stop_BTF".to_owned()));
        }
        entries.push((base + image_size as u64, b'B', "_end".to_owned()));

        // 构造 256 项 token 表；索引 0 可注入自定义 token。
        let mut token_table = Vec::new();
        let mut token_offsets = Vec::with_capacity(256);
        for value in 0u8..=u8::MAX {
            token_offsets.push(u16::try_from(token_table.len()).unwrap());
            if value == 0
                && let Some(token) = first_token
            {
                token_table.extend_from_slice(token);
            } else {
                token_table.push(if (0x20..=0x7e).contains(&value) {
                    value
                } else {
                    b'x'
                });
            }
            token_table.push(0);
        }
        let mut token_index = Vec::with_capacity(KALLSYMS_TOKEN_INDEX_SIZE);
        for offset in token_offsets {
            token_index.extend_from_slice(&offset.to_le_bytes());
        }

        // 按 marker 每 256 个符号记录一次累计偏移。
        let mut names = Vec::new();
        let mut markers = Vec::new();
        for (index, (_, kind, name)) in entries.iter().enumerate() {
            if index.is_multiple_of(256) {
                markers.push(u32::try_from(names.len()).unwrap());
            }
            let mut expanded = Vec::with_capacity(name.len() + 1);
            expanded.push(*kind);
            expanded.extend_from_slice(name.as_bytes());
            names.push(u8::try_from(expanded.len()).unwrap());
            names.extend_from_slice(&expanded);
        }
        let mut marker_bytes = Vec::new();
        for marker in markers {
            marker_bytes.extend_from_slice(&marker.to_le_bytes());
        }
        // 相对基址的 u32 偏移，避免测试镜像出现巨大绝对地址。
        let mut offsets = Vec::new();
        for (address, _, _) in &entries {
            offsets.extend_from_slice(&u32::try_from(address - base).unwrap().to_le_bytes());
        }
        let sequences = (0..count * 3)
            .map(|index| u8::try_from(index % 251 + 1).unwrap())
            .collect::<Vec<_>>();

        // 先写入 ARM64 头，再按布局追加各表。
        let mut image = vec![0u8; image_size];
        write_u64(&mut image, ARM64_IMAGE_SIZE_OFFSET, image_size as u64).unwrap();
        image[ARM64_IMAGE_MAGIC_OFFSET..ARM64_IMAGE_MAGIC_OFFSET + 4]
            .copy_from_slice(ARM64_IMAGE_MAGIC);
        // 各表从 0x10000 开始按对齐依次追加。
        let mut position = 0x10000;
        match layout {
            "pre-6.4" => {
                append_aligned(&mut image, &mut position, &offsets);
                append_aligned(&mut image, &mut position, &base.to_le_bytes());
                append_aligned(
                    &mut image,
                    &mut position,
                    &u32::try_from(count).unwrap().to_le_bytes(),
                );
                append_aligned(&mut image, &mut position, &names);
                append_aligned(&mut image, &mut position, &marker_bytes);
                append_aligned(&mut image, &mut position, &sequences);
                append_aligned(&mut image, &mut position, &token_table);
                append_aligned(&mut image, &mut position, &token_index);
            }
            "6.4+" => {
                append_aligned(
                    &mut image,
                    &mut position,
                    &u32::try_from(count).unwrap().to_le_bytes(),
                );
                append_aligned(&mut image, &mut position, &names);
                append_aligned(&mut image, &mut position, &marker_bytes);
                append_aligned(&mut image, &mut position, &token_table);
                append_aligned(&mut image, &mut position, &token_index);
                append_aligned(&mut image, &mut position, &offsets);
                append_aligned(&mut image, &mut position, &base.to_le_bytes());
                append_aligned(&mut image, &mut position, &sequences);
            }
            _ => panic!("unknown fixture layout"),
        }
        (image, base)
    }

    /// 构造不带 BTF 的合成镜像，返回镜像字节与 `_text` 基址。
    fn build_kallsyms_fixture(layout: &str) -> (Vec<u8>, u64) {
        build_kallsyms_fixture_with_btf(layout, None)
    }

    #[test]
    /// 空 token 仍应能解析 names 表，覆盖 token 内容为空的边界。
    fn recovers_kallsyms_with_empty_tokens() {
        for layout in ["pre-6.4", "6.4+"] {
            let (image, base) = build_kallsyms_fixture_with_btf_and_token(layout, None, Some(&[]));
            let recovered = recover_arm64_kernel_metadata(&image).unwrap();
            assert_eq!(
                recovered.kallsyms.symbols.resolve("_text").unwrap().address,
                base
            );
            assert_eq!(recovered.kallsyms.count, 2049);
        }
    }

    #[test]
    /// 长度接近上限的 token 必须被接受，确认长度窗口计算正确。
    fn recovers_kallsyms_with_long_tokens() {
        for length in [257, 512] {
            let token = vec![b'a'; length];
            let (image, base) =
                build_kallsyms_fixture_with_btf_and_token("pre-6.4", None, Some(&token));
            let recovered = recover_arm64_kernel_metadata(&image).unwrap();
            assert_eq!(
                recovered.kallsyms.symbols.resolve("_text").unwrap().address,
                base
            );
            assert_eq!(recovered.kallsyms.count, 2049);
        }
    }

    #[test]
    /// 超过 KALLSYMS_MAX_TOKEN_LENGTH 的 token 应使整个候选被拒绝。
    fn rejects_kallsyms_with_oversized_tokens() {
        let token = vec![b'a'; 513];
        let (image, _) = build_kallsyms_fixture_with_btf_and_token("pre-6.4", None, Some(&token));
        assert!(recover_arm64_kernel_metadata(&image).is_err());
    }

    #[test]
    /// 非可打印 token 说明候选表是误报，必须拒绝。
    fn rejects_kallsyms_with_non_ascii_tokens() {
        let (image, _) = build_kallsyms_fixture_with_btf_and_token("pre-6.4", None, Some(&[0x80]));
        assert!(recover_arm64_kernel_metadata(&image).is_err());
    }

    #[test]
    /// token 索引与 token 偏移不一致时必须拒绝，防止错位解析。
    fn rejects_kallsyms_with_incorrect_token_index() {
        let (mut image, _) = build_kallsyms_fixture_with_btf_and_token("pre-6.4", None, Some(&[]));
        let token_index = find_kallsyms_token_tables(&image)[0].2;
        image[token_index..token_index + 2].copy_from_slice(&1_u16.to_le_bytes());
        assert!(recover_arm64_kernel_metadata(&image).is_err());
    }

    #[test]
    /// 确认 image_size 读取的是 0x10，而不是头部的其他字段。
    fn arm64_image_size_uses_header_offset_0x10() {
        let mut image = vec![0u8; 64];
        image[ARM64_IMAGE_MAGIC_OFFSET..ARM64_IMAGE_MAGIC_OFFSET + 4]
            .copy_from_slice(ARM64_IMAGE_MAGIC);
        write_u64(&mut image, ARM64_IMAGE_SIZE_OFFSET, 0x20_0000).unwrap();
        write_u64(&mut image, 0x18, 0xa).unwrap();
        assert_eq!(parse_arm64_image_size(&image).unwrap(), 0x20_0000);
    }

    #[test]
    /// BL 编解码在正负位移上互为逆运算，并拒绝超出 26 位范围的目标。
    fn bl_round_trip_in_both_directions() {
        for (source, target) in [(0x1000, 0x2000), (0x4000, 0x1000)] {
            let instruction = encode_bl(source, target).unwrap();
            assert_eq!(decode_bl_target(instruction, source), Some(target));
        }
        assert!(encode_bl(0, 1 << 27).is_err());
    }

    #[test]
    /// ORR 逻辑立即数解码应还原符号扩展后的 PAGE_OFFSET 常量。
    fn page_offset_logical_immediate_decode() {
        assert_eq!(
            decode_orr_immediate(0xb259_6129),
            Some((0xffff_ff80_0000_0000, 9, 9))
        );
    }

    #[test]
    /// 内置模块资源名需与发布产物布局一致，并确实存在于 assets。
    fn embedded_module_uses_release_asset_layout() {
        let name = embedded_module_name("android12-5.10");
        #[cfg(target_os = "android")]
        assert_eq!(name, "android12-5.10_kernelsu.ko");
        #[cfg(not(target_os = "android"))]
        assert_eq!(name, "aarch64/android12-5.10_kernelsu.ko");
        assert!(!assets::get_asset_data(&name).unwrap().is_empty());
    }

    #[test]
    /// 从内核 banner 推断 KMI 的路径应识别 android14-6.1。
    fn detects_kmi_from_boot_kernel_banner() {
        let kernel = b"Linux version 6.1.157-android14-11-gki-test\0";
        assert_eq!(boot_patch::parse_kmi(kernel).unwrap(), "android14-6.1");
    }

    #[test]
    /// capsule 各字段偏移必须与 bootstrap 侧读到的线上格式一致。
    fn capsule_layout_matches_wire_format() {
        let module = b"module-bytes";
        let fixups = (0u8..16).collect::<Vec<_>>();
        let capsule = build_capsule(0x20_0003, module, &fixups, 1, "allow_shell=1").unwrap();
        assert_eq!(capsule.file_offset, 0x20_0010);
        assert!(capsule.image_size.is_multiple_of(CAPSULE_ALIGNMENT));
        assert_eq!(&capsule.data[..8], CAPSULE_MAGIC);
        assert_eq!(read_u32(&capsule.data, 8).unwrap(), CAPSULE_VERSION);
        assert_eq!(
            read_u32(&capsule.data, 12).unwrap(),
            CAPSULE_HEADER_SIZE as u32
        );
        assert_eq!(read_u64(&capsule.data, 32).unwrap(), module.len() as u64);
        assert_eq!(read_u64(&capsule.data, 48).unwrap(), 1);
        let module_offset = usize::try_from(read_u64(&capsule.data, 24).unwrap()).unwrap();
        assert_eq!(
            &capsule.data[module_offset..module_offset + module.len()],
            module
        );
        let args_offset = usize::try_from(read_u64(&capsule.data, 96).unwrap()).unwrap();
        let args_size = usize::try_from(read_u64(&capsule.data, 104).unwrap()).unwrap();
        assert_eq!(args_offset, capsule.args_offset - capsule.file_offset);
        assert!(args_offset >= 16 + usize::try_from(read_u64(&capsule.data, 40).unwrap()).unwrap());
        assert_eq!(
            &capsule.data[args_offset..args_offset + args_size],
            b"allow_shell=1\0"
        );
        assert!(args_offset + args_size <= capsule.data.len());
    }

    #[test]
    /// 含 NUL 或超长的模块参数必须在构造 capsule 前被拒绝。
    fn capsule_rejects_invalid_params() {
        assert!(build_capsule(0x20_0000, b"m", &[], 0, "a\0b").is_err());
        let long = "a".repeat(MAX_MODULE_PARAMS_SIZE + 1);
        assert!(build_capsule(0x20_0000, b"m", &[], 0, &long).is_err());
    }

    #[test]
    /// 内置 bootstrap 能被极简链接器重定位，且入口正好位于代码起点。
    fn embedded_bootstrap_links_with_rust_relocator() {
        let image_base = 0xffff_ffc0_0800_0000;
        let object = BootstrapObject::parse(BOOTSTRAP_OBJECT).unwrap();
        let definitions = bootstrap_test_definitions(&object, image_base);
        let linked = object.link(image_base, &definitions).unwrap();
        assert_eq!(linked.data.len(), object.image_size());
        assert_eq!(linked.entry_address, image_base);
        assert!(
            (image_base..image_base + linked.data.len() as u64)
                .contains(&linked.reserve_wrapper_address)
        );
        assert!(
            (image_base..image_base + linked.data.len() as u64)
                .contains(&linked.strndup_adapter_address)
        );
        assert_eq!(read_u32(&linked.data, 0).unwrap(), 0xd503_245f);
    }

    #[test]
    /// 未知重定位类型必须报错，不能把错误编码写进内核。
    fn embedded_bootstrap_rejects_unknown_relocation() {
        let mut data = BOOTSTRAP_OBJECT.to_vec();
        let relocation_info_offset = {
            let object = BootstrapObject::parse(&data).unwrap();
            object
                .sections
                .iter()
                .find(|section| section.section_type == SHT_RELA)
                .unwrap()
                .offset
                + 8
        };
        let info = read_u64(&data, relocation_info_offset).unwrap();
        write_u64(
            &mut data,
            relocation_info_offset,
            (info & !u64::from(u32::MAX)) | 0xffff,
        )
        .unwrap();
        let object = BootstrapObject::parse(&data).unwrap();
        let image_base = 0xffff_ffc0_0800_0000;
        let definitions = bootstrap_test_definitions(&object, image_base);
        let error = object.link(image_base, &definitions).unwrap_err();
        assert!(error.to_string().contains("unsupported AArch64"));
    }

    #[test]
    /// 只有 x0/x1 由同一内核符号寄存器导出的 memblock_reserve 调用才算匹配。
    fn memblock_kernel_reservation_semantic_match() {
        let base = 0xffff_ffc0_0800_0000;
        let caller = MapSymbol {
            address: base + 0x20,
            name: "arm64_memblock_init".to_owned(),
        };
        let target = MapSymbol {
            address: base + 0x180,
            name: "memblock_reserve".to_owned(),
        };
        let symbols = SymbolMap::new(vec![caller.clone(), target.clone()]).unwrap();
        let mut image = vec![0u8; 0x200];
        let call_offset = 0x60;
        write_u32(
            &mut image,
            call_offset - 8,
            0xcb00_0000 | (3 << 16) | (2 << 5) | 1,
        )
        .unwrap();
        write_u32(
            &mut image,
            call_offset - 4,
            0xcb00_0000 | (4 << 16) | (3 << 5),
        )
        .unwrap();
        write_u32(
            &mut image,
            call_offset,
            encode_bl(base + call_offset as u64, target.address).unwrap(),
        )
        .unwrap();
        let call = find_kernel_image_memblock_reserve_call(
            &image,
            &symbols,
            base,
            &caller,
            &HashSet::from([target.address]),
            0x100,
        )
        .unwrap();
        assert_eq!(call.file_offset, call_offset);
    }

    #[test]
    /// 6.4 之前的 kallsyms 布局应被正确恢复。
    fn recovers_pre_6_4_kallsyms() {
        let (image, base) = build_kallsyms_fixture("pre-6.4");
        let recovered = recover_arm64_kernel_metadata(&image).unwrap();
        assert!(recovered.btf.is_none());
        let recovered = recovered.kallsyms;
        assert_eq!(recovered.layout, "pre-6.4");
        assert_eq!(recovered.count, 2049);
        assert_eq!(recovered.symbols.resolve("_text").unwrap().address, base);
        assert_eq!(
            recovered
                .symbols
                .resolve("fixture_symbol_1024")
                .unwrap()
                .address,
            base + 0x2000 + 1024 * 0x20
        );
    }

    #[test]
    /// 6.4+ 的 ULEB128 名称长度布局应被正确恢复。
    fn recovers_6_4_plus_kallsyms() {
        let (image, base) = build_kallsyms_fixture("6.4+");
        let recovered = recover_arm64_kernel_metadata(&image).unwrap();
        assert!(recovered.btf.is_none());
        let recovered = recovered.kallsyms;
        assert_eq!(recovered.layout, "6.4+");
        assert_eq!(recovered.count, 2049);
        assert_eq!(recovered.symbols.resolve("_text").unwrap().address, base);
        assert_eq!(
            recovered
                .symbols
                .resolve("fixture_symbol_1024")
                .unwrap()
                .address,
            base + 0x2000 + 1024 * 0x20
        );
    }

    #[test]
    /// 存在多个 BTF 候选时，只能选被 kallsyms 边界限定的那个。
    fn selects_vmlinux_btf_by_kallsyms_boundaries() {
        let unrelated_btf = unusable_load_info_test_btf();
        let kernel_btf = minimal_test_btf();
        let unrelated_offset = 0x17_0000usize;
        let kernel_offset = 0x18_0000usize;
        let (mut image, _) =
            build_kallsyms_fixture_with_btf("6.4+", Some((kernel_offset, kernel_btf.len())));
        image[unrelated_offset..unrelated_offset + unrelated_btf.len()]
            .copy_from_slice(&unrelated_btf);
        image[kernel_offset..kernel_offset + kernel_btf.len()].copy_from_slice(&kernel_btf);

        assert_eq!(find_btf_candidates(&image).len(), 2);
        let recovered = recover_arm64_kernel_metadata(&image).unwrap();
        let selected = recovered.btf.unwrap();
        assert_eq!(selected.file_offset, kernel_offset);
        assert_eq!(selected.size, kernel_btf.len());
    }

    #[test]
    /// 与 kallsyms 边界不符的嵌入 BTF 必须被忽略。
    fn ignores_unmatched_embedded_btf() {
        let unrelated_btf = unusable_load_info_test_btf();
        let unrelated_offset = 0x17_0000usize;
        let (mut image, _) = build_kallsyms_fixture("6.4+");
        image[unrelated_offset..unrelated_offset + unrelated_btf.len()]
            .copy_from_slice(&unrelated_btf);

        assert_eq!(find_btf_candidates(&image).len(), 1);
        let recovered = recover_arm64_kernel_metadata(&image).unwrap();
        assert!(recovered.btf.is_none());
    }

    #[test]
    /// BTF 范围与 `__start_BTF`/`__stop_BTF` 不一致时必须报错。
    fn validates_btf_boundaries_against_kallsyms() {
        let base = 0xffff_ffc0_0800_0000;
        let btf = KernelBtf {
            file_offset: 0x1234,
            size: 0x5678,
            type_count: 1,
            load_info: None,
        };
        let symbols = SymbolMap::new(vec![
            MapSymbol {
                address: base,
                name: "_text".to_owned(),
            },
            MapSymbol {
                address: base + btf.file_offset as u64,
                name: "__start_BTF".to_owned(),
            },
            MapSymbol {
                address: base + (btf.file_offset + btf.size) as u64,
                name: "__stop_BTF".to_owned(),
            },
        ])
        .unwrap();
        validate_kallsyms_btf_boundaries(&symbols, btf.file_offset, btf.size).unwrap();

        let mismatched = KernelBtf {
            size: btf.size + 1,
            ..btf
        };
        assert!(
            validate_kallsyms_btf_boundaries(&symbols, mismatched.file_offset, mismatched.size)
                .is_err()
        );
    }

    #[test]
    /// BTF 推导的 load_info 布局应覆盖回退值，并把存储向上对齐到 16 字节。
    fn btf_overlays_load_info_abi_with_aligned_storage() {
        let current = KernelBtf {
            file_offset: 0,
            size: 1,
            type_count: 1,
            load_info: Some(crate::lkm_image_btf::LoadInfoLayout {
                structure_size: 136,
                hdr_offset: 16,
                len_offset: 24,
            }),
        };
        let current_abi = GKI_ABI.apply_btf(&current).unwrap();
        assert_eq!(current_abi.load_info_storage_size, 256);

        let btf = KernelBtf {
            file_offset: 0,
            size: 1,
            type_count: 1,
            load_info: Some(crate::lkm_image_btf::LoadInfoLayout {
                structure_size: 264,
                hdr_offset: 24,
                len_offset: 32,
            }),
        };
        let abi = GKI_ABI.apply_btf(&btf).unwrap();
        assert_eq!(abi.load_info_structure_size, Some(264));
        assert_eq!(abi.load_info_storage_size, 272);
        assert_eq!(abi.load_info_hdr_offset, 24);
        assert_eq!(abi.load_info_len_offset, 32);
    }
}
