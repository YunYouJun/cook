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
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cn.yunyoujun.cook.core.dietary.InstitutionalLowGICatalog
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
    showInstitutionalTag: Boolean = false,
    isSelected: Boolean = false,
    favoriteEnabled: Boolean = true,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (isSelected) selected = true }
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
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
                TextButton(onClick = onToggleFavorite, enabled = favoriteEnabled) {
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
                recipe.tags.filter { it != InstitutionalLowGICatalog.TAG || showInstitutionalTag }.take(3).forEach { add(it) }
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

fun buildDecideContextSummary(
    conditions: CookSessionConditions,
    prefs: DevicePreferencesSnapshot,
    ignoringKeyword: Boolean,
): String? {
    val parts = buildList {
        conditions.dietaryGoal(prefs)?.let { add("饮食目标：${it.title}") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecideRandomDialog(
    recipes: List<Recipe>,
    onOpenRecipe: (Int) -> Unit,
    onDismiss: () -> Unit,
    matchesById: Map<Int, RecipeMatch> = emptyMap(),
    contextSummary: String? = null,
    dietaryNote: (Recipe) -> String? = { null },
    sceneReason: (Recipe) -> String? = { null },
    onClearScene: (() -> Unit)? = null,
    avoidFirstId: Int? = null,
    onRecipePreviewed: (Int) -> Unit = {},
    onOpenFilters: (() -> Unit)? = null,
    filtersOpen: Boolean = false,
) {
    val byId = remember(recipes) { recipes.associateBy { it.id } }
    val poolKey = remember(byId) { byId.keys.sorted() }
    // The modal window can be recreated when density changes.
    val pages = key(poolKey) {
        val deck = rememberSaveable {
            mutableStateOf(
                if (byId.isEmpty()) intArrayOf() else {
                    val first = drawRecipe(byId, avoidFirstId)
                    if (byId.size > 1) intArrayOf(first, drawRecipe(byId, first)) else intArrayOf(first)
                },
            )
        }
        val pager = rememberPagerState { deck.value.size }
        val revealedCount = rememberSaveable { mutableIntStateOf(1) }
        RandomRecipePages(deck, pager, revealedCount)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        key(poolKey) {
            RandomRecipeCards(
                pool = byId,
                pages = pages,
                matchesById = matchesById,
                contextSummary = contextSummary,
                dietaryNote = dietaryNote,
                sceneReason = sceneReason,
                onClearScene = onClearScene,
                onRecipePreviewed = onRecipePreviewed,
                onOpenRecipe = onOpenRecipe,
                onDismiss = onDismiss,
                onOpenFilters = onOpenFilters,
                filtersOpen = filtersOpen,
            )
        }
    }
}

internal class RandomRecipePages(
    val deck: MutableState<IntArray>,
    val pager: PagerState,
    val revealedCount: MutableIntState,
) {
    fun stageReroll(pool: Map<Int, Recipe>): Int {
        val currentPage = pager.settledPage
        // The pager can settle before the effect that records the page in history runs.
        val nextPage = maxOf(revealedCount.intValue, currentPage + 1)
        val ids = deck.value
        val stagedId = ids.getOrNull(nextPage)
        val visibleId = ids[currentPage]
        if (stagedId == null) {
            deck.value = ids + drawRecipe(pool, visibleId, ids.last())
        } else if (pool[stagedId]?.name == pool[visibleId]?.name) {
            deck.value = ids.copyOf().also { it[nextPage] = drawRecipe(pool, visibleId, ids[nextPage - 1]) }
        }
        return nextPage
    }
}

private fun drawRecipe(pool: Map<Int, Recipe>, currentId: Int?, precedingId: Int? = currentId): Int {
    val currentName = pool[currentId]?.name
    val precedingName = pool[precedingId]?.name
    val otherRecipes = pool.values.filter { it.id != currentId }
    val otherNames = otherRecipes.filter { it.name != currentName }
    return otherNames.filter { it.name != precedingName }
        .ifEmpty { otherNames }
        .ifEmpty { otherRecipes }
        .ifEmpty { pool.values.toList() }
        .random().id
}

private fun decideRecipeMeta(recipe: Recipe): String = buildList {
    if (recipe.tools.isNotEmpty()) {
        add(recipe.tools.joinToString(" / ") { if (it == "一口大锅") "炒锅" else it })
    }
    recipe.difficulty?.takeIf { it.isNotEmpty() }?.let(::add)
}.joinToString(" · ")

private fun decideIngredientText(recipe: Recipe): String? =
    recipe.stuff.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "用料：")

private fun decideMatchText(match: RecipeMatch): String =
    if (match.missingStuff.isEmpty()) "所需食材已齐"
    else "还差 ${match.missingStuff.size} 样：${match.missingStuff.joinToString("、")}"

@Composable
private fun DecideContext(text: String, largeText: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (largeText) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun RandomRecipeCards(
    pool: Map<Int, Recipe>,
    pages: RandomRecipePages,
    matchesById: Map<Int, RecipeMatch>,
    contextSummary: String?,
    dietaryNote: (Recipe) -> String?,
    sceneReason: (Recipe) -> String?,
    onClearScene: (() -> Unit)?,
    onRecipePreviewed: (Int) -> Unit,
    onOpenRecipe: (Int) -> Unit,
    onDismiss: () -> Unit,
    onOpenFilters: (() -> Unit)?,
    filtersOpen: Boolean,
) {
    val fontScale = LocalDensity.current.fontScale
    val largeText = fontScale >= 1.5f
    if (pool.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DecideHeader(onOpenFilters, onDismiss)
            if (!contextSummary.isNullOrBlank()) {
                DecideContext(contextSummary, largeText)
            }
            Text(
                text = if (onClearScene != null) {
                    "当前场景下没有符合条件的菜。可以取消场景或调整筛选。"
                } else {
                    "当前候选池为空，请放宽条件后再随机决定。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onClearScene != null) {
                TextButton(onClick = onClearScene, enabled = !filtersOpen) { Text("取消这次场景") }
            }
        }
        return
    }

    val canReroll = pool.size > 1
    var deck by pages.deck
    val pager = pages.pager
    var revealedCount by pages.revealedCount
    var lastSettled by remember(pager) { mutableIntStateOf(pager.settledPage) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val selectedId = deck.getOrNull(pager.settledPage)
    val selectedRecipe = pool[selectedId]
    val canAct = !pager.isScrollInProgress && !filtersOpen
    val pageNumber = pager.settledPage + 1
    val pageTotal = maxOf(revealedCount, pageNumber)
    val canGoBack = canAct && pager.settledPage > 0
    val canGoForward = canAct && canReroll
    val pageDescription = "第 $pageNumber 道，共 $pageTotal 道"
    val recipeDescription = selectedRecipe?.let { recipe ->
        listOfNotNull(
            recipe.name,
            decideRecipeMeta(recipe).takeIf { it.isNotEmpty() },
            decideIngredientText(recipe),
            dietaryNote(recipe),
            sceneReason(recipe),
            matchesById[recipe.id]?.let(::decideMatchText),
        ).joinToString("，")
    }

    LaunchedEffect(pager.settledPage) {
        revealedCount = maxOf(revealedCount, pager.settledPage + 1)
        if (canReroll && deck.size == revealedCount) {
            deck += drawRecipe(pool, deck.last())
        }
        selectedId?.let(onRecipePreviewed)
        if (pager.settledPage != lastSettled) {
            lastSettled = pager.settledPage
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun moveTo(page: Int): Boolean {
        if (pager.isScrollInProgress || filtersOpen || page !in deck.indices || page == pager.settledPage) {
            return false
        }
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
        return true
    }

    fun reroll(): Boolean {
        if (!canReroll || pager.isScrollInProgress || filtersOpen) return false
        return moveTo(pages.stageReroll(pool))
    }

    fun moveForward(): Boolean {
        val targetPage = pager.settledPage + 1
        return if (targetPage < revealedCount) moveTo(targetPage) else reroll()
    }

    val onShake by rememberUpdatedState<() -> Unit> { reroll() }
    val detector = remember(context) { DeviceShakeDetector(context) { onShake() } }
    LifecycleResumeEffect(detector, filtersOpen, canReroll) {
        if (canReroll && !filtersOpen) detector.start()
        onPauseOrDispose { detector.stop() }
    }

    val preferredCardHeight = (240f * fontScale.coerceIn(1f, 1.5f)).dp
    val hintText = when {
        !canReroll -> "当前条件下仅此一道"
        detector.hasAccelerometer -> "左右滑动翻看，摇一摇换一道"
        else -> "左右滑动翻看随机菜谱"
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardHeight = if (constraints.hasBoundedHeight) {
            minOf(preferredCardHeight, (maxHeight - 298.dp).coerceAtLeast(120.dp))
        } else {
            preferredCardHeight
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DecideHeader(onOpenFilters, onDismiss, Modifier.padding(horizontal = 24.dp))

            DecideContext(
                contextSummary ?: "按当前条件从 ${pool.size} 道菜里挑一道",
                largeText,
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )

            HorizontalPager(
                state = pager,
                userScrollEnabled = canReroll && !filtersOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    // Keep the same accessibility focus and actions as the visible card changes.
                    .semantics {
                        recipeDescription?.let { contentDescription = it }
                        stateDescription = pageDescription
                        liveRegion = LiveRegionMode.Polite
                        customActions = buildList {
                            if (canGoBack) {
                                add(CustomAccessibilityAction("上一道") { moveTo(pager.settledPage - 1) })
                            }
                            if (canGoForward) {
                                add(CustomAccessibilityAction("下一道", ::moveForward))
                            }
                        }
                    }
                    .testTag("decide.pager"),
                contentPadding = PaddingValues(horizontal = 24.dp),
                pageSpacing = 12.dp,
                beyondViewportPageCount = 1,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pager,
                    pagerSnapDistance = PagerSnapDistance.atMost(1),
                ),
            ) { index ->
                val recipe = pool.getValue(deck[index])
                val match = matchesById[recipe.id]
                val metaText = decideRecipeMeta(recipe)

                ElevatedCard(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics { hideFromAccessibility() }
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
                            if (!largeText) {
                                Text(
                                    text = recipe.emojis.orEmpty().take(3).joinToString("").ifEmpty { "🍽️" },
                                    fontSize = 40.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            Text(
                                text = recipe.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = if (largeText) Int.MAX_VALUE else 2,
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
                            decideIngredientText(recipe)?.let { ingredients ->
                                Text(
                                    text = ingredients,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            dietaryNote(recipe)?.let { note ->
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            sceneReason(recipe)?.let { reason ->
                                Text(
                                    text = reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            if (match != null) {
                                Text(
                                    text = decideMatchText(match),
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
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { moveTo(pager.settledPage - 1) },
                    enabled = canGoBack,
                ) { Text("上一道") }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.semantics { hideFromAccessibility() },
                ) {
                    Text("$pageNumber / $pageTotal", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
                TextButton(
                    onClick = { moveForward() },
                    enabled = canGoForward,
                ) { Text("下一道") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { reroll() },
                    enabled = canReroll && canAct,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("换一道")
                }
                Button(
                    onClick = { if (!pager.isScrollInProgress) selectedId?.let(onOpenRecipe) },
                    enabled = selectedId != null && canAct,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecideHeader(onOpenFilters: (() -> Unit)?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("今天吃什么", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row {
            onOpenFilters?.let { TextButton(onClick = it) { Text("筛选") } }
            TextButton(onClick = onDismiss) { Text("关闭") }
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
