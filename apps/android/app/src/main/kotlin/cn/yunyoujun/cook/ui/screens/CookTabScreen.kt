package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.core.recommend.Recommender
import cn.yunyoujun.cook.data.local.AiProviderMode
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.SignalConsentState
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.data.repository.CookDiscovery
import cn.yunyoujun.cook.data.repository.CookDiscoveryScene
import cn.yunyoujun.cook.platform.DeviceShakeDetector
import cn.yunyoujun.cook.ui.components.DecideRandomDialog
import cn.yunyoujun.cook.ui.components.DietaryGoalBar
import cn.yunyoujun.cook.ui.components.RecipeSummaryCard
import cn.yunyoujun.cook.ui.components.SearchIntentReviewDialog
import cn.yunyoujun.cook.ui.components.TagFlowChips
import cn.yunyoujun.cook.ui.components.buildDecideContextSummary
import cn.yunyoujun.cook.ui.state.CookSessionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CookTabScreen(
    snapshot: CookCatalogSnapshot,
    sessionController: CookSessionController,
    container: AppContainer,
    onOpenRecipe: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conditions by sessionController.conditions.collectAsState()
    val prefs by container.preferencesStore.state.collectAsState()
    val favoriteIds by container.userLibraryRepository.observeDeviceFavoriteIds().collectAsState(initial = emptySet())
    val fridgeDoc by container.fridgeStore.state.collectAsState()
    val personalCatalog by container.personalCatalogStore.state.collectAsState()

    var showIngredientPicker by rememberSaveable { mutableStateOf(false) }
    var showFilterDialog by rememberSaveable { mutableStateOf(false) }
    var showDecideDialog by rememberSaveable { mutableStateOf(false) }
    var showCompleteKitchenResults by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var sceneBatch by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var pendingIntent by remember { mutableStateOf<Pair<String, SearchIntent>?>(null) }
    var pendingAiConsent by remember { mutableStateOf<Pair<String, AiProviderMode>?>(null) }
    var queryText by rememberSaveable { mutableStateOf(conditions.keyword) }
    var showRecentSearches by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    var intentError by remember { mutableStateOf<String?>(null) }
    var isAiSearching by remember { mutableStateOf(false) }
    var aiSearchJob by remember { mutableStateOf<Job?>(null) }
    val account by container.yunlefunSessionManager.state.collectAsState()
    LaunchedEffect(prefs.aiProviderMode, account.credential) {
        aiSearchJob?.cancel()
        pendingAiConsent = null
        pendingIntent = null
    }

    fun submitKeyword() {
        val text = queryText.trim()
        sessionController.setKeyword(text)
        container.preferencesStore.recordSearch(text)
        focus.clearFocus()
    }

    fun sendIntent(query: String, mode: AiProviderMode) {
        isAiSearching = true
        aiSearchJob = scope.launch {
            try {
                val draft = when (mode) {
                    AiProviderMode.BYOK_DEEPSEEK -> {
                        val key = container.vault.readSecret(AndroidKeystoreVault.KEY_DEEPSEEK_API_KEY)
                            ?: error("请先在 AI 设置中保存个人 DeepSeek Key。")
                        container.deepSeekClient.requestSearchIntent(key, query, snapshot.data, snapshot.availableDietaryGoals)
                    }
                    AiProviderMode.MANAGED_MEMBERSHIP -> {
                        val credential = container.yunlefunSessionManager.state.value.credential
                            ?.takeIf { it.isValid() } ?: error("请先登录有效的云乐坊会员账号。")
                        val result = container.managedAiService.parse(query, credential, snapshot.data, snapshot.availableDietaryGoals)
                        if (container.yunlefunSessionManager.state.value.credential != credential) return@launch
                        result
                    }
                    AiProviderMode.DISABLED -> sessionController.parseLocalIntent(snapshot, query)
                }
                pendingIntent = query to draft
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                intentError = e.message ?: "联网识别暂不可用，已切换到本机识别。"
                pendingIntent = query to sessionController.parseLocalIntent(snapshot, query)
            } finally {
                isAiSearching = false
            }
        }
    }
    var favoriteError by remember { mutableStateOf<String?>(null) }

    val candidateResult = remember(snapshot, conditions, prefs) {
        sessionController.evaluateCandidates(snapshot, prefs)
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

    val canDecide by rememberUpdatedState(candidateResult.recipes.isNotEmpty())
    val shakeDetector = remember(context) {
        DeviceShakeDetector(context) { if (canDecide) showDecideDialog = true }
    }
    DisposableEffect(shakeDetector, showDecideDialog) {
        if (!showDecideDialog) shakeDetector.start()
        onDispose { shakeDetector.stop() }
    }

    val displayedRecipes = if (prefs.cloudKitchen && showCompleteKitchenResults) {
        candidateResult.recipes
    } else if (conditions.discoveryScene != null && conditions.selectedStuff.isEmpty() && conditions.keyword.isBlank()) {
        sceneBatch
    } else {
        candidateResult.recipes.take(120)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (prefs.cloudKitchen) "云厨房" else "今天做什么菜",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            showCompleteKitchenResults = false
                            container.preferencesStore.setCloudKitchen(!prefs.cloudKitchen)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text(if (prefs.cloudKitchen) "列表" else "云厨房") }
                    OutlinedButton(
                        onClick = {
                            showDecideDialog = true
                        },
                        enabled = candidateResult.recipes.isNotEmpty(),
                    ) {
                        Text("随机一道")
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
                    onRotationChanged = container.preferencesStore::setCloudKitchenRotation,
                    onPick = sessionController::toggleStuff,
                    resultCount = candidateResult.recipes.size,
                    onShowResults = {
                        // The results heading is immediately before the recipe rows or the empty-result item.
                        val heading = (listState.layoutInfo.totalItemsCount - displayedRecipes.size.coerceAtLeast(1) - 1)
                            .coerceAtLeast(0)
                        showCompleteKitchenResults = true
                        scope.launch { listState.animateScrollToItem(heading) }
                    },
                )
            }
        }

        item {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it.take(300) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜菜名，或输入一句话让我挑菜") },
                placeholder = { Text("例：番茄炒蛋，或：家里有土豆、别放香菜") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitKeyword() }),
                trailingIcon = {
                    if (queryText.isNotEmpty() || conditions.keyword.isNotEmpty()) {
                        Row {
                            if (queryText.isNotBlank() && queryText != conditions.keyword) {
                                TextButton(onClick = ::submitKeyword) { Text("搜索") }
                            }
                            TextButton(onClick = {
                                queryText = ""
                                sessionController.setKeyword("")
                            }) { Text("清空") }
                        }
                    } else if (prefs.recentSearches.isNotEmpty()) {
                        TextButton(onClick = { showRecentSearches = true }) { Text("最近搜索") }
                    }
                },
            )
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(onClick = { showIngredientPicker = true }) {
                    val countLabel = if (conditions.selectedStuff.isEmpty()) "选食材" else "选食材 (${conditions.selectedStuff.size})"
                    Text(countLabel)
                }
                OutlinedButton(onClick = { showFilterDialog = true }) {
                    val count = conditions.activeFilterCount(prefs)
                    Text(if (count == 0) "筛选与模式" else "筛选 ($count)")
                }
                OutlinedButton(
                    enabled = !isAiSearching && queryText.isNotBlank(),
                    onClick = {
                        val query = queryText.trim()
                        val mode = prefs.aiProviderMode
                        intentError = null
                        if (mode == AiProviderMode.DISABLED) {
                            pendingIntent = query to sessionController.parseLocalIntent(snapshot, query)
                        } else {
                            pendingAiConsent = query to mode
                        }
                    },
                ) { Text(if (isAiSearching) "识别中…" else "智能识别语句") }
                if (conditions.selectedStuff.isNotEmpty() || conditions.isFilterActive(prefs) || conditions.keyword.isNotEmpty()) {
                    TextButton(onClick = {
                        queryText = ""
                        sessionController.clearSessionConditions()
                    }) {
                        Text("清空本次条件")
                    }
                }
            }
            intentError?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        val fridgeNames = fridgeDoc.stock.map { it.ingredientName }.distinct()
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
                        Text(
                            text = "冰箱里有 ${fridgeNames.size} 种食材（${fridgeNames.take(3).joinToString("、")}${if (fridgeNames.size > 3) "…" else ""}）",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                val normalized = snapshot.data.normalizer.unique(conditions.selectedStuff + fridgeNames)
                                sessionController.setSelectedStuff(normalized)
                            },
                        ) {
                            Text("一键选入")
                        }
                    }
                }
            }
        }

        if (conditions.selectedStuff.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已选食材（点击移除）", style = MaterialTheme.typography.labelMedium)
                    TagFlowChips(
                        items = conditions.selectedStuff,
                        selectedItems = conditions.selectedStuff.toSet(),
                        onToggle = { sessionController.toggleStuff(it) },
                    )
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CookDiscoveryScene.entries.forEach { scene ->
                        val enabled = scene != CookDiscoveryScene.INSTITUTIONAL_LOW_GI ||
                            snapshot.institutionalLowGICatalog?.isAvailable == true
                        if (enabled) {
                            FilterChip(
                                selected = conditions.discoveryScene == scene,
                                onClick = { sessionController.toggleDiscoveryScene(scene) },
                                label = { Text("${scene.emoji} ${scene.label}") },
                            )
                        }
                    }
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        onClick = {
                                            showCompleteKitchenResults = false
                                            sceneBatch = CookDiscovery.sample(
                                                candidateResult.recipes,
                                                count = 3,
                                                avoiding = sceneBatch,
                                            )
                                        },
                                    ) {
                                        Text("换一批")
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
                selectedGoal = prefs.rememberedDietaryGoal,
                onSelectGoal = { goal -> container.preferencesStore.selectDietaryGoal(goal) },
            )
        }

        item {
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (conditions.isFilterActive(prefs)) {
                    TextButton(
                        onClick = {
                            sessionController.clearFiltersOnly()
                        },
                    ) {
                        Text("清除筛选")
                    }
                }
            }
        }

        if (displayedRecipes.isEmpty()) {
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
                            OutlinedButton(onClick = { sessionController.clearFiltersOnly() }) {
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
            itemsIndexed(displayedRecipes, key = { _, item -> item.id }) { rank, recipe ->
                val activeGoal = prefs.rememberedDietaryGoal
                val dietaryNote = activeGoal?.let { snapshot.dietaryCatalog?.entry(recipe, it)?.reason(it) }
                val sceneReason = conditions.discoveryScene?.let { CookDiscovery.reason(recipe, it.filters) }
                val isFav = recipe.id in favoriteIds
                RecipeSummaryCard(
                    recipe = recipe,
                    match = candidateResult.matchesById[recipe.id],
                    isFavorite = isFav,
                    sceneReason = sceneReason,
                    dietaryNote = dietaryNote,
                    onToggleFavorite = {
                        scope.launch {
                            container.userLibraryRepository.toggleFavorite(recipe.id, isFav)
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
                        onOpenRecipe(recipe.id)
                    },
                )
            }
        }
    }

    if (showIngredientPicker) {
        val appliedFood = personalCatalog.toDocument().applyingTo(snapshot.data.food)
        val categories = listOf(
            "素菜" to appliedFood.vegetable.map { it.name },
            "荤菜" to appliedFood.meat.map { it.name },
            "主食" to appliedFood.staple.map { it.name },
            "常用调料" to snapshot.data.pantry.options,
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
                        val currentItems = categories[safeIdx].second
                        TagFlowChips(
                            items = currentItems,
                            selectedItems = (conditions.selectedStuff + if (safeIdx == 3) prefs.pantry else emptyList()).toSet(),
                            onToggle = { item ->
                                if (safeIdx == 3) {
                                    container.preferencesStore.togglePantryItem(item)
                                } else {
                                    sessionController.toggleStuff(item)
                                }
                            },
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
        val appliedFood = personalCatalog.toDocument().applyingTo(snapshot.data.food)
        val toolNames = appliedFood.tools.map { it.name }.distinct()
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("筛选条件与推荐模式") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("匹配模式", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            RecommendationMode.LOOSE to "宽松模式",
                            RecommendationMode.STRICT to "严格模式",
                            RecommendationMode.SURVIVAL to "应急模式",
                        ).forEach { (mode, title) ->
                            FilterChip(
                                selected = prefs.mode == mode,
                                onClick = { container.preferencesStore.setMode(mode) },
                                label = { Text(title) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("不吃辣（自动避开辣椒）", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = prefs.noSpicy,
                            onCheckedChange = { container.preferencesStore.setNoSpicy(it) },
                        )
                    }

                    Text("厨具（单选）", style = MaterialTheme.typography.labelMedium)
                    TagFlowChips(
                        items = toolNames,
                        selectedItems = conditions.tool?.let { setOf(it) } ?: emptySet(),
                        onToggle = { sessionController.toggleTool(it) },
                    )

                    Text("难度", style = MaterialTheme.typography.labelMedium)
                    TagFlowChips(
                        items = Recommender.DIFFICULTY_LEVELS,
                        selectedItems = conditions.difficulty.toSet(),
                        onToggle = { sessionController.toggleDifficulty(it) },
                    )

                    Text("做法分类", style = MaterialTheme.typography.labelMedium)
                    TagFlowChips(
                        items = Recommender.METHOD_GROUPS.map { it.label },
                        selectedItems = conditions.methodGroups.toSet(),
                        onToggle = { sessionController.toggleMethodGroup(it) },
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showFilterDialog = false }) {
                    Text("完成")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionController.clearFiltersOnly() }) {
                    Text("清除筛选")
                }
            },
        )
    }

    if (showDecideDialog) {
        DecideRandomDialog(
            recipes = candidateResult.recipes,
            matchesById = candidateResult.matchesById,
            contextSummary = buildDecideContextSummary(conditions, prefs, ignoringKeyword = false),
            onOpenRecipe = { id ->
                showDecideDialog = false
                onOpenRecipe(id)
            },
            onDismiss = { showDecideDialog = false },
        )
    }

    if (showRecentSearches) {
        AlertDialog(
            onDismissRequest = { showRecentSearches = false },
            title = { Text("最近搜索") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (prefs.recentSearches.isEmpty()) Text("暂无搜索记录")
                    prefs.recentSearches.forEach { text ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                modifier = Modifier.weight(1f),
                                onClick = { queryText = text; showRecentSearches = false },
                            ) { Text(text) }
                            TextButton(onClick = { container.preferencesStore.removeSearch(text) }) { Text("删除") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRecentSearches = false }) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = { container.preferencesStore.clearSearches() }) { Text("清空记录") }
            },
        )
    }

    pendingAiConsent?.let { (query, mode) ->
        val recipient = if (mode == AiProviderMode.BYOK_DEEPSEEK) "DeepSeek" else "云乐坊的腾讯云 CloudBase 与 DeepSeek"
        AlertDialog(
            onDismissRequest = { pendingAiConsent = null },
            title = { Text("发送本次搜索？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("以下文字将发送给$recipient。")
                    Text(query)
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingAiConsent = null
                    sendIntent(query, mode)
                }) { Text("同意并识别") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingAiConsent = null
                    pendingIntent = query to sessionController.parseLocalIntent(snapshot, query)
                }) { Text("用本机识别") }
            },
        )
    }

    pendingIntent?.let { (query, draft) ->
        SearchIntentReviewDialog(
            intent = draft,
            onConfirm = { confirmed ->
                container.preferencesStore.recordSearch(query)
                queryText = ""
                val suggestedGoal = sessionController.applySearchIntent(confirmed)
                if (suggestedGoal != null && suggestedGoal in snapshot.availableDietaryGoals) {
                    container.preferencesStore.selectDietaryGoal(suggestedGoal)
                }
                pendingIntent = null
            },
            onDismiss = { pendingIntent = null },
        )
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
