package cn.yunyoujun.cook.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import cn.yunyoujun.cook.data.local.FridgeDateKind
import cn.yunyoujun.cook.data.local.FridgeLabelParser
import cn.yunyoujun.cook.data.local.FridgePhotoInspector
import cn.yunyoujun.cook.data.local.ProduceStorageAdvice
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.platform.FridgeDailyReminderScheduler
import cn.yunyoujun.cook.platform.KitchenNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FridgeTabScreen(
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onCookWithIngredients: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by container.fridgeStore.state.collectAsState()
    var selectedCategoryIndex by rememberSaveable { mutableIntStateOf(0) }
    var showAddItemDialog by rememberSaveable { mutableStateOf(false) }
    var showPhotoIntakeDialog by rememberSaveable { mutableStateOf(false) }
    var showManageCategoriesDialog by rememberSaveable { mutableStateOf(false) }
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
    val filteredItems = doc.items.filter { activeCategoryId == null || it.categoryId == activeCategoryId }
        .sortedBy { it.daysRemaining() ?: Int.MAX_VALUE }
    val dueSoonCount = container.fridgeStore.dueSoonCount()

    LazyColumn(
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
                Column {
                    Text("我的冰箱", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (dueSoonCount > 0) "有 $dueSoonCount 份食材临近保鲜期" else "共记录 ${doc.items.size} 份批次库存",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dueSoonCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { showManageCategoriesDialog = true }) { Text("分类") }
                    OutlinedButton(onClick = { showPhotoIntakeDialog = true }) { Text("拍照/标签") }
                    Button(onClick = { showAddItemDialog = true }) { Text("入库") }
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

        if (doc.items.isNotEmpty()) {
            item {
                Button(
                    onClick = {
                        onCookWithIngredients(doc.items.map { it.ingredientName }.distinct())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("用冰箱里的 ${doc.items.map { it.ingredientName }.distinct().size} 种食材选菜")
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
                    remaining == null -> "未设置到期日"
                    remaining < 0 -> "已超期 ${-remaining} 天"
                    remaining == 0 -> "今天到期"
                    else -> "还剩 $remaining 天"
                }
                val categoryName = doc.categories.firstOrNull { it.id == batch.categoryId }?.name ?: "其他"

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                text = "$urgencyLabel · ${batch.expiresOnIsoDate.orEmpty()}（${batch.dateKind.label}）",
                                style = MaterialTheme.typography.bodySmall,
                                color = urgencyColor,
                            )
                        }
                        Row {
                            TextButton(onClick = { onCookWithIngredients(listOf(batch.ingredientName)) }) {
                                Text("做菜")
                            }
                            TextButton(onClick = { container.fridgeStore.removeItem(batch.id) }) {
                                Text("用完")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddItemDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        var qty by rememberSaveable { mutableStateOf("") }
        var categoryId by rememberSaveable { mutableStateOf(doc.categories.firstOrNull()?.id ?: "default-other") }
        var dateKind by rememberSaveable { mutableStateOf(FridgeDateKind.ESTIMATED) }
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
                    Text("日期依据：", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FridgeDateKind.entries.forEach { kind ->
                            FilterChip(
                                selected = dateKind == kind,
                                onClick = { dateKind = kind },
                                label = { Text(kind.label) },
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
                            dateKind = dateKind,
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
        var labelText by rememberSaveable { mutableStateOf("") }
        var manualName by rememberSaveable { mutableStateOf("") }
        var isCutProduce by rememberSaveable { mutableStateOf(false) }
        var selectedCandidateDate by rememberSaveable { mutableStateOf<String?>(null) }
        var photoInfo by remember { mutableStateOf<String?>(null) }
        var photoError by remember { mutableStateOf<String?>(null) }

        val galleryLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            BoundedIoStreams.readBytesBounded(input, FridgePhotoInspector.MAX_PHOTO_BYTES)
                        } ?: throw FridgePhotoInspector.UnreadableImageException()
                    }
                    FridgePhotoInspector.recognizeInMemoryBytes(bytes)
                }.onSuccess { result ->
                    photoError = null
                    if (result.recognizedText.isNotBlank()) {
                        labelText = if (labelText.isBlank()) {
                            result.recognizedText
                        } else {
                            "${labelText.trim()}\n${result.recognizedText}"
                        }
                        selectedCandidateDate = null
                    }
                    photoInfo = if (result.recognizedText.isNotBlank()) {
                        "已完成端侧中文 OCR 识别（${result.summary.width}×${result.summary.height}，${result.summary.byteCount / 1024} KB，照片与识别原文均不落盘）。"
                    } else {
                        "已在内存中解码照片（${result.summary.width}×${result.summary.height}），未识别到可读文字，可手动补充标签或名称。"
                    }
                }.onFailure { err ->
                    photoInfo = null
                    photoError = if (err is BoundedIoStreams.TooLargeException) {
                        "照片超过 30 MB，请换一张较小的图片。"
                    } else {
                        err.message ?: "无法读取这张照片，请换一张清晰的照片或手动添加。"
                    }
                }
            }
        }

        val cameraLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.TakePicturePreview(),
        ) { bitmap: Bitmap? ->
            if (bitmap == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    FridgePhotoInspector.recognizeInMemoryBitmap(bitmap)
                }.onSuccess { result ->
                    photoError = null
                    if (result.recognizedText.isNotBlank()) {
                        labelText = if (labelText.isBlank()) {
                            result.recognizedText
                        } else {
                            "${labelText.trim()}\n${result.recognizedText}"
                        }
                        selectedCandidateDate = null
                    }
                    photoInfo = if (result.recognizedText.isNotBlank()) {
                        "已完成端侧中文 OCR 识别（${result.summary.width}×${result.summary.height}，照片与识别原文均不落盘）。"
                    } else {
                        "已解码拍摄照片（${result.summary.width}×${result.summary.height}），未检测到清晰文字，可手动填写食材与日期。"
                    }
                }.onFailure { err ->
                    photoInfo = null
                    photoError = err.message ?: "无法识别拍摄的照片，请手动录入。"
                }
            }
        }

        val matchedNames = remember(labelText, snapshot) {
            FridgeLabelParser.matchKnownNames(labelText, snapshot.allBuiltInStuffNames)
        }
        val scan = remember(labelText, matchedNames) {
            FridgeLabelParser.parse(labelText, names = matchedNames)
        }
        val effectiveName = manualName.trim().ifEmpty { scan.names.firstOrNull().orEmpty() }
        val storageAdvice = remember(effectiveName, isCutProduce) {
            ProduceStorageAdvice.recommendation(effectiveName, cut = isCutProduce)
        }

        AlertDialog(
            onDismissRequest = { showPhotoIntakeDialog = false },
            title = { Text("照片 / 包装标签识别录入") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { galleryLauncher.launch("image/*") }) {
                            Text("选相册图片")
                        }
                        OutlinedButton(onClick = { cameraLauncher.launch(null) }) {
                            Text("拍照")
                        }
                    }
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
                        label = { Text("确认食材名称（未填则采用首项识别词）") },
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
                    enabled = effectiveName.isNotEmpty(),
                    onClick = {
                        val chosenIso = selectedCandidateDate
                            ?: scan.expiryDay?.toString()
                            ?: storageAdvice?.dateFrom(LocalDate.now())?.toString()
                            ?: LocalDate.now().plusDays(
                                (snapshot.data.shelfLife.items[effectiveName] ?: snapshot.data.shelfLife.defaultDays).toLong(),
                            ).toString()
                        val kind = when {
                            selectedCandidateDate != null -> FridgeDateKind.PACKAGE_EXPIRY
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
        AlertDialog(
            onDismissRequest = { showManageCategoriesDialog = false },
            title = { Text("管理冰箱分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    doc.categories.forEach { cat ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(cat.name, style = MaterialTheme.typography.bodyMedium)
                            if (cat.id != cn.yunyoujun.cook.data.local.FridgeBatchItem.DEFAULT_CATEGORY_ID) {
                                TextButton(onClick = { container.fridgeStore.deleteCategory(cat.id) }) {
                                    Text("删除")
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
                            label = { Text("新分类名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = newCatName.trim().isNotEmpty(),
                            onClick = {
                                container.fridgeStore.addCategory(newCatName)
                                newCatName = ""
                            },
                        ) { Text("添加") }
                    }
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
