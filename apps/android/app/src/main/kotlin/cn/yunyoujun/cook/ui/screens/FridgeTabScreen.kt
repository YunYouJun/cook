package cn.yunyoujun.cook.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.data.local.BoundedIoStreams
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.FridgeCategory
import cn.yunyoujun.cook.data.local.FridgeDateKind
import cn.yunyoujun.cook.data.local.FridgeLabelParser
import cn.yunyoujun.cook.data.local.FridgePhotoFocus
import cn.yunyoujun.cook.data.local.FridgePhotoInspector
import cn.yunyoujun.cook.data.local.FridgeProduceClassifier
import cn.yunyoujun.cook.data.local.FridgeProduceMatch
import cn.yunyoujun.cook.data.local.FridgeStockEntry
import cn.yunyoujun.cook.data.local.ProduceStorageAdvice
import cn.yunyoujun.cook.data.local.estimateFridgeIntake
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.ui.state.FridgePhotoIntake
import cn.yunyoujun.cook.ui.state.FridgePhotoIntake.ModelStatus
import cn.yunyoujun.cook.ui.state.rememberLocalCalendarClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val EXPIRY_TAB = "by-expiry"
private const val CATEGORY_OVERVIEW_TAB = "by-category"
private const val CATEGORY_TAB_PREFIX = "category/"

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FridgeTabScreen(
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    photoIntake: FridgePhotoIntake,
    onCookWithIngredients: (List<String>) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenMembership: () -> Unit,
    onOpenPrivacy: () -> Unit = {},
    onChooseGalleryPhoto: () -> Unit,
) {
    val context = LocalContext.current
    val fridge = container.fridgeStore
    val doc by fridge.state.collectAsState()
    val personalCatalog by container.personalCatalogStore.state.collectAsState()
    val classificationFood = remember(snapshot, personalCatalog) {
        personalCatalog.toDocument().applyingTo(snapshot.data.food, includeHidden = true)
    }
    val clock = rememberLocalCalendarClock()
    val today = clock.toLocalDate()
    val zone = clock.zone
    var activeTab by rememberSaveable { mutableStateOf(EXPIRY_TAB) }
    var showAddItemDialog by rememberSaveable { mutableStateOf(false) }
    var showManageCategoriesDialog by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var editingBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingItemId by rememberSaveable { mutableStateOf<String?>(null) }

    fun automaticCategory(name: String): String {
        val id = FridgeCategory.automaticId(snapshot.data.normalizer.normalize(name), classificationFood)
        return if (doc.categories.any { it.id == id }) id else FridgeBatchItem.DEFAULT_CATEGORY_ID
    }

    val categoriesById = remember(doc.categories) { doc.categories.associateBy { it.id } }
    val tabs = listOf(EXPIRY_TAB to "按临期", CATEGORY_OVERVIEW_TAB to "按分类") +
        doc.categories.map { "$CATEGORY_TAB_PREFIX${it.id}" to it.name }
    val safeIndex = tabs.indexOfFirst { it.first == activeTab }.coerceAtLeast(0)
    val displayedTab = tabs[safeIndex].first
    val categoryId = displayedTab.takeIf { it.startsWith(CATEGORY_TAB_PREFIX) }?.removePrefix(CATEGORY_TAB_PREFIX)
    val stock = remember(doc) { doc.stock }
    val rows = remember(doc, today) { doc.stockOn(today) }
    val priority = rows.filter { it.needsAttention }
    val priorityNames = priority.map { it.batch.ingredientName }.distinct()
    val usableNames = rows.filter { it.usableForCooking }.map { it.batch.ingredientName }.distinct()
    val otherCategoryId = FridgeBatchItem.DEFAULT_CATEGORY_ID
    fun stockCategoryId(row: FridgeStockEntry): String =
        row.batch.categoryId.takeIf(categoriesById::containsKey) ?: otherCategoryId

    val filteredRows = if (categoryId == null) rows else rows.filter { stockCategoryId(it) == categoryId }
    val sections = when (displayedTab) {
        EXPIRY_TAB -> {
            val byFreshness = rows.groupBy { it.group }
            FridgeStockGroup.entries.mapNotNull { group ->
                byFreshness[group]?.let { entries -> FridgeStockSection(group.name, entries, group.title, group) }
            }
        }
        CATEGORY_OVERVIEW_TAB -> {
            val byCategory = rows.groupBy(::stockCategoryId)
            val orderedIds = doc.categories.map { it.id }.filterNot { it == otherCategoryId } + otherCategoryId
            orderedIds.mapNotNull { id ->
                byCategory[id]?.let { entries ->
                    FridgeStockSection("$CATEGORY_TAB_PREFIX$id", entries, categoriesById[id]?.name ?: "其他")
                }
            }
        }
        else -> listOf(FridgeStockSection(displayedTab, filteredRows))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("我的冰箱", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = if (priority.isNotEmpty()) "有 ${priority.size} 份食材需优先食用" else "共记录 ${stock.size} 份批次库存",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (priority.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { showAddItemDialog = true }) { Text("入库") }
                    OutlinedButton(onClick = photoIntake::open) { Text("拍照识别") }
                    OutlinedButton(onClick = { showManageCategoriesDialog = true }) { Text("分类") }
                    TextButton(onClick = { showHistory = true }) { Text("冰箱历史") }
                }
            }
        }

        item { FridgeReminderControls(container.preferencesStore) }

        if (stock.isNotEmpty()) {
            item {
                Button(
                    onClick = { onCookWithIngredients(usableNames) },
                    enabled = usableNames.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (usableNames.isEmpty()) "先核对冰箱里的食材" else "用冰箱里的 ${usableNames.size} 种食材选菜")
                }
            }
        }

        item {
            SecondaryScrollableTabRow(selectedTabIndex = safeIndex, edgePadding = 0.dp) {
                tabs.forEachIndexed { idx, (id, title) ->
                    Tab(
                        selected = idx == safeIndex,
                        onClick = { activeTab = id },
                        text = { Text(title) },
                    )
                }
            }
        }

        if (filteredRows.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (stock.isEmpty()) "冰箱里还没有食材" else "该分类暂无库存",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (stock.isEmpty()) {
                            Text(
                                "点「入库」记下买回来的食材和日期。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        for (section in sections) {
            section.title?.let { title ->
                item(key = "stock-section-${section.key}") {
                    FridgeStockSectionHeading(title, section.freshness) { onCookWithIngredients(priorityNames) }
                }
            }
            items(section.entries, key = { "stock-batch-${it.batch.id}" }) { row ->
                val batch = row.batch
                FridgeStockCard(
                    row = row,
                    categoryName = categoriesById[stockCategoryId(row)]?.name ?: "其他",
                    onCook = { onCookWithIngredients(listOf(batch.ingredientName)) },
                    onEdit = { editingBatchId = batch.id },
                    onUseUp = {
                        runCatching { container.fridgeStore.markConsumed(batch.id) }
                            .onFailure { historyError = "食材状态未保存，请重试。" }
                    },
                    onDelete = { removingItemId = batch.id },
                )
            }
        }
    }

    stock.firstOrNull { it.id == removingItemId }?.let { batch ->
        AlertDialog(
            onDismissRequest = { removingItemId = null },
            title = { Text("删除「${batch.ingredientName}」？") },
            text = { Text("删除后，这份食材将无法从冰箱历史恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { container.fridgeStore.removeItem(batch.id) }
                        .onFailure { historyError = "食材未删除，请重试。" }
                    removingItemId = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { removingItemId = null }) { Text("取消") } },
        )
    }

    stock.firstOrNull { it.id == editingBatchId }?.let { batch ->
        key(batch.id) {
            FridgeBatchEditor(
                batch = batch,
                categories = doc.categories,
                automaticId = automaticCategory(batch.ingredientName),
                addedDate = container.fridgeStore.formatLocalDate(batch.addedAtMs, zone),
                today = today,
                onCreateCategory = fridge::addCategory,
                onDismiss = { editingBatchId = null },
                onSave = { quantity, category, expiry, kind ->
                    container.fridgeStore.updateItem(batch.id, quantity, category, expiry, kind)
                    editingBatchId = null
                },
            )
        }
    }

    if (showHistory) {
        val history = remember(doc) { doc.consumed }
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("冰箱历史") },
            text = {
                if (history.isEmpty()) {
                    Text("还没有吃完的食材。")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(history, key = { it.id }) { batch ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(batch.ingredientName, style = MaterialTheme.typography.titleSmall)
                                    batch.consumedAtMs?.let {
                                        Text("用完于 ${container.fridgeStore.formatLocalDate(it, zone)}")
                                    }
                                }
                                TextButton(onClick = {
                                    runCatching { container.fridgeStore.restoreItem(batch.id) }
                                        .onFailure { historyError = "食材未恢复，请重试。" }
                                }) { Text("恢复") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } },
        )
    }

    historyError?.let { message ->
        AlertDialog(
            onDismissRequest = { historyError = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { historyError = null }) { Text("知道了") } },
        )
    }

    if (showAddItemDialog) {
        ManualFridgeIntakeDialog(
            snapshot = snapshot,
            personalCatalog = personalCatalog,
            categories = doc.categories,
            today = today,
            automaticCategory = ::automaticCategory,
            onCreateCategory = fridge::addCategory,
            onDismiss = { showAddItemDialog = false },
            onSave = { additions ->
                container.fridgeStore.addItems(additions)
                showAddItemDialog = false
            },
        )
    }

    photoIntake.draft?.let { draft ->
        var labelText by draft::labelText
        var produceMatch by draft::produceMatch
        var ingredientNameOverride by draft::ingredientNameOverride
        var dates by draft::dates
        var selectedCategoryId by draft::selectedCategoryId
        var photoInfo by draft::photoInfo
        var photoError by draft::photoError
        val saving = draft.saving
        val working = draft.working || saving
        val scroll = rememberScrollState()
        val gallerySelection = draft.gallerySelection
        val waitingForPhoto = draft.waitingForPhoto
        var creatingCategory by rememberSaveable(draft) { mutableStateOf(false) }
        var confirmUsingRecognition by rememberSaveable(draft) { mutableStateOf(false) }
        var showCamera by rememberSaveable { mutableStateOf(false) }
        var showPhotoFocus by rememberSaveable { mutableStateOf(false) }
        var cameraPermissionDenied by rememberSaveable { mutableStateOf(false) }
        val batchReview = draft.batchReview
        val visionService = container.fridgeVisionService
        val modelStore = container.fridgeProduceModelStore
        val modelStatus = draft.modelStatus
        val resolver = context.applicationContext.contentResolver
        LaunchedEffect(draft, modelStore) {
            photoIntake.checkProduceModel(draft, modelStore)
        }
        val normalizer = snapshot.data.normalizer
        val ingredientVocabulary = remember(classificationFood, normalizer) {
            FridgeLabelParser.ingredientVocabulary(classificationFood, normalizer)
        }

        fun packagingNames(text: String): List<String> =
            normalizer.normalize(FridgeLabelParser.matchKnownNames(text, ingredientVocabulary)).distinct()

        fun readPhoto(focus: FridgePhotoFocus? = null, read: suspend () -> FridgePhotoIntake.Photo) {
            photoIntake.processPhoto(draft, readingPhoto = true) {
                photoError = null
                try {
                    val source = read()
                    val model = (draft.modelStatus as? ModelStatus.Ready)?.file
                    val result = FridgePhotoInspector.recognizeInMemoryBytes(
                        source.bytes, ingredientVocabulary, source.captureRotation, focus,
                    ) { bitmap, rotation ->
                        model?.let { container.fridgeProduceClassifier.classify(bitmap, rotation, it) } ?: FridgeProduceMatch()
                    }
                    val samePhoto = draft.photo === source
                    if (!samePhoto) draft.clearPhoto()
                    draft.photo = source
                    draft.recognitionFocus = focus
                    draft.setRecognizedLabel(result.recognizedText, keepEdits = samePhoto)
                    val recognizedNames = packagingNames(result.recognizedText)
                    draft.waitingForModel = model == null && recognizedNames.isEmpty()
                    produceMatch = result.produce
                    val imageNames = FridgeLabelParser.imageNames(result.produce.classifications)
                    val names = packagingNames(labelText).ifEmpty { imageNames }
                    draft.reviewing = draft.reviewing || !FridgeLabelParser.parse(labelText, names, today).suggestsBatchReview
                    photoInfo = when {
                        imageNames.isNotEmpty() -> "已在本机识别蔬果，请核对食材名称。"
                        result.recognizedText.isNotBlank() -> "已识别包装文字，请核对食材和日期。"
                        else -> null
                    }
                    if (result.modelUnavailable) photoIntake.recheckModelAfterInferenceFailure(draft, modelStore)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BoundedIoStreams.TooLargeException) {
                    photoError = "照片超过 30 MB，请换一张较小的图片。"
                } catch (_: Exception) {
                    photoError = "本机无法识别这张照片，可以换图或手动录入。"
                }
            }
        }

        fun openBatchReview() {
            val service = visionService ?: return
            val source = draft.photo ?: return
            photoIntake.processPhoto(draft) {
                photoError = null
                try {
                    val jpeg = service.prepare(source.bytes, source.captureRotation)
                    photoIntake.startBatchReview(draft, jpeg, service, container.yunlefunSessionManager) {
                        normalizer.normalize(it.trim())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    photoError = "无法准备这张照片，请换一张。"
                }
            }
        }

        LaunchedEffect(draft, working, gallerySelection) {
            if (working || gallerySelection == null) return@LaunchedEffect
            draft.gallerySelection = null
            readPhoto {
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(gallerySelection)?.use { input ->
                        BoundedIoStreams.readBytesBounded(input, FridgePhotoInspector.MAX_PHOTO_BYTES)
                    } ?: throw FridgePhotoInspector.UnreadableImageException()
                }
                FridgePhotoIntake.Photo(bytes)
            }
        }

        val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraPermissionDenied = !granted
            if (granted) showCamera = true
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraPermissionDenied = false
            }
        }

        val labelNames = remember(labelText, ingredientVocabulary, normalizer) {
            packagingNames(labelText)
        }
        val source = draft.photo
        val focus = draft.recognitionFocus
        val waitingForModel = draft.waitingForModel
        LaunchedEffect(draft, modelStatus, working, waitingForModel, source, focus, batchReview, waitingForPhoto, showCamera, showPhotoFocus) {
            val model = (modelStatus as? ModelStatus.Ready)?.file ?: return@LaunchedEffect
            if (source == null || working || !waitingForModel || batchReview != null ||
                waitingForPhoto || showCamera || showPhotoFocus
            ) return@LaunchedEffect
            draft.waitingForModel = false
            if (labelNames.isNotEmpty()) return@LaunchedEffect
            photoIntake.processPhoto(draft) {
                try {
                    produceMatch = FridgePhotoInspector.classifyInMemoryBytes(
                        source.bytes, source.captureRotation, focus,
                    ) { bitmap, rotation ->
                        container.fridgeProduceClassifier.classify(bitmap, rotation, model)
                    }
                    val imageNames = FridgeLabelParser.imageNames(produceMatch.classifications)
                    if (imageNames.isNotEmpty()) {
                        photoInfo = "已在本机识别蔬果，请核对食材名称。"
                        if (!FridgeLabelParser.parse(labelText, imageNames, today).suggestsBatchReview) {
                            draft.reviewing = true
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: FridgeProduceClassifier.ModelUnavailable) {
                    photoIntake.recheckModelAfterInferenceFailure(draft, modelStore)
                } catch (_: Exception) {
                    photoError = "本机无法识别这张照片，可以换图或手动录入。"
                }
            }
        }
        val matchedNames = remember(labelNames, produceMatch) {
            labelNames.ifEmpty { FridgeLabelParser.imageNames(produceMatch.classifications) }
        }
        val suggestedNames = if (matchedNames.isEmpty()) {
            FridgeLabelParser.suggestedProduceNames(produceMatch.suggestions)
        } else emptyList()
        val hasRecognition = draft.recognizedLabelText.isNotBlank() ||
            produceMatch.classifications.isNotEmpty() || produceMatch.suggestions.isNotEmpty()
        val scan = remember(labelText, matchedNames, today) {
            FridgeLabelParser.parse(labelText, names = matchedNames, today = today)
        }
        val nameInput = ingredientNameOverride ?: matchedNames.singleOrNull().orEmpty()
        val effectiveName = normalizer.normalize(nameInput.trim())
        val automaticId = automaticCategory(effectiveName)
        val review = dates.review(scan, effectiveName, today, snapshot.data.shelfLife)
        val isProduce = effectiveName in ProduceStorageAdvice.PRODUCE_NAMES ||
            classificationFood.vegetable.any { it.name == effectiveName }

        fun editIngredientName(name: String) {
            if (dates.cut && effectiveName != normalizer.normalize(name.trim())) {
                dates = dates.copy(cut = false, startDay = null)
            }
            ingredientNameOverride = name
        }

        val reviewing = draft.reviewing || (source != null && visionService == null)
        val offerRecordingChoice = source != null && !reviewing
        if (showCamera) {
            FridgeCameraDialog(
                capturedPhoto = draft.capturedPhoto,
                onCapture = { draft.capturedPhoto = it },
                onRetake = { draft.capturedPhoto = null },
                onUsePhoto = { photo ->
                    draft.capturedPhoto = null
                    showCamera = false
                    readPhoto { photo }
                },
                onDismiss = {
                    draft.capturedPhoto = null
                    showCamera = false
                },
            )
        } else if (showPhotoFocus && source != null) {
            FridgePhotoFocusDialog(
                photo = source,
                initialFocus = focus,
                onRecognize = { selection ->
                    showPhotoFocus = false
                    readPhoto(selection) { source }
                },
                onDismiss = { showPhotoFocus = false },
            )
        } else if (batchReview != null) {
            FridgeVisionDialog(
                review = batchReview,
                onBack = { photoIntake.endBatchReview(draft, replacePhoto = false) },
                onReplacePhoto = { photoIntake.endBatchReview(draft, replacePhoto = true) },
                onOpenAccount = onOpenAccount,
                onOpenMembership = onOpenMembership,
                onOpenPrivacy = onOpenPrivacy,
                writeItems = { names ->
                    val today = LocalDate.now()
                    val additions = names.map { name ->
                        val estimate = estimateFridgeIntake(name, snapshot.data.shelfLife)
                        FridgeBatchItem(
                            ingredientName = name,
                            categoryId = FridgeCategory.automaticId(name, classificationFood),
                            quantityText = "1 份",
                            expiresOnIsoDate = estimate.dateFrom(today).toString(),
                            dateKind = estimate.kind,
                        )
                    }
                    container.fridgeStore.addItems(additions)
                },
                onSaved = photoIntake::close,
            )
        } else AlertDialog(
            onDismissRequest = { if (!draft.saving) photoIntake.close() },
            title = { Text(if (offerRecordingChoice && !waitingForPhoto) "选择录入方式" else "拍照识别") },
            text = {
                if (saving) {
                    Text("正在保存食材…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    return@AlertDialog
                }
                if (waitingForPhoto) {
                    Text(
                        if (draft.choosingGalleryPhoto) "正在等待所选照片…" else "正在读取并识别照片…",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    return@AlertDialog
                }
                Column(
                    modifier = Modifier.verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(enabled = !working, onClick = {
                            photoIntake.chooseGalleryPhoto(draft, onChooseGalleryPhoto)
                        }) { Text("选相册图片") }
                        OutlinedButton(enabled = !working, onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                cameraPermissionDenied = false
                                showCamera = true
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        }) { Text("拍照") }
                        if (source != null) {
                            TextButton(enabled = !working, onClick = { showPhotoFocus = true }) { Text("圈选照片区域") }
                        }
                        if (source != null && visionService != null && !offerRecordingChoice) {
                            TextButton(enabled = !working, onClick = ::openBatchReview) { Text("批量识图") }
                        }
                    }
                    if (focus != null) {
                        val batchHint = if (visionService != null) "批量识图使用整张原照片。" else ""
                        Text("本机识别使用所选区域。$batchHint", style = MaterialTheme.typography.bodySmall)
                    }
                    if (cameraPermissionDenied) {
                        Text("相机权限未开启。可以选择相册图片或手动入库。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            runCatching { context.startActivity(settings) }
                                .onFailure { photoError = "无法打开应用设置，请在系统设置中开启相机权限。" }
                        }) { Text("打开相机权限设置") }
                    }
                    if (working) Text("正在处理照片…")
                    photoInfo?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    photoError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (offerRecordingChoice) {
                        if (suggestedNames.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                suggestedNames.forEach { name ->
                                    OutlinedButton(enabled = !working, onClick = {
                                        editIngredientName(name)
                                        draft.reviewing = true
                                    }) { Text("可能是 $name") }
                                }
                            }
                        }
                        Text("本机还没确认单份食材。照片里有多种食材时，可以用同一张照片继续批量识图。")
                        OutlinedButton(enabled = !working, onClick = { draft.reviewing = true }) { Text("按一份食材录入") }
                    } else if (!reviewing && !working) {
                        val batchHint = if (visionService != null) "需要识别多种食材时，可用同一张照片继续批量识图。" else ""
                        Text("先在本机识别食材和日期。$batchHint", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { draft.reviewing = true }) { Text("手动录入包装或食材") }
                    } else if (reviewing) {
                        OutlinedTextField(
                            value = labelText,
                            onValueChange = { labelText = it },
                            label = { Text("包装标签文字（支持生产日期、保质期、到期日）") },
                            placeholder = { Text("例：日本豆腐 生产日期 2026-11-10 保质期 15天") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (draft.recognizedLabelText.isNotBlank()) {
                            TextButton(
                                onClick = { draft.showingRecognizedLabel = !draft.showingRecognizedLabel },
                                modifier = Modifier.semantics {
                                    stateDescription = if (draft.showingRecognizedLabel) "已展开" else "已收起"
                                },
                            ) { Text("核对识别原文") }
                            if (draft.showingRecognizedLabel) {
                                SelectionContainer {
                                    Text(draft.recognizedLabelText, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (source != null && hasRecognition && draft.hasReviewEdits) {
                            TextButton(
                                enabled = !working,
                                onClick = { confirmUsingRecognition = true },
                            ) { Text("使用本次识别结果") }
                        }
                        if (scan.names.isNotEmpty()) {
                            Text("选用识别出的食材，或填写名称", style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                scan.names.forEach { candidate ->
                                    FilterChip(
                                        selected = effectiveName == candidate,
                                        onClick = { editIngredientName(candidate) },
                                        label = { Text(candidate) },
                                    )
                                }
                            }
                        }
                        if (suggestedNames.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                suggestedNames.forEach { name ->
                                    FilterChip(
                                        selected = effectiveName == name,
                                        enabled = !working,
                                        onClick = { editIngredientName(name) },
                                        label = { Text("可能是 $name") },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = ::editIngredientName,
                            label = { Text("核对食材名称") },
                            placeholder = { Text("例：蓝莓、日本豆腐、牛奶") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (effectiveName.isNotBlank()) {
                            FridgeCategoryChoices(
                                doc.categories, selectedCategoryId, automaticId,
                                onCreate = { creatingCategory = true },
                                canCreate = !working,
                                onSelect = { selectedCategoryId = it },
                            )
                        }
                        FridgePhotoDateControls(scan, review, dates, effectiveName, isProduce, today) { dates = it }
                        Text("请按包装要求核对日期和储存条件。食用计划仅用于安排食用。", style = MaterialTheme.typography.bodySmall)
                        FridgeReminderControls(container.preferencesStore)
                    }
                    when (modelStatus) {
                        ModelStatus.Checking -> Unit
                        is ModelStatus.Ready -> if (source == null && !draft.reviewing) {
                            Text("可在本机离线识别蔬果。", style = MaterialTheme.typography.bodySmall)
                        }
                        is ModelStatus.Downloading -> {
                            val received by modelStatus.receivedBytes.collectAsState()
                            Text("正在下载蔬果模型…", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(
                                progress = { (received.toDouble() / modelStore.model.bytes).toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (waitingForModel) Text("下载完成后会继续识别这张照片。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { photoIntake.pauseProduceModel(draft) }) { Text("暂停下载") }
                        }
                        is ModelStatus.RuntimeUnavailable -> {
                            Text("本机暂时无法运行蔬果识别，可以继续识别包装文字或手动录入。", style = MaterialTheme.typography.bodySmall)
                            if (source != null && labelNames.isEmpty()) {
                                OutlinedButton(enabled = !working, onClick = { photoIntake.retryProduceRecognition(draft) }) {
                                    Text("重试蔬果识别")
                                }
                            }
                        }
                        ModelStatus.Missing, ModelStatus.Paused, ModelStatus.Failed, ModelStatus.NeedsRepair -> {
                            val sizeMiB = (modelStore.model.bytes + 524_288) / 1_048_576
                            Text("下载约 $sizeMiB MiB 的蔬果模型后即可离线识别。", style = MaterialTheme.typography.bodySmall)
                            if (waitingForModel) Text("下载完成后会继续识别这张照片。", style = MaterialTheme.typography.bodySmall)
                            if (modelStatus == ModelStatus.Failed) {
                                Text("模型下载失败。可以重试，也可以继续识别包装文字或手动录入。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (modelStatus == ModelStatus.NeedsRepair) {
                                Text("本机蔬果模型校验失败，可以重新下载后识别这张照片。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(enabled = !working, onClick = { photoIntake.downloadProduceModel(draft, modelStore) }) {
                                Text(when (modelStatus) {
                                    ModelStatus.Paused -> "继续下载"
                                    ModelStatus.Failed -> "重试下载"
                                    ModelStatus.NeedsRepair -> "重新下载模型"
                                    else -> "下载蔬果模型"
                                })
                            }
                        }
                    }
                    if (source == null && !draft.reviewing && !working) {
                        Text("蔬果记录示例", style = MaterialTheme.typography.titleSmall)
                        FridgeProduceExample("蓝莓")
                        FridgeProduceExample("西瓜")
                    }
                }
            },
            confirmButton = {
                if (saving || waitingForPhoto) return@AlertDialog
                if (offerRecordingChoice) {
                    Button(enabled = !working, onClick = ::openBatchReview) { Text("批量识图（会员）") }
                } else if (reviewing) {
                    Button(
                        enabled = !working && effectiveName.length in 1..50 && review.expiry != null && review.problem == null,
                        onClick = {
                            val expiry = review.expiry ?: return@Button
                            val kind = review.kind ?: return@Button
                            val categoryId = selectedCategoryId?.takeIf(categoriesById::containsKey) ?: automaticId
                            val quantity = if (dates.cut) "已切开" else ""
                            photoIntake.saveSingleItem(draft) {
                                container.fridgeStore.addItem(
                                    ingredientName = effectiveName,
                                    categoryId = categoryId,
                                    quantityText = quantity,
                                    shelfLifeDays = review.estimate.days,
                                    explicitIsoDate = expiry.toString(),
                                    dateKind = kind,
                                    productionIsoDate = review.production?.toString(),
                                    storageStart = review.storageStart,
                                )
                            }
                        },
                    ) { Text("确认入库") }
                }
            },
            dismissButton = {
                if (!saving) TextButton(onClick = photoIntake::close) { Text("取消") }
            },
        )
        if (creatingCategory) {
            FridgeCreateCategoryDialog(
                categories = doc.categories,
                onCreate = fridge::addCategory,
                onCreated = {
                    selectedCategoryId = it.id
                    creatingCategory = false
                },
                onDismiss = { creatingCategory = false },
            )
        }
        if (confirmUsingRecognition) {
            AlertDialog(
                onDismissRequest = { confirmUsingRecognition = false },
                title = { Text("使用本次识别结果？") },
                text = { Text("将用本次识别结果重新填写食材名称和包装文字，并重新计算日期与分类。") },
                confirmButton = {
                    TextButton(
                        enabled = !working,
                        onClick = {
                            draft.resetReviewEdits()
                            confirmUsingRecognition = false
                        },
                    ) { Text("使用识别结果") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmUsingRecognition = false }) { Text("保留修改") }
                },
            )
        }
    }

    if (showManageCategoriesDialog) {
        var newCatName by rememberSaveable { mutableStateOf("") }
        var editingCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
        var removingCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
        var categoryError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showManageCategoriesDialog = false },
            title = { Text("管理冰箱分类") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    doc.categories.forEach { cat ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(cat.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (cat.id != FridgeBatchItem.DEFAULT_CATEGORY_ID) {
                                TextButton(onClick = {
                                    editingCategoryId = cat.id
                                    newCatName = cat.name
                                    categoryError = null
                                }) { Text("改名") }
                                TextButton(
                                    onClick = { removingCategoryId = cat.id },
                                    modifier = Modifier.semantics { contentDescription = "删除${cat.name}分类" },
                                ) {
                                    Text("删除分类")
                                }
                            } else {
                                Text("默认兜底", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newCatName,
                            onValueChange = { newCatName = it },
                            label = { Text(if (editingCategoryId == null) "新分类名" else "分类名称") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = newCatName.trim().isNotEmpty(),
                            onClick = {
                                runCatching {
                                    val id = editingCategoryId
                                    if (id == null) container.fridgeStore.addCategory(newCatName)
                                    else container.fridgeStore.renameCategory(id, newCatName)
                                }.onSuccess {
                                    editingCategoryId = null
                                    newCatName = ""
                                    categoryError = null
                                }.onFailure { categoryError = it.message ?: "分类未保存，请重试。" }
                            },
                        ) { Text(if (editingCategoryId == null) "添加" else "保存") }
                    }
                    categoryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(
                        "删除分类会按食材重新归类，库存和历史会保留。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showManageCategoriesDialog = false }) { Text("完成") }
            },
        )
        categoriesById[removingCategoryId]?.let { category ->
            AlertDialog(
                onDismissRequest = { removingCategoryId = null },
                title = { Text("删除分类「${category.name}」？") },
                text = { Text("库存和历史中的食材会保留，并按食材自动归类。") },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { fridge.deleteCategory(category.id, ::automaticCategory) }
                            .onSuccess {
                                if (editingCategoryId == category.id) {
                                    editingCategoryId = null
                                    newCatName = ""
                                }
                                categoryError = null
                            }
                            .onFailure { categoryError = "分类未删除，请重试。" }
                        removingCategoryId = null
                    }) { Text("确认删除分类") }
                },
                dismissButton = { TextButton(onClick = { removingCategoryId = null }) { Text("取消") } },
            )
        }
    }
}

private enum class FridgeStockGroup(val title: String) {
    PRIORITY("优先食用"),
    LATER("稍后食用"),
    UNDATED("日期未设置"),
    PAST("已过记录日期"),
}

private data class FridgeStockSection(
    val key: String,
    val entries: List<FridgeStockEntry>,
    val title: String? = null,
    val freshness: FridgeStockGroup? = null,
)

private val FridgeStockEntry.group: FridgeStockGroup
    get() = when {
        remaining == null -> FridgeStockGroup.UNDATED
        !usableForCooking -> FridgeStockGroup.PAST
        needsAttention -> FridgeStockGroup.PRIORITY
        else -> FridgeStockGroup.LATER
    }

@Composable
private fun FridgeStockSectionHeading(title: String, group: FridgeStockGroup?, onCookPriority: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (group == FridgeStockGroup.PRIORITY) {
                TextButton(onClick = onCookPriority) { Text("只用这几样做菜") }
            }
        }
        if (group == FridgeStockGroup.PAST) {
            Text(
                "请核对包装及储存情况。外观和气味无法保证食用安全。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FridgeStockCard(
    row: FridgeStockEntry,
    categoryName: String,
    onCook: () -> Unit,
    onEdit: () -> Unit,
    onUseUp: () -> Unit,
    onDelete: () -> Unit,
) {
    val batch = row.batch
    val urgency = fridgeFreshnessLabel(row.remaining, batch.dateKind.isSuggested)
    val urgencyColor = when (row.group) {
        FridgeStockGroup.PRIORITY -> MaterialTheme.colorScheme.tertiary
        FridgeStockGroup.PAST -> MaterialTheme.colorScheme.error
        FridgeStockGroup.LATER, FridgeStockGroup.UNDATED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(batch.ingredientName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (batch.quantityText.isNotEmpty()) {
                    Text(batch.quantityText, style = MaterialTheme.typography.bodySmall)
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(categoryName, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Text(
                text = batch.expiresOnIsoDate?.let { "$urgency · $it（${batch.dateKind.label}）" } ?: urgency,
                style = MaterialTheme.typography.bodySmall,
                color = urgencyColor,
            )
            FlowRow {
                TextButton(onClick = onCook, enabled = row.usableForCooking) { Text("做菜") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onUseUp) { Text("用完") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun fridgeFreshnessLabel(days: Long?, suggested: Boolean): String {
    if (days == null) return "未设置日期"
    return if (suggested) {
        when {
            days < 0 -> "已超建议 ${-days} 天"
            days == 0L -> "建议今天吃完"
            days == 1L -> "建议明天吃完"
            days == 2L -> "建议后天吃完"
            else -> "建议 $days 天内吃完"
        }
    } else {
        when {
            days < 0 -> "过期 ${-days} 天"
            days == 0L -> "今天到期"
            days == 1L -> "明天到期"
            days == 2L -> "后天到期"
            else -> "还剩 $days 天"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FridgeBatchEditor(
    batch: FridgeBatchItem,
    categories: List<FridgeCategory>,
    automaticId: String,
    addedDate: String,
    today: LocalDate,
    onCreateCategory: (String) -> FridgeCategory,
    onDismiss: () -> Unit,
    onSave: (String, String, LocalDate?, FridgeDateKind) -> Unit,
) {
    val originalExpiry = remember(batch.expiresOnIsoDate) {
        batch.expiresOnIsoDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
    var quantity by rememberSaveable { mutableStateOf(batch.quantityText) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(batch.categoryId) }
    var creatingCategory by rememberSaveable { mutableStateOf(false) }
    var expiryText by rememberSaveable { mutableStateOf(originalExpiry?.toString()) }
    var kind by rememberSaveable { mutableStateOf(batch.dateKind) }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val expiry = expiryText?.let(LocalDate::parse)
    val changedDate = expiry != originalExpiry
    val shownKind = kind.afterDateEdit(changedDate)

    if (choosingDate) {
        FridgeDatePicker(
            title = if (shownKind.isSuggested) "建议吃完日期" else "到期日",
            initial = expiry ?: today,
            onDismiss = { choosingDate = false },
            onSelect = { day ->
                expiryText = day.toString()
                choosingDate = false
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("编辑 ${batch.ingredientName}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("放入日期 $addedDate", style = MaterialTheme.typography.bodySmall)
                    batch.productionIsoDate?.let { Text("生产日期 $it", style = MaterialTheme.typography.bodySmall) }
                    batch.storageStart?.let { Text("${it.label} ${it.isoDate}", style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("数量说明") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FridgeCategoryChoices(
                        categories, categoryId, automaticId,
                        onCreate = { creatingCategory = true },
                        onSelect = { categoryId = it },
                    )
                    val dateLabel = if (shownKind.isSuggested) "建议吃完日期" else "到期日"
                    OutlinedButton(onClick = { choosingDate = true }) {
                        Text("$dateLabel ${expiryText ?: "未设置"}")
                    }
                    if (expiry != null) {
                        Text(fridgeFreshnessLabel(ChronoUnit.DAYS.between(today, expiry), shownKind.isSuggested))
                        Text("日期依据", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FridgeDateKind.entries.filter { it.afterDateEdit(changedDate) == it }.forEach { option ->
                                FilterChip(
                                    selected = shownKind == option,
                                    onClick = { kind = option },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                    }
                    if (error) Text("食材未保存，请重试。", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val selectedId = categoryId?.takeIf { id -> categories.any { it.id == id } } ?: automaticId
                    runCatching { onSave(quantity, selectedId, expiry, kind) }
                        .onFailure { error = true }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }
    if (creatingCategory) {
        FridgeCreateCategoryDialog(
            categories = categories,
            onCreate = onCreateCategory,
            onCreated = {
                categoryId = it.id
                creatingCategory = false
            },
            onDismiss = { creatingCategory = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FridgeCategoryChoices(
    categories: List<FridgeCategory>,
    selectedId: String?,
    automaticId: String?,
    onCreate: () -> Unit,
    canCreate: Boolean = true,
    onSelect: (String?) -> Unit,
) {
    Text("分类", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val suggestedName = categories.firstOrNull { it.id == automaticId }?.name
        FilterChip(
            selected = categories.none { it.id == selectedId },
            onClick = { onSelect(null) },
            label = { Text(suggestedName?.let { "自动归类（$it）" } ?: "自动归类") },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text(category.name) },
            )
        }
        TextButton(enabled = canCreate, onClick = onCreate) { Text("新建分类") }
    }
}

@Composable
internal fun FridgeCreateCategoryDialog(
    categories: List<FridgeCategory>,
    onCreate: (String) -> FridgeCategory,
    onCreated: (FridgeCategory) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    val nameError = FridgeCategory.nameError(name, categories)
    val visibleError = if (name.isBlank()) null else nameError ?: saveError
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分类") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    saveError = null
                },
                label = { Text("分类名称") },
                singleLine = true,
                isError = visibleError != null,
                supportingText = visibleError?.let { message -> { Text(message) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = nameError == null, onClick = {
                runCatching { onCreate(name) }
                    .onSuccess(onCreated)
                    .onFailure { saveError = "分类未保存，请重试。" }
            }) { Text("保存分类") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
