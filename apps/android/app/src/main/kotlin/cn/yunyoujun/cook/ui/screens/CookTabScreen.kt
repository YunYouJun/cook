package cn.yunyoujun.cook.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.core.model.StuffItem
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.SignalConsentState
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.data.repository.CookDiscovery
import cn.yunyoujun.cook.platform.DeviceShakeDetector
import cn.yunyoujun.cook.ui.components.DietaryGoalBar
import cn.yunyoujun.cook.ui.components.RecipeSummaryCard
import cn.yunyoujun.cook.ui.components.TagFlowChips
import cn.yunyoujun.cook.ui.components.rememberCookSceneCamera
import cn.yunyoujun.cook.ui.navigation.CookNavKey
import cn.yunyoujun.cook.ui.state.CookSessionController
import cn.yunyoujun.cook.ui.state.RecordRecipeVisit
import cn.yunyoujun.cook.ui.state.rememberLocalCalendarClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CookTabScreen(
    snapshot: CookCatalogSnapshot,
    sessionController: CookSessionController,
    container: AppContainer,
    onOpenRecipe: (CookNavKey.RecipeDetail) -> Unit,
    onOpenWorkbench: (CookNavKey.KitchenWorkbench) -> Unit = {},
    onOpenJournal: (JournalRecipeId) -> Unit = {},
    onOpenRecipeFeedback: ((Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conditions by sessionController.conditions.collectAsState()
    val prefs by container.preferencesStore.state.collectAsState()
    val session by container.yunlefunSessionManager.state.collectAsState()
    val libraryScope = session.libraryScope
    val favorites by remember(container) { container.userLibraryRepository.observeFavorites() }.collectAsState(initial = null)
    val favoriteEntries = favorites?.takeIf { it.scope == libraryScope }
    val favoriteIds = remember(favoriteEntries) { favoriteEntries?.entries.orEmpty().map { it.recipeId }.toSet() }
    val fridgeDoc by container.fridgeStore.state.collectAsState()
    val today = rememberLocalCalendarClock().toLocalDate()
    val fridgeStock = remember(fridgeDoc, today) { fridgeDoc.stockOn(today).filter { it.usableForCooking } }
    val fridgeNames = remember(fridgeStock, snapshot) {
        snapshot.data.normalizer.unique(fridgeStock.map { it.batch.ingredientName })
    }
    val fridgePriority = remember(fridgeStock, snapshot) {
        snapshot.data.normalizer.unique(fridgeStock.filter { it.needsAttention }.map { it.batch.ingredientName })
    }
    val personalCatalog by container.personalCatalogStore.state.collectAsState()
    val appliedFood = remember(personalCatalog, snapshot) {
        personalCatalog.toDocument().applyingTo(snapshot.data.food)
    }

    var showIngredientPicker by rememberSaveable { mutableStateOf(false) }
    var showFilterDialog by rememberSaveable { mutableStateOf(false) }
    var showDecideDialog by rememberSaveable { mutableStateOf(false) }
    var decideBatchIds by rememberSaveable { mutableStateOf<IntArray?>(null) }
    var showCompleteResults by rememberSaveable { mutableStateOf(false) }
    var returnToCloudKitchen by rememberSaveable { mutableStateOf(false) }
    var lastDecidedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Int?>(null) }
    var detailGoal by rememberSaveable { mutableStateOf<DietaryGoal?>(null) }
    var detailIngredients by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var showRecipesInSidebar by rememberSaveable { mutableStateOf(false) }
    val compactScroll = rememberLazyListState()
    val chooserScroll = rememberLazyListState()
    val resultsScroll = rememberLazyListState()
    val cloudKitchenCamera = rememberCookSceneCamera()
    var sceneBatch by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var favoriteError by remember { mutableStateOf<String?>(null) }

    RecordRecipeVisit(detailId?.let(snapshot::recipe), container.userLibraryRepository, libraryScope)

    val candidateResult = remember(snapshot, conditions, prefs) {
        sessionController.evaluateCandidates(snapshot, prefs)
    }
    val canDecide = candidateResult.recipes.isNotEmpty() || conditions.discoveryScene != null
    fun openDecision(batch: List<Recipe>? = null) {
        decideBatchIds = batch?.map { it.id }?.toIntArray()
        showDecideDialog = true
    }

    fun closeDetail() {
        detailId = null
        detailIngredients = arrayListOf()
        showRecipesInSidebar = false
    }

    fun showUpdatedResults() {
        closeDetail()
        resultsScroll.requestScrollToItem(0)
    }

    fun toggleIngredient(name: String) {
        sessionController.toggleStuff(name)
        resultsScroll.requestScrollToItem(0)
    }

    val clearFilters = {
        sessionController.clearFiltersOnly()
        if (prefs.noSpicy) container.preferencesStore.setNoSpicy(false)
        showUpdatedResults()
    }

    LaunchedEffect(conditions.discoveryScene, candidateResult.recipes) {
        sceneBatch = if (conditions.discoveryScene != null) {
            CookDiscovery.sample(candidateResult.recipes, count = 3, avoiding = emptyList())
        } else {
            emptyList()
        }
    }

    LaunchedEffect(conditions.selectedStuff, conditions.tool, prefs.mode, candidateResult.recipes.size) {
        if (conditions.selectedStuff.isNotEmpty() && candidateResult.recipes.isEmpty()) {
            container.feedbackClient.signalEmpty(
                stuff = conditions.selectedStuff,
                tool = conditions.tool,
                mode = prefs.mode,
                recipeVersion = snapshot.data.meta.dataVersion,
                consentAllowed = prefs.signalConsent == SignalConsentState.ALLOWED,
            )
        }
    }

    val showingSample = !showCompleteResults && conditions.discoveryScene != null &&
        conditions.selectedStuff.isEmpty() && conditions.keyword.isBlank()
    val shakeToDecide by rememberUpdatedState {
        if (candidateResult.recipes.isNotEmpty() && detailId == null && !showSearch && !showIngredientPicker && !showFilterDialog) {
            openDecision(sceneBatch.takeIf { showingSample && it.isNotEmpty() })
        }
    }
    val shakeDetector = remember(context) {
        DeviceShakeDetector(context) { shakeToDecide() }
    }
    LifecycleResumeEffect(shakeDetector, showDecideDialog) {
        if (!showDecideDialog) shakeDetector.start()
        onPauseOrDispose { shakeDetector.stop() }
    }

    val displayedRecipes = if (showingSample) sceneBatch else candidateResult.recipes
    val openCandidate: (Boolean, CookNavKey.RecipeDetail) -> Unit = { expanded, route ->
        if (expanded) {
            detailId = route.recipeId
            detailGoal = route.dietaryGoal
            detailIngredients = ArrayList(route.matchedIngredients)
            showRecipesInSidebar = true
        } else {
            onOpenRecipe(route)
        }
    }

    fun LazyListScope.chooser(expanded: Boolean) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        prefs.cloudKitchen -> "云厨房"
                        expanded -> "食材快选"
                        else -> "今天做什么菜"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            showCompleteResults = false
                            returnToCloudKitchen = false
                            container.preferencesStore.setCloudKitchen(!prefs.cloudKitchen)
                            closeDetail()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text(if (prefs.cloudKitchen) "列表" else "云厨房") }
                    OutlinedButton(
                        onClick = { openDecision() },
                        enabled = canDecide,
                    ) {
                        Text("随机一道")
                    }
                }
            }
        }

        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    showUpdatedResults()
                    showSearch = true
                }) { Text("搜索菜名或食材") }
                if (conditions.keyword.isNotEmpty()) {
                    TextButton(onClick = {
                        sessionController.setKeyword("")
                        showUpdatedResults()
                    }) {
                        Text("清除菜名：“${conditions.keyword}”")
                    }
                }
            }
        }

        if (prefs.cloudKitchen) {
            item("cloud-kitchen") {
                CloudKitchenCard(
                    selected = conditions.selectedStuff.toSet(),
                    hidden = personalCatalog.hiddenBuiltInNames,
                    normalize = snapshot.data.normalizer::normalize,
                    allowRotation = prefs.cloudKitchenRotation,
                    camera = cloudKitchenCamera,
                    onRotationChanged = container.preferencesStore::setCloudKitchenRotation,
                    onPick = ::toggleIngredient,
                    onShowAllIngredients = { showIngredientPicker = true },
                    recipes = candidateResult.recipes,
                    menuAvailable = !showSearch && !showIngredientPicker && !showFilterDialog && !showDecideDialog,
                    onShowResults = {
                        showCompleteResults = true
                        if (expanded) {
                            showUpdatedResults()
                        } else {
                            val heading = (compactScroll.layoutInfo.totalItemsCount - displayedRecipes.size.coerceAtLeast(1) - 1)
                                .coerceAtLeast(0)
                            returnToCloudKitchen = true
                            compactScroll.requestScrollToItem(heading)
                        }
                    },
                )
            }
        }

        if (expanded && !prefs.cloudKitchen) {
            item("vegetables") {
                DiscoveryIngredientSection("蔬菜", appliedFood.vegetable, conditions.selectedStuff, ::toggleIngredient)
            }
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!expanded && !prefs.cloudKitchen) {
                    Button(onClick = { showIngredientPicker = true }) {
                        val countLabel = if (conditions.selectedStuff.isEmpty()) "选食材" else "选食材 (${conditions.selectedStuff.size})"
                        Text(countLabel)
                    }
                }
                OutlinedButton(onClick = {
                    showUpdatedResults()
                    showFilterDialog = true
                }) {
                    val count = conditions.activeFilterCount(prefs)
                    Text(if (count == 0) "筛选与模式" else "筛选 ($count)")
                }
                if (conditions.selectedStuff.isNotEmpty() || conditions.isFilterActive(prefs) || conditions.keyword.isNotEmpty()) {
                    TextButton(onClick = {
                        sessionController.clearSessionConditions()
                        showUpdatedResults()
                    }) {
                        Text("清空本次条件")
                    }
                }
            }
        }

        if (fridgeNames.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("冰箱里有 ${fridgeNames.size} 种食材", style = MaterialTheme.typography.bodySmall)
                            val preview = fridgePriority.ifEmpty { fridgeNames }
                            val names = preview.take(3).joinToString("、") + if (preview.size > 3) "…" else ""
                            Text(
                                text = if (fridgePriority.isEmpty()) names else "优先食用 $names",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (fridgePriority.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        val added = conditions.selectedStuff.containsAll(fridgeNames)
                        TextButton(
                            enabled = !added,
                            onClick = {
                                val normalized = snapshot.data.normalizer.unique(conditions.selectedStuff + fridgeNames)
                                sessionController.setSelectedStuff(normalized)
                                showUpdatedResults()
                            },
                        ) {
                            Text(if (added) "已选入" else "一键选入")
                        }
                    }
                }
            }
        }

        if (!prefs.cloudKitchen && conditions.selectedStuff.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已选食材（点击移除）", style = MaterialTheme.typography.labelMedium)
                    TagFlowChips(
                        items = conditions.selectedStuff,
                        selectedItems = conditions.selectedStuff.toSet(),
                        onToggle = ::toggleIngredient,
                    )
                }
            }
        }

        if (expanded && !prefs.cloudKitchen) {
            item("protein") {
                DiscoveryIngredientSection("肉和蛋", appliedFood.meat, conditions.selectedStuff, ::toggleIngredient)
            }
            item("staples") {
                DiscoveryIngredientSection("主食", appliedFood.staple, conditions.selectedStuff, ::toggleIngredient)
            }
            item("tools") {
                Text("厨具（可选）", style = MaterialTheme.typography.titleSmall)
                TagFlowChips(appliedFood.tools.map { it.name }.distinct(), setOfNotNull(conditions.tool)) {
                    sessionController.toggleTool(it)
                    showUpdatedResults()
                }
            }
        }

        val avoidsSpicy = conditions.noSpicy || prefs.noSpicy
        if (conditions.avoid.isNotEmpty() || avoidsSpicy) {
            item {
                val restrictionLabels = conditions.avoid.map { "忌口：$it" } +
                    if (avoidsSpicy) listOf("不吃辣") else emptyList()
                Text(
                    text = "本次限制：${restrictionLabels.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "发现灵感场景",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CookDiscoverySceneChips(snapshot, conditions.discoveryScene) {
                    sessionController.toggleDiscoveryScene(it)
                    showUpdatedResults()
                }
                conditions.discoveryScene?.let { activeScene ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(activeScene.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(activeScene.story, style = MaterialTheme.typography.bodySmall)
                            Text(
                                activeScene.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            if (conditions.selectedStuff.isEmpty() && conditions.keyword.isBlank()) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        onClick = {
                                            showCompleteResults = false
                                            sceneBatch = CookDiscovery.sample(
                                                candidateResult.recipes,
                                                count = 3,
                                                avoiding = sceneBatch,
                                            )
                                        },
                                    ) {
                                        Text("换一批")
                                    }
                                    if (showingSample && sceneBatch.isNotEmpty()) {
                                        TextButton(onClick = { openDecision(sceneBatch) }) {
                                            Text("从这几道里选")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            DietaryGoalBar(
                availableGoals = snapshot.availableDietaryGoals,
                selectedGoal = conditions.dietaryGoal(prefs),
                onSelectGoal = { goal ->
                    sessionController.selectDietaryGoal(goal, container.preferencesStore)
                    showUpdatedResults()
                },
            )
        }
    }

    fun LazyListScope.results(recipes: List<Recipe>, expanded: Boolean, inSidebar: Boolean = false) {
        item("results-heading") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (candidateResult.isCandidatePoolMode) {
                        "候选菜谱：${candidateResult.recipes.size} 道"
                    } else {
                        "为你匹配到 ${candidateResult.recipes.size} 道菜"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (conditions.isFilterActive(prefs)) {
                    TextButton(
                        onClick = clearFilters,
                    ) {
                        Text("清除筛选")
                    }
                }
            }
            if (expanded && conditions.selectedStuff.isNotEmpty()) {
                Text(
                    "已选：${conditions.selectedStuff.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!inSidebar && showingSample && recipes.size < candidateResult.recipes.size) {
                TextButton(onClick = { showCompleteResults = true }) {
                    Text("查看全部 ${candidateResult.recipes.size} 道菜")
                }
            }
        }

        if (recipes.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("暂时没有同时满足全部条件的菜谱", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "可以试着切换「宽松模式」、减少几种必需食材，或清除部分筛选。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = clearFilters, enabled = conditions.isFilterActive(prefs)) {
                                Text("清除筛选")
                            }
                            if (prefs.mode != RecommendationMode.LOOSE) {
                                Button(onClick = { container.preferencesStore.setMode(RecommendationMode.LOOSE) }) {
                                    Text("切到宽松模式")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            itemsIndexed(recipes, key = { _, item -> item.id }) { rank, recipe ->
                val activeGoal = conditions.dietaryGoal(prefs)
                val dietaryNote = activeGoal?.let { snapshot.dietaryCatalog?.entry(recipe, it)?.reason(it) }
                val isFav = recipe.id in favoriteIds
                RecipeSummaryCard(
                    recipe = recipe,
                    match = candidateResult.matchesById[recipe.id],
                    isFavorite = isFav,
                    sceneReason = snapshot.sceneReason(recipe, conditions.discoveryScene),
                    dietaryNote = dietaryNote,
                    showInstitutionalTag = snapshot.institutionalLowGICatalog?.entry(recipe) != null,
                    modifier = Modifier.testTag("cook.recipe.${recipe.id}"),
                    isSelected = inSidebar && detailId == recipe.id,
                    favoriteEnabled = favoriteEntries != null,
                    onToggleFavorite = {
                        scope.launch {
                            container.userLibraryRepository.toggleFavorite(recipe.id, isFav, libraryScope)
                                .onFailure { favoriteError = it.message ?: "收藏保存失败，请重试。" }
                        }
                    },
                    onClick = {
                        if (conditions.selectedStuff.isNotEmpty()) {
                            scope.launch {
                                container.feedbackClient.signalOpen(
                                    stuff = conditions.selectedStuff,
                                    tool = conditions.tool,
                                    mode = prefs.mode,
                                    recipeId = recipe.id,
                                    rank = rank,
                                    recipeVersion = snapshot.data.meta.dataVersion,
                                    consentAllowed = prefs.signalConsent == SignalConsentState.ALLOWED,
                                )
                            }
                        }
                        openCandidate(expanded, CookNavKey.RecipeDetail(
                            recipe.id,
                            activeGoal,
                            candidateResult.matchesById[recipe.id]?.matchedStuff.orEmpty(),
                        ))
                    },
                )
            }
        }
    }

    @Composable
    fun EmbeddedRecipe(id: Int) {
        key(id, detailGoal) {
            RecipeDetailScreen(
                recipeId = id,
                snapshot = snapshot,
                container = container,
                onBack = ::closeDetail,
                onOpenWorkbench = { onOpenWorkbench(CookNavKey.KitchenWorkbench(it, detailGoal)) },
                onOpenJournal = onOpenJournal,
                dietaryGoal = detailGoal,
                embedded = true,
                recommendationSession = sessionController.takeIf { detailIngredients.isNotEmpty() },
                onOpenRecipeFeedback = onOpenRecipeFeedback,
            )
        }
    }

    CookDiscoveryLayout(overlay = { expanded ->
        if (showDecideDialog) {
            CookDecideDialog(
                snapshot = snapshot,
                container = container,
                controller = sessionController,
                ignoringKeyword = false,
                avoidFirstId = lastDecidedId,
                batchIds = decideBatchIds,
                onRecipePreviewed = { lastDecidedId = it },
                onOpenRecipe = { route ->
                    lastDecidedId = route.recipeId
                    showDecideDialog = false
                    openCandidate(expanded, route)
                },
                onDismiss = { showDecideDialog = false },
            )
        }
    }) { sidebarWidth, separation ->
        val activeDetail = detailId
        val canReturnToCloudKitchen = sidebarWidth == null && prefs.cloudKitchen && returnToCloudKitchen &&
            compactScroll.firstVisibleItemIndex > 0
        BackHandler(enabled = activeDetail != null || canReturnToCloudKitchen) {
            if (activeDetail != null) {
                closeDetail()
            } else {
                returnToCloudKitchen = false
                compactScroll.requestScrollToItem(0)
            }
        }
        if (sidebarWidth == null) {
            if (activeDetail != null) {
                EmbeddedRecipe(activeDetail)
            } else {
                LazyColumn(
                    state = compactScroll,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    chooser(expanded = false)
                    results(displayedRecipes, expanded = false)
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(sidebarWidth).fillMaxHeight().clipToBounds()) {
                    if (activeDetail != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                                SegmentedButton(
                                    selected = !showRecipesInSidebar,
                                    onClick = { showRecipesInSidebar = false },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                    label = { Text("食材") },
                                )
                                SegmentedButton(
                                    selected = showRecipesInSidebar,
                                    onClick = { showRecipesInSidebar = true },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                    label = { Text("菜谱列表") },
                                )
                            }
                            if (showRecipesInSidebar) {
                                TextButton(
                                    onClick = { openDecision() },
                                    enabled = canDecide,
                                ) { Text("随机一道") }
                            }
                        }
                    }
                    if (activeDetail != null && showRecipesInSidebar) {
                        LazyColumn(
                            state = resultsScroll,
                            modifier = Modifier.fillMaxSize().testTag("cook.sidebar.recipes"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            results(candidateResult.recipes, expanded = true, inSidebar = true)
                        }
                    } else {
                        LazyColumn(
                            state = chooserScroll,
                            modifier = Modifier.fillMaxSize().testTag("cook.chooser"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            chooser(expanded = true)
                        }
                    }
                }
                Spacer(Modifier.width(separation).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().testTag("cook.resultsPane")) {
                    if (activeDetail != null) {
                        EmbeddedRecipe(activeDetail)
                    } else {
                        LazyColumn(
                            state = resultsScroll,
                            modifier = Modifier.fillMaxSize().testTag("cook.results"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            results(displayedRecipes, expanded = true)
                        }
                    }
                }
            }
        }
    }

    if (showIngredientPicker) {
        val pantryCategory = "常用调料"
        val categories = listOf(
            "素菜" to appliedFood.vegetable.map { it.name },
            "荤菜" to appliedFood.meat.map { it.name },
            "主食" to appliedFood.staple.map { it.name },
            pantryCategory to snapshot.data.pantry.options,
        ).filter { it.second.isNotEmpty() }
        var tabIdx by rememberSaveable { mutableIntStateOf(0) }
        val safeIdx = tabIdx.coerceIn(0, (categories.size - 1).coerceAtLeast(0))

        AlertDialog(
            onDismissRequest = { showIngredientPicker = false },
            title = { Text("选择现有食材或调料") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (categories.isNotEmpty()) {
                        SecondaryScrollableTabRow(selectedTabIndex = safeIdx, edgePadding = 0.dp) {
                            categories.forEachIndexed { idx, (label, _) ->
                                Tab(
                                    selected = idx == safeIdx,
                                    onClick = { tabIdx = idx },
                                    text = { Text(label) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val (category, currentItems) = categories[safeIdx]
                        val isPantry = category == pantryCategory
                        TagFlowChips(
                            items = currentItems,
                            selectedItems = (conditions.selectedStuff + if (isPantry) prefs.pantry else emptyList()).toSet(),
                            onToggle = if (isPantry) container.preferencesStore::togglePantryItem else ::toggleIngredient,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showIngredientPicker = false }) {
                    Text("完成")
                }
            },
        )
    }

    if (showFilterDialog) {
        CookFilterDialog(
            snapshot, appliedFood, conditions, sessionController, prefs, container.preferencesStore,
            count = candidateResult.recipes.size,
            onClear = clearFilters,
            onClose = { showFilterDialog = false },
        )
    }

    if (showSearch) {
        CookSearchDialog(snapshot, sessionController, container, onClose = { showSearch = false })
    }

    favoriteError?.let { err ->
        AlertDialog(
            onDismissRequest = { favoriteError = null },
            title = { Text("收藏保存失败") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { favoriteError = null }) {
                    Text("知道了")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryIngredientSection(
    title: String,
    ingredients: List<StuffItem>,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    if (ingredients.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    val selectedNames = selected.toSet()
    val options = ingredients.distinctBy { it.name }
    val visible = if (showAll) options else options.filter { it.more != true || it.name in selectedNames }
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        visible.forEach { item ->
            FilterChip(
                selected = item.name in selectedNames,
                onClick = { onToggle(item.name) },
                modifier = Modifier.testTag("cook.ingredient.${item.name}"),
                label = { Text(item.name) },
            )
        }
        if (showAll || visible.size < options.size) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "收起" else "更多 ${options.size - visible.size} 种")
            }
        }
    }
}
