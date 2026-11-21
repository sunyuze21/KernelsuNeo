//! 用户态特性开关管理器。
//!
//! 特性值存在于两处：内核（通过 feature supercall 访问）和 /data/adb/ksu 下的
//! 小型二进制配置。本模块在启动时加载、应用、裁剪并持久化该配置，同时为
//! 通过 `manage.<feature>` 模块配置声明归属的特性执行模块所有权校验。

use crate::sulog;
use anyhow::{Context, Result, bail};
use const_format::concatcp;
use std::collections::HashMap;
use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;

use crate::defs;
use crate::feature_id::{FeatureId, parse_feature_id};

const FEATURE_CONFIG_PATH: &str = concatcp!(defs::WORKING_DIR, ".feature_config");
// 标识配置文件；按小端字节序依次是 "USK" 和 0x7f。
#[allow(clippy::unreadable_literal)]
const FEATURE_MAGIC: u32 = 0x7f4b5355;
// 仅当磁盘布局变化时才递增；读取端在版本不匹配时仅告警，
// 以便降级时仍有机会解析。
const FEATURE_VERSION: u32 = 1;

/// 在内核更新成功后执行用户态副作用。
///
/// 值由内核持有，这个钩子只做响应。尤其是 sulog，需要其守护进程运行，
/// 内核才有日志事件的消费者。
fn on_feature_applied(feature_id: FeatureId, value: u64) {
    if feature_id == FeatureId::Sulog
        && value != 0
        && let Err(err) = sulog::ensure_sulogd_running()
    {
        log::warn!("failed to ensure sulogd is running after feature init: {err:#}");
    }
}

/// 在内核中应用特性，然后执行其用户态钩子。
fn set_kernel_feature(feature_id: FeatureId, value: u64) -> Result<()> {
    crate::ksucalls::set_feature(feature_id as u32, value)
        .with_context(|| format!("Failed to set feature {} to {value}", feature_id.name()))?;

    on_feature_applied(feature_id, value);

    Ok(())
}

/// 判断内核是否将该特性标记为由启动配置强制指定。
///
/// 查询失败按“未强制”处理；后续写入会报告真正的错误，
/// 而不是让这个探测阻断整个启动。
fn is_forced(id: u32) -> bool {
    crate::ksucalls::get_feature(id).is_ok_and(|state| state.forced)
}

/// 以 `特性 id -> 值` 的形式加载持久化配置。
///
/// 文件不存在不算错误，返回空映射，从而使用内核默认值。格式为小端：
/// magic、version、条目数，然后是一组 `(u32 id, u64 value)`。
pub fn load_binary_config() -> Result<HashMap<u32, u64>> {
    let path = Path::new(FEATURE_CONFIG_PATH);
    if !path.exists() {
        log::info!("Feature config not found, using defaults");
        return Ok(HashMap::new());
    }

    let mut file = File::open(path).with_context(|| "Failed to open feature config")?;

    let mut magic_buf = [0u8; 4];
    file.read_exact(&mut magic_buf)
        .with_context(|| "Failed to read magic")?;
    let magic = u32::from_le_bytes(magic_buf);

    if magic != FEATURE_MAGIC {
        bail!("Invalid feature config magic: expected 0x{FEATURE_MAGIC:08x}, got 0x{magic:08x}");
    }

    let mut version_buf = [0u8; 4];
    file.read_exact(&mut version_buf)
        .with_context(|| "Failed to read version")?;
    let version = u32::from_le_bytes(version_buf);

    if version != FEATURE_VERSION {
        // 仅告警但继续解析：若在此拒绝，较新 ksud 写出的配置会让较旧版本无法启动。
        log::warn!(
            "Feature config version mismatch: expected {FEATURE_VERSION}, got {version
            }",
        );
    }

    let mut count_buf = [0u8; 4];
    file.read_exact(&mut count_buf)
        .with_context(|| "Failed to read count")?;
    let count = u32::from_le_bytes(count_buf);

    let mut features = HashMap::new();
    for _ in 0..count {
        let mut id_buf = [0u8; 4];
        let mut value_buf = [0u8; 8];

        file.read_exact(&mut id_buf)
            .with_context(|| "Failed to read feature id")?;
        file.read_exact(&mut value_buf)
            .with_context(|| "Failed to read feature value")?;

        let id = u32::from_le_bytes(id_buf);
        let value = u64::from_le_bytes(value_buf);

        features.insert(id, value);
    }

    log::info!("Loaded {} features from config", features.len());
    Ok(features)
}

/// 将 `features` 持久化到配置文件，覆盖原有内容。
///
/// 先创建目录，因为此操作可能在工作目录其余部分就绪前运行。HashMap 的迭代
/// 顺序任意，但加载端不依赖该顺序。`sync_all` 保证文件在可能的重启前落盘。
pub fn save_binary_config(features: &HashMap<u32, u64>) -> Result<()> {
    crate::utils::ensure_dir_exists(Path::new(defs::WORKING_DIR))?;

    let path = Path::new(FEATURE_CONFIG_PATH);
    let mut file = File::create(path).with_context(|| "Failed to create feature config")?;

    file.write_all(&FEATURE_MAGIC.to_le_bytes())
        .with_context(|| "Failed to write magic")?;

    file.write_all(&FEATURE_VERSION.to_le_bytes())
        .with_context(|| "Failed to write version")?;

    let count = features.len() as u32;
    file.write_all(&count.to_le_bytes())
        .with_context(|| "Failed to write count")?;

    for (&id, &value) in features {
        file.write_all(&id.to_le_bytes())
            .with_context(|| format!("Failed to write feature id {id}"))?;
        file.write_all(&value.to_le_bytes())
            .with_context(|| format!("Failed to write feature value for id {id}"))?;
    }

    // 刷盘，避免崩溃或重启留下截断的配置，导致下次启动解析失败。
    file.sync_all()
        .with_context(|| "Failed to sync feature config")?;

    log::info!("Saved {} features to config", features.len());
    Ok(())
}

/// 把持久化的特性值推送到内核。
///
/// 应用过程是尽力而为的：某个特性不受支持或被拒绝时只记录日志并跳过，
/// 让其余特性和启动流程继续。强制特性保持不变，因为内核已应用启动配置。
pub fn apply_config(features: &HashMap<u32, u64>) {
    log::info!("Applying feature configuration to kernel...");

    let mut applied = 0;
    for (&id, &value) in features {
        if is_forced(id) {
            log::info!("Skip feature {id}: forced by boot config");
            continue;
        }
        match FeatureId::from_u32(id) {
            Some(feature_id) => match set_kernel_feature(feature_id, value) {
                Ok(()) => {
                    log::info!("Set feature {} to {value}", feature_id.name());
                    applied += 1;
                }
                Err(e) => {
                    log::warn!("Failed to set feature {}: {e}", feature_id.name());
                }
            },
            None => match crate::ksucalls::set_feature(id, value) {
                // 当前 ksud 不认识：直接透传原始 id，避免较新内核的特性在旧配置中丢失。
                Ok(()) => {
                    log::info!("Set feature {id} to {value}");
                    applied += 1;
                }
                Err(e) => {
                    log::warn!("Failed to set feature {id}: {e}");
                }
            },
        }
    }

    log::info!("Applied {applied} features successfully");
}

/// 打印单个特性在内核中的当前状态。
pub fn get_feature(id: &str) -> Result<()> {
    let feature_id = parse_feature_id(id)?;
    let state = crate::ksucalls::get_feature(feature_id as u32)
        .with_context(|| format!("Failed to get feature {id}"))?;

    println!("Feature: {}", feature_id.name());
    println!("ID: {}", feature_id as u32);
    println!("Description: {}", feature_id.description());
    println!("Supported: {}", if state.supported { "yes" } else { "no" });

    if !state.supported {
        println!("Status: unsupported");
        return Ok(());
    }

    // Manager 通过 `Value:` 行读取当前值，格式保持不变。
    println!("Value: {}", state.value);
    println!(
        "Status: {}",
        if state.value != 0 {
            "enabled"
        } else {
            "disabled"
        }
    );
    println!("Forced: {}", if state.forced { "yes" } else { "no" });

    Ok(())
}

/// 打印配置文件中持久化的值，不查询内核。
pub fn get_feature_config(id: &str) -> Result<()> {
    let feature_id = parse_feature_id(id)?;

    let features = load_binary_config()?;
    let id_u32 = feature_id as u32;

    println!("Feature: {}", feature_id.name());
    println!("ID: {id_u32}");
    println!("Description: {}", feature_id.description());
    println!("Source: config");

    if let Some(value) = features.get(&id_u32) {
        println!("Value: {value}");
        println!(
            "Status: {}",
            if *value != 0 { "enabled" } else { "disabled" }
        );
    } else {
        println!("Status: not-set");
    }

    Ok(())
}

/// 从 CLI 设置特性，并遵守启动配置与模块所有权限制。
///
/// 强制特性由内核启动配置持有，不能在此修改。若某个活跃模块通过
/// `manage.<feature>=true` 声明了该特性，则只有该模块可以修改（通过 ksud
/// 为模块脚本导出的 `KSU_MODULE` 环境变量识别）。
pub fn set_feature(id: &str, value: u64) -> Result<()> {
    let feature_id = parse_feature_id(id)?;

    if let Ok(state) = crate::ksucalls::get_feature(feature_id as u32)
        && state.forced
    {
        bail!(
            "Feature '{}' is forced to {} by boot config. Direct modification is not allowed.",
            feature_id.name(),
            state.value
        );
    }

    // 检查该特性是否由任意模块管理
    if let Ok(managed_features_map) = crate::module::get_managed_features() {
        // 找出管理该特性的模块
        let managing_modules: Vec<&String> = managed_features_map
            .iter()
            .filter(|(_, features)| features.iter().any(|f| f == feature_id.name()))
            .map(|(module_id, _)| module_id)
            .collect();

        if !managing_modules.is_empty() {
            // 特性已被管理，检查调用方是否为授权模块
            let caller_module = std::env::var("KSU_MODULE").unwrap_or_default();

            if caller_module.is_empty() || !managing_modules.contains(&&caller_module) {
                bail!(
                    "Feature '{}' is managed by module(s): {}. Direct modification is not allowed.",
                    feature_id.name(),
                    managing_modules
                        .iter()
                        .map(|s| s.as_str())
                        .collect::<Vec<_>>()
                        .join(", ")
                );
            }

            log::info!(
                "Module '{caller_module}' is setting managed feature '{}'",
                feature_id.name()
            );
        }
    }

    set_kernel_feature(feature_id, value)?;

    println!(
        "Feature '{}' set to {value} ({})",
        feature_id.name(),
        if value != 0 { "enabled" } else { "disabled" }
    );

    Ok(())
}

/// 打印所有已知特性的内核状态和归属标记。
///
/// 模块归属使用反向映射渲染，因此一个特性可以显示所有声明它的模块，
/// 而不只是第一个找到的模块。
pub fn list_features() {
    println!("Available Features:");
    println!("{}", "=".repeat(80));

    // 从模块获取受管理的特性
    let managed_features_map = crate::module::get_managed_features().unwrap_or_default();

    // 构建反向映射：feature_name -> Vec<module_id>
    let mut feature_to_modules: HashMap<String, Vec<String>> = HashMap::new();
    for (module_id, feature_list) in &managed_features_map {
        for feature_name in feature_list {
            feature_to_modules
                .entry(feature_name.clone())
                .or_default()
                .push(module_id.clone());
        }
    }

    for feature_id in &FeatureId::ALL {
        let id = *feature_id as u32;
        // 查询失败时回落到默认状态（不支持、值为 0），
        // 使内核缺少某特性时列表仍然可用。
        let state = crate::ksucalls::get_feature(id).unwrap_or_default();

        let status = if !state.supported {
            "NOT_SUPPORTED".to_string()
        } else if state.value != 0 {
            format!("ENABLED ({})", state.value)
        } else {
            "DISABLED".to_string()
        };

        let managed_by = feature_to_modules.get(feature_id.name());
        let managed_mark = if state.forced {
            " [FORCED]"
        } else if managed_by.is_some() {
            " [MODULE_MANAGED]"
        } else {
            ""
        };

        println!(
            "[{}] {} (ID={}){}",
            status,
            feature_id.name(),
            id,
            managed_mark
        );
        println!("    {}", feature_id.description());

        if let Some(modules) = managed_by {
            println!(
                "    ⚠️  Managed by module(s): {} (forced to 0 on initialization)",
                modules.join(", ")
            );
        }

        println!();
    }
}

/// CLI 入口：加载持久化配置并立即应用。
pub fn load_config_and_apply() -> Result<()> {
    let features = load_binary_config()?;

    if features.is_empty() {
        println!("No features found in config file");
        return Ok(());
    }

    apply_config(&features);
    println!("Feature configuration loaded and applied");
    Ok(())
}

/// 把当前内核特性值快照写入配置文件。
///
/// 先加载现有配置，使未被覆盖的条目得以保留：强制特性保留持久化的值，
/// 不支持的特性维持原样。只更新受支持且未被强制的值；强制特性仍由启动配置
/// 控制，待其不再强制后可再编辑。
pub fn save_config() -> Result<()> {
    // 保留强制特性的持久化值；一旦启动配置不再强制指定它们，
    // 这些值会重新生效。
    let mut features = load_binary_config().unwrap_or_default();

    for feature_id in &FeatureId::ALL {
        let id = *feature_id as u32;
        if let Ok(state) = crate::ksucalls::get_feature(id)
            && state.supported
            && !state.forced
        {
            features.insert(id, state.value);
            log::info!("Saved feature {} = {}", feature_id.name(), state.value);
        }
    }

    save_binary_config(&features)?;
    println!(
        "Current feature states saved to config file ({} features)",
        features.len()
    );
    Ok(())
}

/// 为脚本和模块打印单个机器可读的状态标记。
///
/// 优先级依次为 forced、模块管理、内核支持；输出是 `forced`、`managed`、
/// `supported` 或 `unsupported` 之一。
pub fn check_feature(id: &str) -> Result<()> {
    let feature_id = parse_feature_id(id)?;

    // 启动配置强制指定的优先级高于模块管理
    if crate::ksucalls::get_feature(feature_id as u32).is_ok_and(|state| state.forced) {
        println!("forced");
        return Ok(());
    }

    // 检查该特性是否由任意模块管理
    let managed_features_map = crate::module::get_managed_features().unwrap_or_default();
    let is_managed = managed_features_map
        .values()
        .any(|features| features.iter().any(|f| f == feature_id.name()));

    if is_managed {
        println!("managed");
        return Ok(());
    }

    // 检查内核是否支持该特性
    let state = crate::ksucalls::get_feature(feature_id as u32)
        .with_context(|| format!("Failed to get feature {id}"))?;

    if state.supported {
        println!("supported");
    } else {
        println!("unsupported");
    }

    Ok(())
}

/// 强制特性由内核自身应用，这里只执行用户态部分。
/// 无论安全模式和持久化配置如何都会运行，因为两种情况下
/// 内核都会应用它们。
pub fn init_forced_features() {
    for feature_id in FeatureId::ALL {
        if let Ok(state) = crate::ksucalls::get_feature(feature_id as u32)
            && state.forced
        {
            log::info!(
                "Feature '{}' is forced to {} by boot config, skip loading",
                feature_id.name(),
                state.value
            );
            on_feature_applied(feature_id, state.value);
        }
    }
}

/// 启动时初始化非强制特性。
///
/// 在活跃模块集合确定后运行，因为被声明为模块管理的特性必须在应用前从配置中
/// 移除：由所属模块自行设置。裁剪后的配置会写回，避免后续启动重复尝试。
/// 枚举模块失败时只记录告警，并应用完整配置。
pub fn init_features() -> Result<()> {
    log::info!("Initializing features from config...");

    let mut features = load_binary_config()?;

    // 获取活跃模块管理的特性，并在初始化时跳过它们
    if let Ok(managed_features_map) = crate::module::get_managed_features() {
        if !managed_features_map.is_empty() {
            log::info!(
                "Found {} modules managing features",
                managed_features_map.len()
            );

            // 汇总所有需要跳过的受管理特性 ID
            for (module_id, feature_list) in &managed_features_map {
                log::info!(
                    "Module '{module_id}' manages {} feature(s)",
                    feature_list.len()
                );

                for feature_name in feature_list {
                    if let Ok(feature_id) = parse_feature_id(feature_name) {
                        let feature_id_u32 = feature_id as u32;
                        // 从配置中移除受管理的特性，交由模块自行控制
                        if features.remove(&feature_id_u32).is_some() {
                            log::info!(
                                "  - Skipping managed feature '{feature_name}' (controlled by module: {module_id})",
                            );
                        } else {
                            log::info!(
                                "  - Feature '{feature_name}' is managed by module '{module_id}', skipping",
                            );
                        }
                    } else {
                        log::warn!(
                            "  - Unknown managed feature '{feature_name}' from module '{module_id}', ignoring",
                        );
                    }
                }
            }
        }
    } else {
        log::warn!(
            "Failed to get managed features from modules, continuing with normal initialization"
        );
    }

    if features.is_empty() {
        log::info!("No features to apply, skipping initialization");
        return Ok(());
    }

    apply_config(&features);

    // 保存配置（不含受管理的特性）
    save_binary_config(&features)?;
    log::info!("Saved feature configuration to file");

    Ok(())
}
