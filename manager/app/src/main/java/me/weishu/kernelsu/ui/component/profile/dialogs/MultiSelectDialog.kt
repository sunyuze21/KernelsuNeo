package me.weishu.kernelsu.ui.component.profile.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.component.material.SegmentedCheckboxItem
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import top.yukonga.miuix.kmp.nav.gesture.WindowNavigationEventBridge

/**
 * 通用多选底部弹窗，带关键字过滤与最大选择数限制。
 *
 * 选择变更即时回调 [onSelectionChange]（不在此保存副本），便于上层做实时预览；
 * [maxSelection] 用 Int.MAX_VALUE 表示不限制。过滤结果用 remember 缓存，避免每次重组重新匹配。
 */
@Composable
fun <T> MultiSelectDialog(
    title: String,
    subtitle: String? = null,
    items: List<T>,
    selectedItems: Set<T>,
    itemTitle: (T) -> String,
    itemSubtitle: (T) -> String?,
    maxSelection: Int = Int.MAX_VALUE,
    onSelectionChange: (Set<T>) -> Unit,
    onDismiss: () -> Unit
) {
    // 搜索关键字只影响展示，不写回外部状态
    var searchQuery by remember { mutableStateOf("") }
    // 同时匹配标题与副标题，忽略大小写；空关键字时直接复用原列表
    val filteredItems = remember(items, searchQuery) {
        if (searchQuery.isBlank()) {
            items
        } else {
            items.filter { item ->
                itemTitle(item).contains(searchQuery, ignoreCase = true) ||
                        itemSubtitle(item)?.contains(searchQuery, ignoreCase = true) == true
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 只允许隐藏/展开，禁用半展开，避免中间态遮挡内容
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ) {
        WindowNavigationEventBridge()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    // 有关键字时才显示清除按钮
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                },
                singleLine = true
            )

            SegmentedColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    // weight(fill = false) 让列表按内容收缩，同时不超过可用高度
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                content = filteredItems.map { item ->
                    {
                        SegmentedCheckboxItem(
                            title = itemTitle(item),
                            summary = itemSubtitle(item),
                            // 用 surface 色让复选项融入底部弹窗背景
                            colors = ListItemDefaults.segmentedColors().copy(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = Color.Unspecified
                            ),
                            checked = selectedItems.contains(item),
                            onCheckedChange = { isChecked ->
                                // 每次变更都基于外部集合新建 Set，避免就地修改触发意外重组
                                val newSelection = selectedItems.toMutableSet()
                                if (isChecked && newSelection.size < maxSelection) {
                                    newSelection.add(item)
                                } else if (!isChecked) {
                                    newSelection.remove(item)
                                }
                                onSelectionChange(newSelection)
                            }
                        )
                    }
                }
            )
        }
    }
}
