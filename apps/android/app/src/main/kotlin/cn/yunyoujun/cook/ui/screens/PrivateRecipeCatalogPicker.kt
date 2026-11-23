package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.yunyoujun.cook.core.model.StuffItem

internal enum class PrivateRecipeCatalogField(val title: String, val searchLabel: String) {
    INGREDIENT("从食材列表添加", "搜索食材或别名"),
    TOOL("从厨具列表添加", "搜索厨具"),
}

internal class PrivateRecipeEditorList(
    private var preserved: List<String>,
    initialText: String = preserved.joinToString("、"),
) {
    var text by mutableStateOf(initialText)

    // Imported entries can contain separators that belong to the entry itself.
    val values: List<String>
        get() = if (text == preserved.joinToString("、")) {
            preserved
        } else {
            text.split('、', ',', '，', '\n', '\r').map(String::trim).filter(String::isNotEmpty).distinct()
        }

    fun append(name: String, normalize: (String) -> String) {
        val canonical = normalize(name)
        val current = values
        if (current.any { normalize(it) == canonical }) return
        preserved = current + canonical
        text = preserved.joinToString("、")
    }

    companion object {
        val Saver = listSaver<PrivateRecipeEditorList, String>(
            save = { listOf(it.text) + it.preserved },
            restore = { PrivateRecipeEditorList(it.drop(1), it.first()) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivateRecipeCatalogPicker(
    field: PrivateRecipeCatalogField,
    entries: List<StuffItem>,
    draft: PrivateRecipeEditorList,
    normalize: (String) -> String,
    onClose: () -> Unit,
) {
    var query by rememberSaveable(field) { mutableStateOf("") }
    val term = query.trim()
    val canonical = normalize(term)
    val options = remember(entries, term, canonical) {
        entries.distinctBy { normalize(it.name) }.filter { entry ->
            term.isEmpty() || normalize(entry.name) == canonical ||
                entry.name.contains(term, ignoreCase = true) || entry.alias?.contains(term, ignoreCase = true) == true
        }
    }
    val addedNames = draft.values.mapTo(HashSet(), normalize)
    val listState = rememberLazyListState()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        fun returnToEditor() {
            keyboard?.hide()
            onClose()
        }
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(field.title) },
                    navigationIcon = { TextButton(onClick = ::returnToEditor) { Text("返回编辑") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        listState.requestScrollToItem(0)
                    },
                    label = { Text(field.searchLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (options.isEmpty()) {
                    Text(
                        "没有匹配的词条",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(options, key = { it.name }) { entry ->
                        val added = normalize(entry.name) in addedNames
                        ListItem(
                            supportingContent = entry.alias?.takeIf { it.isNotBlank() }?.let { alias ->
                                { Text(alias) }
                            },
                            trailingContent = if (added) { { Text("已添加") } } else null,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !added, role = Role.Button) {
                                draft.append(entry.name, normalize)
                                returnToEditor()
                            },
                        ) {
                            Text(entry.name)
                        }
                    }
                }
            }
        }
    }
}
