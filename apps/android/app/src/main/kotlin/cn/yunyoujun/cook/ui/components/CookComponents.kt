package cn.yunyoujun.cook.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.core.recommend.RecipeMatch
import cn.yunyoujun.cook.data.local.DevicePreferencesSnapshot
import cn.yunyoujun.cook.platform.DeviceShakeDetector
import cn.yunyoujun.cook.ui.state.CookSessionConditions
import kotlinx.coroutines.launch
import kotlin.math.abs

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

fun buildDecideContextSummary(
    conditions: CookSessionConditions,
    prefs: DevicePreferencesSnapshot,
    ignoringKeyword: Boolean,
): String? {
    val parts = buildList {
        prefs.rememberedDietaryGoal?.let { add("饮食目标：${it.title}") }
        if (conditions.selectedStuff.isNotEmpty()) {
            add("已有食材：${conditions.selectedStuff.joinToString("、")}")
            val modeLabel = when (prefs.mode) {
                RecommendationMode.LOOSE -> "宽松模式"
                RecommendationMode.STRICT -> "严格模式"
                RecommendationMode.SURVIVAL -> "应急模式"
            }
            add("推荐方式：$modeLabel")
        }
        conditions.tool?.let { tool ->
            add("厨具：${if (tool == "一口大锅") "炒锅" else tool}")
        }
        val keyword = conditions.keyword.trim()
        if (!ignoringKeyword && keyword.isNotEmpty()) {
            add("菜名：$keyword")
        }
        conditions.discoveryScene?.let { add("想法：${it.label}") }
        val avoid = (prefs.avoid + conditions.avoid).distinct()
        if (avoid.isNotEmpty()) {
            add("忌口：${avoid.joinToString("、")}")
        }
        if (prefs.noSpicy || conditions.noSpicy) {
            add("不吃辣")
        }
        addAll(conditions.methodGroups)
        addAll(conditions.difficulty)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
fun DecideRandomDialog(
    recipes: List<Recipe>,
    onOpenRecipe: (Int) -> Unit,
    onDismiss: () -> Unit,
    matchesById: Map<Int, RecipeMatch> = emptyMap(),
    contextSummary: String? = null,
) {
    val byId = remember(recipes) { recipes.associateBy { it.id } }
    val poolKey = byId.keys.toList()
    val shuffled = rememberSaveable(poolKey) { poolKey.shuffled().toIntArray() }
    val order = remember(shuffled, byId) { shuffled.asList().mapNotNull(byId::get) }
    key(poolKey) {
        RandomRecipeCards(
            order = order,
            matchesById = matchesById,
            contextSummary = contextSummary,
            onOpenRecipe = onOpenRecipe,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RandomRecipeCards(
    order: List<Recipe>,
    matchesById: Map<Int, RecipeMatch>,
    contextSummary: String?,
    onOpenRecipe: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        if (order.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "今天吃什么",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (!contextSummary.isNullOrBlank()) {
                    Text(
                        text = contextSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "当前候选池为空，请放宽条件后再随机决定。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("关闭")
                }
            }
            return@ModalBottomSheet
        }

        val pager = rememberPagerState { order.size }
        var revealedCount by rememberSaveable { mutableIntStateOf(1) }
        var lastSettled by rememberSaveable { mutableIntStateOf(0) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val selected = order.getOrNull(pager.currentPage)

        LaunchedEffect(pager.settledPage, order.size) {
            revealedCount = maxOf(revealedCount, pager.settledPage + 1)
            if (pager.settledPage != lastSettled) {
                lastSettled = pager.settledPage
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        fun moveTo(page: Int) {
            if (!pager.isScrollInProgress && page in order.indices && page != pager.currentPage) {
                scope.launch {
                    if (abs(page - pager.currentPage) <= 3) {
                        pager.animateScrollToPage(
                            page,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    } else {
                        pager.scrollToPage(page)
                    }
                }
            }
        }

        val next = {
            if (order.size > 1 && !pager.isScrollInProgress) {
                moveTo(if (revealedCount < order.size) revealedCount else (pager.currentPage + 1) % order.size)
            }
        }
        val onShake by rememberUpdatedState(next)
        val detector = remember(context) { DeviceShakeDetector(context) { onShake() } }
        DisposableEffect(detector) {
            detector.start()
            onDispose { detector.stop() }
        }

        val preferredCardHeight = (240f * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)).dp
        val hintText = when {
            order.size <= 1 -> "当前条件下仅此一道"
            detector.hasAccelerometer -> "左右滑动翻看，摇一摇换一道"
            else -> "左右滑动翻看随机菜谱"
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardHeight = if (constraints.hasBoundedHeight) {
                minOf(preferredCardHeight, (maxHeight - 248.dp).coerceAtLeast(120.dp))
            } else {
                preferredCardHeight
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "今天吃什么",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = "${pager.currentPage + 1} / ${maxOf(revealedCount, pager.currentPage + 1)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        TextButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }

                Text(
                    text = contextSummary ?: "按当前条件从 ${order.size} 道菜里挑一道",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )

                HorizontalPager(
                    state = pager,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cardHeight)
                        .testTag("decide.pager"),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    pageSpacing = 12.dp,
                    beyondViewportPageCount = 1,
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = pager,
                        pagerSnapDistance = PagerSnapDistance.atMost(1),
                    ),
                ) { index ->
                    val recipe = order[index]
                    val match = matchesById[recipe.id]
                    val metaText = buildList {
                        if (recipe.tools.isNotEmpty()) {
                            add(recipe.tools.joinToString(" / ") { if (it == "一口大锅") "炒锅" else it })
                        }
                        recipe.difficulty?.takeIf { it.isNotEmpty() }?.let(::add)
                    }.joinToString(" · ")

                    ElevatedCard(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pageOffset = ((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                                val progress = 1f - abs(pageOffset)
                                val scale = 0.94f + 0.06f * progress
                                scaleX = scale
                                scaleY = scale
                                alpha = 0.84f + 0.16f * progress
                            },
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = maxHeight)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                            ) {
                                Text(
                                    text = recipe.emojis.orEmpty().take(3).joinToString("").ifEmpty { "🍽️" },
                                    fontSize = 40.sp,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = recipe.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (metaText.isNotEmpty()) {
                                    Text(
                                        text = metaText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                if (recipe.stuff.isNotEmpty()) {
                                    Text(
                                        text = "用料：${recipe.stuff.joinToString("、")}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                if (match != null) {
                                    val matchText = if (match.missingStuff.isEmpty()) {
                                        "所需食材已齐"
                                    } else {
                                        "还差 ${match.missingStuff.size} 样：${match.missingStuff.joinToString("、")}"
                                    }
                                    Text(
                                        text = matchText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (match.missingStuff.isEmpty()) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        },
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (order.size > 1) {
                        TextButton(
                            onClick = { moveTo(pager.currentPage - 1) },
                            enabled = pager.currentPage > 0,
                        ) {
                            Text("上一道")
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = next,
                        enabled = order.size > 1,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("换一道")
                    }
                    Button(
                        onClick = { selected?.let { onOpenRecipe(it.id) } },
                        enabled = selected != null,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("看做法")
                    }
                }
            }
        }
    }
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
