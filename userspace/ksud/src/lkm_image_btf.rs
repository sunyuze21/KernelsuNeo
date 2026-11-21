// SPDX-License-Identifier: GPL-2.0-only

//! LKM 镜像流程使用的精简 BTF 解析器。
//!
//! Android GKI 内核会在 vmlinux 镜像中携带 BTF 数据块。内嵌模块加载器需要
//! 一些随内核构建而变化的 ABI 信息，因此这里从该数据块中恢复，而不是硬编码：
//!
//! * `struct load_info` 的布局（`hdr` 与 `len` 成员偏移以及整个结构体大小），
//!   镜像改写器会把这些值写入模块；
//! * 加载器调用的内核函数的参数个数/签名（`load_module`、`vmalloc`、
//!   `memcpy` 等），用作一致性校验。
//!
//! 输入是原始内核镜像，因此通过扫描 BTF 魔数定位数据块，而不依赖节表。
//! 这里只理解上述查询所需的 BTF 子集（小端、64 位 ARM 目标；不支持大端和
//! BTF.ext）。任何格式错误都视为“没有 BTF”，调用方随后回退到内置 GKI 默认值。

#![allow(clippy::similar_names)]

use std::collections::BTreeSet;

use anyhow::{Result, ensure};

// BTF 头部魔数与版本，定义见内核 include/uapi/linux/btf.h。
const BTF_MAGIC: u16 = 0xeb9f;
const BTF_VERSION: u8 = 1;
// 最小头部长度：魔数、版本、标志、hdr_len，以及四个 u32 的节描述
// （类型区偏移/长度、字符串区偏移/长度）。
const BTF_HEADER_SIZE: usize = 24;
// 任何有效 BTF 数据块的磁盘前缀：小端魔数、版本 1、标志 0。
const BTF_MAGIC_BYTES: &[u8; 4] = b"\x9f\xeb\x01\x00";
// 这里处理的内核构建始终是 64 位 ARM。
const ARM64_POINTER_SIZE: u64 = 8;

// BTF kind 编号；属于内核 UAPI，取值不可更改。
const BTF_KIND_INT: u8 = 1;
const BTF_KIND_PTR: u8 = 2;
const BTF_KIND_ARRAY: u8 = 3;
const BTF_KIND_STRUCT: u8 = 4;
const BTF_KIND_UNION: u8 = 5;
const BTF_KIND_ENUM: u8 = 6;
const BTF_KIND_FWD: u8 = 7;
const BTF_KIND_TYPEDEF: u8 = 8;
const BTF_KIND_VOLATILE: u8 = 9;
const BTF_KIND_CONST: u8 = 10;
const BTF_KIND_RESTRICT: u8 = 11;
const BTF_KIND_FUNC: u8 = 12;
const BTF_KIND_FUNC_PROTO: u8 = 13;
const BTF_KIND_VAR: u8 = 14;
const BTF_KIND_DATASEC: u8 = 15;
const BTF_KIND_FLOAT: u8 = 16;
const BTF_KIND_DECL_TAG: u8 = 17;
const BTF_KIND_TYPE_TAG: u8 = 18;
const BTF_KIND_ENUM64: u8 = 19;

/// 内核 `struct load_info` 的字节布局，从 BTF 中恢复。
///
/// 所有偏移均相对于结构体起始位置。`Eq`/`Ord` 派生使多份独立的 BTF 定义
/// 可以放入集合并比较一致性（它们必须完全一致）。
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct LoadInfoLayout {
    /// `struct load_info` 的总字节大小。
    pub structure_size: u64,
    /// `hdr` 成员的偏移（指向模块 ELF 头的指针）。
    pub hdr_offset: u64,
    /// `len` 成员的偏移（模块长度）。
    pub len_offset: u64,
}

/// 从镜像中恢复并校验后的 BTF 数据块摘要。
///
/// 只保留镜像流程所需的信息；原始类型不会被保存，因此调用方无法再从该值
/// 查询任意 BTF 信息。
#[derive(Clone, Debug)]
pub struct KernelBtf {
    /// BTF 头部首字节在镜像中的偏移。
    pub file_offset: usize,
    /// 整个数据块的长度（头部、类型区和字符串区）。
    pub size: usize,
    /// 类型区中类型记录的数量。
    pub type_count: usize,
    /// 当数据块中没有可用的 `struct load_info` 定义时为 `None`。
    pub load_info: Option<LoadInfoLayout>,
}

/// 一条解析后的 BTF 类型记录。
///
/// 固定的 12 字节类型头被解码到这些字段中。与 kind 相关的数据（成员、参数、
/// 数组维度等）不会被复制，而是按需从 `BtfCandidate::data` 的
/// `payload_offset` 处读取。
#[derive(Clone, Copy, Debug)]
struct BtfType {
    kind: u8,
    /// kind 相关负载中的元素个数（成员、参数等）。
    vlen: u16,
    /// 对 struct/union：成员偏移的高 8 位还会携带位域宽度，而不再是单纯的
    /// 位偏移。
    kind_flag: bool,
    /// 类型名在字符串区中的偏移（0 表示空字符串）。
    name_offset: u32,
    /// 对 INT/STRUCT/UNION/ENUM/FLOAT/ENUM64 记录保存字节大小，其他情况保存
    /// 被引用的类型 id（指针目标、数组元素、函数返回类型、修饰符目标或
    /// FUNC 原型）。
    size_or_type: u32,
    /// 紧随 12 字节类型头之后的负载偏移。
    payload_offset: usize,
}

/// 在镜像中发现并通过结构校验的 BTF 数据块。
///
/// 解析刻意保持宽松：只有当候选内部自相矛盾时才会拒绝。需要外部上下文的
/// 交叉校验（例如与 kallsyms 匹配）由调用方完成。
#[derive(Debug)]
pub struct BtfCandidate<'a> {
    /// 发现该数据块的整个镜像缓冲区。
    data: &'a [u8],
    /// BTF 头部在 `data` 中的偏移。
    file_offset: usize,
    /// 数据块末尾之后的第一个偏移（取类型区与字符串区末尾的较大值）。
    blob_end: usize,
    /// 字符串区起始位置（`data` 中的绝对偏移）。
    strings_start: usize,
    /// 字符串区结束位置（`data` 中的绝对偏移）。
    strings_end: usize,
    /// 按节内顺序排列的已解析类型记录；类型 id N 对应 `types[N - 1]`。
    types: Vec<BtfType>,
}

/// 读取一个小端 `u16`；越界或溢出时返回 `None`。
fn read_u16(data: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_le_bytes(
        data.get(offset..offset.checked_add(2)?)?.try_into().ok()?,
    ))
}

/// 读取一个小端 `u32`；越界或溢出时返回 `None`。
fn read_u32(data: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_le_bytes(
        data.get(offset..offset.checked_add(4)?)?.try_into().ok()?,
    ))
}

/// 半开区间重叠检测，用于避免类型区与字符串区相互重叠。
fn ranges_overlap(
    left_start: usize,
    left_end: usize,
    right_start: usize,
    right_end: usize,
) -> bool {
    left_start.max(right_start) < left_end.min(right_end)
}

/// 从字符串区解析一个以 NUL 结尾的字符串。
///
/// `offset` 相对于 `strings_start`，与 BTF 名称字段的存储方式一致；偏移 0
/// 通常指向最前面的空字符串。字符串必须在节内结束且为合法 UTF-8。
fn read_string(data: &[u8], strings_start: usize, strings_end: usize, offset: u32) -> Option<&str> {
    let start = strings_start.checked_add(usize::try_from(offset).ok()?)?;
    if start >= strings_end {
        return None;
    }
    let tail = data.get(start..strings_end)?;
    let length = tail.iter().position(|byte| *byte == 0)?;
    std::str::from_utf8(tail.get(..length)?).ok()
}

/// 类型记录头之后与 kind 相关负载的字节大小。
///
/// `vlen` 表示负载元素个数；遇到未知 kind 返回 `None`，以便解析直接拒绝该
/// 数据块，而不是猜测记录长度。
fn payload_size(kind: u8, vlen: u16) -> Option<usize> {
    let vlen = usize::from(vlen);
    match kind {
        // u32 编码 / 链接属性 / 组件索引。
        BTF_KIND_INT | BTF_KIND_VAR | BTF_KIND_DECL_TAG => Some(4),
        // 这些 kind 的信息全部包含在固定头部中。
        BTF_KIND_PTR | BTF_KIND_FWD | BTF_KIND_TYPEDEF | BTF_KIND_VOLATILE | BTF_KIND_CONST
        | BTF_KIND_RESTRICT | BTF_KIND_FUNC | BTF_KIND_FLOAT | BTF_KIND_TYPE_TAG => Some(0),
        // struct btf_array：元素类型 u32、索引类型 u32、元素个数 u32。
        BTF_KIND_ARRAY => Some(12),
        // struct btf_member / btf_var_secinfo / btf_enum64 条目。
        BTF_KIND_STRUCT | BTF_KIND_UNION | BTF_KIND_DATASEC | BTF_KIND_ENUM64 => {
            vlen.checked_mul(12)
        }
        // struct btf_enum / btf_param 条目。
        BTF_KIND_ENUM | BTF_KIND_FUNC_PROTO => vlen.checked_mul(8),
        _ => None,
    }
}

impl<'a> BtfCandidate<'a> {
    /// 尝试解析头部位于 `file_offset` 的完整 BTF 数据块。
    ///
    /// 校验头部、各节边界以及每条类型记录，然后检查所有名称和类型引用都
    /// 指向数据块内部。任一步失败都返回 `None`，这样 `find_btf_candidates`
    /// 可以把随机出现的魔数匹配当作误报。
    fn parse(data: &'a [u8], file_offset: usize) -> Option<Self> {
        let magic = read_u16(data, file_offset)?;
        let version = *data.get(file_offset.checked_add(2)?)?;
        let flags = *data.get(file_offset.checked_add(3)?)?;
        let header_length = usize::try_from(read_u32(data, file_offset.checked_add(4)?)?).ok()?;
        if magic != BTF_MAGIC
            || version != BTF_VERSION
            || flags != 0
            || header_length < BTF_HEADER_SIZE
        {
            return None;
        }

        // 头部中的各节偏移相对于头部末尾（头部可能长于 24 字节的最小值），
        // 而不是数据块起始位置。
        let header_end = file_offset.checked_add(header_length)?;
        let type_offset = usize::try_from(read_u32(data, file_offset.checked_add(8)?)?).ok()?;
        let type_length = usize::try_from(read_u32(data, file_offset.checked_add(12)?)?).ok()?;
        let strings_offset = usize::try_from(read_u32(data, file_offset.checked_add(16)?)?).ok()?;
        let strings_length = usize::try_from(read_u32(data, file_offset.checked_add(20)?)?).ok()?;
        let types_start = header_end.checked_add(type_offset)?;
        let types_end = types_start.checked_add(type_length)?;
        let strings_start = header_end.checked_add(strings_offset)?;
        let strings_end = strings_start.checked_add(strings_length)?;
        // 拒绝越出缓冲区、彼此重叠，或字符串表首尾不是 NUL 字节的各节。
        if header_end > data.len()
            || types_start < header_end
            || strings_start < header_end
            || types_end > data.len()
            || strings_end > data.len()
            || strings_length == 0
            || ranges_overlap(types_start, types_end, strings_start, strings_end)
            || data.get(strings_start).copied() != Some(0)
            || data.get(strings_end.checked_sub(1)?).copied() != Some(0)
        {
            return None;
        }

        let mut types = Vec::new();
        let mut position = types_start;
        while position < types_end {
            // 每条类型记录都以 12 字节头部开始：
            //   u32 name_off、u32 info、u32 size_or_type
            let header_end = position.checked_add(12)?;
            if header_end > types_end {
                return None;
            }
            let name_offset = read_u32(data, position)?;
            let info = read_u32(data, position.checked_add(4)?)?;
            let size_or_type = read_u32(data, position.checked_add(8)?)?;
            // info：位 0..16 为 vlen，位 24..29 为 kind，位 31 为 kind_flag
            //（位 16..24 和 29..31 保留，忽略）。
            let kind = u8::try_from((info >> 24) & 0x1f).ok()?;
            let vlen = u16::try_from(info & 0xffff).ok()?;
            let payload_length = payload_size(kind, vlen)?;
            let next = header_end.checked_add(payload_length)?;
            if next > types_end {
                return None;
            }
            read_string(data, strings_start, strings_end, name_offset)?;
            types.push(BtfType {
                kind,
                vlen,
                kind_flag: info >> 31 != 0,
                name_offset,
                size_or_type,
                payload_offset: header_end,
            });
            position = next;
        }
        // 最后一条记录必须恰好结束于节的边界；不匹配说明类型区被截断或填充。
        if position != types_end {
            return None;
        }

        let parsed = Self {
            data,
            file_offset,
            // 各节可能以任意顺序出现，因此数据块在两者中较晚的末尾结束。
            blob_end: strings_end.max(types_end),
            strings_start,
            strings_end,
            types,
        };
        parsed.validate_references().then_some(parsed)
    }

    /// 解析属于该数据块的字符串区偏移。
    fn string(&self, offset: u32) -> Option<&'a str> {
        read_string(self.data, self.strings_start, self.strings_end, offset)
    }

    /// 按从 1 开始的 id 查找类型。类型 id 0 表示 `void` 且没有记录，因此
    /// 返回 `None`。
    fn type_by_id(&self, type_id: u32) -> Option<&BtfType> {
        if type_id == 0 {
            return None;
        }
        self.types
            .get(usize::try_from(type_id).ok()?.checked_sub(1)?)
    }

    /// 类型 id 为 0（`void`）或在类型数量范围内时有效。
    fn valid_type_id(&self, type_id: u32) -> bool {
        type_id == 0 || usize::try_from(type_id).is_ok_and(|id| id <= self.types.len())
    }

    /// 检查所有类型和名称引用都指向本数据块内部。
    ///
    /// 这是低成本的整体一致性检查，避免把截断的数据块（或恰好以 BTF 魔数
    /// 开头的随机数据）当作 BTF 接受。
    fn validate_references(&self) -> bool {
        self.types.iter().all(|btf_type| {
            let valid_name = self.string(btf_type.name_offset).is_some();
            if !valid_name {
                return false;
            }
            let valid_base_reference = match btf_type.kind {
                BTF_KIND_PTR | BTF_KIND_TYPEDEF | BTF_KIND_VOLATILE | BTF_KIND_CONST
                | BTF_KIND_RESTRICT | BTF_KIND_FUNC | BTF_KIND_FUNC_PROTO | BTF_KIND_VAR
                | BTF_KIND_DECL_TAG | BTF_KIND_TYPE_TAG => {
                    self.valid_type_id(btf_type.size_or_type)
                }
                _ => true,
            };
            if !valid_base_reference {
                return false;
            }
            match btf_type.kind {
                BTF_KIND_ARRAY => {
                    // btf_array：元素类型 id、索引类型 id、元素个数。
                    let Some(element_type) = read_u32(self.data, btf_type.payload_offset) else {
                        return false;
                    };
                    let Some(index_type) = read_u32(self.data, btf_type.payload_offset + 4) else {
                        return false;
                    };
                    self.valid_type_id(element_type) && self.valid_type_id(index_type)
                }
                // btf_member：名称偏移、类型 id、偏移（位偏移或位域描述）。
                BTF_KIND_STRUCT | BTF_KIND_UNION => (0..usize::from(btf_type.vlen)).all(|index| {
                    let offset = btf_type.payload_offset + index * 12;
                    read_u32(self.data, offset).is_some_and(|name| self.string(name).is_some())
                        && read_u32(self.data, offset + 4)
                            .is_some_and(|type_id| self.valid_type_id(type_id))
                }),
                // btf_enum：名称偏移、有符号值。
                BTF_KIND_ENUM => (0..usize::from(btf_type.vlen)).all(|index| {
                    read_u32(self.data, btf_type.payload_offset + index * 8)
                        .is_some_and(|name| self.string(name).is_some())
                }),
                // FUNC.size_or_type 指向描述它的 FUNC_PROTO。
                BTF_KIND_FUNC => self
                    .type_by_id(btf_type.size_or_type)
                    .is_some_and(|prototype| prototype.kind == BTF_KIND_FUNC_PROTO),
                // btf_param：名称偏移、类型 id（vlen 不包含返回类型，返回类型
                // 保存在 FUNC_PROTO 的 size_or_type 中）。
                BTF_KIND_FUNC_PROTO => (0..usize::from(btf_type.vlen)).all(|index| {
                    let offset = btf_type.payload_offset + index * 8;
                    read_u32(self.data, offset).is_some_and(|name| self.string(name).is_some())
                        && read_u32(self.data, offset + 4)
                            .is_some_and(|type_id| self.valid_type_id(type_id))
                }),
                // btf_var_secinfo：变量类型 id、偏移、大小。第一个字段必须
                // 引用一条 VAR 记录。
                BTF_KIND_DATASEC => (0..usize::from(btf_type.vlen)).all(|index| {
                    read_u32(self.data, btf_type.payload_offset + index * 12)
                        .and_then(|type_id| self.type_by_id(type_id))
                        .is_some_and(|variable| variable.kind == BTF_KIND_VAR)
                }),
                // btf_enum64：名称偏移、值低 32 位、值高 32 位。
                BTF_KIND_ENUM64 => (0..usize::from(btf_type.vlen)).all(|index| {
                    read_u32(self.data, btf_type.payload_offset + index * 12)
                        .is_some_and(|name| self.string(name).is_some())
                }),
                _ => true,
            }
        })
    }

    /// 沿 typedef/限定符链找到底层的具体类型。
    ///
    /// 链最终为 `void` 时返回 `Some(0)`，无法终止时返回 `None`。循环上界
    /// 用于防止格式错误的数据块中出现引用环。
    fn strip_modifiers(&self, mut type_id: u32) -> Option<u32> {
        for _ in 0..self.types.len() {
            let Some(btf_type) = self.type_by_id(type_id) else {
                return (type_id == 0).then_some(0);
            };
            if matches!(
                btf_type.kind,
                BTF_KIND_TYPEDEF
                    | BTF_KIND_VOLATILE
                    | BTF_KIND_CONST
                    | BTF_KIND_RESTRICT
                    | BTF_KIND_TYPE_TAG
            ) {
                type_id = btf_type.size_or_type;
            } else {
                return Some(type_id);
            }
        }
        None
    }

    /// 计算类型的字节大小；无法静态确定（函数、前向声明、不完整类型）时
    /// 返回 `None`。
    fn type_size(&self, type_id: u32) -> Option<u64> {
        self.type_size_inner(type_id, 0)
    }

    /// `BtfCandidate::type_size` 的递归实现；`depth` 限制嵌套深度，防止
    /// 自引用数组无限递归。
    fn type_size_inner(&self, type_id: u32, depth: usize) -> Option<u64> {
        if depth > 64 {
            return None;
        }
        let type_id = self.strip_modifiers(type_id)?;
        let btf_type = self.type_by_id(type_id)?;
        match btf_type.kind {
            BTF_KIND_INT | BTF_KIND_STRUCT | BTF_KIND_UNION | BTF_KIND_ENUM | BTF_KIND_FLOAT
            | BTF_KIND_ENUM64 => Some(u64::from(btf_type.size_or_type)),
            // 目标始终是 64 位 ARM，因此指针大小固定。
            BTF_KIND_PTR => Some(ARM64_POINTER_SIZE),
            BTF_KIND_ARRAY => {
                // btf_array：+0 为元素类型 id，+4 为索引类型 id（这里不用），
                // +8 为元素个数。
                let element_type = read_u32(self.data, btf_type.payload_offset)?;
                let count = read_u32(self.data, btf_type.payload_offset + 8)?;
                self.type_size_inner(element_type, depth + 1)?
                    .checked_mul(u64::from(count))
            }
            _ => None,
        }
    }

    /// 当 `type_id` 是指向名称完全匹配的结构体的指针（可带限定符）时
    /// 返回 true。
    fn points_to_named_struct(&self, type_id: u32, expected_name: &str) -> bool {
        let Some(type_id) = self.strip_modifiers(type_id) else {
            return false;
        };
        let Some(pointer) = self.type_by_id(type_id) else {
            return false;
        };
        if pointer.kind != BTF_KIND_PTR {
            return false;
        }
        let Some(pointee_id) = self.strip_modifiers(pointer.size_or_type) else {
            return false;
        };
        self.type_by_id(pointee_id).is_some_and(|pointee| {
            pointee.kind == BTF_KIND_STRUCT
                && self.string(pointee.name_offset) == Some(expected_name)
        })
    }

    /// 返回 struct/union 成员的字节偏移。
    ///
    /// BTF 成员偏移字段是复用的：没有 `kind_flag` 时整个 u32 是位偏移；有
    /// `kind_flag` 时位 0..24 是位偏移，位 24..32 是位域宽度。调用方需要
    /// 普通的字节对齐成员，因此这里拒绝位域。
    fn member_offset(&self, structure: &BtfType, member_index: usize) -> Option<u64> {
        // 成员条目布局：名称偏移（+0）、类型 id（+4）、偏移（+8）。
        let raw = read_u32(self.data, structure.payload_offset + member_index * 12 + 8)?;
        if structure.kind_flag && raw >> 24 != 0 {
            return None;
        }
        // kind_flag 置位时掩掉位域宽度。
        let bit_offset = if structure.kind_flag {
            raw & 0x00ff_ffff
        } else {
            raw
        };
        bit_offset
            .is_multiple_of(8)
            .then_some(u64::from(bit_offset / 8))
    }

    /// 从 BTF 中提取 `struct load_info` 的 hdr/len 偏移和结构体大小。
    ///
    /// 数据块完全没有描述 `load_info` 时返回 `Ok(None)`，这不算错误（调用方
    /// 保留内置 GKI 默认值）。错误表示定义互相矛盾或不可用。若 BTF 中存在
    /// 多份 `load_info` 定义，它们必须对布局达成一致。
    fn load_info_layout(&self) -> Result<Option<LoadInfoLayout>> {
        let mut named_structs = 0usize;
        // 使用集合既去掉重复定义，也能通过长度发现冲突。
        let mut layouts = BTreeSet::new();
        for structure in self.types.iter().filter(|btf_type| {
            btf_type.kind == BTF_KIND_STRUCT
                && self.string(btf_type.name_offset) == Some("load_info")
        }) {
            named_structs += 1;
            let mut hdr = None;
            let mut len = None;
            for index in 0..usize::from(structure.vlen) {
                // btf_member：名称偏移、类型 id、成员偏移。
                let member_offset = structure.payload_offset + index * 12;
                let Some(name_offset) = read_u32(self.data, member_offset) else {
                    continue;
                };
                let Some(name) = self.string(name_offset) else {
                    continue;
                };
                if !matches!(name, "hdr" | "len") {
                    continue;
                }
                let member_type = read_u32(self.data, member_offset + 4)
                    .ok_or_else(|| anyhow::anyhow!("BTF load_info member type is truncated"))?;
                // 目标上这两个字段都必须是 8 字节宽；否则就不是我们所知的
                // load_info。
                ensure!(
                    self.type_size(member_type) == Some(ARM64_POINTER_SIZE),
                    "BTF struct load_info.{name} is not eight bytes"
                );
                let offset = self.member_offset(structure, index).ok_or_else(|| {
                    anyhow::anyhow!("BTF struct load_info.{name} is not byte-aligned")
                })?;
                if name == "hdr" {
                    hdr = Some(offset);
                } else {
                    len = Some(offset);
                }
            }
            // 忽略不完整的定义（例如缺少某个成员的精简结构体），而不是让整个
            // 解析失败。
            let Some((hdr_offset, len_offset)) = hdr.zip(len) else {
                continue;
            };
            // 对 STRUCT 记录，size_or_type 是总字节大小。
            let structure_size = u64::from(structure.size_or_type);
            let hdr_end = hdr_offset.checked_add(ARM64_POINTER_SIZE);
            let len_end = len_offset.checked_add(ARM64_POINTER_SIZE);
            ensure!(
                hdr_end.is_some_and(|end| end <= structure_size)
                    && len_end.is_some_and(|end| end <= structure_size),
                "BTF struct load_info fields exceed the structure size"
            );
            layouts.insert(LoadInfoLayout {
                structure_size,
                hdr_offset,
                len_offset,
            });
        }
        if named_structs == 0 {
            return Ok(None);
        }
        // 内核确实有 load_info，但没有任何定义可用；此时拒绝比悄悄混入过期
        // 偏移量更安全。
        ensure!(
            !layouts.is_empty(),
            "BTF contains struct load_info without usable hdr/len members"
        );
        // 不同定义不一致意味着无法确定运行内核实际使用哪套 ABI。
        ensure!(
            layouts.len() == 1,
            "BTF contains conflicting struct load_info layouts"
        );
        Ok(layouts.into_iter().next())
    }

    /// 校验模块加载器所调用内核函数的签名。
    ///
    /// 这些是镜像改写器会解析并调用的函数；若参数个数变化，镜像中固化的
    /// 调用约定就会出错。BTF 可能省略 FUNC 记录，因此缺失时静默通过，只
    /// 校验实际存在的记录。
    fn validate_function_abis(&self) -> Result<()> {
        const EXPECTED_ARITIES: &[(&str, u16)] = &[
            ("load_module", 3),
            ("vmalloc", 1),
            ("vmalloc_noprof", 1),
            ("memcpy", 3),
            ("kstrdup", 2),
            ("strndup_user", 2),
            ("memblock_reserve", 2),
        ];

        for &(name, expected_arity) in EXPECTED_ARITIES {
            for function in self.types.iter().filter(|btf_type| {
                btf_type.kind == BTF_KIND_FUNC && self.string(btf_type.name_offset) == Some(name)
            }) {
                // FUNC.size_or_type 是函数原型的类型 id。
                let prototype = self
                    .type_by_id(function.size_or_type)
                    .ok_or_else(|| anyhow::anyhow!("BTF function {name} has no prototype"))?;
                ensure!(
                    prototype.kind == BTF_KIND_FUNC_PROTO && prototype.vlen == expected_arity,
                    "BTF function {name} has {} parameters; expected {expected_arity}",
                    prototype.vlen
                );
                if name == "load_module" {
                    // btf_param 条目为名称偏移（+0）和类型 id（+4）；
                    // 第一个参数必须是 `struct load_info *`。
                    let first_parameter = read_u32(self.data, prototype.payload_offset + 4)
                        .ok_or_else(|| anyhow::anyhow!("BTF load_module prototype is truncated"))?;
                    ensure!(
                        self.points_to_named_struct(first_parameter, "load_info"),
                        "BTF load_module first parameter is not struct load_info *"
                    );
                }
            }
        }
        Ok(())
    }

    /// BTF 头部在被扫描镜像中的偏移。
    pub const fn file_offset(&self) -> usize {
        self.file_offset
    }

    /// 整个 BTF 数据块的长度，从头部到较晚结束的那个节。
    pub const fn size(&self) -> usize {
        self.blob_end - self.file_offset
    }

    /// 重新校验候选并提取调用方需要的 ABI 数据。
    ///
    /// 结构上合法但与加载器目标内核 ABI 不一致的 BTF，也可能在这两步失败。
    pub fn to_kernel_btf(&self) -> Result<KernelBtf> {
        let load_info = self.load_info_layout()?;
        self.validate_function_abis()?;
        Ok(KernelBtf {
            file_offset: self.file_offset,
            size: self.size(),
            type_count: self.types.len(),
            load_info,
        })
    }
}

/// 扫描镜像中的所有结构合法的 BTF 数据块。
///
/// 此时内核镜像没有可靠的节表，因此通过搜索魔数/版本/标志前缀来寻找候选。
/// 每个命中都会完整解析；误报只会解析失败。命中后从下一字节继续扫描，以便
/// 发现重叠或嵌套的数据块。将候选与真实 BTF 节匹配的工作留给调用方。
pub fn find_btf_candidates(image: &[u8]) -> Vec<BtfCandidate<'_>> {
    let mut candidates = Vec::new();
    let mut search_from = 0usize;
    while search_from <= image.len().saturating_sub(BTF_MAGIC_BYTES.len()) {
        let Some(relative) = image[search_from..]
            .windows(BTF_MAGIC_BYTES.len())
            .position(|window| window == BTF_MAGIC_BYTES)
        else {
            break;
        };
        let file_offset = search_from + relative;
        if let Some(candidate) = BtfCandidate::parse(image, file_offset) {
            candidates.push(candidate);
        }
        search_from = file_offset.saturating_add(1);
    }
    candidates
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 累积 BTF 字符串区。偏移 0 处的起始 NUL 表示空字符串，这是 BTF 的
    /// 要求。
    #[derive(Default)]
    struct Strings {
        data: Vec<u8>,
    }

    impl Strings {
        fn new() -> Self {
            Self { data: vec![0] }
        }

        /// 追加 `value` 和 NUL 终止符，并返回其节内偏移。
        fn add(&mut self, value: &str) -> u32 {
            let offset = u32::try_from(self.data.len()).unwrap();
            self.data.extend_from_slice(value.as_bytes());
            self.data.push(0);
            offset
        }
    }

    fn push_u32(output: &mut Vec<u8>, value: u32) {
        output.extend_from_slice(&value.to_le_bytes());
    }

    /// 写入 12 字节 BTF 类型头：名称偏移、打包的 kind/vlen 信息和
    /// `size_or_type` 字段。
    fn push_type(output: &mut Vec<u8>, name: u32, kind: u8, vlen: u16, size_or_type: u32) {
        push_u32(output, name);
        push_u32(output, (u32::from(kind) << 24) | u32::from(vlen));
        push_u32(output, size_or_type);
    }

    /// 组装一个最小 BTF 数据块，包含一个或多个 `load_info` 定义、可选的
    /// `LOADING_MODULE` 枚举以及可选的 `load_module` 函数原型。类型 id 按
    /// 写入顺序从 1 开始分配，因此下面的指针/原型 id 由前面记录的数量计算。
    fn build_btf(
        load_info_layouts: &[(u32, u32, u32)],
        loading_modules: &[u32],
        arity: u16,
    ) -> Vec<u8> {
        let mut strings = Strings::new();
        let unsigned_long = strings.add("unsigned long");
        let hdr = strings.add("hdr");
        let len = strings.add("len");
        let load_info = strings.add("load_info");
        let enum_name = strings.add("kernel_load_data_id");
        let loading_module_name = strings.add("LOADING_MODULE");
        let load_module = strings.add("load_module");
        let parameter = strings.add("parameter");

        let mut types = Vec::new();
        // 类型 id 1：unsigned long（INT 负载是其 32 位编码）。
        push_type(&mut types, unsigned_long, BTF_KIND_INT, 0, 8);
        push_u32(&mut types, 64);
        // 类型 id 2：指向类型 1 的指针。
        push_type(&mut types, 0, BTF_KIND_PTR, 0, 1);
        // 第一份 load_info 定义从类型 id 3 开始。
        for &(size, hdr_offset, len_offset) in load_info_layouts {
            push_type(&mut types, load_info, BTF_KIND_STRUCT, 2, size);
            // 两个成员都是 8 字节宽（指针和 unsigned long）；BTF 成员偏移
            // 以位为单位存储，因此乘以 8。
            push_u32(&mut types, hdr);
            push_u32(&mut types, 2);
            push_u32(&mut types, hdr_offset * 8);
            push_u32(&mut types, len);
            push_u32(&mut types, 1);
            push_u32(&mut types, len_offset * 8);
        }
        if !loading_modules.is_empty() {
            push_type(
                &mut types,
                enum_name,
                BTF_KIND_ENUM,
                u16::try_from(loading_modules.len()).unwrap(),
                4,
            );
            for &value in loading_modules {
                push_u32(&mut types, loading_module_name);
                push_u32(&mut types, value);
            }
        }
        if !load_info_layouts.is_empty() {
            // id 1 和 2 是 int 和指针；第一份 load_info 紧随其后。
            let first_load_info_id = 3u32;
            // 若存在枚举记录则跳过它，找到下一个空闲 id。
            let pointer_id = u32::try_from(3 + load_info_layouts.len()).unwrap()
                + u32::from(!loading_modules.is_empty());
            push_type(&mut types, 0, BTF_KIND_PTR, 0, first_load_info_id);
            let prototype_id = pointer_id + 1;
            // 返回类型 id 1（unsigned long）不计入 vlen；第一个参数是
            // load_info 指针，其余是普通 int。
            push_type(&mut types, 0, BTF_KIND_FUNC_PROTO, arity, 1);
            for index in 0..arity {
                push_u32(&mut types, parameter);
                push_u32(&mut types, if index == 0 { pointer_id } else { 1 });
            }
            push_type(&mut types, load_module, BTF_KIND_FUNC, 0, prototype_id);
        }

        // BTF 头部：魔数、版本、标志、hdr_len，然后是类型/字符串区的偏移
        // （相对于头部末尾）和长度。类型紧接头部之后，因此类型偏移为 0；
        // 字符串在类型之后，因此字符串偏移等于类型长度。
        let mut output = Vec::new();
        output.extend_from_slice(&BTF_MAGIC.to_le_bytes());
        output.push(BTF_VERSION);
        output.push(0);
        push_u32(&mut output, BTF_HEADER_SIZE as u32);
        push_u32(&mut output, 0);
        push_u32(&mut output, u32::try_from(types.len()).unwrap());
        push_u32(&mut output, u32::try_from(types.len()).unwrap());
        push_u32(&mut output, u32::try_from(strings.data.len()).unwrap());
        output.extend_from_slice(&types);
        output.extend_from_slice(&strings.data);
        output
    }

    /// 解析镜像，断言恰好找到一个候选，并将其校验为 `KernelBtf`。
    fn recover_single_btf(image: &[u8]) -> Result<KernelBtf> {
        let candidates = find_btf_candidates(image);
        assert_eq!(candidates.len(), 1);
        candidates[0].to_kernel_btf()
    }

    #[test]
    fn recovers_load_info_and_loading_module() {
        let blob = build_btf(&[(136, 16, 24)], &[2], 3);
        let mut image = vec![0xaa; 32];
        image.extend_from_slice(&blob);
        let btf = recover_single_btf(&image).unwrap();
        assert_eq!(btf.file_offset, 32);
        assert_eq!(btf.size, blob.len());
        assert_eq!(
            btf.load_info,
            Some(LoadInfoLayout {
                structure_size: 136,
                hdr_offset: 16,
                len_offset: 24,
            })
        );
    }

    #[test]
    fn ignores_false_magic_before_valid_btf() {
        let mut image = BTF_MAGIC_BYTES.to_vec();
        image.extend_from_slice(&[0; 20]);
        image.extend_from_slice(&build_btf(&[(136, 16, 24)], &[2], 3));
        assert_eq!(find_btf_candidates(&image).len(), 1);
    }

    #[test]
    fn returns_multiple_valid_btf_candidates() {
        let blob = build_btf(&[(136, 16, 24)], &[2], 3);
        let mut image = blob.clone();
        image.extend_from_slice(&blob);
        let candidates = find_btf_candidates(&image);
        assert_eq!(candidates.len(), 2);
        assert_eq!(candidates[0].file_offset(), 0);
        assert_eq!(candidates[1].file_offset(), blob.len());
    }

    #[test]
    fn malformed_btf_is_treated_as_unavailable() {
        assert!(find_btf_candidates(BTF_MAGIC_BYTES).is_empty());
    }

    #[test]
    fn rejects_conflicting_load_info_layouts() {
        let blob = build_btf(&[(136, 16, 24), (152, 24, 32)], &[2], 3);
        assert!(
            recover_single_btf(&blob)
                .unwrap_err()
                .to_string()
                .contains("conflicting struct load_info layouts")
        );
    }

    #[test]
    fn rejects_incompatible_load_module_arity() {
        let blob = build_btf(&[(136, 16, 24)], &[2], 2);
        assert!(
            recover_single_btf(&blob)
                .unwrap_err()
                .to_string()
                .contains("load_module has 2 parameters")
        );
    }

    #[test]
    fn missing_abi_records_use_callers_fallback() {
        let blob = build_btf(&[], &[], 0);
        let btf = recover_single_btf(&blob).unwrap();
        assert_eq!(btf.load_info, None);
    }

    #[test]
    fn rejects_conflicting_loading_module_values() {
        let blob = build_btf(&[(136, 16, 24)], &[2, 3], 3);
        assert!(
            recover_single_btf(&blob)
                .unwrap_err()
                .to_string()
                .contains("conflicting LOADING_MODULE values")
        );
    }
}
