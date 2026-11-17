package cn.yunyoujun.cook.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.ai.StepGuidanceDraft
import cn.yunyoujun.cook.core.ai.StepGuidanceKind
import cn.yunyoujun.cook.core.ai.StepGuidancePrompt
import cn.yunyoujun.cook.core.dietary.InstitutionalLowGICatalog
import cn.yunyoujun.cook.core.recipe.RecipeAmounts
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeStepUsagePlan
import cn.yunyoujun.cook.core.recipe.RecipeText
import cn.yunyoujun.cook.core.recipe.RecipeTextBlock
import cn.yunyoujun.cook.data.local.AiProviderMode
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.RecipeTimerState
import cn.yunyoujun.cook.data.remote.FeedbackLinkIssue
import cn.yunyoujun.cook.data.remote.RecipeIssueKind
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.platform.AlarmSchedulePrecision
import cn.yunyoujun.cook.platform.KitchenTimerScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(
    recipeId: Int,
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onBack: () -> Unit,
    onOpenWorkbench: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recipe = snapshot.recipe(recipeId)
    val favoriteIds by container.userLibraryRepository.observeDeviceFavoriteIds().collectAsState(initial = emptySet())
    val workbenchState by container.workbenchStore.state.collectAsState()
    val prefs by container.preferencesStore.state.collectAsState()

    var servings by rememberSaveable(recipeId) {
        mutableIntStateOf(workbenchState.entries[recipeId]?.servings ?: 1)
    }
    var showFeedbackDialog by rememberSaveable { mutableStateOf(false) }
    var viewingImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var favoriteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(recipe?.id) {
        if (recipe != null) {
            container.userLibraryRepository.recordVisit(recipe)
        }
    }

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

    val recipeText: RecipeText? = remember(recipe.id, snapshot) { snapshot.recipeText(recipe.id) }
    val presentation = remember(recipeText) { recipeText?.let(::RecipePresentation) }
    val amounts = remember(presentation, servings) {
        presentation?.let { RecipeAmounts(it.allQuantities, servings) }
    }
    val usagePlan = remember(presentation, amounts) {
        if (presentation != null && amounts != null) {
            RecipeStepUsagePlan(presentation.steps, amounts, presentation.amountAnnotations)
        } else {
            null
        }
    }
    val isFav = recipe.id in favoriteIds

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe.name, maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = { showFeedbackDialog = true }) {
                        Text("反馈")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                container.userLibraryRepository.toggleFavorite(recipe.id, isFav)
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
                        recipe.tags.forEach { tag ->
                            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                                Text(tag, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            prefs.rememberedDietaryGoal?.let { goal ->
                snapshot.dietaryCatalog?.entry(recipe, goal)?.let { entry ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f))) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("饮食目标核对：${goal.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(entry.reason(goal), style = MaterialTheme.typography.bodyMedium)
                                Text(goal.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            snapshot.institutionalLowGICatalog?.entry(recipe)?.let { lowGi ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("机构低 GI 参考：${lowGi.classificationTitle}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("原方要求：${lowGi.requirements}", style = MaterialTheme.typography.bodySmall)
                            Text(InstitutionalLowGICatalog.EXPLANATION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onOpenWorkbench(recipe.id) },
                        modifier = Modifier.weight(1f),
                        enabled = presentation != null,
                    ) {
                        Text(if (presentation != null) "进入厨房工作台" else "暂无步骤原文")
                    }
                    OutlinedButton(onClick = { showFeedbackDialog = true }) {
                        Text("报错/反馈")
                    }
                }
            }

            if (amounts != null && amounts.supportsServings) {
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
                                            servings -= 1
                                            container.workbenchStore.updateServings(recipe.id, servings, presentation)
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
                                            servings += 1
                                            container.workbenchStore.updateServings(recipe.id, servings, presentation)
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
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("图文做法正文", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            recipeText.blocks.forEachIndexed { idx, block ->
                                when (block) {
                                    is RecipeTextBlock.Heading -> {
                                        if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                        Text(
                                            text = block.text,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    is RecipeTextBlock.Paragraph -> {
                                        Text(block.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    is RecipeTextBlock.ListBlock -> {
                                        block.items.forEachIndexed { itemIdx, itemText ->
                                            val matchingStep = presentation?.steps?.firstOrNull {
                                                it.sourceBlock == idx && it.sourceItem == itemIdx
                                            }
                                            val resolvedText = if (matchingStep != null && usagePlan != null) {
                                                usagePlan.instruction(matchingStep)?.text ?: itemText
                                            } else {
                                                amounts?.instruction(itemText)?.text ?: itemText
                                            }
                                            val prefix = if (block.ordered) "${block.start + itemIdx}." else "•"
                                            Text("$prefix $resolvedText", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    is RecipeTextBlock.ImageBlock -> {
                                        val imgUrl = snapshot.textLibrary?.imageURL(block.src) ?: block.src
                                        OutlinedButton(
                                            onClick = { viewingImageUrl = imgUrl },
                                        ) {
                                            Text("🖼️ 查看菜谱配图（支持放大与分享）")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                val safeExternalUrls = buildList {
                    if (!recipe.bv.isNullOrEmpty()) add("打开 B 站视频" to "https://www.bilibili.com/video/${recipe.bv}")
                    val safeLink = recipe.link?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    if (safeLink != null) add("打开图文原链接" to safeLink)
                }
                if (safeExternalUrls.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        safeExternalUrls.forEach { (label, url) ->
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }.onFailure {
                                        Toast.makeText(context, "未找到可打开链接的浏览器或应用", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFeedbackDialog) {
        RecipeFeedbackDialog(
            recipe = recipe,
            snapshot = snapshot,
            container = container,
            onDismiss = { showFeedbackDialog = false },
        )
    }

    viewingImageUrl?.let { imgUrl ->
        var scalePercent by rememberSaveable(imgUrl) { mutableIntStateOf(100) }
        AlertDialog(
            onDismissRequest = { viewingImageUrl = null },
            title = { Text("菜谱配图预览（${scalePercent}%）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("图片地址：$imgUrl", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scalePercent = (scalePercent - 25).coerceAtLeast(50) }) { Text("缩小 -") }
                        OutlinedButton(onClick = { scalePercent = 100 }) { Text("100%") }
                        OutlinedButton(onClick = { scalePercent = (scalePercent + 25).coerceAtMost(300) }) { Text("放大 +") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "${recipe.name} 配图：$imgUrl")
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(sendIntent, "分享配图链接"))
                                }
                            },
                        ) { Text("分享配图") }
                        if (imgUrl.startsWith("https://") || imgUrl.startsWith("http://")) {
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(imgUrl)))
                                    }
                                },
                            ) { Text("浏览器打开原图") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingImageUrl = null }) { Text("关闭") }
            },
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

@Composable
private fun RecipeFeedbackDialog(
    recipe: cn.yunyoujun.cook.core.model.Recipe,
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val issues = remember(recipe.id) { RecipeIssueKind.availableFor(recipe) }
    var selectedIssue by remember { mutableStateOf(issues.first()) }
    var selectedLinkIssue by remember { mutableStateOf(FeedbackLinkIssue.UNSPECIFIED) }
    var note by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var feedbackResult by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("报告菜谱问题：${recipe.name}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("选择问题类型：", style = MaterialTheme.typography.labelMedium)
                issues.forEach { issue ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedIssue = issue },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = selectedIssue == issue,
                            onClick = { selectedIssue = issue },
                            label = { Text(issue.label) },
                        )
                    }
                }
                if (selectedIssue == RecipeIssueKind.VIDEO || selectedIssue == RecipeIssueKind.TEXT) {
                    Text("链接打不开的原因：", style = MaterialTheme.typography.labelMedium)
                    FeedbackLinkIssue.entries.forEach { li ->
                        FilterChip(
                            selected = selectedLinkIssue == li,
                            onClick = { selectedLinkIssue = li },
                            label = { Text(li.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    label = { Text("补充说明（选填，最多 200 字）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                feedbackResult?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting,
                onClick = {
                    submitting = true
                    feedbackResult = null
                    scope.launch {
                        runCatching {
                            container.feedbackClient.reportRecipeIssue(
                                recipe = recipe,
                                issue = selectedIssue,
                                note = note,
                                linkIssue = selectedLinkIssue,
                                recipeVersion = snapshot.data.meta.dataVersion,
                            )
                        }.onSuccess {
                            submitting = false
                            feedbackResult = "已提交问题报告（平台标记 android），感谢反馈！"
                        }.onFailure { err ->
                            submitting = false
                            feedbackResult = err.message ?: "提交失败，请检查网络或配置。"
                        }
                    }
                },
            ) {
                Text(if (submitting) "提交中…" else "提交报告")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KitchenWorkbenchScreen(
    recipeId: Int,
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recipe = snapshot.recipe(recipeId)
    val recipeText = remember(recipeId, snapshot) { snapshot.recipeText(recipeId) }
    val presentation = remember(recipeText) { recipeText?.let(::RecipePresentation) }
    val workbenchMap by container.workbenchStore.state.collectAsState()
    val prefs by container.preferencesStore.state.collectAsState()
    val entry = workbenchMap.entries[recipeId] ?: cn.yunyoujun.cook.data.local.RecipeWorkbenchEntry(recipeId = recipeId)

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(entry.timer.running) {
        while (entry.timer.running) {
            nowMs = System.currentTimeMillis()
            if (entry.timer.remainingSeconds(nowMs) <= 0) {
                container.workbenchStore.updateTimer(
                    recipeId,
                    entry.timer.copy(running = false, deadlineEpochMs = null, pausedRemainingSeconds = 0),
                )
                break
            }
            delay(500L)
        }
    }

    var alarmHint by remember { mutableStateOf<String?>(null) }
    var guidanceStep by remember { mutableStateOf<RecipePresentation.Step?>(null) }
    var contextRevision by remember { mutableStateOf(UUID.randomUUID().toString().lowercase()) }
    var lastScheduledPrecision by remember { mutableStateOf<AlarmSchedulePrecision?>(null) }

    fun buildTimerHint(precision: AlarmSchedulePrecision, hasNotificationPermission: Boolean): String {
        if (!hasNotificationPermission) {
            return "未授予系统通知权限，倒计时到期时不会弹出后台通知（请前往系统设置开启通知），当前仅在前台界面倒计时。"
        }
        return when (precision) {
            AlarmSchedulePrecision.EXACT -> "已设定后台精确提醒。"
            AlarmSchedulePrecision.INEXACT_FALLBACK -> "当前系统未授权精确闹钟，后台提醒可能会受省电策略影响稍有延迟。"
            AlarmSchedulePrecision.NOT_SCHEDULED -> "仅在前台倒计时，后台系统闹钟不可用。"
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        lastScheduledPrecision?.let { precision ->
            alarmHint = buildTimerHint(precision, granted)
        }
    }

    if (recipe == null || recipeText == null || presentation == null) {
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
                Text("当前菜谱没有可用于工作台的步骤原文。")
            }
        }
        return
    }

    val amounts = remember(presentation, entry.servings) { RecipeAmounts(presentation.allQuantities, entry.servings) }
    val usagePlan = remember(presentation, amounts) {
        RecipeStepUsagePlan(presentation.steps, amounts, presentation.amountAnnotations)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工作台 · ${recipe.name}", maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = { container.workbenchStore.resetRecipeChecks(recipeId) }) {
                        Text("重置勾选")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 倒计时器卡片
            item {
                val remaining = entry.timer.remainingSeconds(nowMs)
                val minutes = remaining / 60
                val secs = remaining % 60
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("本菜计时器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "%02d:%02d".format(minutes, secs),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !entry.timer.running,
                                onClick = {
                                    val nextDur = (entry.timer.durationSeconds + 60).coerceAtMost(7200)
                                    container.workbenchStore.updateTimer(
                                        recipeId,
                                        RecipeTimerState(durationSeconds = nextDur, running = false, pausedRemainingSeconds = nextDur),
                                    )
                                },
                            ) { Text("+1 分") }
                            OutlinedButton(
                                enabled = !entry.timer.running && entry.timer.durationSeconds > 60,
                                onClick = {
                                    val nextDur = (entry.timer.durationSeconds - 60).coerceAtLeast(60)
                                    container.workbenchStore.updateTimer(
                                        recipeId,
                                        RecipeTimerState(durationSeconds = nextDur, running = false, pausedRemainingSeconds = nextDur),
                                    )
                                },
                            ) { Text("-1 分") }
                            Button(
                                onClick = {
                                    if (entry.timer.running) {
                                        val left = entry.timer.remainingSeconds(System.currentTimeMillis())
                                        KitchenTimerScheduler.cancel(context, recipeId, recipe.name)
                                        container.workbenchStore.updateTimer(
                                            recipeId,
                                            entry.timer.copy(running = false, deadlineEpochMs = null, pausedRemainingSeconds = left),
                                        )
                                        alarmHint = null
                                    } else {
                                        val secsLeft = entry.timer.remainingSeconds(System.currentTimeMillis())
                                            .takeIf { it > 0 } ?: entry.timer.durationSeconds
                                        val deadline = System.currentTimeMillis() + secsLeft * 1000L
                                        val precision = KitchenTimerScheduler.schedule(context, recipeId, recipe.name, deadline)
                                        lastScheduledPrecision = precision
                                        container.workbenchStore.updateTimer(
                                            recipeId,
                                            entry.timer.copy(running = true, deadlineEpochMs = deadline, pausedRemainingSeconds = null),
                                        )
                                        val hasNotifPerm = cn.yunyoujun.cook.platform.KitchenNotificationHelper.hasNotificationPermission(context)
                                        if (!hasNotifPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        alarmHint = buildTimerHint(precision, hasNotifPerm)
                                    }
                                },
                            ) {
                                Text(if (entry.timer.running) "暂停" else "开始计时")
                            }
                        }
                        alarmHint?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (amounts.supportsServings) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("工作台份数：${entry.servings} 份（调份数仅清除用量变化项勾选）", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Row {
                                TextButton(
                                    onClick = {
                                        contextRevision = UUID.randomUUID().toString().lowercase()
                                        container.workbenchStore.updateServings(recipeId, entry.servings - 1, presentation)
                                    },
                                    enabled = entry.servings > RecipeAmounts.SERVING_RANGE.first,
                                ) { Text("-") }
                                TextButton(
                                    onClick = {
                                        contextRevision = UUID.randomUUID().toString().lowercase()
                                        container.workbenchStore.updateServings(recipeId, entry.servings + 1, presentation)
                                    },
                                    enabled = entry.servings < RecipeAmounts.SERVING_RANGE.last,
                                ) { Text("+") }
                            }
                        }
                    }
                }
            }

            if (presentation.allQuantities.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("备料清单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            presentation.allQuantities.forEach { qty ->
                                val key = "${qty.name}=${qty.amount}"
                                val checked = key in entry.checkedIngredients
                                val label = "${qty.name}：${amounts.amount(qty).text}"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { container.workbenchStore.toggleIngredient(recipeId, key) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { container.workbenchStore.toggleIngredient(recipeId, key) },
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("制作步骤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        presentation.steps.forEachIndexed { idx, step ->
                            val checked = step.id in entry.checkedSteps
                            val displayText = usagePlan.instruction(step)?.text ?: step.text
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { container.workbenchStore.toggleStep(recipeId, step.id) },
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { container.workbenchStore.toggleStep(recipeId, step.id) },
                                    )
                                    Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
                                        Text(
                                            text = "第 ${idx + 1} 步：$displayText",
                                            style = MaterialTheme.typography.bodyMedium,
                                            textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                                        )
                                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                            TextButton(
                                                onClick = {
                                                    contextRevision = UUID.randomUUID().toString().lowercase()
                                                    guidanceStep = step
                                                },
                                            ) {
                                                Text(
                                                    if (prefs.aiProviderMode != AiProviderMode.DISABLED) {
                                                        "AI 讲解这一步"
                                                    } else {
                                                        "原文核对"
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                if (idx < presentation.steps.lastIndex) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    guidanceStep?.let { activeStep ->
        var question by rememberSaveable(activeStep.id) { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        var errorText by remember { mutableStateOf<String?>(null) }
        var resultDraft by remember { mutableStateOf<StepGuidanceDraft?>(null) }
        val activeRevision = contextRevision

        fun runRequest(kind: StepGuidanceKind) {
            scope.launch {
                loading = true
                errorText = null
                resultDraft = null
                runCatching {
                    val prompt = StepGuidancePrompt(
                        recipe = recipe,
                        text = recipeText,
                        dataVersion = snapshot.data.meta.dataVersion,
                        step = activeStep,
                        servings = entry.servings,
                        kind = kind,
                        question = if (kind == StepGuidanceKind.QUESTION) question else null,
                        contextRevision = activeRevision,
                    )
                    val draft = when (prefs.aiProviderMode) {
                        AiProviderMode.BYOK_DEEPSEEK -> {
                            val key = container.vault.readSecret(AndroidKeystoreVault.KEY_DEEPSEEK_API_KEY)
                                ?: throw IllegalStateException("请先在「我的 → AI 设置」填入个人 DeepSeek Key。")
                            container.deepSeekClient.requestStepGuidance(key, prompt)
                        }
                        AiProviderMode.MANAGED_MEMBERSHIP -> {
                            val cred = container.yunlefunSessionManager.state.value.credential
                                ?: throw IllegalStateException("请先在「我的 → 云乐坊账号与同步」登录有效会员账号。")
                            val resp = container.managedStepGuidanceService.complete(prompt, cred)
                            StepGuidanceDraft(
                                status = resp.status,
                                answer = resp.answer,
                                sourceIDs = resp.sourceIDs,
                            )
                        }
                        AiProviderMode.DISABLED -> {
                            throw IllegalStateException("请先在「我的 → AI 设置」开启 AI 辅助。")
                        }
                    }
                    if (activeRevision != contextRevision) {
                        throw IllegalStateException("步骤已切换，已丢弃过期讲解。")
                    }
                    draft
                }.onSuccess { draft ->
                    loading = false
                    resultDraft = draft
                }.onFailure { err ->
                    loading = false
                    errorText = err.message ?: "暂时无法获取分步讲解，请按原文做菜。"
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!loading) guidanceStep = null },
            title = { Text("单步讲解（严格引用原文）") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("原文：${activeStep.text}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it.take(300) },
                        label = { Text("针对这一步提问（留空则讲解操作要点）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("正在校验用量数字与引用来源…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    errorText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    resultDraft?.let { draft ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(draft.answer, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "引用原文段落：${draft.sourceIDs.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !loading && prefs.aiProviderMode != AiProviderMode.DISABLED,
                    onClick = {
                        runRequest(
                            if (question.isBlank()) StepGuidanceKind.EXPLAIN else StepGuidanceKind.QUESTION,
                        )
                    },
                ) {
                    Text(if (question.isBlank()) "生成本步讲解" else "提问")
                }
            },
            dismissButton = {
                TextButton(onClick = { guidanceStep = null }) { Text("关闭") }
            },
        )
    }
}
