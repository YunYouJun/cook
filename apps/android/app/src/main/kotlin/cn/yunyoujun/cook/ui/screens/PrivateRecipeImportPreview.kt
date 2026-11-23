package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.yunyoujun.cook.core.sync.PrivateRecipeImport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivateRecipeImportPreview(
    plan: PrivateRecipeImport,
    working: Boolean,
    message: String?,
    onCancel: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var keepCopies by rememberSaveable(plan) { mutableStateOf(false) }
    val listState = key(plan) { rememberLazyListState() }
    val count = plan.additions.size + if (keepCopies) plan.conflicts.size else 0
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("导入预览") },
                    navigationIcon = { TextButton(enabled = !working, onClick = onCancel) { Text("取消") } },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 2.dp) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                        Column(
                            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Button(
                                enabled = !working && count > 0,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onConfirm(keepCopies) },
                            ) { Text(if (working) "正在导入…" else "导入 $count 道菜谱") }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().testTag("privateRecipeImport.recipes"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text("新增菜谱：${plan.additions.size} 道")
                        Text("同内容跳过：${plan.duplicates.size} 道")
                        Text("编号冲突 / 曾删除记录：${plan.conflicts.size} 道")
                    }
                    if (plan.conflicts.isNotEmpty()) {
                        item {
                            ListItem(
                                headlineContent = { Text("冲突菜谱另存副本") },
                                supportingContent = { Text("开启后为冲突菜谱分配新编号，已有菜谱会保留。") },
                                trailingContent = { Switch(checked = keepCopies, onCheckedChange = null, enabled = !working) },
                                modifier = Modifier.toggleable(
                                    value = keepCopies,
                                    enabled = !working,
                                    role = Role.Switch,
                                    onValueChange = { keepCopies = it },
                                ),
                            )
                        }
                        items(plan.conflicts, key = { it.customId }) { recipe ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(recipe.name, style = MaterialTheme.typography.titleMedium)
                                    Text("食材：${recipe.stuff.joinToString("、")}")
                                    if (recipe.tools.isNotEmpty()) Text("厨具：${recipe.tools.joinToString("、")}")
                                    recipe.sourceUrl?.let { Text("来源：$it") }
                                    recipe.note?.takeIf { it.isNotBlank() }?.let { Text(it) }
                                    recipe.video?.let { Text("关联视频：${it.originalName}") }
                                }
                            }
                        }
                    }
                    if (plan.additions.isNotEmpty()) {
                        item {
                            Text("待新增菜谱", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                        }
                        items(plan.additions, key = { it.customId }) { recipe ->
                            Text(recipe.name, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
