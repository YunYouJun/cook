package cn.yunyoujun.cook.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.recommend.RecipeMatch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeSummaryCard(
    recipe: Recipe,
    match: RecipeMatch?,
    isFavorite: Boolean,
    sceneReason: String? = null,
    dietaryNote: String? = null,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val emojiPrefix = recipe.emojis.orEmpty().take(3).joinToString("")
                    if (emojiPrefix.isNotEmpty()) {
                        Text(
                            text = emojiPrefix,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    Text(
                        text = recipe.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onToggleFavorite) {
                    Text(if (isFavorite) "★ 已收藏" else "☆ 收藏")
                }
            }

            if (match != null) {
                val missingText = if (match.missingStuff.isEmpty()) {
                    "食材齐全（匹配 ${match.matchedStuff.size} 项）"
                } else {
                    "还差 ${match.missingStuff.size} 样：${match.missingStuff.joinToString("、")}"
                }
                Text(
                    text = missingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (match.missingStuff.isEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            } else if (recipe.stuff.isNotEmpty()) {
                Text(
                    text = "食材：${recipe.stuff.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!sceneReason.isNullOrBlank()) {
                Text(
                    text = sceneReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (!dietaryNote.isNullOrBlank()) {
                Text(
                    text = dietaryNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            val badges = buildList {
                recipe.difficulty?.takeIf { it.isNotEmpty() }?.let { add(it) }
                if (recipe.tools.isNotEmpty()) add(recipe.tools.joinToString("/"))
                recipe.tags.take(3).forEach { add(it) }
            }
            if (badges.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    badges.forEach { badge ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchIntentReviewDialog(
    intent: SearchIntent,
    onConfirm: (SearchIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("智能条件预览（需确认应用）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "以下条件由${if (intent.source.rawValue == "local") "本机词典" else "DeepSeek"}解析产生，请确认后再应用，系统不会悄悄修改你的长期偏好：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (intent.query.stuff.isNotEmpty()) {
                    Text("识别食材：${intent.query.stuff.joinToString("、")}", style = MaterialTheme.typography.bodyMedium)
                }
                if (intent.query.avoid.isNotEmpty()) {
                    Text("避开食材：${intent.query.avoid.joinToString("、")}", style = MaterialTheme.typography.bodyMedium)
                }
                intent.query.tool?.let {
                    Text("厨具：$it", style = MaterialTheme.typography.bodyMedium)
                }
                if (intent.query.difficulty.isNotEmpty()) {
                    Text("难度：${intent.query.difficulty.joinToString("、")}", style = MaterialTheme.typography.bodyMedium)
                }
                if (intent.query.methodGroups.isNotEmpty()) {
                    Text("做法：${intent.query.methodGroups.joinToString("、")}", style = MaterialTheme.typography.bodyMedium)
                }
                if (intent.query.noSpicy) {
                    Text("辣度：不吃辣", style = MaterialTheme.typography.bodyMedium)
                }
                intent.goal?.let {
                    Text("建议饮食目标：${it.title}", style = MaterialTheme.typography.bodyMedium)
                }
                if (intent.unresolved.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "以下要求未转换为固定筛选：",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            intent.unresolved.forEach { item ->
                                Text("• $item", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (intent.isEmpty && intent.unresolved.isEmpty()) {
                    Text("暂未提取到明确的食材或厨具条件。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(intent) },
                enabled = !intent.isEmpty,
            ) {
                Text("应用条件")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
fun DecideRandomDialog(
    selectedRecipe: Recipe?,
    poolSize: Int,
    hasAccelerometer: Boolean,
    onReroll: () -> Unit,
    onOpenRecipe: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("今天吃这道怎么样？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedRecipe == null) {
                    Text("当前候选池为空，请放宽条件后再随机决定。")
                } else {
                    Text(
                        text = "${selectedRecipe.emojis.orEmpty().take(3).joinToString("")} ${selectedRecipe.name}".trim(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (selectedRecipe.stuff.isNotEmpty()) {
                        Text(
                            text = "用料：${selectedRecipe.stuff.joinToString("、")}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    selectedRecipe.difficulty?.let {
                        Text("难度：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = if (hasAccelerometer) {
                            "当前满足条件的共有 $poolSize 道菜；摇一摇手机或点「换一道」重抽。"
                        } else {
                            "当前满足条件的共有 $poolSize 道菜；点下方按钮即可重抽。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReroll, enabled = poolSize > 1) {
                    Text("换一道")
                }
                if (selectedRecipe != null) {
                    Button(onClick = { onOpenRecipe(selectedRecipe.id) }) {
                        Text("看做法")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DietaryGoalBar(
    availableGoals: List<DietaryGoal>,
    selectedGoal: DietaryGoal?,
    onSelectGoal: (DietaryGoal?) -> Unit,
) {
    if (availableGoals.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "饮食目标",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedGoal != null) {
                TextButton(onClick = { onSelectGoal(null) }) {
                    Text("取消默认目标")
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            availableGoals.forEach { goal ->
                FilterChip(
                    selected = selectedGoal == goal,
                    onClick = { onSelectGoal(if (selectedGoal == goal) null else goal) },
                    label = { Text(goal.title) },
                )
            }
        }
        if (selectedGoal != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = selectedGoal.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TagFlowChips(
    items: List<String>,
    selectedItems: Set<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            FilterChip(
                selected = item in selectedItems,
                onClick = { onToggle(item) },
                label = { Text(item) },
            )
        }
    }
}
