//! APK Signature Scheme v2 解析器，用于获取 APK 签名者指纹。
//!
//! APK Signing Block 紧邻 ZIP 中央目录之前，因此先反向扫描文件找到
//! End of Central Directory 记录。这里只理解 v2 块；遇到 v3/v3.1 块会直接
//! 报错而不是忽略，避免调用方拿到有歧义的签名者。

use anyhow::{Result, ensure};
use std::io::{Read, Seek, SeekFrom};

/// 返回首个 v2 签名者的 `(证书长度, 证书 SHA-256)`。
///
/// `apk` 是完整 APK 文件路径。哈希针对 DER 编码的证书字节，而非 APK 内容。
pub fn get_apk_signature(apk: &str) -> Result<(u32, String)> {
    let mut buffer = [0u8; 0x10]; // 16 字节的 "APK Sig Block 42" 魔数
    let mut size4 = [0u8; 4];
    let mut size8 = [0u8; 8];
    let mut size_of_block = [0u8; 8];

    let mut f = std::fs::File::open(apk)?;

    // EOCD 记录固定 22 字节，后面是可选的注释；其最后两个字节保存注释长度。
    // 逐个尝试候选长度，直到该值指向自身。
    let mut i = 0;
    loop {
        let mut n = [0u8; 2];
        f.seek(SeekFrom::End(-i - 2))?;
        f.read_exact(&mut n)?;

        let n = u16::from_le_bytes(n);
        if i64::from(n) == i {
            // 找到候选：回退到固定 22 字节 EOCD 的起始位置。
            f.seek(SeekFrom::Current(-22))?;
            f.read_exact(&mut size4)?;

            // 用 XOR 校验 EOCD 签名 0x06054b50 ("PK\x05\x06")。
            if u32::from_le_bytes(size4) ^ 0xcafe_babe_u32 == 0xccfb_f1ee_u32 {
                if i > 0 {
                    println!("warning: comment length is {i}");
                }
                break;
            }
        }

        // 注释长度是 u16，超过 0xffff 就不可能是 EOCD。
        ensure!(i < 0xffff, "not a zip file");

        i += 1;
    }

    // 从 EOCD+4 跳到 EOCD+16 处的中央目录偏移字段。
    f.seek(SeekFrom::Current(12))?;
    // 偏移量
    f.read_exact(&mut size4)?;
    // 签名块尾部是 8 字节的 size 加上 16 字节魔数，共 0x18 字节，紧贴中央目录之前。
    f.seek(SeekFrom::Start(u64::from(u32::from_le_bytes(size4)) - 0x18))?;

    f.read_exact(&mut size8)?; // 尾部块大小
    f.read_exact(&mut buffer)?;

    ensure!(&buffer == b"APK Sig Block 42", "Can not found sig block");

    // 前导 size 字段位于中央目录前 block_size + 8 字节处：
    // size 值本身不包含前导 size 字段。
    let pos = u64::from(u32::from_le_bytes(size4)) - (u64::from_le_bytes(size8) + 0x8);
    f.seek(SeekFrom::Start(pos))?;
    f.read_exact(&mut size_of_block)?;

    ensure!(size_of_block == size8, "not a signed apk");

    // 只记录 v2 的首个签名者；v3/v3.1 只记录存在性，最终统一拒绝以避免歧义。
    let mut v2_signing: Option<(u32, String)> = None;
    let mut v3_signing_exist = false;
    let mut v3_1_signing_exist = false;

    // 键值对的布局是 (8 字节 value 长度, 4 字节 id, value)。当接下来读到的
    // 8 字节等于尾部块大小时停止，表示已到达 footer。
    loop {
        let mut id = [0u8; 4];
        let mut offset = 4u32;

        f.read_exact(&mut size8)?; // 序列长度
        if size8 == size_of_block {
            break;
        }

        f.read_exact(&mut id)?; // id

        let id = u32::from_le_bytes(id);
        if id == 0x7109_871a_u32 {
            // APK 签名方案 v2
            v2_signing = Some(calc_cert_sha256(&mut f, &mut size4, &mut offset)?);
        } else if id == 0xf053_68c0_u32 {
            // v3 签名方案
            v3_signing_exist = true;
        } else if id == 0x1b93_ad61_u32 {
            // v3.1 签名方案：致谢 vvb2060
            v3_1_signing_exist = true;
        }

        // 跳过本键值对的剩余部分；对 v2 而言，`offset` 已被解析器推进，
        // 因此从证书数据结束处继续。
        f.seek(SeekFrom::Current(
            i64::from_le_bytes(size8) - i64::from(offset),
        ))?;
    }

    if v3_signing_exist || v3_1_signing_exist {
        return Err(anyhow::anyhow!("Unexpected v3 signature found!"));
    }

    v2_signing.ok_or_else(|| anyhow::anyhow!("No signature found!"))
}

/// 遍历首个 v2 签名者并哈希其首张证书。
///
/// v2 的 value 布局是签名者序列；此函数只读取首个签名者和首张证书，
/// 足以识别签名者。`offset` 从 4 开始（pair 长度和 id 已消费），
/// 每读一个字节都会递增，供调用方跳到下一个键值对。
fn calc_cert_sha256(
    f: &mut std::fs::File,
    size4: &mut [u8; 4],
    offset: &mut u32,
) -> Result<(u32, String)> {
    f.read_exact(size4)?; // 签名者序列长度
    f.read_exact(size4)?; // 签名者长度
    f.read_exact(size4)?; // 签名数据长度
    *offset += 0x4 * 3;

    f.read_exact(size4)?; // 摘要序列长度
    let pos = u32::from_le_bytes(*size4); // 跳过摘要（digests）
    f.seek(SeekFrom::Current(i64::from(pos)))?;
    *offset += 0x4 + pos;

    f.read_exact(size4)?; // 证书序列长度
    f.read_exact(size4)?; // 证书长度
    *offset += 0x4 * 2;

    let cert_len = u32::from_le_bytes(*size4);
    // 证书长度来自不可信 APK，按声明长度一次性读入；不足时 read_exact 会报错。
    let mut cert: Vec<u8> = vec![0; cert_len as usize];
    f.read_exact(&mut cert)?;
    *offset += cert_len;

    Ok((cert_len, sha256::digest(&cert)))
}
