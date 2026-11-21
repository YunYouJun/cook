package cn.yunyoujun.cook.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.data.local.BoundedIoStreams
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.FridgeDateKind
import cn.yunyoujun.cook.data.local.FridgeLabelParser
import cn.yunyoujun.cook.data.local.FridgePhotoInspector
import cn.yunyoujun.cook.data.local.ProduceStorageAdvice
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.platform.FridgeDailyReminderScheduler
import cn.yunyoujun.cook.platform.KitchenNotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FridgeTabScreen(
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onCookWithIngredients: (List<String>) -> Unit,
    onOpenAccount: () -> Unit = {},
) {
    val context = LocalContext.current
    val doc by container.fridgeStore.state.collectAsState()
    var selectedCategoryIndex by rememberSaveable { mutableIntStateOf(0) }
    var showAddItemDialog by rememberSaveable { mutableStateOf(false) }
    var showPhotoIntakeDialog by rememberSaveable { mutableStateOf(false) }
    var showManageCategoriesDialog by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var editingDateId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationDeniedNotice by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationDeniedNotice = !granted
        if (granted) {
            FridgeDailyReminderScheduler.checkAndNotifyIfNeeded(context)
        }
    }

    fun ensureNotificationPermissionOrCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !KitchenNotificationHelper.hasNotificationPermission(context)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationDeniedNotice = false
            FridgeDailyReminderScheduler.checkAndNotifyIfNeeded(context)
        }
    }

    val allTabCategories = listOf(null to "全部") + doc.categories.map { it.id to it.name }
    val safeIndex = selectedCategoryIndex.coerceIn(0, (allTabCategories.size - 1).coerceAtLeast(0))
    val activeCategoryId = allTabCategories[safeIndex].first
    val stock = remember(doc) { doc.stock }
    val filteredItems = stock.filter { activeCategoryId == null || it.categoryId == activeCategoryId }
        .sortedBy { it.daysRemaining() ?: Int.MAX_VALUE }
    val stockNames = stock.map { it.ingredientName }.distinct()
    val dueSoonCount = container.fridgeStore.dueSoonCount()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("我的冰箱", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = if (dueSoonCount > 0) "有 $dueSoonCount 份食材临近保鲜期" else "共记录 ${stock.size} 份批次库存",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dueSoonCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { showAddItemDialog = true }) { Text("入库") }
                    OutlinedButton(onClick = { showPhotoIntakeDialog = true }) { Text("拍照识别") }
                    OutlinedButton(onClick = { showManageCategoriesDialog = true }) { Text("分类") }
                    TextButton(onClick = { showHistory = true }) { Text("冰箱历史") }
                }
            }
        }

        if (notificationDeniedNotice || !KitchenNotificationHelper.hasNotificationPermission(context)) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "尚未开启通知权限，临期食材的后台系统提醒不会弹出。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            TextButton(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text("去授权")
                            }
                        }
                    }
                }
            }
        }

        if (stockNames.isNotEmpty()) {
            item {
                Button(
                    onClick = { onCookWithIngredients(stockNames) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("用冰箱里的 ${stockNames.size} 种食材选菜")
                }
            }
        }

        item {
            SecondaryScrollableTabRow(selectedTabIndex = safeIndex, edgePadding = 0.dp) {
                allTabCategories.forEachIndexed { idx, (_, title) ->
                    Tab(
                        selected = idx == safeIndex,
                        onClick = { selectedCategoryIndex = idx },
                        text = { Text(title) },
                    )
                }
            }
        }

        if (filteredItems.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("这里还没有记录食材", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "点击右上角「入库」记录采购食材，会按自带保质期表自动推算参考天数，绝不覆盖真实包装日期。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(filteredItems, key = { it.id }) { batch ->
                val remaining = batch.daysRemaining()
                val urgencyColor = when {
                    remaining == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    remaining < 0 -> MaterialTheme.colorScheme.error
                    remaining <= 2 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
                val urgencyLabel = when {
                    remaining == null -> "未设置日期"
                    batch.dateKind.isSuggested -> when {
                        remaining < 0 -> "已过建议食用日"
                        remaining == 0 -> "建议今天吃完"
                        remaining == 1 -> "建议明天吃完"
                        else -> "建议 $remaining 天内吃完"
                    }
                    remaining < 0 -> "已到期 ${-remaining} 天"
                    remaining == 0 -> "今天到期"
                    remaining == 1 -> "明天到期"
                    else -> "$remaining 天后到期"
                }
                val categoryName = doc.categories.firstOrNull { it.id == batch.categoryId }?.name ?: "其他"

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                text = batch.expiresOnIsoDate?.let { "$urgencyLabel · $it（${batch.dateKind.label}）" } ?: urgencyLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = urgencyColor,
                            )
                        }
                        FlowRow {
                            TextButton(onClick = { onCookWithIngredients(listOf(batch.ingredientName)) }) {
                                Text("做菜")
                            }
                            TextButton(onClick = { editingDateId = batch.id }) { Text("改日期") }
                            TextButton(onClick = {
                                runCatching { container.fridgeStore.markConsumed(batch.id) }
                                    .onFailure { historyError = "食材状态未保存，请重试。" }
                            }) {
                                Text("用完")
                            }
                            TextButton(onClick = { removingItemId = batch.id }) { Text("删除") }
                        }
                    }
                }
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

    stock.firstOrNull { it.id == editingDateId }?.let { batch ->
        val initial = batch.expiresOnIsoDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { editingDateId = null },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    val selected = picker.selectedDateMillis ?: return@TextButton
                    val date = Instant.ofEpochMilli(selected).atOffset(ZoneOffset.UTC).toLocalDate().toString()
                    runCatching { container.fridgeStore.updateItemDate(batch.id, date, FridgeDateKind.MANUAL) }
                        .onSuccess {
                            editingDateId = null
                            FridgeDailyReminderScheduler.checkAndNotifyIfNeeded(context)
                        }
                        .onFailure { historyError = "日期未保存，请重试。" }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingDateId = null }) { Text("取消") } },
        ) { DatePicker(state = picker) }
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
                                        Text("用完于 ${container.fridgeStore.formatLocalDate(it)}")
                                    }
                                }
                                TextButton(onClick = {
                                    runCatching { container.fridgeStore.restoreItem(batch.id) }
                                        .onSuccess { FridgeDailyReminderScheduler.checkAndNotifyIfNeeded(context) }
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
        var name by rememberSaveable { mutableStateOf("") }
        var qty by rememberSaveable { mutableStateOf("") }
        var categoryId by rememberSaveable { mutableStateOf(doc.categories.firstOrNull()?.id ?: "default-other") }
        var daysText by rememberSaveable { mutableStateOf("") }

        val defaultDays = snapshot.data.shelfLife.items[name.trim()] ?: snapshot.data.shelfLife.defaultDays

        AlertDialog(
            onDismissRequest = { showAddItemDialog = false },
            title = { Text("放入冰箱库存") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("食材名称") },
                        placeholder = { Text("例：番茄、鸡蛋、牛奶") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = qty,
                        onValueChange = { qty = it },
                        label = { Text("数量说明（选填）") },
                        placeholder = { Text("例：3 个 / 500ml") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { input -> daysText = input.filter { it.isDigit() } },
                        label = { Text("保存天数（默认推算：$defaultDays 天）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("分类：", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        doc.categories.forEach { cat ->
                            FilterChip(
                                selected = categoryId == cat.id,
                                onClick = { categoryId = cat.id },
                                label = { Text(cat.name) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = name.trim().isNotEmpty(),
                    onClick = {
                        val days = daysText.toIntOrNull()?.coerceIn(1, 365) ?: defaultDays
                        container.fridgeStore.addItem(
                            ingredientName = name,
                            categoryId = categoryId,
                            quantityText = qty,
                            shelfLifeDays = days,
                            explicitIsoDate = LocalDate.now().plusDays(days.toLong()).toString(),
                            dateKind = if (daysText.isBlank()) FridgeDateKind.ESTIMATED else FridgeDateKind.MANUAL,
                        )
                        ensureNotificationPermissionOrCheck()
                        showAddItemDialog = false
                    },
                ) { Text("放入") }
            },
            dismissButton = {
                TextButton(onClick = { showAddItemDialog = false }) { Text("取消") }
            },
        )
    }

    if (showPhotoIntakeDialog) {
        val photoScope = rememberCoroutineScope()
        val modelStore = container.fridgeProduceModelStore
        var produceModel by remember { mutableStateOf<File?>(null) }
        var checkedModel by remember { mutableStateOf(false) }
        var downloadingModel by remember { mutableStateOf(false) }
        var downloadFailed by remember { mutableStateOf(false) }
        var labelText by remember { mutableStateOf("") }
        var imageClassifications by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }
        var manualName by remember { mutableStateOf("") }
        var isCutProduce by remember { mutableStateOf(false) }
        var selectedCandidateDate by remember { mutableStateOf<String?>(null) }
        var photoInfo by remember { mutableStateOf<String?>(null) }
        var photoError by remember { mutableStateOf<String?>(null) }
        var working by remember { mutableStateOf(false) }
        var prepareUpload by remember { mutableStateOf<(suspend () -> ByteArray)?>(null) }
        var uploadJpeg by remember { mutableStateOf<ByteArray?>(null) }

        LaunchedEffect(modelStore) {
            produceModel = modelStore.installedModel()
            checkedModel = true
        }

        fun classifyProduce(bitmap: Bitmap, rotation: Int): List<Pair<String, Float>> {
            val model = produceModel ?: return emptyList()
            return container.fridgeProduceClassifier.classify(bitmap, rotation, model)
        }

        fun readPhoto(read: suspend () -> FridgePhotoInspector.InMemoryRecognitionResult) {
            photoInfo = null
            photoError = null
            labelText = ""
            imageClassifications = emptyList()
            manualName = ""
            isCutProduce = false
            selectedCandidateDate = null
            prepareUpload = null
            working = true
            photoScope.launch {
                try {
                    val result = read()
                    labelText = result.recognizedText
                    imageClassifications = result.classifications
                    photoInfo = when {
                        FridgeLabelParser.imageNames(imageClassifications).isNotEmpty() ->
                            "已在本机识别蔬果，请核对食材名称。"
                        labelText.isNotBlank() -> "已识别包装文字，请核对食材和日期。"
                        else -> "本机未识别到食材或包装文字。可以批量识图或手动录入。"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BoundedIoStreams.TooLargeException) {
                    photoError = "照片超过 30 MB，请换一张较小的图片。"
                } catch (_: Exception) {
                    photoError = "本机无法识别这张照片，可以换图或手动录入。"
                } finally {
                    working = false
                }
            }
        }

        val galleryLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) readPhoto {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BoundedIoStreams.readBytesBounded(input, FridgePhotoInspector.MAX_PHOTO_BYTES)
                    } ?: throw FridgePhotoInspector.UnreadableImageException()
                }
                prepareUpload = { container.fridgeVisionService.prepare(bytes) }
                FridgePhotoInspector.recognizeInMemoryBytes(
                    bytes, snapshot.allBuiltInStuffNames, ::classifyProduce,
                )
            }
        }

        val cameraLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.TakePicturePreview(),
        ) { bitmap: Bitmap? ->
            if (bitmap != null) readPhoto {
                prepareUpload = { container.fridgeVisionService.prepare(bitmap) }
                FridgePhotoInspector.recognizeInMemoryBitmap(
                    bitmap, snapshot.allBuiltInStuffNames, ::classifyProduce,
                )
            }
        }

        val matchedNames = remember(labelText, snapshot, imageClassifications) {
            FridgeLabelParser.matchKnownNames(labelText, snapshot.allBuiltInStuffNames, imageClassifications)
        }
        val scan = remember(labelText, matchedNames) {
            FridgeLabelParser.parse(labelText, names = matchedNames)
        }
        val effectiveName = manualName.trim().ifEmpty { scan.names.singleOrNull().orEmpty() }
        val storageAdvice = remember(effectiveName, isCutProduce) {
            ProduceStorageAdvice.recommendation(effectiveName, cut = isCutProduce)
        }

        val photo = uploadJpeg
        if (photo != null) {
            FridgeVisionDialog(
                jpeg = photo,
                service = container.fridgeVisionService,
                sessions = container.yunlefunSessionManager,
                normalize = { snapshot.data.normalizer.normalize(it) },
                onBack = { uploadJpeg = null },
                onOpenAccount = onOpenAccount,
                onAdd = { names ->
                    val today = LocalDate.now()
                    val veggieNames = snapshot.data.food.vegetable.mapTo(mutableSetOf()) { it.name }
                    val meatNames = snapshot.data.food.meat.mapTo(mutableSetOf()) { it.name }
                    val additions = names.map { name ->
                        val advice = ProduceStorageAdvice.recommendation(name, cut = false)
                        val days = snapshot.data.shelfLife.items[name] ?: snapshot.data.shelfLife.defaultDays
                        FridgeBatchItem(
                            ingredientName = name,
                            categoryId = when {
                                name in veggieNames || advice != null -> "veg"
                                name in meatNames -> "meat"
                                else -> FridgeBatchItem.DEFAULT_CATEGORY_ID
                            },
                            quantityText = "1 份",
                            expiresOnIsoDate = (advice?.dateFrom(today) ?: today.plusDays(days.toLong())).toString(),
                            dateKind = if (advice == null) FridgeDateKind.ESTIMATED else FridgeDateKind.ADVISORY,
                        )
                    }
                    container.fridgeStore.addItems(additions)
                    showPhotoIntakeDialog = false
                    ensureNotificationPermissionOrCheck()
                },
            )
        } else AlertDialog(
            onDismissRequest = { showPhotoIntakeDialog = false },
            title = { Text("拍照识别") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (checkedModel && produceModel == null) {
                        val modelSize = (modelStore.model.bytes + 524_288) / 1_048_576
                        Text("首次识别蔬果需下载约 ${modelSize} MiB 的模型。下载后可离线识别。")
                        when {
                            downloadingModel -> {
                                Text("正在下载蔬果模型…")
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            downloadFailed -> Text(
                                "模型下载失败。请检查网络和可用空间，也可以继续识别包装文字或手动录入。",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (!downloadingModel) {
                            OutlinedButton(enabled = !working, onClick = {
                                downloadingModel = true
                                downloadFailed = false
                                photoScope.launch {
                                    try {
                                        produceModel = modelStore.download()
                                    } catch (_: IOException) {
                                        downloadFailed = true
                                    } finally {
                                        downloadingModel = false
                                    }
                                }
                            }) { Text(if (downloadFailed) "重试下载" else "下载蔬果模型") }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(enabled = !working && !downloadingModel, onClick = {
                            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("选相册图片") }
                        OutlinedButton(enabled = !working && !downloadingModel, onClick = {
                            runCatching { cameraLauncher.launch(null) }
                                .onFailure { photoError = "相机不可用，请从相册选图。" }
                        }) { Text("拍照") }
                        prepareUpload?.let { prepare ->
                            TextButton(enabled = !working && !downloadingModel, onClick = {
                                working = true
                                photoError = null
                                photoScope.launch {
                                    try {
                                        uploadJpeg = prepare()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        photoError = "无法准备这张照片，请换一张。"
                                    } finally {
                                        working = false
                                    }
                                }
                            }) { Text("批量识图") }
                        }
                    }
                    if (working) Text("正在处理照片…")
                    photoInfo?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    photoError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(
                        value = labelText,
                        onValueChange = {
                            labelText = it
                            selectedCandidateDate = null
                        },
                        label = { Text("包装标签文字（支持生产日期/保质期/EXP 自动解析）") },
                        placeholder = { Text("例：日本豆腐 生产日期 2026-11-10 保质期 15天") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (scan.names.isNotEmpty()) {
                        Text("识别到的食材候选：", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            scan.names.forEach { candidate ->
                                FilterChip(
                                    selected = effectiveName == candidate,
                                    onClick = { manualName = candidate },
                                    label = { Text(candidate) },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it },
                        label = { Text("核对食材名称") },
                        placeholder = { Text(scan.names.firstOrNull() ?: "例：蓝莓、日本豆腐、牛奶") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isCutProduce,
                            onClick = { isCutProduce = false },
                            label = { Text("完整食材") },
                        )
                        FilterChip(
                            selected = isCutProduce,
                            onClick = { isCutProduce = true },
                            label = { Text("已切开/分装") },
                        )
                    }
                    scan.expiryDay?.let { exp ->
                        Text(
                            text = "标签到期日：$exp（${scan.basis.title}）",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (scan.dateCandidates.size > 1 || (scan.expiryDay == null && scan.dateCandidates.isNotEmpty())) {
                        Text("从识别出的日期中手动指定到期日：", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            scan.dateCandidates.forEach { d ->
                                val iso = d.toString()
                                FilterChip(
                                    selected = selectedCandidateDate == iso,
                                    onClick = { selectedCandidateDate = iso },
                                    label = { Text(iso) },
                                )
                            }
                        }
                    }
                    storageAdvice?.let { advice ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("公开储存建议：建议 ${advice.days} 天内食用（预计 ${advice.dateFrom(LocalDate.now())}）", style = MaterialTheme.typography.labelMedium)
                                Text(advice.condition, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    scan.messages.forEach { msg ->
                        Text("• $msg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !working && effectiveName.isNotEmpty(),
                    onClick = {
                        val chosenIso = selectedCandidateDate
                            ?: scan.expiryDay?.toString()
                            ?: storageAdvice?.dateFrom(LocalDate.now())?.toString()
                            ?: LocalDate.now().plusDays(
                                (snapshot.data.shelfLife.items[effectiveName] ?: snapshot.data.shelfLife.defaultDays).toLong(),
                            ).toString()
                        val kind = when {
                            selectedCandidateDate != null -> FridgeDateKind.MANUAL
                            scan.expiryDay != null -> scan.basis.toDateKind()
                            storageAdvice != null -> FridgeDateKind.ADVISORY
                            else -> FridgeDateKind.ESTIMATED
                        }
                        container.fridgeStore.addItem(
                            ingredientName = effectiveName,
                            categoryId = doc.categories.firstOrNull()?.id ?: "default-other",
                            quantityText = if (isCutProduce) "已切开" else "",
                            shelfLifeDays = storageAdvice?.days ?: snapshot.data.shelfLife.defaultDays,
                            explicitIsoDate = chosenIso,
                            dateKind = kind,
                            productionIsoDate = scan.productionDay?.toString(),
                        )
                        ensureNotificationPermissionOrCheck()
                        showPhotoIntakeDialog = false
                    },
                ) { Text("确认入库") }
            },
            dismissButton = {
                TextButton(onClick = { showPhotoIntakeDialog = false }) { Text("取消") }
            },
        )
    }

    if (showManageCategoriesDialog) {
        var newCatName by rememberSaveable { mutableStateOf("") }
        var editingCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
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
                                TextButton(onClick = {
                                    runCatching { container.fridgeStore.deleteCategory(cat.id) }
                                        .onSuccess {
                                            if (editingCategoryId == cat.id) {
                                                editingCategoryId = null
                                                newCatName = ""
                                            }
                                        }
                                        .onFailure { categoryError = "分类未删除，请重试。" }
                                }) {
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
                        "删除分类时，原分类下的食材批次会自动归入「其他」，不会丢失。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showManageCategoriesDialog = false }) { Text("完成") }
            },
        )
    }
}
