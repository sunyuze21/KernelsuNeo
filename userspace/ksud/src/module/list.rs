//! 读取模块元数据，并生成 Manager 使用的模块列表和受管理特性。

use anyhow::{Context, Result, ensure};
use java_properties::PropertiesIter;
use log::{info, warn};

use std::collections::HashMap;
use std::io::Cursor;
use std::path::Path;

use super::foreach_active_module;
use crate::defs;
use crate::module_config::{self, parse_bool_config};
use crate::preinit;

/// 从给定模块路径读取 module.prop，并以 HashMap 形式返回
pub fn read_module_prop(module_path: &Path) -> Result<HashMap<String, String>> {
    let module_prop = module_path.join("module.prop");
    ensure!(
        module_prop.exists(),
        "module.prop not found in {}",
        module_path.display()
    );

    let content = std::fs::read(&module_prop)
        .with_context(|| format!("Failed to read module.prop: {}", module_prop.display()))?;

    let mut prop_map: HashMap<String, String> = HashMap::new();
    PropertiesIter::new_with_encoding(Cursor::new(content), encoding_rs::UTF_8)
        .read_into(|key, value| {
            prop_map.insert(key, value);
        })
        .with_context(|| format!("Failed to parse module.prop: {}", module_prop.display()))?;

    Ok(prop_map)
}

/// 把模块图标路径解析为磁盘上的绝对路径
fn resolve_icon_path(props: &mut HashMap<String, String>, key: &str, module_path: &Path) {
    if let Some(icon) = props.get(key) {
        let icon = icon.trim();
        if icon.is_empty() {
            return;
        }
        let icon_path = Path::new(icon);
        if icon_path.is_absolute() {
            log::warn!(
                "Rejected {} with absolute path for module {}: {}",
                key,
                props.get("id").map_or("", String::as_str),
                icon
            );
            return;
        }
        let escapes_module = icon_path
            .components()
            .any(|component| matches!(component, std::path::Component::ParentDir));
        if escapes_module {
            log::warn!(
                "Rejected {} with parent traversal for module {}: {}",
                key,
                props.get("id").map_or("", String::as_str),
                icon
            );
            return;
        }
        let resolved = module_path.join(icon_path);
        if resolved.exists() && resolved.is_file() {
            if let Some(resolved_str) = resolved.to_str() {
                props.insert(key.to_owned(), resolved_str.to_string());
            }
        } else {
            log::debug!(
                "{} not found for module {}: {}",
                key,
                props.get("id").map_or("", String::as_str),
                resolved.display()
            );
        }
    }
}

/// 构造供 Manager 使用的模块列表。
///
/// 除 module.prop 字段外，还会附加派生标志（enabled、update、remove、web、
/// action、mount）、配置覆盖项以及模块的 preinit 状态。
fn collect_module_props(path: &str) -> Vec<HashMap<String, String>> {
    // 一次性加载所有模块配置，尽量减少 I/O 开销
    let all_configs = match module_config::load_all_module_configs() {
        Ok(configs) => configs,
        Err(e) => {
            warn!("Failed to load module configs: {e}");
            HashMap::new()
        }
    };

    // 每个模块在下次开机时的 preinit 状态，会作为派生字段附给 Manager。
    let preinit_states = preinit::module_states();

    // 先检查已启用的模块
    let Ok(dir) = std::fs::read_dir(path) else {
        return Vec::new();
    };

    let mut modules: Vec<HashMap<String, String>> = Vec::new();

    for entry in dir.flatten() {
        let module_path = entry.path();
        info!("path: {}", module_path.display());

        if !module_path.join("module.prop").exists() {
            continue;
        }

        let mut props = match read_module_prop(&module_path) {
            Ok(prop) => prop,
            Err(e) => {
                warn!(
                    "Failed to read module.prop for {}: {e}",
                    module_path.display()
                );
                continue;
            }
        };

        // 若 id 缺失或为空，则回退为目录名
        if !props.contains_key("id") || props["id"].is_empty() {
            if let Some(id) = entry.file_name().to_str() {
                info!("Use dir name as module id: {id}");
                props.insert("id".to_owned(), id.to_owned());
            } else {
                info!("Failed to get module id from dir name");
                continue;
            }
        }

        // Manager 渲染模块卡片所用的派生标志。`mount` 沿用 Magisk 的
        // skip_mount 约定：存在 system/ 目录即表示模块希望覆盖分区，
        // 除非存在 skip_mount。
        let enabled = !module_path.join(defs::DISABLE_FILE_NAME).exists();
        let update = module_path.join(defs::UPDATE_FILE_NAME).exists();
        let remove = module_path.join(defs::REMOVE_FILE_NAME).exists();
        let web = module_path.join(defs::MODULE_WEB_DIR).exists();
        let action = module_path.join(defs::MODULE_ACTION_SH).exists();
        let needs_mount =
            module_path.join("system").exists() && !module_path.join("skip_mount").exists();

        props.insert("enabled".to_owned(), enabled.to_string());
        props.insert("update".to_owned(), update.to_string());
        props.insert("remove".to_owned(), remove.to_string());
        props.insert("web".to_owned(), web.to_string());
        props.insert("action".to_owned(), action.to_string());
        props.insert("mount".to_owned(), needs_mount.to_string());
        if let Some(state) = props
            .get("id")
            .and_then(|id| preinit_states.get(id.as_str()))
        {
            props.insert("preinit".to_owned(), state.clone());
        }

        resolve_icon_path(&mut props, "actionIcon", &module_path);
        resolve_icon_path(&mut props, "webuiIcon", &module_path);

        // 应用模块配置覆盖项，并提取受管理的功能
        if let Some(module_id) = props.get("id")
            && let Some(config) = all_configs.get(module_id.as_str())
        {
            // 应用 override.description
            if let Some(desc) = config.get("override.description") {
                props.insert("description".to_owned(), desc.clone());
            }

            // 从 manage.* 配置项中提取受管理的功能
            let managed_features: Vec<String> = config
                .iter()
                .filter_map(|(key, value)| {
                    if key.starts_with("manage.") && parse_bool_config(value) {
                        key.strip_prefix("manage.")
                            .map(std::string::ToString::to_string)
                    } else {
                        None
                    }
                })
                .collect();

            if !managed_features.is_empty() {
                props.insert("managedFeatures".to_owned(), managed_features.join(","));
            }
        }

        modules.push(props);
    }

    modules
}

/// 输出供 Manager 消费的模块列表（单行 JSON）。
///
/// 输出失败会向上传播；读取单个模块失败时只跳过该模块，不影响其余模块。
pub fn list_modules() -> Result<()> {
    let modules = collect_module_props(defs::MODULE_DIR);
    // Manager 会把多行输出重新拼接成 JSON；直接输出单行可避免逐行处理带来的
    // 兼容问题，也减少传输量。
    println!("{}", serde_json::to_string(&modules)?);
    Ok(())
}

/// 获取所有活动模块的受管理功能
/// 模块通过配置系统声明受管理的功能（manage.<feature>=true）
/// 返回：HashMap<ModuleId, Vec<ManagedFeature>>
pub fn get_managed_features() -> Result<HashMap<String, Vec<String>>> {
    let mut managed_features: HashMap<String, Vec<String>> = HashMap::new();

    foreach_active_module(|module_path| {
        // 获取模块 ID
        let Some(module_id) = module_path.file_name().and_then(|name| name.to_str()) else {
            warn!(
                "Failed to get module id from path: {}",
                module_path.display()
            );
            return Ok(());
        };

        // 读取模块配置
        let config = match module_config::merge_configs(module_id) {
            Ok(config) => config,
            Err(e) => {
                warn!("Failed to merge configs for module '{module_id}': {e}");
                return Ok(()); // 跳过该模块
            }
        };

        // 提取 manage.* 配置项
        let mut feature_list = Vec::new();
        for (key, value) in &config {
            if key.starts_with("manage.") {
                // 解析功能名
                if let Some(feature_name) = key.strip_prefix("manage.")
                    && parse_bool_config(value)
                {
                    feature_list.push(feature_name.to_string());
                }
            }
        }

        if !feature_list.is_empty() {
            managed_features.insert(module_id.to_string(), feature_list);
        }

        Ok(())
    })?;

    Ok(managed_features)
}
