package cn.yunyoujun.cook.ui.screens

import android.app.Activity
import android.content.ClipboardManager
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.sync.PrivateRecipe
import cn.yunyoujun.cook.core.sync.PrivateRecipeFile
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.PrivateVideoException
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.platform.exportFilename
import cn.yunyoujun.cook.platform.exportMimeType
import cn.yunyoujun.cook.platform.privateRecipeVideoShareIntent
import cn.yunyoujun.cook.ui.components.RecipeSummaryCard
import cn.yunyoujun.cook.ui.navigation.CookNavKey
import cn.yunyoujun.cook.ui.state.CookSessionController
import cn.yunyoujun.cook.ui.state.PrivateRecipeImportViewModel
import cn.yunyoujun.cook.ui.state.PrivateRecipeVideoViewModel
import kotlinx.coroutines.CancellationException
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
    onOpenRecipe: (CookNavKey.RecipeDetail) -> Unit,
    onOpenPrivateRecipes: () -> Unit,
    onOpenPersonalCatalog: () -> Unit,
    onOpenLibraryTransfer: () -> Unit,
    onOpenAccountSync: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val sessions = container.yunlefunSessionManager
    val accountSyncState by sessions.state.collectAsState()
    val libraryScope = accountSyncState.libraryScope
    val favoriteUpdates by remember(container) { container.userLibraryRepository.observeFavorites() }.collectAsState(initial = null)
    val historyUpdates by remember(container) { container.userLibraryRepository.observeHistories() }.collectAsState(initial = null)
    val favoriteEntries = favoriteUpdates?.takeIf { it.scope == libraryScope }
    val historyEntries = historyUpdates?.takeIf { it.scope == libraryScope }
    val favorites = favoriteEntries?.entries.orEmpty()
    val histories = historyEntries?.entries.orEmpty()
    val privateState by container.privateRecipesStore.state.collectAsState()
    val catalogState by container.personalCatalogStore.state.collectAsState()

    var sectionIndex by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showRandom by rememberSaveable { mutableStateOf(false) }
    var lastDecidedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var favoriteError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var confirmClearHistory by remember(libraryScope) { mutableStateOf(false) }
    val trimmedQuery = query.trim()
    val ingredient = snapshot.data.normalizer.normalize(trimmedQuery)
    fun matches(name: String, stuff: List<String>): Boolean =
        trimmedQuery.isEmpty() || name.contains(trimmedQuery, ignoreCase = true) || ingredient in stuff

    val favoriteIds = remember(favorites) { favorites.mapTo(mutableSetOf()) { it.recipeId } }

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
                        text = when {
                            !accountSyncState.isHydrated -> "正在读取收藏与历史库…"
                            accountSyncState.usesAccountLibrary ->
                                "当前模式：云乐坊账号同步库（${accountSyncState.account?.displayName ?: "已登录"}）"
                            else -> "当前模式：本机设备库（未开启云端同步）"
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
                OutlinedButton(onClick = onOpenLibraryTransfer) { Text("导入与导出") }
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
                                when {
                                    sectionIndex == 1 && favoriteEntries == null -> "正在读取收藏…"
                                    sectionIndex == 1 && trimmedQuery.isEmpty() -> "还没有收藏菜谱"
                                    else -> "没有匹配的菜谱"
                                },
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
                        favoriteEnabled = favoriteEntries != null,
                        showInstitutionalTag = snapshot.institutionalLowGICatalog?.entry(recipe) != null,
                        onToggleFavorite = {
                            scope.launch {
                                container.userLibraryRepository.toggleFavorite(recipe.id, isFavorite, libraryScope)
                                    .onFailure { favoriteError = it.message ?: "收藏请求失败" }
                            }
                        },
                        onClick = { onOpenRecipe(CookNavKey.RecipeDetail(recipe.id)) },
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
                            Text(
                                when {
                                    historyEntries == null -> "正在读取浏览记录…"
                                    histories.isEmpty() -> "暂无最近浏览记录"
                                    else -> "没有匹配的浏览记录"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
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
                                TextButton(onClick = { onOpenRecipe(CookNavKey.RecipeDetail(matchedRecipe.id)) }) { Text("打开") }
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        container.userLibraryRepository.removeHistory(item.snapshot.name, item.visitedAtMs, libraryScope)
                                            .onFailure { statusMessage = it.message ?: "未能移除浏览记录，请重试。" }
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
        CookDecideDialog(
            snapshot = snapshot,
            container = container,
            controller = sessionController,
            ignoringKeyword = true,
            avoidFirstId = lastDecidedId,
            onRecipePreviewed = { lastDecidedId = it },
            onOpenRecipe = { route ->
                lastDecidedId = route.recipeId
                showRandom = false
                onOpenRecipe(route)
            },
            onDismiss = { showRandom = false },
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空最近浏览") },
            text = {
                Text(
                    if (libraryScope.usesAccountLibrary) {
                        "将清空当前云乐坊账号的全部浏览记录，变更会同步到其他设备。"
                    } else {
                        "将清空本机设备库的全部浏览记录。"
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearHistory = false
                        scope.launch {
                            container.userLibraryRepository.clearAllHistory(libraryScope)
                                .onFailure { statusMessage = it.message ?: "未能清空浏览记录，请重试。" }
                        }
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
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onBack: () -> Unit,
    targetId: String? = null,
    onOpenJournal: (JournalRecipeId) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.privateRecipesStore.state.collectAsState()
    val videoEditor = viewModel { PrivateRecipeVideoViewModel(container.privateRecipesStore, container.videoStore) }
    val importer = viewModel { PrivateRecipeImportViewModel(container.privateRecipesStore) }
    var query by rememberSaveable { mutableStateOf("") }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deletionError by rememberSaveable { mutableStateOf(false) }
    var bannerText by rememberSaveable { mutableStateOf<String?>(null) }
    var playingVideoFile by remember { mutableStateOf<Pair<String, File>?>(null) }
    var pendingVideoExport by rememberSaveable { mutableStateOf<String?>(null) }
    val term = query.trim()
    val visibleRecipes = remember(state.recipes, targetId, term) {
        when {
            targetId != null -> state.recipes.filter { it.customId == targetId }
            term.isEmpty() -> state.recipes
            else -> state.recipes.filter { recipe ->
                recipe.name.contains(term, ignoreCase = true) || recipe.stuff.any { it.contains(term, ignoreCase = true) }
            }
        }
    }

    fun closeEditor() {
        videoEditor.finishEditor()
        showCreateDialog = false
        editingId = null
    }

    LaunchedEffect(videoEditor.saved) {
        if (videoEditor.saved) closeEditor()
    }
    LaunchedEffect(videoEditor.message) {
        videoEditor.message?.let { bannerText = it }
        videoEditor.clearMessage()
    }
    LaunchedEffect(importer.message, importer.plan) {
        if (importer.plan == null) {
            importer.message?.let { bannerText = it }
            importer.clearMessage()
        }
    }

    val exportVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingVideoExport
        pendingVideoExport = null
        val uri = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK } ?: return@rememberLauncherForActivityResult
        videoEditor.export(id, uri)
    }

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
        uri?.let { importer.readFile(context.applicationContext.contentResolver, it) }
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
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索菜名或食材") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (query.isNotEmpty()) {
                        { TextButton(onClick = { query = "" }) { Text("清除搜索") } }
                    } else null,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { exportPortableLauncher.launch("cook-private-recipes.json") }) {
                        Text("导出通用 JSON (v1)")
                    }
                    OutlinedButton(onClick = { exportBackupLauncher.launch("cook-private-recipes-backup.json") }) {
                        Text("导出原生备份 (v2)")
                    }
                    OutlinedButton(
                        enabled = !importer.working,
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    ) {
                        Text("导入私房菜 JSON")
                    }
                    OutlinedButton(enabled = !importer.working, onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        importer.readClipboard(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
                    }) { Text("从剪贴板导入") }
                }
                if (importer.working && importer.plan == null) Text("正在读取私房菜…")
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
                            Text(
                                when {
                                    targetId != null -> "这道私房菜已不可用"
                                    state.recipes.isEmpty() -> "还没有私房菜"
                                    else -> "没有符合搜索的私房菜"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
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
                                TextButton(onClick = {
                                    deletionError = false
                                    deletingId = recipe.customId
                                }) {
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
                                val localVideo = video.location == PrivateRecipeVideo.Location.LOCAL &&
                                    video.deviceID == container.videoStore.deviceId
                                val noteText = when {
                                    localVideo -> "本地视频：${video.originalName}（${video.byteCount / 1024} KB）"
                                    video.location == PrivateRecipeVideo.Location.ICLOUD -> "请在原 iOS 设备导出 iCloud 视频，再编辑这道菜并选择视频文件。"
                                    else -> "请在原设备保存视频，再编辑这道菜并选择视频文件。"
                                }
                                Text(noteText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                if (localVideo) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = {
                                                runCatching { container.videoStore.playableFile(video) }
                                                    .onSuccess { file -> playingVideoFile = recipe.name to file }
                                                    .onFailure { err -> bannerText = err.message ?: "视频无法播放" }
                                            },
                                        ) { Text("播放视频") }
                                        TextButton(onClick = {
                                            runCatching {
                                                val share = privateRecipeVideoShareIntent(context, container.videoStore, video)
                                                context.startActivity(Intent.createChooser(share, "分享视频文件"))
                                            }.onFailure { error ->
                                                bannerText = (error as? PrivateVideoException)?.message ?: "无法打开视频分享。"
                                            }
                                        }) { Text("分享视频文件") }
                                        TextButton(
                                            enabled = pendingVideoExport == null && !videoEditor.exporting,
                                            onClick = {
                                                val picker = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                                    addCategory(Intent.CATEGORY_OPENABLE)
                                                    type = video.exportMimeType
                                                    putExtra(Intent.EXTRA_TITLE, video.exportFilename)
                                                }
                                                pendingVideoExport = video.id
                                                runCatching { exportVideoLauncher.launch(picker) }.onFailure {
                                                    pendingVideoExport = null
                                                    bannerText = "无法打开系统文件选择器。"
                                                }
                                            },
                                        ) { Text("保存视频文件") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.recipes.firstOrNull { it.customId == deletingId }?.let { recipe ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deletingId = null },
            title = { Text("删除这道私人菜谱？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(recipe.name)
                    if (deletionError) Text("菜谱未能删除，请重试。", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    scope.launch {
                        try {
                            val removedVideo = withContext(Dispatchers.IO) {
                                val store = container.privateRecipesStore
                                val removed = store.deleteRecipe(recipe.customId)
                                removed?.video?.let {
                                    runCatching { store.removeUnreferencedVideo(it, container.videoStore) }.getOrDefault(false)
                                } ?: true
                            }
                            deletingId = null
                            if (!removedVideo) {
                                val message = "菜谱已删除，旧视频副本未能清理。"
                                if (targetId == null) bannerText = message
                                else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                            if (targetId != null) onBack()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            deletionError = true
                        } finally {
                            deleting = false
                        }
                    }
                }) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { deletingId = null }) { Text("取消") }
            },
        )
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
                            onReset = null,
                            onRelease = { it.stopPlayback() },
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
        val ingredients = rememberSaveable(editingId, saver = PrivateRecipeEditorList.Saver) {
            PrivateRecipeEditorList(editing?.stuff.orEmpty())
        }
        val tools = rememberSaveable(editingId, saver = PrivateRecipeEditorList.Saver) {
            PrivateRecipeEditorList(editing?.tools.orEmpty())
        }
        var catalogField by rememberSaveable(editingId) { mutableStateOf<PrivateRecipeCatalogField?>(null) }
        val editorScroll = rememberScrollState()
        var note by rememberSaveable(editingId) { mutableStateOf(editing?.note.orEmpty()) }
        val originalSource = editing?.bv?.takeIf { it.isNotEmpty() } ?: editing?.link.orEmpty()
        var source by rememberSaveable(editingId) { mutableStateOf(originalSource) }
        val stagedVideoDraft = videoEditor.draft
        val hasVideo = stagedVideoDraft != null || (videoEditor.keepOriginal && editing?.video != null)

        val pickVideoLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri -> videoEditor.videoChosen(uri) }

        if (catalogField == null) AlertDialog(
            onDismissRequest = { if (!videoEditor.editorBusy) closeEditor() },
            title = { Text(if (editingId == null) "新建私房菜" else "编辑私房菜") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(editorScroll),
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
                        value = ingredients.text,
                        onValueChange = { ingredients.text = it },
                        label = { Text("食材（顿号、逗号或换行分隔）") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(enabled = !videoEditor.editorBusy, onClick = { catalogField = PrivateRecipeCatalogField.INGREDIENT }) {
                        Text(PrivateRecipeCatalogField.INGREDIENT.title)
                    }
                    OutlinedTextField(
                        value = tools.text,
                        onValueChange = { tools.text = it },
                        label = { Text("厨具（选填）") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(enabled = !videoEditor.editorBusy, onClick = { catalogField = PrivateRecipeCatalogField.TOOL }) {
                        Text(PrivateRecipeCatalogField.TOOL.title)
                    }
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
                            enabled = !videoEditor.editorBusy,
                            onClick = {
                                videoEditor.chooseVideo {
                                    pickVideoLauncher.launch(arrayOf("video/mp4", "video/quicktime", "video/x-m4v", "video/*"))
                                }
                            },
                        ) {
                            Text(if (hasVideo) "更换视频" else "添加本地视频")
                        }
                        if (hasVideo) {
                            TextButton(
                                enabled = !videoEditor.editorBusy,
                                onClick = videoEditor::removeVideo,
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
                    if (videoEditor.importing) Text("正在读取视频…", style = MaterialTheme.typography.bodySmall)
                    videoEditor.editorError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !videoEditor.editorBusy && (editingId == null || editing != null),
                    onClick = {
                        val now = System.currentTimeMillis()
                        val ingredientNames = ingredients.values
                        val toolNames = tools.values
                        val existingVideo = editing?.video?.takeIf { videoEditor.keepOriginal }
                        val validatedCandidate = runCatching {
                            val previous = editing ?: PrivateRecipe(
                                customId = "custom-${UUID.randomUUID()}",
                                name = name,
                                stuff = ingredientNames,
                                tools = toolNames,
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
                                stuff = ingredientNames,
                                tools = toolNames,
                                note = note.trim().takeIf { it.isNotEmpty() },
                                bv = if (sourceChanged) sourceValue.takeIf { isBilibili } else previous.bv,
                                link = if (sourceChanged) sourceValue.takeIf { it.isNotEmpty() && !isBilibili } else previous.link,
                                video = stagedVideoDraft?.video ?: existingVideo,
                                updatedAt = maxOf(now, previous.updatedAt),
                            ).validated()
                        }.getOrElse { e ->
                            videoEditor.editorError = e.message ?: "请填写有效菜名与至少一项食材（不超过上限）。"
                            return@Button
                        }
                        videoEditor.save(validatedCandidate)
                    },
                ) { Text(if (videoEditor.saving) "保存中…" else "保存") }
            },
            dismissButton = {
                TextButton(
                    enabled = !videoEditor.editorBusy,
                    onClick = ::closeEditor,
                ) { Text("取消") }
            },
        )
        catalogField?.let { field ->
            val personalCatalog by container.personalCatalogStore.state.collectAsState()
            val food = remember(personalCatalog, snapshot) { personalCatalog.toDocument().applyingTo(snapshot.data.food) }
            val isIngredient = field == PrivateRecipeCatalogField.INGREDIENT
            PrivateRecipeCatalogPicker(
                field = field,
                entries = if (isIngredient) food.vegetable + food.meat + food.staple else food.tools,
                draft = if (isIngredient) ingredients else tools,
                normalize = { name -> if (isIngredient) snapshot.data.normalizer.normalize(name) else name.trim() },
                onClose = { catalogField = null },
            )
        }
    }

    importer.plan?.let { plan ->
        PrivateRecipeImportPreview(
            plan = plan,
            working = importer.working,
            message = importer.message,
            onCancel = importer::cancel,
            onConfirm = importer::confirm,
        )
    }
}
