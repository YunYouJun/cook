package cn.yunyoujun.cook.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateFormat
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.data.local.AppThemeMode
import cn.yunyoujun.cook.data.local.SignalConsentState
import cn.yunyoujun.cook.data.remote.YunlefunAccountWebHandoff
import cn.yunyoujun.cook.data.remote.AccountWebsitePurpose
import cn.yunyoujun.cook.data.remote.DeviceLibraryCopyStatus
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.platform.canOpenYunlefunAuthorizationApp
import cn.yunyoujun.cook.ui.state.AccountBrowserFlow
import kotlinx.coroutines.launch
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTabScreen(
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    onOpenAccountSync: () -> Unit = {},
    onOpenMembership: () -> Unit = {},
    onOpenPrivateRecipes: () -> Unit,
    onOpenPersonalCatalog: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenRecipeData: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenCookingJournal: () -> Unit,
    onOpenDeviceBackup: () -> Unit = {},
) {
    val prefs by container.preferencesStore.state.collectAsState()
    val accountState by container.yunlefunSessionManager.state.collectAsState()
    var preference by rememberSaveable { mutableStateOf<CookingPreference?>(null) }

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
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    CookingPreferenceRows(prefs, onOpen = { preference = it })
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

        item { FridgeReminderControls(container.preferencesStore) }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    val journal by container.cookingJournalStore.state.collectAsState()
                    val journalSubtitle = when {
                        container.cookingJournalStore.loadFailed -> "暂时无法读取"
                        journal.cooked.isEmpty() && journal.learned.isEmpty() -> "回看做过和学会的菜"
                        else -> "学会 ${journal.learned.size} 道 · 做过 ${journal.cooked.size} 次"
                    }
                    val accountSubtitle = if (accountState.credential != null) {
                        val name = accountState.account?.displayName ?: "已登录"
                        val library = if (accountState.syncEnabled) "已开启跨端同步" else "仅本机记录"
                        "$name · $library"
                    } else {
                        "SSO v3 登录 · 显式云同步 · 会员权益读取"
                    }
                    SettingsNavRow("云乐坊账号与跨端同步", accountSubtitle, onOpenAccountSync)
                    HorizontalDivider()
                    SettingsNavRow("云乐坊会员", "查看和核对会员权益", onOpenMembership)
                    HorizontalDivider()
                    SettingsNavRow("AI 辅助与 DeepSeek Key", prefs.aiProviderMode.title, onOpenAiSettings)
                    HorizontalDivider()
                    SettingsNavRow("做菜足迹", journalSubtitle, onOpenCookingJournal)
                    HorizontalDivider()
                    SettingsNavRow("本机备份与换机", "保存本机数据或从文件分项恢复", onOpenDeviceBackup)
                    HorizontalDivider()
                    SettingsNavRow("我的私房菜", "自建做法与 JSON 备份", onOpenPrivateRecipes)
                    HorizontalDivider()
                    SettingsNavRow("个人食材与厨具词典", "增删自定义与隐藏内置", onOpenPersonalCatalog)
                    HorizontalDivider()
                    SettingsNavRow("帮助与反馈", "意见反馈、菜谱投稿与支持中心", onOpenHelp)
                    HorizontalDivider()
                    SettingsNavRow("内置菜谱数据说明", "${snapshot.data.meta.total} 道菜 · ${snapshot.data.meta.dataVersion}", onOpenRecipeData)
                    HorizontalDivider()
                    SettingsNavRow("隐私政策", "本机数据、可选服务与隐私选择", onOpenPrivacy)
                    HorizontalDivider()
                    SettingsNavRow("关于云食册", "版本、更新日志与项目链接", onOpenAbout)
                }
            }
        }
    }

    preference?.let { page ->
        val personalCatalog by container.personalCatalogStore.state.collectAsState()
        val food = remember(personalCatalog, snapshot) {
            personalCatalog.toDocument().applyingTo(snapshot.data.food)
        }
        Dialog(
            onDismissRequest = { preference = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            CookingPreferenceEditor(
                page, food, snapshot.data.normalizer, snapshot.data.pantry, prefs, container.preferencesStore,
                onBack = { preference = null },
            )
        }
    }
}

@Composable
internal fun SettingsNavRow(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDataSettingsScreen(
    snapshot: CookCatalogSnapshot,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val version = snapshot.data.meta.dataVersion
    var copied by remember(version) { mutableStateOf(false) }
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
            SelectionContainer {
                Text("数据包指纹版本：$version", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("菜谱数据版本", version))
                    copied = true
                }
            }) {
                Text(if (copied) "已复制版本标识" else "复制版本标识")
            }
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
            Text("数据来源", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            SettingsNavRow("菜谱与食材数据", "食用手册开源项目") {
                context.openExternalLink("https://github.com/YunYouJun/cook/tree/main/packages/data")
            }
            snapshot.textLibrary?.source?.let { source ->
                SettingsNavRow("图文做法", source.name) { context.openExternalLink(source.url) }
            }
        }
    }
}

private enum class AccountSyncConfirmation { ENABLE, COPY_DEVICE_LIBRARY }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AccountAndSyncScreen(
    container: AppContainer,
    accountBrowser: AccountBrowserFlow,
    onBack: () -> Unit,
    onOpenWeChatSignup: () -> Unit = {},
    onOpenMembership: () -> Unit = {},
    onOpenLibraryTransfer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions = container.yunlefunSessionManager
    val state by sessions.state.collectAsState()
    val libraryScope = state.libraryScope
    var manualCallbackUrl by remember { mutableStateOf("") }
    var websiteCallback by remember { mutableStateOf("") }
    val browserBusy by accountBrowser.busy.collectAsState()
    val accountWebsiteApproved = state.approvedWebsitePurpose == AccountWebsitePurpose.ACCOUNT_SETTINGS
    var accountWebsiteMessage by remember { mutableStateOf<String?>(null) }
    var syncConfirmation by remember(libraryScope) {
        mutableStateOf<AccountSyncConfirmation?>(null)
    }

    LaunchedEffect(state.awaitingAccountWebsite, accountWebsiteApproved) {
        if (!state.awaitingAccountWebsite || accountWebsiteApproved) websiteCallback = ""
    }

    fun completeSignIn(callback: String) {
        manualCallbackUrl = ""
        accountBrowser.completeSignIn(sessions, callback)
    }

    fun approveAccountWebsite(callback: String) {
        accountWebsiteMessage = null
        accountBrowser.approveWebsite(sessions, callback, AccountWebsitePurpose.ACCOUNT_SETTINGS)
    }

    fun openAccountWebsite() {
        val session = state.membershipSession ?: return
        accountWebsiteMessage = null
        accountBrowser.beginWebsite(sessions, AccountWebsitePurpose.ACCOUNT_SETTINGS, expectedSession = session)
    }

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
                    Text("云乐坊账号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (state.credential == null) {
                        val appSignInNonce = state.appSignInNonce
                        if (appSignInNonce == null) {
                            Text(
                                "首次登录不会自动上传本机收藏与历史。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                enabled = !browserBusy,
                                onClick = {
                                    accountBrowser.beginSignIn(
                                        sessions, preferApp = context.canOpenYunlefunAuthorizationApp(),
                                    )
                                },
                            ) { Text("登录云乐坊") }
                        } else {
                            Text("请在云乐坊 App 确认登录。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = !browserBusy, onClick = {
                                manualCallbackUrl = ""
                                accountBrowser.continueSignInOnWeb(sessions, appSignInNonce)
                            }) { Text("改用浏览器") }
                            TextButton(enabled = !browserBusy, onClick = {
                                manualCallbackUrl = ""
                                accountBrowser.cancelAppSignIn(sessions, appSignInNonce)
                            }) { Text("取消本次登录") }
                        }
                        PasteAccountCallbackButton(
                            label = "粘贴并验证登录",
                            enabled = !browserBusy,
                            isExpectedCallback = YunlefunSSO::isSignInCallback,
                            onSubmit = ::completeSignIn,
                        )
                        OutlinedTextField(
                            value = manualCallbackUrl,
                            onValueChange = { manualCallbackUrl = it },
                            enabled = !browserBusy,
                            label = { Text("若未自动跳回 App，可粘贴回调完整链接 (#ylf_sso=...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (manualCallbackUrl.isNotBlank()) {
                            OutlinedButton(
                                enabled = !browserBusy,
                                onClick = { completeSignIn(manualCallbackUrl.trim()) },
                            ) { Text("验证回调并完成登录") }
                        }
                        if (browserBusy) Text("正在处理登录…")
                    } else {
                        Text(
                            text = "当前账号：${state.account?.displayName ?: state.credential?.subject ?: "已授权"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.entitlement?.let { ent ->
                            Text(
                                text = if (ent.isActiveAt()) {
                                    ent.expiresAtEpochMs?.let {
                                        "云乐坊会员 · 有效期至 ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}"
                                    } ?: "云乐坊会员：有效"
                                } else {
                                    "云乐坊会员：未开通或已到期"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ent.isActiveAt()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.profileRefreshError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !browserBusy && !state.awaitingAccountWebsite,
                                onClick = { openAccountWebsite() },
                            ) { Text(if (browserBusy) "正在打开…" else "管理云乐坊账号") }
                            TextButton(
                                enabled = !browserBusy && !state.awaitingAccountWebsite,
                                onClick = ::openAccountWebsite,
                            ) { Text("申请注销账号") }
                            OutlinedButton(
                                enabled = !state.isRefreshingProfile,
                                onClick = {
                                    scope.launch { sessions.refreshProfileAndEntitlement(libraryScope.session) }
                                },
                            ) { Text(if (state.isRefreshingProfile) "正在刷新账号资料…" else "刷新资料与会员权益") }
                            OutlinedButton(
                                onClick = {
                                    scope.launch { container.yunlefunSessionManager.signOut() }
                                },
                            ) { Text("退出登录") }
                        }
                        Text(
                            "注销需在云乐坊网站提交。提交前请核对网页显示的账号。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.awaitingAccountWebsite) {
                            if (!accountWebsiteApproved) {
                                Text("浏览器若停留在授权回调页，请复制回调链接并在这里继续。")
                                PasteAccountCallbackButton(
                                    label = "粘贴并继续",
                                    enabled = !browserBusy,
                                    isExpectedCallback = YunlefunAccountWebHandoff::isCallback,
                                    onSubmit = ::approveAccountWebsite,
                                )
                                OutlinedTextField(
                                    value = websiteCallback,
                                    onValueChange = { websiteCallback = it.take(256) },
                                    label = { Text("账号管理回调链接") },
                                    enabled = !browserBusy,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !browserBusy && (accountWebsiteApproved || websiteCallback.isNotBlank()),
                                    onClick = {
                                        if (accountWebsiteApproved) accountBrowser.resumeWebsite(sessions, AccountWebsitePurpose.ACCOUNT_SETTINGS)
                                        else approveAccountWebsite(websiteCallback.trim())
                                    },
                                ) { Text(if (accountWebsiteApproved) "继续打开账号管理" else "继续打开") }
                                TextButton(enabled = !browserBusy, onClick = {
                                    scope.launch {
                                        sessions.cancelAccountWebsite().onFailure {
                                            accountWebsiteMessage = it.message ?: "暂时无法取消授权，请重试。"
                                        }
                                    }
                                }) { Text("取消") }
                            }
                        }
                        if (state.expiredWebsitePurpose == AccountWebsitePurpose.ACCOUNT_SETTINGS) {
                            Text(
                                "账号管理授权已过期，请重新打开账号管理。",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        accountWebsiteMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = onOpenMembership) { Text("会员与权益") }
                    WeChatAccountActions(
                        authorization = container.weChatAuthorization,
                        sessions = sessions,
                        subject = state.credential?.subject,
                        revision = state.revision,
                        onOpenSignup = onOpenWeChatSignup,
                    )
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
                            modifier = Modifier.semantics { contentDescription = "账号跨端同步" },
                            enabled = state.credential != null && state.isHydrated && !state.accountCacheUnreadable &&
                                !state.isSavingSyncConsent,
                            onCheckedChange = { enabled ->
                                if (enabled) syncConfirmation = AccountSyncConfirmation.ENABLE
                                else sessions.requestSyncEnabled(false, libraryScope)
                            },
                        )
                    }

                    if (state.accountCacheUnreadable) {
                        Text(
                            "账号缓存无法读取，已暂停同步并保留原记录。当前使用本机收藏和历史。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            text = "当前账号缓存：${state.accountDocument.favorites.size} 条收藏 · ${state.accountDocument.histories.size} 条历史",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (state.usesAccountLibrary) {
                        state.lastSyncedAtMs?.let { timestamp ->
                            val date = Date(timestamp)
                            val day = DateFormat.getMediumDateFormat(context).format(date)
                            val time = DateFormat.getTimeFormat(context).format(date)
                            Text(
                                text = "上次同步 $day $time",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.isSavingSyncConsent) {
                        Text(
                            if (state.syncEnabled) "正在保存同步设置…" else "正在核对同步资格…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.deviceLibraryCopyStatus == DeviceLibraryCopyStatus.FAILED) {
                        Text(
                            "未能复制本机记录，原有记录已保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    val canSync = state.usesAccountLibrary && !state.isSyncing && !state.isSavingSyncConsent
                    val copying = state.deviceLibraryCopyStatus == DeviceLibraryCopyStatus.COPYING
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            enabled = canSync && !copying,
                            onClick = { syncConfirmation = AccountSyncConfirmation.COPY_DEVICE_LIBRARY },
                        ) {
                            Text(if (copying) "正在复制…" else "将本机记录复制到当前账号")
                        }
                        Button(
                            enabled = canSync,
                            onClick = { sessions.requestManualSync(libraryScope) },
                        ) {
                            Text(if (state.isSyncing) "正在同步…" else "立即同步")
                        }
                    }
                }
            }
            SettingsNavRow("导入与导出", "在设备间迁移收藏和浏览历史", onOpenLibraryTransfer)
        }
    }

    syncConfirmation?.let { action ->
        val enabling = action == AccountSyncConfirmation.ENABLE
        AlertDialog(
            onDismissRequest = { syncConfirmation = null },
            title = { Text(if (enabling) "启用账号同步？" else "复制到当前云乐坊账号？") },
            text = {
                Text(
                    if (enabling) {
                        "账号的收藏和浏览历史会通过腾讯云 CloudBase 跨端同步。已有本机记录可在开启后手动复制。"
                    } else {
                        "现有本机收藏和浏览历史会加入当前账号并上传到云端，本机记录也会保留。"
                    },
                )
            },
            dismissButton = { TextButton(onClick = { syncConfirmation = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    syncConfirmation = null
                    if (enabling) {
                        sessions.requestSyncEnabled(true, libraryScope)
                    } else {
                        sessions.requestDeviceLibraryCopy(libraryScope)
                    }
                }) { Text(if (enabling) "启用同步" else "复制记录") }
            },
        )
    }
}
