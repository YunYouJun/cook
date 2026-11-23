package cn.yunyoujun.cook.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.dietary.InstitutionalLowGICatalog
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.recipe.RecipeAmounts
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeStepUsagePlan
import cn.yunyoujun.cook.core.recipe.RecipeText
import cn.yunyoujun.cook.core.recipe.RecipeTextBlock
import cn.yunyoujun.cook.core.recommend.RecommendationOptions
import cn.yunyoujun.cook.core.recommend.Recommender
import cn.yunyoujun.cook.data.local.AiProviderMode
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.KitchenWorkbenchStore
import cn.yunyoujun.cook.data.local.canAdjustWorkbenchServings
import cn.yunyoujun.cook.data.local.kitchenPreparationItems
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.platform.RecipeShareLinks
import cn.yunyoujun.cook.ui.state.CookSessionController
import cn.yunyoujun.cook.ui.state.KitchenStepGuidanceModel
import cn.yunyoujun.cook.ui.state.RecordRecipeVisit
import kotlinx.coroutines.launch

private fun Context.openRecipeSource(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(this, "未找到可打开链接的浏览器或应用", Toast.LENGTH_SHORT).show() }
}

private sealed interface RecipeBodyRow {
    data class Block(val sourceIndex: Int, val block: RecipeTextBlock) : RecipeBodyRow
    data class Step(val step: RecipePresentation.Step) : RecipeBodyRow
}

private enum class WorkbenchCheckReset(val title: String, val confirmLabel: String, val message: String) {
    PREPARATION("重新备料？", "清除备料勾选", "计时器和已完成步骤会保留。"),
    STEPS("重新勾选做菜步骤？", "清除步骤勾选", "已备好的原料、厨具和计时器会保留。"),
    ALL("重置所有勾选？", "清除全部勾选", "将清除备料和步骤的勾选。计时器会保留。"),
}

@Composable
private fun WorkbenchCheckResetDialog(reset: WorkbenchCheckReset, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(reset.title) },
        text = { Text(reset.message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(reset.confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun KitchenWorkbenchStore.toggleAndFindNextStep(
    recipeId: Int,
    step: RecipePresentation.Step,
    steps: List<RecipePresentation.Step>,
): RecipePresentation.Step? {
    val checked = entry(recipeId).checkedSteps
    val advances = steps.firstOrNull { it.id !in checked } == step
    toggleStep(recipeId, step.id)
    val updated = entry(recipeId).checkedSteps
    return if (advances && step.id in updated) steps.firstOrNull { it.id !in updated } else null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(
    recipeId: Int,
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onBack: () -> Unit,
    onOpenWorkbench: (Int) -> Unit,
    onOpenJournal: (JournalRecipeId) -> Unit = {},
    dietaryGoal: DietaryGoal? = null,
    embedded: Boolean = false,
    recommendationSession: CookSessionController? = null,
    onOpenRecipeFeedback: ((Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recipe = snapshot.recipe(recipeId)
    val session by container.yunlefunSessionManager.state.collectAsState()
    val libraryScope = session.libraryScope
    val favorites by remember(container) { container.userLibraryRepository.observeFavorites() }.collectAsState(initial = null)
    val favoriteEntries = favorites?.takeIf { it.scope == libraryScope }
    val workbenchState by container.workbenchStore.state.collectAsState()

    val servings = workbenchState.entries[recipeId]?.servings ?: 1
    var photoBlock by rememberSaveable(recipeId) { mutableStateOf<Int?>(null) }
    var confirmingStepReset by rememberSaveable(recipeId) { mutableStateOf(false) }
    var photoPreviewRevision by remember(recipeId) { mutableIntStateOf(0) }
    var favoriteError by remember { mutableStateOf<String?>(null) }

    if (!embedded) RecordRecipeVisit(recipe, container.userLibraryRepository, libraryScope)

    if (recipe == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("菜谱详情") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                )
            },
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("未找到编号为 #$recipeId 的菜谱。")
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onBack) { Text("返回列表") }
            }
        }
        return
    }

    val normalizer = snapshot.data.normalizer
    val ingredientMatch = if (recommendationSession != null) {
        val conditions by recommendationSession.conditions.collectAsState()
        val prefs by container.preferencesStore.state.collectAsState()
        val recommender = remember(recipe, normalizer) { Recommender(listOf(recipe), normalizer) }
        val options = RecommendationOptions(
            stuff = conditions.selectedStuff,
            pantry = prefs.pantry,
            avoid = prefs.avoid + conditions.avoid,
            noSpicy = prefs.noSpicy || conditions.noSpicy,
        )
        remember(recommender, options) { recommender.recommend(options).firstOrNull() }
    } else null
    val ingredientGroups = remember(recipe, normalizer, ingredientMatch) {
        if (ingredientMatch == null) listOf("食材" to normalizer.unique(recipe.stuff))
        else listOf(
            "已有食材" to ingredientMatch.matchedStuff,
            "还缺" to ingredientMatch.missingStuff,
            "常备调料" to ingredientMatch.pantryStuff,
            "还能加" to ingredientMatch.optionalStuff,
        )
    }
    val review = remember(recipe, dietaryGoal, snapshot) { snapshot.reviewedRecipe(recipe, dietaryGoal) }
    val institutionalCatalog = snapshot.institutionalLowGICatalog
    val institutionalReview = institutionalCatalog?.entry(recipe)
    val recipeText: RecipeText? = remember(recipe.id, snapshot, review) { review?.text ?: snapshot.recipeText(recipe.id) }
    val photos = remember(recipeText, snapshot, recipe.name) { recipePhotos(recipeText, snapshot.textLibrary, recipe.name) }
    val photosByBlock = remember(photos) { photos.associateBy { it.blockIndex } }
    val presentation = remember(recipeText) { recipeText?.let(::RecipePresentation) }
    val bodyRows = remember(recipeText, presentation, review) {
        recipeText?.blocks.orEmpty().flatMapIndexed { index, block ->
            if (review != null && block is RecipeTextBlock.Paragraph &&
                presentation?.metadataBlocks?.contains(index) == true && block.text.trimStart().startsWith("预估卡路里")
            ) return@flatMapIndexed emptyList()
            val steps = presentation?.stepsByBlock?.get(index)
            if (steps.isNullOrEmpty()) listOf(RecipeBodyRow.Block(index, block)) else steps.map { RecipeBodyRow.Step(it) }
        }
    }
    val supplementary = remember(recipeText) { recipeSupplementaryIndices(recipeText?.blocks.orEmpty()) }
    val steps = presentation?.steps.orEmpty()
    val checkedSteps = workbenchState.entries[recipeId]?.checkedSteps.orEmpty()
    val contentScroll = rememberLazyListState()
    val locator = rememberRecipeStepLocator(recipeId, contentScroll)
    fun locateFrom(rowIndex: Int, target: RecipePresentation.Step) {
        // The body is contiguous, so the visible source row anchors it after the optional detail cards.
        val sourceItem = contentScroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "body-$rowIndex" } ?: return
        val targetRow = bodyRows.indexOfFirst { it is RecipeBodyRow.Step && it.step.id == target.id }
        if (targetRow >= 0) locator.locate(target, sourceItem.index - rowIndex + targetRow)
    }
    val amounts = remember(presentation, servings) {
        presentation?.let { RecipeAmounts(it.allQuantities, servings) }
    }
    val adjustableServings = remember(presentation, amounts) { amounts?.canAdjustWorkbenchServings(presentation) == true }
    val usagePlan = remember(presentation, amounts) {
        if (presentation != null && amounts != null) {
            RecipeStepUsagePlan(presentation.steps, amounts, presentation.amountAnnotations)
        } else {
            null
        }
    }
    val isFav = favoriteEntries?.entries?.any { it.recipeId == recipe.id } == true

    Scaffold(
        modifier = Modifier.testTag("recipe.detail.$recipeId"),
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
                title = { Text(recipe.name, maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    if (onOpenRecipeFeedback != null) {
                        TextButton(onClick = { onOpenRecipeFeedback(recipeId) }) {
                            Text("反馈")
                        }
                    }
                    TextButton(
                        enabled = favoriteEntries != null,
                        onClick = {
                            scope.launch {
                                container.userLibraryRepository.toggleFavorite(recipe.id, isFav, libraryScope)
                                    .onFailure { favoriteError = it.message ?: "收藏保存失败，请重试。" }
                            }
                        },
                    ) {
                        Text(if (isFav) "★ 已收藏" else "☆ 收藏")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            state = contentScroll,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${recipe.emojis.orEmpty().take(3).joinToString("")} ${recipe.name}".trim(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        recipe.difficulty?.takeIf { it.isNotEmpty() }?.let {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                                Text("难度：$it", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (recipe.tools.isNotEmpty()) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                                Text("厨具：${recipe.tools.joinToString("、")}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        recipe.tags.filterNot { it == InstitutionalLowGICatalog.TAG }.forEach { tag ->
                            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                                Text(tag, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (ingredientGroups.any { (_, names) -> names.isNotEmpty() }) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((label, names) in ingredientGroups) {
                            if (names.isEmpty()) continue
                            val matched = label == "已有食材"
                            Text(
                                text = "$label：${names.joinToString("、")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = if (matched) Modifier.testTag("recipe.matchedIngredients") else Modifier,
                            )
                        }
                    }
                }
            }

            if (dietaryGoal != null) {
                item {
                    if (review == null) MissingRecipeDietaryReview()
                    else RecipeDietaryReview(review) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                // The body header, its rows and the source links are the final list items.
                                val index = contentScroll.layoutInfo.totalItemsCount - bodyRows.size - 2
                                if (index >= 0) scope.launch { contentScroll.animateScrollToItem(index) }
                            }) { Text("查看已核对做法") }
                            TextButton(onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "${recipe.name}\n${review.entry.sourceLink}")
                                }
                                context.startActivity(Intent.createChooser(share, "分享已核对原文"))
                            }) { Text("分享已核对原文") }
                        }
                    }
                }
            }

            if (institutionalReview != null) {
                institutionalLowGIReview(institutionalReview, institutionalCatalog.chineseReferences, context::openRecipeSource)
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onOpenWorkbench(recipe.id) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("进入厨房工作台")
                    }
                    if (onOpenRecipeFeedback != null) {
                        OutlinedButton(onClick = { onOpenRecipeFeedback(recipeId) }) {
                            Text("报错/反馈")
                        }
                    }
                }
            }
            item {
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, RecipeShareLinks.url(recipe.id))
                        putExtra(Intent.EXTRA_TITLE, recipe.name)
                        putExtra(Intent.EXTRA_SUBJECT, recipe.name)
                    }
                    context.startActivity(Intent.createChooser(share, "分享菜谱"))
                }) { Text("分享菜谱") }
                val journalId = JournalRecipeId.Catalog(recipe.id)
                CookingRecordActions(container.cookingJournalStore, journalId, recipe.name) { onOpenJournal(journalId) }
            }

            if (adjustableServings) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("按份数调整用量", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "当前：$servings 份（固定批次调料不倍乘）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = {
                                        if (servings > RecipeAmounts.SERVING_RANGE.first) {
                                            container.workbenchStore.updateServings(recipe.id, servings - 1, presentation)
                                        }
                                    },
                                    enabled = servings > RecipeAmounts.SERVING_RANGE.first,
                                ) { Text("-") }
                                Text(
                                    text = " $servings ",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                                OutlinedButton(
                                    onClick = {
                                        if (servings < RecipeAmounts.SERVING_RANGE.last) {
                                            container.workbenchStore.updateServings(recipe.id, servings + 1, presentation)
                                        }
                                    },
                                    enabled = servings < RecipeAmounts.SERVING_RANGE.last,
                                ) { Text("+") }
                            }
                        }
                    }
                }
            }

            if (presentation != null && presentation.allQuantities.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("必备原料与用量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            presentation.allQuantities.forEach { qty ->
                                val scaled = amounts?.amount(qty)
                                val displayText = if (scaled != null && scaled.text != qty.amount) {
                                    "${qty.name}：${scaled.text}  （原：${qty.amount}）"
                                } else {
                                    "${qty.name}：${qty.amount}"
                                }
                                Text("• $displayText", style = MaterialTheme.typography.bodyMedium)
                                scaled?.note?.takeIf { it.isNotEmpty() }?.let { note ->
                                    Text("   注：$note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            if (recipeText != null) {
                item(key = "body--1") {
                    Text(
                        if (review != null) "已核对的图文做法" else "图文做法正文",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (steps.isNotEmpty()) {
                        RecipeStepProgress(steps, checkedSteps, onReset = { confirmingStepReset = true }) { locateFrom(-1, it) }
                    }
                }
                bodyRows.forEachIndexed { rowIndex, row ->
                    item(key = "body-$rowIndex") {
                        if (row is RecipeBodyRow.Step) {
                            val step = row.step
                            RecipeStepCard(
                                step = step,
                                text = usagePlan?.instruction(step)?.text ?: step.text,
                                checked = step.id in checkedSteps,
                                located = locator.destination?.stepId == step.id,
                                onCheckedChange = {
                                    container.workbenchStore.toggleAndFindNextStep(recipeId, step, steps)?.let { locateFrom(rowIndex, it) }
                                },
                            )
                            return@item
                        }
                        val (index, block) = row as RecipeBodyRow.Block
                        if (index in supplementary && block !is RecipeTextBlock.ImageBlock) {
                            RecipeSupplementaryBlock(block, recipeText.blocks.getOrNull(index + 1) is RecipeTextBlock.ListBlock, steps) {
                                locateFrom(rowIndex, it)
                            }
                            return@item
                        }
                        when (block) {
                            is RecipeTextBlock.Heading -> {
                                if (index > 0) HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                                Text(
                                    block.text,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            is RecipeTextBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                            is RecipeTextBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                block.items.forEachIndexed { itemIndex, itemText ->
                                    val resolvedText = amounts?.instruction(itemText)?.text ?: itemText
                                    val prefix = if (block.ordered) "${block.start + itemIndex}." else "•"
                                    Text("$prefix $resolvedText", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            is RecipeTextBlock.ImageBlock -> photosByBlock[index]?.let { photo ->
                                key(photoPreviewRevision) {
                                    RecipePhotoPreview(photo, container.recipeImages) { photoBlock = index }
                                }
                            }
                        }
                    }
                }
            }

            item {
                val safeExternalUrls = buildList {
                    if (!recipe.bv.isNullOrEmpty()) {
                        val label = if (review != null) "其他视频做法（未核对）" else "打开 B 站视频"
                        add(label to "https://www.bilibili.com/video/${recipe.bv}")
                    }
                    val reviewedSource = review?.entry?.sourceLink
                    if (reviewedSource != null) add("打开已核对原文" to reviewedSource)
                    else if (institutionalReview == null) {
                        recipe.link?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
                            add("打开图文原链接" to it)
                        }
                    }
                }
                if (safeExternalUrls.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        safeExternalUrls.forEach { (label, url) ->
                            OutlinedButton(
                                onClick = { context.openRecipeSource(url) },
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
    }

    photoBlock?.let { block ->
        RecipeImageViewer(recipeId, photos, block, container.recipeImages) {
            photoBlock = null
            photoPreviewRevision += 1
        }
    }

    if (confirmingStepReset) {
        WorkbenchCheckResetDialog(
            WorkbenchCheckReset.STEPS,
            onConfirm = {
                container.workbenchStore.clearSteps(recipeId)
                confirmingStepReset = false
            },
            onDismiss = { confirmingStepReset = false },
        )
    }

    favoriteError?.let { err ->
        AlertDialog(
            onDismissRequest = { favoriteError = null },
            title = { Text("收藏保存失败") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { favoriteError = null }) { Text("知道了") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KitchenWorkbenchScreen(
    recipeId: Int,
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onBack: () -> Unit,
    onOpenJournal: (JournalRecipeId) -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenMembership: () -> Unit = {},
    dietaryGoal: DietaryGoal? = null,
    revealTimer: Boolean = false,
    onTimerRevealed: () -> Unit = {},
) {
    val context = LocalContext.current
    val recipe = snapshot.recipe(recipeId)
    val review = remember(recipe, dietaryGoal, snapshot) { recipe?.let { snapshot.reviewedRecipe(it, dietaryGoal) } }
    val recipeText = remember(recipeId, snapshot, review) { review?.text ?: snapshot.recipeText(recipeId) }
    val presentation = remember(recipeText) { recipeText?.let(::RecipePresentation) }
    val workbenchMap by container.workbenchStore.state.collectAsState()
    val prefs by container.preferencesStore.state.collectAsState()
    val entry = workbenchMap.entries[recipeId] ?: cn.yunyoujun.cook.data.local.RecipeWorkbenchEntry(recipeId = recipeId)

    val guidance: KitchenStepGuidanceModel = viewModel()
    val guidanceStep = presentation?.steps?.firstOrNull { it.id == guidance.stepId }
    if (guidance.stepId != null && (recipe == null || guidanceStep == null)) {
        LaunchedEffect(guidance, guidance.stepId) { guidance.close() }
    }
    var showCompletion by rememberSaveable(recipeId) { mutableStateOf(false) }
    var photoBlock by rememberSaveable(recipeId) { mutableStateOf<Int?>(null) }
    var photoPreviewRevision by remember(recipeId) { mutableIntStateOf(0) }
    var layout by rememberSaveable(recipeId) { mutableStateOf(WorkbenchLayout.AUTOMATIC) }
    var showLayoutMenu by remember { mutableStateOf(false) }
    var checkReset by rememberSaveable(recipeId) { mutableStateOf<WorkbenchCheckReset?>(null) }
    var timerOverDetails by rememberSaveable(recipeId) { mutableStateOf(false) }

    if (recipe == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("厨房工作台") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                )
            },
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(24.dp),
            ) {
                Text("这道菜谱已不可用。")
            }
        }
        return
    }

    val institutionalReview = snapshot.institutionalLowGICatalog?.entry(recipe)
    val photos = remember(recipeText, snapshot, recipe.name) { recipePhotos(recipeText, snapshot.textLibrary, recipe.name) }
    val viewingPhoto = photos.any { it.blockIndex == photoBlock }
    val viewingGuidance = recipeText != null && guidanceStep != null
    val viewingDetails = viewingPhoto || viewingGuidance
    LaunchedEffect(revealTimer, viewingDetails) {
        if (revealTimer && viewingDetails) {
            timerOverDetails = true
            onTimerRevealed()
        }
    }
    val blocks = recipeText?.blocks.orEmpty()
    val supplementary = remember(blocks) { recipeSupplementaryIndices(blocks) }
    val steps = presentation?.steps.orEmpty()
    val instructionsScroll = rememberLazyListState()
    val locator = rememberRecipeStepLocator(recipeId, instructionsScroll)
    val locate: (RecipePresentation.Step) -> Unit = { step ->
        val index = steps.indexOf(step)
        // The first item is the instructions heading.
        if (index >= 0) locator.locate(step, index + 1)
    }
    val amounts = remember(presentation, entry.servings) { RecipeAmounts(presentation?.allQuantities.orEmpty(), entry.servings) }
    val preparationItems = remember(review, institutionalReview, presentation, recipe, snapshot) {
        kitchenPreparationItems(
            presentation,
            institutionalReview?.ingredients ?: review?.entry?.ingredients ?: recipe.stuff,
            recipe.tools,
            legacyCatalogIngredients = if (review == null && institutionalReview == null) recipe.stuff else emptyList(),
        )
    }
    val usagePlan = remember(presentation, amounts) {
        presentation?.let { RecipeStepUsagePlan(it.steps, amounts, it.amountAnnotations) }
    }
    val completionNotice = remember { SnackbarHostState() }
    val completionScope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(completionNotice) },
        topBar = {
            TopAppBar(
                title = { Text("工作台 · ${recipe.name}", maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    Box {
                        TextButton(onClick = { showLayoutMenu = true }) { Text("布局") }
                        DropdownMenu(expanded = showLayoutMenu, onDismissRequest = { showLayoutMenu = false }) {
                            WorkbenchLayout.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = { RadioButton(selected = layout == option, onClick = null) },
                                    onClick = {
                                        layout = option
                                        showLayoutMenu = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("重置步骤") },
                                enabled = entry.checkedSteps.isNotEmpty(),
                                onClick = {
                                    showLayoutMenu = false
                                    checkReset = WorkbenchCheckReset.STEPS
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("重置所有勾选") },
                                enabled = entry.checkedIngredients.isNotEmpty() || entry.checkedSteps.isNotEmpty(),
                                onClick = {
                                    showLayoutMenu = false
                                    checkReset = WorkbenchCheckReset.ALL
                                },
                            )
                        }
                    }
                    TextButton(onClick = { showCompletion = true }) { Text("做完了") }
                },
            )
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            KitchenWorkbenchPanels(
                recipeId = recipeId,
                layout = layout,
                instructionsScroll = instructionsScroll,
                startsWithInstructions = dietaryGoal != null || institutionalReview != null,
                modifier = Modifier.fillMaxSize(),
                revealTimer = revealTimer && !viewingDetails,
                onTimerRevealed = {
                    showLayoutMenu = false
                    checkReset = null
                    showCompletion = false
                    onTimerRevealed()
                },
                instructionHeader = {
                    if (steps.isNotEmpty()) RecipeStepProgress(steps, entry.checkedSteps, onLocate = locate)
                },
                preparation = {
                    item {
                        KitchenTimerCard(recipeId, recipe.name, entry.timer, container.workbenchStore, dietaryGoal)
                    }

                    kitchenPreparationChecklist(
                        checklist = preparationItems,
                        entry = entry,
                        presentation = presentation,
                        amounts = amounts,
                        institutional = institutionalReview != null,
                        onServingsChange = { container.workbenchStore.updateServings(recipeId, it, presentation) },
                        onToggle = { item ->
                            container.workbenchStore.toggleIngredient(recipeId, item.key, item.previousKeys)
                        },
                        onClearPrepared = { checkReset = WorkbenchCheckReset.PREPARATION },
                    )
                },
                instructions = {
                    item {
                        if (review != null) RecipeDietaryReview(review)
                        else if (dietaryGoal != null) MissingRecipeDietaryReview()
                        if (institutionalReview != null) InstitutionalLowGIRecipeSource(institutionalReview, context::openRecipeSource)
                        if (steps.isNotEmpty() || institutionalReview == null) {
                            Text(
                                if (review != null) "已核对的制作步骤" else "制作步骤",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (steps.isEmpty() && institutionalReview == null) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("暂无图文步骤，可以先备料和计时。")
                                    val source = recipe.videoUrl ?: recipe.link?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                                    source?.let {
                                        TextButton(onClick = { context.openRecipeSource(it) }) { Text("查看原做法") }
                                    }
                                }
                            }
                        }
                    }
                    items(steps, key = { "step-${it.id}" }) { step ->
                        RecipeStepCard(
                            step = step,
                            text = usagePlan?.instruction(step)?.text ?: step.text,
                            checked = step.id in entry.checkedSteps,
                            located = locator.destination?.stepId == step.id,
                            onCheckedChange = {
                                container.workbenchStore.toggleAndFindNextStep(recipeId, step, steps)?.let(locate)
                            },
                            guidanceLabel = if (prefs.aiProviderMode == AiProviderMode.DISABLED) "原文核对" else "AI 讲解这一步",
                            onGuidance = { guidance.open(step.id) },
                        )
                    }
                    supplementary.forEach { index ->
                        val block = blocks[index]
                        if (block !is RecipeTextBlock.ImageBlock && presentation?.stepsByBlock?.containsKey(index) != true) {
                            item(key = "supplementary-$index") {
                                RecipeSupplementaryBlock(block, blocks.getOrNull(index + 1) is RecipeTextBlock.ListBlock, steps, locate)
                            }
                        }
                    }
                    if (photos.isNotEmpty()) {
                        item { Text("做法配图", style = MaterialTheme.typography.titleMedium) }
                        items(photos, key = { it.blockIndex }) { photo ->
                            key(photoPreviewRevision) {
                                RecipePhotoPreview(photo, container.recipeImages) { photoBlock = photo.blockIndex }
                            }
                        }
                    }
                },
            )
        }
    }

    photoBlock?.let { block ->
        RecipeImageViewer(recipeId, photos, block, container.recipeImages) {
            photoBlock = null
            photoPreviewRevision += 1
        }
    }

    checkReset?.let { reset ->
        WorkbenchCheckResetDialog(
            reset,
            onConfirm = {
                when (reset) {
                    WorkbenchCheckReset.PREPARATION -> container.workbenchStore.clearPrepared(recipeId)
                    WorkbenchCheckReset.STEPS -> container.workbenchStore.clearSteps(recipeId)
                    WorkbenchCheckReset.ALL -> container.workbenchStore.resetRecipeChecks(recipeId)
                }
                checkReset = null
            },
            onDismiss = { checkReset = null },
        )
    }

    if (showCompletion) {
        val journalId = JournalRecipeId.Catalog(recipe.id)
        KitchenCompletionDialog(
            store = container.cookingJournalStore,
            recipeId = journalId,
            name = recipe.name,
            onDismiss = { showCompletion = false },
            onCooked = { recorded ->
                showCompletion = false
                completionNotice.currentSnackbarData?.dismiss()
                completionScope.launch {
                    completionNotice.showSnackbar(if (recorded) "已记下这次下厨时光" else ALREADY_COOKED_TODAY)
                }
            },
            onOpenJournal = {
                showCompletion = false
                onOpenJournal(journalId)
            },
        )
    }

    if (recipeText != null && guidanceStep != null) {
        KitchenStepGuidance(
            container = container,
            model = guidance,
            recipe = recipe,
            recipeText = recipeText,
            dataVersion = snapshot.data.meta.dataVersion,
            step = guidanceStep,
            stepNumber = guidanceStep.number,
            stepCompleted = guidanceStep.id in entry.checkedSteps,
            servings = entry.servings,
            provider = prefs.aiProviderMode,
            onOpenAiSettings = onOpenAiSettings,
            onOpenAccount = onOpenAccount,
            onOpenMembership = onOpenMembership,
        )
    }

    if (timerOverDetails) {
        AlertDialog(
            onDismissRequest = { timerOverDetails = false },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    KitchenTimerCard(recipeId, recipe.name, entry.timer, container.workbenchStore, dietaryGoal)
                }
            },
            confirmButton = {
                TextButton(onClick = { timerOverDetails = false }) {
                    Text(when {
                        viewingGuidance -> "返回分步指导"
                        viewingPhoto -> "返回配图"
                        else -> "返回工作台"
                    })
                }
            },
        )
    }
}
