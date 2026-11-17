package cn.yunyoujun.cook.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.data.local.AiProviderMode
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.AppThemeMode
import cn.yunyoujun.cook.data.local.SignalConsentState
import cn.yunyoujun.cook.data.remote.AppFeedbackCategory
import cn.yunyoujun.cook.data.remote.FeedbackClient
import cn.yunyoujun.cook.data.remote.FeedbackReceipt
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.ui.components.TagFlowChips
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTabScreen(
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onOpenAccountSync: () -> Unit = {},
    onOpenPrivateRecipes: () -> Unit,
    onOpenPersonalCatalog: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenRecipeData: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val prefs by container.preferencesStore.state.collectAsState()
    val accountState by container.yunlefunSessionManager.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("设置与我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("外观主题", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = prefs.themeMode == mode,
                                onClick = { container.preferencesStore.setThemeMode(mode) },
                                label = { Text(mode.title) },
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("默认选菜匹配模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            RecommendationMode.LOOSE to "宽松模式",
                            RecommendationMode.STRICT to "严格模式",
                            RecommendationMode.SURVIVAL to "应急模式",
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = prefs.mode == mode,
                                onClick = { container.preferencesStore.setMode(mode) },
                                label = { Text(label) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("默认不吃辣", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = prefs.noSpicy,
                            onCheckedChange = { container.preferencesStore.setNoSpicy(it) },
                        )
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("常备调料（默认视为家里已有）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TagFlowChips(
                        items = snapshot.data.pantry.options,
                        selectedItems = prefs.pantry.toSet(),
                        onToggle = { container.preferencesStore.togglePantryItem(it) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("忌口清单（选菜时自动避开）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val commonAvoid = (snapshot.data.food.vegetable + snapshot.data.food.meat + snapshot.data.food.staple)
                        .map { it.name }
                        .take(24)
                    TagFlowChips(
                        items = commonAvoid,
                        selectedItems = prefs.avoid.toSet(),
                        onToggle = { container.preferencesStore.toggleAvoidItem(it) },
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("匿名选菜搜索信号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "仅上报纯食材组合无结果与点击位次，不含设备标识，且与微信 SDK 独立开关。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = prefs.signalConsent == SignalConsentState.ALLOWED,
                            onCheckedChange = { container.preferencesStore.setSignalConsent(it) },
                        )
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    val accountSubtitle = if (accountState.account != null) {
                        "${accountState.account?.displayName} · ${if (accountState.syncEnabled) "已开启跨端同步" else "仅本机记录"}"
                    } else {
                        "SSO v3 登录 · 显式云同步 · 会员权益读取"
                    }
                    SettingsNavRow("云乐坊账号与跨端同步", accountSubtitle, onOpenAccountSync)
                    HorizontalDivider()
                    SettingsNavRow("AI 辅助与 DeepSeek Key", prefs.aiProviderMode.title, onOpenAiSettings)
                    HorizontalDivider()
                    SettingsNavRow("我的私房菜", "自建做法与 JSON 备份", onOpenPrivateRecipes)
                    HorizontalDivider()
                    SettingsNavRow("个人食材与厨具词典", "增删自定义与隐藏内置", onOpenPersonalCatalog)
                    HorizontalDivider()
                    SettingsNavRow("意见反馈与菜谱投稿", "平台标识 android · 含支持工单与邮件兜底", onOpenFeedback)
                    HorizontalDivider()
                    SettingsNavRow("内置菜谱数据说明", "${snapshot.data.meta.total} 道菜 · ${snapshot.data.meta.dataVersion}", onOpenRecipeData)
                    HorizontalDivider()
                    SettingsNavRow("隐私与本地存储说明", "设备库隔离 · Keystore AES-GCM", onOpenPrivacy)
                }
            }
        }

        item {
            Text(
                text = "云食册 Android (${BuildConfig.VERSION_NAME} · ${BuildConfig.APPLICATION_ID})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val prefs by container.preferencesStore.state.collectAsState()
    var keyInput by rememberSaveable { mutableStateOf("") }
    var hasStoredKey by remember {
        mutableStateOf(
            runCatching { !container.vault.readSecret(AndroidKeystoreVault.KEY_DEEPSEEK_API_KEY).isNullOrBlank() }
                .getOrDefault(false),
        )
    }
    var vaultMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 辅助设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("选择提供方", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AiProviderMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.aiProviderMode == mode,
                        onClick = { container.preferencesStore.setAiProviderMode(mode) },
                        label = { Text(mode.title) },
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DeepSeek 个人 Key（BYOK）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (hasStoredKey) "当前设备已安全保存 Key（使用 Android Keystore AES-256-GCM 加密，不参与系统自动备份）。" else "尚未配置个人 Key。即使不配置，也可免费使用本机词典智能找菜。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("输入新的 DeepSeek API Key (sk-...)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = keyInput.trim().isNotEmpty(),
                            onClick = {
                                runCatching {
                                    container.vault.writeSecret(AndroidKeystoreVault.KEY_DEEPSEEK_API_KEY, keyInput.trim())
                                }.onSuccess {
                                    hasStoredKey = true
                                    keyInput = ""
                                    container.preferencesStore.setAiProviderMode(AiProviderMode.BYOK_DEEPSEEK)
                                    vaultMessage = "已加密写入本机安全密钥库。"
                                }.onFailure { err ->
                                    vaultMessage = err.message ?: "安全密钥库不可用，未保存明文。"
                                }
                            },
                        ) { Text("加密保存 Key") }
                        if (hasStoredKey) {
                            OutlinedButton(
                                onClick = {
                                    runCatching { container.vault.deleteSecret(AndroidKeystoreVault.KEY_DEEPSEEK_API_KEY) }
                                        .onSuccess {
                                            hasStoredKey = false
                                            vaultMessage = "已清除本机 Key。"
                                        }
                                        .onFailure { vaultMessage = it.message }
                                },
                            ) { Text("清除 Key") }
                        }
                    }
                    vaultMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppFeedbackScreen(
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var category by rememberSaveable { mutableStateOf(AppFeedbackCategory.IDEA) }
    var note by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<FeedbackReceipt?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("意见反馈与支持") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("选择反馈类型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFeedbackCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(cat.label) },
                    )
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(FeedbackClient.MAX_FEEDBACK_NOTE) },
                label = { Text("详细说明（最多 ${FeedbackClient.MAX_FEEDBACK_NOTE} 字）") },
                placeholder = { Text(category.placeholder) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it.take(FeedbackClient.MAX_CONTACT) },
                label = { Text("联系方式（选填，便于回访或查看工单进度）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                enabled = !submitting && note.trim().isNotEmpty(),
                onClick = {
                    submitting = true
                    message = null
                    scope.launch {
                        runCatching {
                            container.feedbackClient.sendAppFeedback(
                                category = category,
                                note = note,
                                contact = contact,
                                recipeVersion = snapshot.data.meta.dataVersion,
                            )
                        }.onSuccess { res ->
                            submitting = false
                            receipt = res
                            message = if (res != null) "提交成功！回执编号：${res.id}" else "反馈已提交，感谢你的建议！"
                        }.onFailure { err ->
                            submitting = false
                            message = err.message ?: "提交失败，可尝试下方邮件草稿或微信客服入口。"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (submitting) "正在提交…" else "提交反馈（平台：android）")
            }

            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            receipt?.ticketUrl?.let { ticketUrl ->
                OutlinedButton(
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ticketUrl))) }
                    },
                ) { Text("查看工单进度 ($ticketUrl)") }
            }

            HorizontalDivider()
            Text("备用联系渠道", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val lines = listOf("类型：${category.label}", "说明：${note.trim()}", "联系方式：${contact.trim()}")
                OutlinedButton(
                    onClick = {
                        val mailUri = FeedbackClient.buildMailToUri(category.label, lines)
                        runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, mailUri)) }
                            .onFailure { Toast.makeText(context, "未安装邮件客户端，请使用复制邮箱内容", Toast.LENGTH_SHORT).show() }
                    },
                ) { Text("发送邮件 (${FeedbackClient.SUPPORT_EMAIL})") }
                OutlinedButton(
                    onClick = {
                        val text = FeedbackClient.buildMailPreviewText(category.label, lines)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("cook-feedback", text))
                        Toast.makeText(context, "已复制反馈模板到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("复制反馈邮件文本") }
                snapshot.links.wecomKf?.let { url ->
                    OutlinedButton(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                    ) { Text("联系人工客服") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDataSettingsScreen(
    snapshot: CookCatalogSnapshot,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内置菜谱数据说明") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("数据协议版本：schema v${snapshot.data.meta.schemaVersion}", style = MaterialTheme.typography.bodyMedium)
            Text("数据包指纹版本：${snapshot.data.meta.dataVersion}", style = MaterialTheme.typography.bodyMedium)
            Text("内置菜谱总量：${snapshot.data.meta.total} 道", style = MaterialTheme.typography.bodyMedium)
            Text("图文正文条数：${snapshot.textLibrary?.count ?: 0} 篇", style = MaterialTheme.typography.bodyMedium)
            Text(
                "饮食目标状态：${if (snapshot.dietaryCatalog != null) "已启用（${snapshot.availableDietaryGoals.size} 个目标）" else "未启用（${snapshot.dietaryError ?: "无数据"}）"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "机构低 GI 栏目：${if (snapshot.institutionalLowGICatalog?.isAvailable == true) "已启用（${snapshot.institutionalLowGICatalog.entries.size} 条已核对原方）" else "未达到发布门槛或已关闭"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "当前版本采用应用内固化的离线数据包，启动与筛选零网络依赖，与 Web 端及 iOS 端共享同一份构建输出及 SHA-256/FNV-1a 指纹。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("隐私与本地存储说明") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("1. 本地设备库与账号库隔离", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "本机匿名收藏、最近浏览、冰箱库存、私房菜与个人词典均保存在独立的设备本地域；未来接入账号云同步时，也绝不会未授权自动上传本机旧数据。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("2. 安全密钥库", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "AI API Key 与登录凭据由 Android Keystore 硬件主密钥配合 AES-256-GCM AEAD 加密写入专属非备份目录，已关闭系统自动云备份（android:allowBackup=\"false\"）。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("3. 网络请求隔离", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "DeepSeek BYOK、云乐坊会员托管 AI 与意见反馈各自使用独立连接，禁止自动跨站重定向、禁用 Cookie 与缓存；菜谱原文仅随白名单校验后的当前步上下文发送，不上传你的冰箱库存或私房菜库。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountAndSyncScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.yunlefunSessionManager.state.collectAsState()
    var manualCallbackUrl by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云乐坊账号、同步与会员") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.statusMessage?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(msg, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("云乐坊 SSO v3 登录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (state.credential == null) {
                        Text(
                            "使用系统浏览器发起 PKCE S256 授权（10 分钟有效期、一次性消费）。首次登录不会自动上传本机收藏与历史。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                val authUrl = container.yunlefunSessionManager.beginSsoAuthorization()
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                                }.onFailure {
                                    Toast.makeText(context, "未找到可用的系统浏览器", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) {
                            Text("浏览器登录云乐坊账号")
                        }
                        OutlinedTextField(
                            value = manualCallbackUrl,
                            onValueChange = { manualCallbackUrl = it },
                            label = { Text("若未自动跳回 App，可粘贴回调完整链接 (#ylf_sso=...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (manualCallbackUrl.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    val target = manualCallbackUrl.trim()
                                    manualCallbackUrl = ""
                                    scope.launch {
                                        container.yunlefunSessionManager.handleSsoCallbackUrl(target)
                                    }
                                },
                            ) { Text("验证回调并完成登录") }
                        }
                    } else {
                        Text(
                            text = "当前账号：${state.account?.displayName ?: state.credential?.subject ?: "已授权"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.entitlement?.let { ent ->
                            Text(
                                text = if (ent.isActive) {
                                    "云乐坊会员：有效（AccountToken: ${ent.accountToken}）"
                                } else {
                                    "云乐坊会员：未开通或已到期（AccountToken: ${ent.accountToken}）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ent.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch { container.yunlefunSessionManager.refreshProfileAndEntitlement() }
                                },
                            ) { Text("刷新资料与会员权益") }
                            OutlinedButton(
                                onClick = {
                                    scope.launch { container.yunlefunSessionManager.signOut() }
                                },
                            ) { Text("退出登录") }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("CloudBase 跨平台同步 (v2)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "需有效云乐坊会员并显式开启；关闭或退出登录时自动恢复显示本机设备库，同时保留账号离线缓存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.syncEnabled,
                            enabled = state.credential != null && !state.isSyncing,
                            onCheckedChange = { enabled ->
                                scope.launch { container.yunlefunSessionManager.setSyncEnabled(enabled) }
                            },
                        )
                    }

                    Text(
                        text = "当前账号缓存：${state.accountDocument.favorites.size} 条收藏 · ${state.accountDocument.histories.size} 条历史",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            enabled = state.credential != null && !state.isSyncing,
                            onClick = {
                                scope.launch { container.yunlefunSessionManager.copyDeviceLibraryToAccount() }
                            },
                        ) {
                            Text("将本机记录复制到当前账号")
                        }
                        Button(
                            enabled = state.usesAccountLibrary && !state.isSyncing,
                            onClick = {
                                scope.launch { container.yunlefunSessionManager.syncNow() }
                            },
                        ) {
                            Text(if (state.isSyncing) "正在同步…" else "立即同步")
                        }
                    }
                }
            }
        }
    }
}
