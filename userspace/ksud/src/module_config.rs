//! 模块级键值配置存储。
//!
//! 模块脚本通过 `ksud module config` 读写这些值；ksud 会把合并结果提供给 Manager
//! （`module list`），并用其中的 `manage.*` 键判断特性归属。每个模块在
//! /data/adb/ksu/module_configs/<id>/ 下拥有两个二进制文件：persist.config 跨重启保留，
//! tmp.config 由开机流程清除，存在期间覆盖 persist 中的值。

use anyhow::{Context, Result, bail};
use log::{debug, warn};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use crate::defs;
use crate::utils::ensure_dir_exists;

#[allow(clippy::unreadable_literal)]
const MODULE_CONFIG_MAGIC: u32 = 0x4b53554d; // "KSUM"
const MODULE_CONFIG_VERSION: u32 = 1;

// 校验上限
// 读写两端都会强制执行这些上限，避免损坏或恶意的文件让我们分配任意大的内存，
// 或生成其他工具无法解析的文件。
pub const MAX_CONFIG_KEY_LEN: usize = 256;
pub const MAX_CONFIG_VALUE_LEN: usize = 1024 * 1024; // 1MB
pub const MAX_CONFIG_COUNT: usize = 32;

/// 操作针对两个配置文件中的哪一个。
///
/// `Temp` 的值只对本次开机有效，合并时会覆盖 `Persist`。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConfigType {
    Persist,
    Temp,
}

impl ConfigType {
    const fn filename(self) -> &'static str {
        match self {
            Self::Persist => defs::PERSIST_CONFIG_NAME,
            Self::Temp => defs::TEMP_CONFIG_NAME,
        }
    }
}

/// 解析布尔型配置值
/// "true"、"1"（不区分大小写）视为真，其余一律视为假
pub fn parse_bool_config(value: &str) -> bool {
    // 除了明确的真值，其他内容（包括空值）都按 false 处理，方便模块写开关型配置。
    let trimmed = value.trim();
    trimmed.eq_ignore_ascii_case("true") || trimmed == "1"
}

/// 校验配置键
/// 与 module_id 使用相同的校验规则：^[a-zA-Z][a-zA-Z0-9._-]+$
/// - 必须以字母开头（a-zA-Z）
/// - 其后是一个或多个字母、数字、点、下划线或连字符
/// - 最短长度为 2 个字符
pub fn validate_config_key(key: &str) -> Result<()> {
    if key.is_empty() {
        bail!("Config key cannot be empty");
    }

    if key.len() > MAX_CONFIG_KEY_LEN {
        bail!(
            "Config key too long: {} bytes (max: {})",
            key.len(),
            MAX_CONFIG_KEY_LEN
        );
    }

    // 沿用 module_id 的模式以保持一致
    // 这同时保证键里没有路径分隔符，不会逃出模块配置目录。
    let re = regex_lite::Regex::new(r"^[a-zA-Z][a-zA-Z0-9._-]+$")?;
    if !re.is_match(key) {
        bail!("Invalid config key: '{key}'. Must match /^[a-zA-Z][a-zA-Z0-9._-]+$/");
    }

    Ok(())
}

/// 校验配置值
/// 只限制最大长度，不限制字符内容
/// 值以带长度前缀的二进制格式存储，因此任意 UTF-8 数据都是安全的
pub fn validate_config_value(value: &str) -> Result<()> {
    if value.len() > MAX_CONFIG_VALUE_LEN {
        bail!(
            "Config value too long: {} bytes (max: {})",
            value.len(),
            MAX_CONFIG_VALUE_LEN
        );
    }

    // 不限制字符内容：二进制存储格式可以安全承载任意 UTF-8
    Ok(())
}

/// 校验配置条目数量
fn validate_config_count(config: &HashMap<String, String>) -> Result<()> {
    if config.len() > MAX_CONFIG_COUNT {
        bail!(
            "Too many config entries: {} (max: {})",
            config.len(),
            MAX_CONFIG_COUNT
        );
    }
    Ok(())
}

/// 获取模块的配置目录路径
///
/// `module_id` 会被拼进路径，调用方必须先校验它。
fn get_config_dir(module_id: &str) -> PathBuf {
    Path::new(defs::MODULE_CONFIG_DIR).join(module_id)
}

/// 获取模块的配置文件路径
fn get_config_path(module_id: &str, config_type: ConfigType) -> PathBuf {
    get_config_dir(module_id).join(config_type.filename())
}

/// 确保配置目录存在
fn ensure_config_dir(module_id: &str) -> Result<PathBuf> {
    let dir = get_config_dir(module_id);
    ensure_dir_exists(&dir)?;
    Ok(dir)
}

/// 从二进制文件加载配置
///
/// 布局（所有整数均为小端）：magic、version、条目数，然后每个条目依次是
/// 键长度 + 键内容、值长度 + 值内容。文件不存在不算错误，返回空 map。
/// 分配内存前会用上面的上限检查各长度字段。
pub fn load_config(module_id: &str, config_type: ConfigType) -> Result<HashMap<String, String>> {
    crate::module::validate_module_id(module_id)?;

    let config_path = get_config_path(module_id, config_type);

    if !config_path.exists() {
        debug!("Config file not found: {}", config_path.display());
        return Ok(HashMap::new());
    }

    let mut file = File::open(&config_path)
        .with_context(|| format!("Failed to open config file: {}", config_path.display()))?;

    // 读取 magic
    let mut magic_buf = [0u8; 4];
    file.read_exact(&mut magic_buf)
        .with_context(|| "Failed to read magic")?;
    let magic = u32::from_le_bytes(magic_buf);

    if magic != MODULE_CONFIG_MAGIC {
        bail!("Invalid config magic: expected 0x{MODULE_CONFIG_MAGIC:08x}, got 0x{magic:08x}");
    }

    // 读取 version
    let mut version_buf = [0u8; 4];
    file.read_exact(&mut version_buf)
        .with_context(|| "Failed to read version")?;
    let version = u32::from_le_bytes(version_buf);

    if version != MODULE_CONFIG_VERSION {
        bail!("Unsupported config version: expected {MODULE_CONFIG_VERSION}, got {version}");
    }

    // 读取条目数
    let mut count_buf = [0u8; 4];
    file.read_exact(&mut count_buf)
        .with_context(|| "Failed to read count")?;
    let count = u32::from_le_bytes(count_buf) as usize;

    if count > MAX_CONFIG_COUNT {
        bail!("Too many config entries: {count} (max: {MAX_CONFIG_COUNT})");
    }

    // 读取各条目
    let mut config = HashMap::new();
    for i in 0..count {
        // 读取键长度
        let mut key_len_buf = [0u8; 4];
        file.read_exact(&mut key_len_buf)
            .with_context(|| format!("Failed to read key length for entry {i}"))?;
        let key_len = u32::from_le_bytes(key_len_buf) as usize;

        if key_len > MAX_CONFIG_KEY_LEN {
            bail!("Config key too long for entry {i}: {key_len} bytes (max: {MAX_CONFIG_KEY_LEN})");
        }

        // 读取键内容
        let mut key_buf = vec![0u8; key_len];
        file.read_exact(&mut key_buf)
            .with_context(|| format!("Failed to read key data for entry {i}"))?;
        let key = String::from_utf8(key_buf)
            .with_context(|| format!("Invalid UTF-8 in key for entry {i}"))?;

        // 读取值长度
        let mut value_len_buf = [0u8; 4];
        file.read_exact(&mut value_len_buf)
            .with_context(|| format!("Failed to read value length for entry {i}"))?;
        let value_len = u32::from_le_bytes(value_len_buf) as usize;

        if value_len > MAX_CONFIG_VALUE_LEN {
            bail!(
                "Config value too long for entry {i}: {value_len} bytes (max: {MAX_CONFIG_VALUE_LEN})"
            );
        }

        // 读取值内容
        let mut value_buf = vec![0u8; value_len];
        file.read_exact(&mut value_buf)
            .with_context(|| format!("Failed to read value data for entry {i}"))?;
        let value = String::from_utf8(value_buf)
            .with_context(|| format!("Invalid UTF-8 in value for entry {i}"))?;

        config.insert(key, value);
    }

    debug!(
        "Loaded {} entries from {}",
        config.len(),
        config_path.display()
    );
    Ok(config)
}

/// 将配置保存到二进制文件
///
/// 先校验所有键值，再写临时文件、fsync，然后改名覆盖目标文件。同一文件系统上的
/// rename 是原子的，因此读者不会看到写了一半的配置。
pub fn save_config(
    module_id: &str,
    config_type: ConfigType,
    config: &HashMap<String, String>,
) -> Result<()> {
    crate::module::validate_module_id(module_id)?;

    // 校验条目数量
    validate_config_count(config)?;

    // 校验所有键和值
    for (key, value) in config {
        validate_config_key(key).with_context(|| format!("Invalid config key: '{key}'"))?;
        validate_config_value(value)
            .with_context(|| format!("Invalid config value for key '{key}'"))?;
    }

    ensure_config_dir(module_id)?;

    let config_path = get_config_path(module_id, config_type);
    // with_extension 会替换已有的扩展名：persist.config -> persist.tmp。
    let temp_path = config_path.with_extension("tmp");

    // 先写入临时文件
    let mut file = File::create(&temp_path)
        .with_context(|| format!("Failed to create temp config file: {}", temp_path.display()))?;

    // 写入 magic
    file.write_all(&MODULE_CONFIG_MAGIC.to_le_bytes())
        .with_context(|| "Failed to write magic")?;

    // 写入 version
    file.write_all(&MODULE_CONFIG_VERSION.to_le_bytes())
        .with_context(|| "Failed to write version")?;

    // 写入条目数
    // 安全的类型转换：上面已按 MAX_CONFIG_COUNT 校验过条目数。
    let count = config.len() as u32;
    file.write_all(&count.to_le_bytes())
        .with_context(|| "Failed to write count")?;

    // 写入各条目
    for (key, value) in config {
        // 写入键长度
        let key_bytes = key.as_bytes();
        let key_len = key_bytes.len() as u32;
        file.write_all(&key_len.to_le_bytes())
            .with_context(|| format!("Failed to write key length for '{key}'"))?;

        // 写入键内容
        file.write_all(key_bytes)
            .with_context(|| format!("Failed to write key data for '{key}'"))?;

        // 写入值长度
        let value_bytes = value.as_bytes();
        let value_len = value_bytes.len() as u32;
        file.write_all(&value_len.to_le_bytes())
            .with_context(|| format!("Failed to write value length for '{key}'"))?;

        // 写入值内容
        file.write_all(value_bytes)
            .with_context(|| format!("Failed to write value data for '{key}'"))?;
    }

    file.sync_all()
        .with_context(|| "Failed to sync config file")?;

    // 原子重命名
    fs::rename(&temp_path, &config_path).with_context(|| {
        format!(
            "Failed to rename config file: {} -> {}",
            temp_path.display(),
            config_path.display()
        )
    })?;

    debug!(
        "Saved {} entries to {}",
        config.len(),
        config_path.display()
    );
    Ok(())
}

/// 获取单个配置值
#[allow(dead_code)]
pub fn get_config_value(
    module_id: &str,
    key: &str,
    config_type: ConfigType,
) -> Result<Option<String>> {
    // 只读取指定的一层；需要 tmp 覆盖 persist 时请用 merge_configs。
    let config = load_config(module_id, config_type)?;
    Ok(config.get(key).cloned())
}

/// 设置单个配置值
pub fn set_config_value(
    module_id: &str,
    key: &str,
    value: &str,
    config_type: ConfigType,
) -> Result<()> {
    // 尽早校验输入，以便给出更明确的错误信息
    validate_config_key(key)?;
    validate_config_value(value)?;

    // 读取-修改-写入；同一文件的并发写入没有加锁协调。
    let mut config = load_config(module_id, config_type)?;
    config.insert(key.to_string(), value.to_string());

    // 注意：save_config 也会校验，这里只是更早反馈问题
    save_config(module_id, config_type, &config)?;
    Ok(())
}

/// 删除单个配置值
pub fn delete_config_value(module_id: &str, key: &str, config_type: ConfigType) -> Result<()> {
    let mut config = load_config(module_id, config_type)?;

    if config.remove(key).is_none() {
        // 键不存在时直接报错，而不是悄悄重写文件。
        bail!("Key '{key}' not found in config");
    }

    save_config(module_id, config_type, &config)?;
    Ok(())
}

/// 清空所有配置值
pub fn clear_config(module_id: &str, config_type: ConfigType) -> Result<()> {
    let config_path = get_config_path(module_id, config_type);

    // 文件本来就不存在时，正是期望的最终状态，因此不算错误。
    if config_path.exists() {
        fs::remove_file(&config_path)
            .with_context(|| format!("Failed to remove config file: {}", config_path.display()))?;
        debug!("Cleared config: {}", config_path.display());
    }

    Ok(())
}

/// 合并 persist 与 temp 配置（temp 优先）
pub fn merge_configs(module_id: &str) -> Result<HashMap<String, String>> {
    crate::module::validate_module_id(module_id)?;

    // 某一层损坏时只告警并按空处理，这样另一层仍然可读；直接返回错误会隐藏本来完好的
    // persist 值。
    let mut merged = match load_config(module_id, ConfigType::Persist) {
        Ok(config) => config,
        Err(e) => {
            warn!("Failed to load persist config for module '{module_id}': {e}");
            HashMap::new()
        }
    };

    let temp = match load_config(module_id, ConfigType::Temp) {
        Ok(config) => config,
        Err(e) => {
            warn!("Failed to load temp config for module '{module_id}': {e}");
            HashMap::new()
        }
    };

    // temp 配置覆盖 persist 配置
    for (key, value) in temp {
        merged.insert(key, value);
    }

    Ok(merged)
}

/// 获取所有模块的配置（供遍历使用）
/// 一次性加载全部配置，尽量减少 I/O 开销
pub fn get_all_module_configs() -> Result<HashMap<String, HashMap<String, String>>> {
    let config_root = Path::new(defs::MODULE_CONFIG_DIR);

    if !config_root.exists() {
        return Ok(HashMap::new());
    }

    let mut all_configs = HashMap::new();

    for entry in fs::read_dir(config_root)
        .with_context(|| format!("Failed to read config directory: {}", config_root.display()))?
    {
        let entry = entry?;
        let path = entry.path();

        // 只有模块目录才有意义，配置根目录下的普通文件一律忽略。
        if !path.is_dir() {
            continue;
        }

        if let Some(module_id) = path.file_name().and_then(|n| n.to_str()) {
            match merge_configs(module_id) {
                Ok(config) => {
                    // 没有任何配置的模块会被省略，以减小发给 Manager 的数据量。
                    if !config.is_empty() {
                        all_configs.insert(module_id.to_string(), config);
                    }
                }
                Err(e) => {
                    warn!("Failed to load config for module '{module_id}': {e}");
                    // 继续处理其他模块
                }
            }
        }
    }

    Ok(all_configs)
}

/// 清空所有临时配置（在 post-fs-data 阶段调用）
pub fn clear_all_temp_configs() -> Result<()> {
    let config_root = Path::new(defs::MODULE_CONFIG_DIR);

    if !config_root.exists() {
        debug!("Config directory does not exist, nothing to clear");
        return Ok(());
    }

    let mut cleared_count = 0;

    for entry in fs::read_dir(config_root)
        .with_context(|| format!("Failed to read config directory: {}", config_root.display()))?
    {
        let entry = entry?;
        let path = entry.path();

        if !path.is_dir() {
            continue;
        }

        // 只删除 tmp.config；模块目录本身以及其中的 persist.config 都要保留。
        let temp_config = path.join(defs::TEMP_CONFIG_NAME);
        if temp_config.exists() {
            match fs::remove_file(&temp_config) {
                Ok(()) => {
                    debug!("Cleared temp config: {}", temp_config.display());
                    cleared_count += 1;
                }
                Err(e) => {
                    warn!("Failed to clear temp config {}: {e}", temp_config.display());
                }
            }
        }
    }

    if cleared_count > 0 {
        debug!("Cleared {cleared_count} temp config file(s)");
    }

    Ok(())
}

/// 清空某个模块的全部配置（卸载时调用）
pub fn clear_module_configs(module_id: &str) -> Result<()> {
    crate::module::validate_module_id(module_id)?;

    let config_dir = get_config_dir(module_id);

    // 卸载会同时丢弃两层配置；模块以后重新安装时从干净状态开始。
    if config_dir.exists() {
        fs::remove_dir_all(&config_dir).with_context(|| {
            format!(
                "Failed to remove config directory: {}",
                config_dir.display()
            )
        })?;
        debug!("Cleared all configs for module: {module_id}");
    }

    Ok(())
}
