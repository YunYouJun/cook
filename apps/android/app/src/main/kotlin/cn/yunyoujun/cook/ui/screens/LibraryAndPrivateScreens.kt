package cn.yunyoujun.cook.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.viewinterop.AndroidView
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.sync.CatalogCategory
import cn.yunyoujun.cook.core.sync.CatalogEntry
import cn.yunyoujun.cook.core.sync.itemsIn
import cn.yunyoujun.cook.core.sync.PrivateRecipe
import cn.yunyoujun.cook.core.sync.PrivateRecipeFile
import cn.yunyoujun.cook.core.sync.PrivateRecipeImport
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.BoundedIoStreams
import cn.yunyoujun.cook.data.local.PrivateVideoDraft
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.ui.components.DecideRandomDialog
import cn.yunyoujun.cook.ui.components.RecipeSummaryCard
import cn.yunyoujun.cook.ui.components.buildDecideContextSummary
import cn.yunyoujun.cook.ui.state.CookSessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryTabScreen(
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    sessionController: CookSessionController,
    onOpenRecipe: (Int) -> Unit,
    onOpenPrivateRecipes: () -> Unit,
    onOpenPersonalCatalog: () -> Unit,
    onOpenAccountSync: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favorites by container.userLibraryRepository.observeDeviceFavorites().collectAsState(initial = emptyList())
    val histories by container.userLibraryRepository.observeDeviceHistories().collectAsState(initial = emptyList())
    val accountSyncState by container.yunlefunSessionManager.state.collectAsState()
    val privateState by container.privateRecipesStore.state.collectAsState()
    val catalogState by container.personalCatalogStore.state.collectAsState()
    val prefs by container.preferencesStore.state.collectAsState()
    val conditions by sessionController.conditions.collectAsState()

    var sectionIndex by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showRandom by rememberSaveable { mutableStateOf(false) }
    var favoriteError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    val trimmedQuery = query.trim()
    val ingredient = snapshot.data.normalizer.normalize(trimmedQuery)
    fun matches(name: String, stuff: List<String>): Boolean =
        trimmedQuery.isEmpty() || name.contains(trimmedQuery, ignoreCase = true) || ingredient in stuff

    val favoriteIds = remember(favorites) { favorites.mapTo(mutableSetOf()) { it.recipeId } }

    val exportUserDataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val payload = container.userLibraryRepository.exportUserDataDocumentJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开写入目标文件")
                }
            }.onSuccess {
                val label = if (accountSyncState.usesAccountLibrary) "当前账号同步库" else "本机"
                statusMessage = "已导出${label}收藏与浏览历史 JSON。"
            }.onFailure { err ->
                statusMessage = "导出失败：${err.message}"
            }
        }
    }

    val importUserDataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BoundedIoStreams.readUtf8Bounded(input, maxBytes = 10 * 1024 * 1024)
                    } ?: error("无法读取选中的文件")
                }
                container.userLibraryRepository.importUserDataDocumentJson(raw).getOrThrow()
            }.onSuccess { (favCount, historyCount) ->
                val label = if (accountSyncState.usesAccountLibrary) "当前账号同步库" else "本机"
                statusMessage = "已合并导入数据，${label}共 $favCount 条收藏、$historyCount 条历史。"
            }.onFailure { err ->
                statusMessage = "导入未生效：${err.message ?: "格式校验未通过"}"
            }
        }
    }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text("菜谱与个人库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (accountSyncState.usesAccountLibrary) {
                            "当前模式：云乐坊账号同步库（${accountSyncState.account?.displayName ?: "已登录"}）"
                        } else {
                            "当前模式：本机设备库（未开启云端同步）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { showRandom = true }) { Text("今天吃什么") }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索菜名或食材") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("全部", "收藏", "最近浏览").forEachIndexed { idx, label ->
                    SegmentedButton(
                        selected = sectionIndex == idx,
                        onClick = { sectionIndex = idx },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = 3),
                    ) {
                        Text(label)
                    }
                }
            }
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(onClick = onOpenPrivateRecipes) {
                    Text("我的私房菜 (${privateState.recipes.size})")
                }
                OutlinedButton(onClick = onOpenPersonalCatalog) {
                    Text("个人食材词典 (${catalogState.customEntries.size})")
                }
                OutlinedButton(onClick = onOpenAccountSync) {
                    Text(if (accountSyncState.usesAccountLibrary) "同步状态" else "云同步")
                }
                OutlinedButton(onClick = { exportUserDataLauncher.launch("cook-user-data.json") }) {
                    Text("导出收藏与历史")
                }
                OutlinedButton(onClick = { importUserDataLauncher.launch(arrayOf("application/json", "*/*")) }) {
                    Text("导入互通 JSON")
                }
            }
        }

        statusMessage?.let { msg ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { statusMessage = null }) { Text("收起") }
                    }
                }
            }
        }

        if (sectionIndex != 2) {
            val visibleRecipes = when (sectionIndex) {
                1 -> favorites.mapNotNull { snapshot.recipe(it.recipeId) }
                else -> snapshot.data.recipes
            }.filter { matches(it.name, it.stuff) }
            if (visibleRecipes.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (sectionIndex == 1 && trimmedQuery.isEmpty()) "还没有收藏菜谱" else "没有匹配的菜谱",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            } else {
                items(visibleRecipes, key = { it.id }) { recipe ->
                    val isFavorite = recipe.id in favoriteIds
                    RecipeSummaryCard(
                        recipe = recipe,
                        match = null,
                        isFavorite = isFavorite,
                        onToggleFavorite = {
                            scope.launch {
                                container.userLibraryRepository.toggleFavorite(recipe.id, isFavorite)
                                    .onFailure { favoriteError = it.message ?: "收藏请求失败" }
                            }
                        },
                        onClick = { onOpenRecipe(recipe.id) },
                    )
                }
            }
        } else {
            if (histories.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { confirmClearHistory = true }) { Text("清空历史记录") }
                    }
                }
            }
            val visibleHistory = histories.filter { matches(it.snapshot.name, it.snapshot.stuff) }
            if (visibleHistory.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(if (histories.isEmpty()) "暂无最近浏览记录" else "没有匹配的浏览记录", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            } else {
                val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                items(visibleHistory, key = { "${it.snapshot.name}-${it.visitedAtMs}" }) { item ->
                    val matchedRecipe = snapshot.data.recipes.firstOrNull { it.name == item.snapshot.name }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.snapshot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "查看于 ${formatter.format(Date(item.visitedAtMs.toLong()))} · ${item.snapshot.stuff.take(4).joinToString("、")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (matchedRecipe != null) {
                                TextButton(onClick = { onOpenRecipe(matchedRecipe.id) }) { Text("打开") }
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        container.userLibraryRepository.removeHistory(item.snapshot.name, item.visitedAtMs)
                                    }
                                },
                            ) { Text("移除") }
                        }
                    }
                }
            }
        }
    }

    if (showRandom) {
        val randomCandidates = remember(snapshot, prefs, conditions) {
            sessionController.evaluateCandidates(snapshot, prefs, ignoringKeyword = true)
        }
        DecideRandomDialog(
            recipes = randomCandidates.recipes,
            matchesById = randomCandidates.matchesById,
            contextSummary = buildDecideContextSummary(conditions, prefs, ignoringKeyword = true),
            onOpenRecipe = {
                showRandom = false
                onOpenRecipe(it)
            },
            onDismiss = { showRandom = false },
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空最近浏览") },
            text = { Text("将清除本机设备库中的全部历史浏览记录，收藏与私房菜不受影响。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearHistory = false
                        scope.launch { container.userLibraryRepository.clearAllHistory() }
                    },
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("取消") }
            },
        )
    }

    favoriteError?.let { err ->
        AlertDialog(
            onDismissRequest = { favoriteError = null },
            title = { Text("操作失败") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { favoriteError = null }) { Text("知道了") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PrivateRecipesManageScreen(
    container: AppContainer,
    onBack: () -> Unit,
    targetId: String? = null,
    onOpenJournal: (JournalRecipeId) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.privateRecipesStore.state.collectAsState()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<Pair<String, PrivateRecipeImport>?>(null) }
    var bannerText by remember { mutableStateOf<String?>(null) }
    var playingVideoFile by remember { mutableStateOf<Pair<String, File>?>(null) }
    val visibleRecipes = state.recipes.filter { targetId == null || it.customId == targetId }

    val exportPortableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val jsonText = container.privateRecipesStore.exportPortableV1()
            context.contentResolver.openOutputStream(uri)?.use { it.write(jsonText.toByteArray(Charsets.UTF_8)) }
                ?: error("无法写入文件")
        }.onSuccess { bannerText = "已导出跨端通用 v1 私房菜 JSON。" }
            .onFailure { bannerText = "导出失败：${it.message}" }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val jsonText = container.privateRecipesStore.exportBackupV2()
            context.contentResolver.openOutputStream(uri)?.use { it.write(jsonText.toByteArray(Charsets.UTF_8)) }
                ?: error("无法写入文件")
        }.onSuccess { bannerText = "已导出完整备份（含视频元数据）。" }
            .onFailure { bannerText = "导出失败：${it.message}" }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BoundedIoStreams.readUtf8Bounded(input, PrivateRecipeFile.BYTE_LIMIT)
                    } ?: error("无法读取文件")
                }
                val plan = container.privateRecipesStore.previewImport(text)
                text to plan
            }.onSuccess { preview ->
                importPreview = preview
            }.onFailure { err ->
                bannerText = err.message ?: "导入校验失败，原有私房菜未变化。"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的私房菜") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    if (targetId == null) TextButton(onClick = { showCreateDialog = true }) { Text("新建") }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (targetId == null) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { exportPortableLauncher.launch("cook-private-recipes.json") }) {
                        Text("导出通用 JSON (v1)")
                    }
                    OutlinedButton(onClick = { exportBackupLauncher.launch("cook-private-recipes-backup.json") }) {
                        Text("导出原生备份 (v2)")
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Text("导入私房菜 JSON")
                    }
                }
            }

            bannerText?.let { msg ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Text(msg, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (visibleRecipes.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (targetId == null) "还没有私房菜" else "这道私房菜已不可用", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            } else {
                items(visibleRecipes, key = { it.customId }) { recipe ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(recipe.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = {
                                    editingId = recipe.customId
                                    showCreateDialog = true
                                }) { Text("编辑") }
                                TextButton(onClick = { container.privateRecipesStore.deleteRecipe(recipe.customId) }) {
                                    Text("删除")
                                }
                            }
                            Text("食材：${recipe.stuff.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                            if (recipe.tools.isNotEmpty()) {
                                Text("厨具：${recipe.tools.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                            }
                            recipe.note?.takeIf { it.isNotEmpty() }?.let { note ->
                                Text(note, style = MaterialTheme.typography.bodyMedium)
                            }
                            recipe.sourceUrl?.let { url ->
                                TextButton(onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                        .onFailure { bannerText = "没有可打开来源链接的应用。" }
                                }) { Text("打开做法来源") }
                            }
                            val journalId = JournalRecipeId.Personal(recipe.customId)
                            CookingRecordActions(container.cookingJournalStore, journalId, recipe.name) {
                                onOpenJournal(journalId)
                            }
                            recipe.video?.let { video ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val noteText = if (video.location == PrivateRecipeVideo.Location.ICLOUD) {
                                        "🎬 iOS iCloud 视频引用（Android 端仅保留记录，不拉取 iCloud 流）"
                                    } else {
                                        "🎬 本地视频附件：${video.originalName}（${video.byteCount / 1024} KB）"
                                    }
                                    Text(
                                        noteText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (video.location == PrivateRecipeVideo.Location.LOCAL) {
                                        TextButton(
                                            onClick = {
                                                runCatching { container.videoStore.playableFile(video) }
                                                    .onSuccess { file -> playingVideoFile = recipe.name to file }
                                                    .onFailure { err -> bannerText = err.message ?: "视频无法播放" }
                                            },
                                        ) { Text("播放视频") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    playingVideoFile?.let { (recipeTitle, videoFile) ->
        AlertDialog(
            onDismissRequest = { playingVideoFile = null },
            title = { Text("视频演示 · $recipeTitle") },
            text = {
                var playerError by remember(videoFile) { mutableStateOf<String?>(null) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (playerError != null) {
                        Text(playerError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val controller = MediaController(ctx)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)
                                    setVideoPath(videoFile.absolutePath)
                                    setOnErrorListener { _, _, _ ->
                                        playerError = "视频无法播放，文件可能已损坏或编码不受支持。请重新选择视频。"
                                        true
                                    }
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = false
                                        start()
                                    }
                                }
                            },
                        )
                    }
                    Text(
                        text = "文件：${videoFile.name}（${videoFile.length() / 1024} KB）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { playingVideoFile = null }) { Text("关闭") }
            },
        )
    }

    if (showCreateDialog) {
        val editing = state.recipes.firstOrNull { it.customId == editingId }
        var name by rememberSaveable(editingId) { mutableStateOf(editing?.name.orEmpty()) }
        var stuffText by rememberSaveable(editingId) { mutableStateOf(editing?.stuff.orEmpty().joinToString("、")) }
        var toolsText by rememberSaveable(editingId) { mutableStateOf(editing?.tools.orEmpty().joinToString("、")) }
        var note by rememberSaveable(editingId) { mutableStateOf(editing?.note.orEmpty()) }
        val originalSource = editing?.bv?.takeIf { it.isNotEmpty() } ?: editing?.link.orEmpty()
        var source by rememberSaveable(editingId) { mutableStateOf(originalSource) }
        var keepOriginalVideo by rememberSaveable(editingId) { mutableStateOf(true) }
        var stagedVideoDraft by remember { mutableStateOf<PrivateVideoDraft?>(null) }
        var isSaving by remember { mutableStateOf(false) }
        var isPickingVideo by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }

        val pickVideoLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            isPickingVideo = true
            scope.launch {
                runCatching {
                    container.videoStore.stageFromUri(uri, displayName = null)
                }.onSuccess { draft ->
                    stagedVideoDraft?.let { container.videoStore.discard(it) }
                    stagedVideoDraft = draft
                    err = null
                }.onFailure { e ->
                    err = e.message ?: "请选择可播放的 MP4、MOV 或 M4V 视频。"
                }
                isPickingVideo = false
            }
        }

        fun closeEditor() {
            stagedVideoDraft?.let { container.videoStore.discard(it) }
            showCreateDialog = false
            editingId = null
        }

        AlertDialog(
            onDismissRequest = { if (!isSaving && !isPickingVideo) closeEditor() },
            title = { Text(if (editingId == null) "新建私房菜" else "编辑私房菜") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(30) },
                        label = { Text("菜名（1–30 字）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = stuffText,
                        onValueChange = { stuffText = it },
                        label = { Text("食材（用顿号、或逗号分隔）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = toolsText,
                        onValueChange = { toolsText = it },
                        label = { Text("厨具（选填，分隔填写）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(20000) },
                        label = { Text("做法笔记") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it.take(4096) },
                        label = { Text("做法来源（BV 号或网页链接）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = !isSaving && !isPickingVideo,
                            onClick = {
                                pickVideoLauncher.launch(arrayOf("video/mp4", "video/quicktime", "video/x-m4v", "video/*"))
                            },
                        ) {
                            Text(if (stagedVideoDraft == null && editing?.video == null) "添加本地视频" else "更换视频")
                        }
                        if (stagedVideoDraft != null || (keepOriginalVideo && editing?.video != null)) {
                            TextButton(
                                enabled = !isSaving && !isPickingVideo,
                                onClick = {
                                    stagedVideoDraft?.let { container.videoStore.discard(it) }
                                    stagedVideoDraft = null
                                    keepOriginalVideo = false
                                },
                            ) { Text("移除视频") }
                        }
                    }
                    stagedVideoDraft?.let { draft ->
                        Text(
                            text = "已选视频：${draft.video.originalName}（${draft.video.byteCount / 1024} KB）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isSaving && !isPickingVideo && (editingId == null || editing != null),
                    onClick = {
                        val now = System.currentTimeMillis()
                        val stuff = stuffText.split('、', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                        val tools = toolsText.split('、', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                        val draftToInstall = stagedVideoDraft
                        val existingVideo = editing?.video?.takeIf { keepOriginalVideo }
                        val validatedCandidate = runCatching {
                            val previous = editing ?: PrivateRecipe(
                                customId = "custom-${UUID.randomUUID()}",
                                name = name,
                                stuff = stuff,
                                tools = tools,
                                createdAt = now,
                                updatedAt = now,
                            )
                            val sourceValue = source.trim()
                            val sourceChanged = sourceValue != originalSource
                            val isBilibili = Regex("^BV[0-9A-Za-z]{10}$", RegexOption.IGNORE_CASE).matches(sourceValue)
                            require(!sourceChanged || sourceValue.isBlank() || isBilibili || PrivateRecipeFile.isSafeUrl(sourceValue)) {
                                "请填写有效的 BV 号或网页链接。"
                            }
                            previous.copy(
                                name = name.trim(),
                                stuff = stuff,
                                tools = tools,
                                note = note.trim().takeIf { it.isNotEmpty() },
                                bv = if (sourceChanged) sourceValue.takeIf { isBilibili } else previous.bv,
                                link = if (sourceChanged) sourceValue.takeIf { it.isNotEmpty() && !isBilibili } else previous.link,
                                video = draftToInstall?.video ?: existingVideo,
                                updatedAt = maxOf(now, previous.updatedAt),
                            ).validated()
                        }.getOrElse { e ->
                            err = e.message ?: "请填写有效菜名与至少一项食材（不超过上限）。"
                            return@Button
                        }
                        isSaving = true
                        scope.launch {
                            var installedVideo = null as PrivateRecipeVideo?
                            val result = runCatching {
                                if (draftToInstall != null) {
                                    installedVideo = container.videoStore.installWithoutDiscarding(draftToInstall)
                                }
                                val finalItem = validatedCandidate.copy(video = installedVideo ?: existingVideo)
                                withContext(Dispatchers.IO) {
                                    container.privateRecipesStore.upsertRecipe(finalItem)
                                }
                            }
                            result.onSuccess {
                                isSaving = false
                                closeEditor()
                            }.onFailure { e ->
                                installedVideo?.let { container.videoStore.remove(it) }
                                isSaving = false
                                err = e.message ?: "请填写有效菜名与至少一项食材（不超过上限）。"
                            }
                        }
                    },
                ) { Text(if (isSaving) "保存中…" else "保存") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSaving && !isPickingVideo,
                    onClick = ::closeEditor,
                ) { Text("取消") }
            },
        )
    }

    importPreview?.let { (rawJson, plan) ->
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text("确认导入私房菜") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("将新增：${plan.additions.size} 道")
                    Text("同内容跳过：${plan.duplicates.size} 道")
                    Text("编号冲突 / 曾删除记录：${plan.conflicts.size} 道")
                    if (plan.conflicts.isNotEmpty()) {
                        Text(
                            "冲突项可选择「跳过冲突」或「保留带新编号副本」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            container.privateRecipesStore.commitImport(rawJson, keepCopies = true)
                        }.onSuccess { count ->
                            bannerText = "已导入 $count 道私房菜（含保留副本）。"
                        }.onFailure { err ->
                            bannerText = err.message ?: "导入失败，原数据未变动。"
                        }
                        importPreview = null
                    },
                ) { Text(if (plan.conflicts.isNotEmpty()) "导入并保留副本" else "确认导入") }
            },
            dismissButton = {
                Row {
                    if (plan.conflicts.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    container.privateRecipesStore.commitImport(rawJson, keepCopies = false)
                                }.onSuccess { count ->
                                    bannerText = "已导入 $count 道新私房菜（已跳过冲突）。"
                                }.onFailure { err ->
                                    bannerText = err.message ?: "导入失败。"
                                }
                                importPreview = null
                            },
                        ) { Text("仅新增并跳过冲突") }
                    }
                    TextButton(onClick = { importPreview = null }) { Text("取消") }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonalCatalogManageScreen(
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.personalCatalogStore.state.collectAsState()
    var newName by rememberSaveable { mutableStateOf("") }
    var newCategory by rememberSaveable { mutableStateOf(CatalogCategory.VEGETABLE) }
    var statusText by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val payload = container.personalCatalogStore.exportJson()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法写入文件")
                }
            }.onSuccess { statusText = "已导出个人食材词典 JSON。" }
                .onFailure { statusText = "导出失败：${it.message}" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                        BoundedIoStreams.readUtf8Bounded(input, maxBytes = 2 * 1024 * 1024)
                    } ?: error("无法读取文件")
                    container.personalCatalogStore.importJson(raw)
                }
            }.onSuccess { doc ->
                statusText = "已合并个人词典（本次解析 ${doc.items.size} 条自定义、${doc.hidden.size} 条隐藏内置）。"
            }.onFailure { err ->
                Toast.makeText(context, err.message ?: "导入失败", Toast.LENGTH_SHORT).show()
                statusText = err.message ?: "导入失败，现有词典未变动。"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个人食材/厨具词典") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("cook-personal-catalog.json") }) {
                        Text("导出词典 JSON")
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Text("导入词典 JSON")
                    }
                }
            }

            statusText?.let { msg ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Text(msg, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("添加自定义词条", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it.take(24) },
                            label = { Text("名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CatalogCategory.entries.forEach { cat ->
                                FilterChip(
                                    selected = newCategory == cat,
                                    onClick = { newCategory = cat },
                                    label = { Text("${cat.emoji} ${cat.title}") },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            Button(
                                enabled = newName.trim().isNotEmpty(),
                                onClick = {
                                    container.personalCatalogStore.addCustomEntry(newName, newCategory)
                                        .onSuccess {
                                            newName = ""
                                            statusText = null
                                        }
                                        .onFailure { err ->
                                            statusText = err.message ?: "添加失败"
                                        }
                                },
                            ) { Text("添加") }
                        }
                    }
                }
            }

            if (state.customEntries.isNotEmpty()) {
                item {
                    Text("自定义词条 (${state.customEntries.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(state.customEntries, key = { it.id }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${entry.category.emoji} ${entry.name}（${entry.category.title}）")
                            TextButton(onClick = { container.personalCatalogStore.removeCustomEntry(entry.id) }) { Text("移除") }
                        }
                    }
                }
            }

            item {
                Text("内置词条显示控制（点选隐藏或恢复）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            CatalogCategory.entries.forEach { cat ->
                val sampleItems = snapshot.data.food.itemsIn(cat).take(12)
                if (sampleItems.isNotEmpty()) {
                    item(key = "builtin_${cat.rawValue}") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${cat.emoji} ${cat.title}", style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                sampleItems.forEach { stuff ->
                                    val hidden = CatalogEntry(cat, stuff.name) in state.hiddenEntries
                                    FilterChip(
                                        selected = !hidden,
                                        onClick = { container.personalCatalogStore.toggleHiddenEntry(cat, stuff.name) },
                                        label = { Text(if (hidden) "已隐藏：${stuff.name}" else stuff.name) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
