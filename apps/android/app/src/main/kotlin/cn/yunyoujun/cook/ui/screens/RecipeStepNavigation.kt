package cn.yunyoujun.cook.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeTextBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class RecipeStepLocator {
    class Destination(val stepId: String, val itemIndex: Int)

    var destination: Destination? by mutableStateOf(null)
        private set

    fun locate(step: RecipePresentation.Step, itemIndex: Int) {
        destination = Destination(step.id, itemIndex)
    }

    fun clear(expected: Destination) {
        if (destination === expected) destination = null
    }
}

@Composable
internal fun rememberRecipeStepLocator(recipeId: Int, list: LazyListState): RecipeStepLocator {
    val locator = remember(recipeId, list) { RecipeStepLocator() }
    val destination = locator.destination
    LaunchedEffect(destination) {
        if (destination != null) {
            launch { list.animateScrollToItem(destination.itemIndex) }
            delay(5_000)
            locator.clear(destination)
        }
    }
    return locator
}

private val recipeStepReference = Regex("""(?:继续[\p{Z}\s]*)?第[\p{Z}\s]*([0-9]+)[\p{Z}\s]*步[。！!]?""")

internal fun recipeStepDestination(line: String, steps: List<RecipePresentation.Step>): RecipePresentation.Step? {
    val text = line.trim().removePrefix("• ").trimStart()
    val number = recipeStepReference.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return steps.singleOrNull { it.number == number }
}

internal fun recipeSupplementaryIndices(blocks: List<RecipeTextBlock>): Set<Int> = buildSet {
    var supplementary = false
    blocks.forEachIndexed { index, block ->
        if (block is RecipeTextBlock.Heading) supplementary = block.text.trim() == "附加内容"
        if (supplementary) add(index)
    }
}

@Composable
internal fun RecipeSupplementaryBlock(
    block: RecipeTextBlock,
    leadsToList: Boolean,
    steps: List<RecipePresentation.Step>,
    onLocate: (RecipePresentation.Step) -> Unit,
) {
    when (block) {
        is RecipeTextBlock.Heading -> Text(
            block.text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        is RecipeTextBlock.Paragraph -> {
            if (leadsToList && block.text.lineSequence().none { recipeStepDestination(it, steps) != null }) {
                Text(block.text, style = MaterialTheme.typography.bodyMedium)
            } else {
                RecipeSupplementaryCard(block.text, steps, onLocate)
            }
        }
        is RecipeTextBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.items.forEachIndexed { index, text ->
                RecipeSupplementaryCard(text, steps, onLocate, number = if (block.ordered) block.start + index else null)
            }
        }
        is RecipeTextBlock.ImageBlock -> Unit
    }
}

@Composable
private fun RecipeSupplementaryCard(
    text: String,
    steps: List<RecipePresentation.Step>,
    onLocate: (RecipePresentation.Step) -> Unit,
    number: Int? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            text.lineSequence().forEachIndexed { index, line ->
                val destination = recipeStepDestination(line, steps)
                val prefix = if (number != null && index == 0) "$number. " else ""
                if (destination == null) {
                    Text(prefix + line, style = MaterialTheme.typography.bodyMedium)
                } else {
                    TextButton(onClick = { onLocate(destination) }) { Text(prefix + line.trim().removePrefix("• ")) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipeStepProgress(
    steps: List<RecipePresentation.Step>,
    checked: Set<String>,
    onReset: (() -> Unit)? = null,
    onLocate: (RecipePresentation.Step) -> Unit,
) {
    val completed = steps.count { it.id in checked }
    val next = steps.firstOrNull { it.id !in checked }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "已完成 $completed / ${steps.size} 步",
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (next != null) TextButton(onClick = { onLocate(next) }) { Text("继续第 ${next.number} 步") }
            if (completed > 0 && onReset != null) TextButton(onClick = onReset) { Text("重置步骤") }
        }
    }
}

@Composable
internal fun RecipeStepCard(
    step: RecipePresentation.Step,
    text: String,
    checked: Boolean,
    located: Boolean,
    onCheckedChange: () -> Unit,
    guidanceLabel: String? = null,
    onGuidance: () -> Unit = {},
) {
    val context = LocalContext.current
    var showActions by remember(step.id) { mutableStateOf(false) }
    val location = if (located) Modifier.semantics { stateDescription = "已定位" } else Modifier
    Card(
        Modifier.fillMaxWidth().then(location),
        colors = CardDefaults.cardColors(
            containerColor = if (located) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .combinedClickable(
                        role = Role.Checkbox,
                        onClickLabel = if (checked) "撤销完成" else "标记完成",
                        onClick = onCheckedChange,
                        onLongClickLabel = "打开步骤操作",
                        onLongClick = { showActions = true },
                    )
                    .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 10.dp))
                Text(
                    "第 ${step.number} 步：$text",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                )
            }
            DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                DropdownMenuItem(text = { Text("复制步骤") }, onClick = {
                    showActions = false
                    val copied = runCatching {
                        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@runCatching false
                        clipboard.setPrimaryClip(ClipData.newPlainText("菜谱步骤", text))
                        true
                    }.getOrDefault(false)
                    if (!copied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        val message = if (copied) "已复制步骤" else "暂时无法复制步骤。"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
        if (guidanceLabel != null) {
            TextButton(onGuidance, modifier = Modifier.align(Alignment.End)) { Text(guidanceLabel) }
        }
    }
}
