package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.core.recipe.RecipeAmounts
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.data.local.KitchenPreparationItem
import cn.yunyoujun.cook.data.local.RecipeWorkbenchEntry
import cn.yunyoujun.cook.data.local.canAdjustWorkbenchServings

internal fun LazyListScope.kitchenPreparationChecklist(
    checklist: List<KitchenPreparationItem>,
    entry: RecipeWorkbenchEntry,
    presentation: RecipePresentation?,
    amounts: RecipeAmounts,
    institutional: Boolean,
    onServingsChange: (Int) -> Unit,
    onToggle: (KitchenPreparationItem) -> Unit,
    onClearPrepared: () -> Unit,
) {
    val foods = checklist.filterNot { it.isTool }
    val tools = checklist.filter { it.isTool }
    val prepared = checklist.count { it.isChecked(entry.checkedIngredients) }
    item(key = "preparation-summary") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (institutional) "原方备料" else "备料清单",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            if (checklist.isNotEmpty()) {
                Text("已备好 $prepared / ${checklist.size} 项", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { prepared.toFloat() / checklist.size }, modifier = Modifier.fillMaxWidth())
            } else {
                Text("这道菜还没有材料记录，请按做法准备。", style = MaterialTheme.typography.bodyMedium)
            }
            if (institutional) Text("用量和比例见英文原方。", style = MaterialTheme.typography.bodySmall)
            presentation?.servingDescription?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val adjustable = remember(presentation, amounts) { amounts.canAdjustWorkbenchServings(presentation) }
            if (adjustable) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("工作台份数 ${amounts.servings} 份", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = { onServingsChange(amounts.servings - 1) },
                        enabled = amounts.servings > RecipeAmounts.SERVING_RANGE.first,
                        modifier = Modifier.semantics { contentDescription = "减少份数" },
                    ) { Text("−") }
                    TextButton(
                        onClick = { onServingsChange(amounts.servings + 1) },
                        enabled = amounts.servings < RecipeAmounts.SERVING_RANGE.last,
                        modifier = Modifier.semantics { contentDescription = "增加份数" },
                    ) { Text("+") }
                }
            }
        }
    }
    items(foods, key = { "preparation-${it.key}" }) { item ->
        PreparationCheckRow(item, item.isChecked(entry.checkedIngredients), amounts) { onToggle(item) }
    }
    if (tools.isNotEmpty()) {
        item(key = "preparation-tools") {
            Text("厨具", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        }
        items(tools, key = { "preparation-${it.key}" }) { item ->
            PreparationCheckRow(item, item.isChecked(entry.checkedIngredients), amounts) { onToggle(item) }
        }
    }
    if (entry.checkedIngredients.isNotEmpty()) {
        item(key = "preparation-clear") { TextButton(onClick = onClearPrepared) { Text("重新备料") } }
    }
}

@Composable
private fun PreparationCheckRow(
    item: KitchenPreparationItem,
    checked: Boolean,
    amounts: RecipeAmounts,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Checkbox) { onToggle() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item.labels.forEach { label ->
                Text(label, textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None)
            }
            item.quantity?.let { quantity ->
                val amount = amounts.amount(quantity)
                val amountLabel = if (item.labels.singleOrNull() == quantity.name) amount.text else "${quantity.name} ${amount.text}"
                Text(amountLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                amount.note?.let { note -> Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
