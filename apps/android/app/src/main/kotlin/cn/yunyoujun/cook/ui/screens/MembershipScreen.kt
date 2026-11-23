package cn.yunyoujun.cook.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.data.remote.AccountWebsitePurpose
import cn.yunyoujun.cook.data.remote.MembershipCheckReason
import cn.yunyoujun.cook.data.remote.MembershipSession
import cn.yunyoujun.cook.data.remote.PlayLedger
import cn.yunyoujun.cook.data.remote.SUBSCRIPTION_PRODUCT_ID
import cn.yunyoujun.cook.data.remote.YunlefunAccountWebHandoff
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.platform.PlayMembershipStore
import cn.yunyoujun.cook.platform.canOpenWebMembershipCheckout
import cn.yunyoujun.cook.ui.state.AccountBrowserFlow
import cn.yunyoujun.cook.ui.state.rememberLocalCalendarClock
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MembershipScreen(
    sessions: YunlefunSessionManager,
    accountBrowser: AccountBrowserFlow,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    playMembership: PlayMembershipStore? = null,
    readLocalTime: () -> ZonedDateTime = ZonedDateTime::now,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by sessions.state.collectAsState()
    val membershipSession = state.membershipSession
    val subject = membershipSession?.subject
    val entitled = state.entitlement?.takeIf { it.userID == subject }
    val now = rememberLocalCalendarClock(updateEveryMinute = true, readLocalTime = readLocalTime)
    val currentExpiry = entitled?.takeIf { it.isActiveAt(now.toInstant().toEpochMilli()) }?.expiresAtEpochMs?.let {
        Instant.ofEpochMilli(it).atZone(now.zone).toLocalDate()
    }
    val canPurchase = remember(context) { context.canOpenWebMembershipCheckout() }
    val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
    val awaitingCallback = state.pendingWebsitePurpose == purchase
    val checkoutApproved = state.approvedWebsitePurpose == purchase
    val browserBusy by accountBrowser.busy.collectAsState()
    var callback by remember(subject) { mutableStateOf("") }
    var purchaseError by remember(subject) { mutableStateOf<String?>(null) }

    LaunchedEffect(membershipSession) {
        val current = sessions.state.value
        if (membershipSession != null && !current.awaitingMembershipReturn && !current.isCheckingMembership) {
            sessions.checkMembership(MembershipCheckReason.SCREEN, membershipSession)
        }
    }
    LaunchedEffect(awaitingCallback, checkoutApproved) {
        if (!awaitingCallback || checkoutApproved) callback = ""
    }

    fun approveCheckout(returnLink: String) {
        purchaseError = null
        accountBrowser.approveWebsite(sessions, returnLink, purchase)
    }

    fun openCheckout() {
        purchaseError = null
        accountBrowser.beginWebsite(sessions, purchase)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云乐坊会员") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { inset ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (membershipSession == null) {
                        Text("登录购买时使用的云乐坊账号，即可核对已有会员权益。")
                        Button(onClick = onOpenAccount) { Text("登录云乐坊账号") }
                    } else {
                        Text(state.account?.displayName ?: "当前云乐坊账号", style = MaterialTheme.typography.titleLarge)
                        val status = when {
                            currentExpiry != null -> "会员有效期至 $currentExpiry"
                            entitled != null -> "尚未开通会员或会员已到期"
                            state.isCheckingMembership -> "正在核对会员权益…"
                            else -> "会员权益待核对"
                        }
                        Text(status, style = MaterialTheme.typography.titleMedium)
                        if (state.isCheckingMembership) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        state.membershipMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(
                            enabled = !state.isCheckingMembership,
                            onClick = { sessions.requestMembershipRestore(membershipSession) },
                        ) { Text("核对已购会员") }
                    }
                }
            }

            if (subject != null && canPurchase) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在浏览器中用当前账号选择月付或年付方案。支付后返回云食册即可核对权益。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        enabled = !browserBusy && !awaitingCallback && !state.isCheckingMembership,
                        onClick = { openCheckout() },
                    ) { Text(if (browserBusy) "正在打开…" else if (currentExpiry != null) "前往续费" else "前往购买会员") }
                    if (awaitingCallback) {
                        if (!checkoutApproved) {
                            Text("若浏览器停留在授权回调页，可以复制该链接，在这里继续。", style = MaterialTheme.typography.bodySmall)
                            PasteAccountCallbackButton(
                                label = "粘贴并继续",
                                enabled = !browserBusy,
                                isExpectedCallback = YunlefunAccountWebHandoff::isCallback,
                                onSubmit = ::approveCheckout,
                            )
                            OutlinedTextField(
                                value = callback,
                                onValueChange = { callback = it.take(256) },
                                enabled = !browserBusy,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("购买入口回调链接") },
                                singleLine = true,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !browserBusy && (checkoutApproved || callback.isNotBlank()),
                                onClick = {
                                    if (checkoutApproved) accountBrowser.resumeWebsite(sessions, purchase)
                                    else approveCheckout(callback.trim())
                                },
                            ) { Text(if (checkoutApproved) "继续打开结账页" else "继续打开") }
                            TextButton(enabled = !browserBusy, onClick = {
                                scope.launch {
                                    sessions.cancelAccountWebsite(purchase).onFailure {
                                        purchaseError = it.message ?: "暂时无法取消授权，请重试。"
                                    }
                                }
                            }) {
                                Text("取消")
                            }
                        }
                    }
                    if (state.expiredWebsitePurpose == purchase) {
                        Text(
                            "购买授权已过期，请重新打开购买入口。",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    purchaseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            if (playMembership != null) {
                if (membershipSession != null) PlayMembershipCard(playMembership, membershipSession, now)
                else PlaySubscriptionManagement(ownsSubscription = false)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("会员可用功能", style = MaterialTheme.typography.titleMedium)
                Text("收藏和浏览历史跨平台同步")
                Text("会员智能找菜")
                if (BuildConfig.COOK_FRIDGE_VISION_ENABLED) Text("冰箱批量识图")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenPrivacy) { Text("隐私政策") }
                TextButton(onClick = onOpenTerms) { Text("使用条款") }
            }
        }
    }
}

@Composable
private fun PlayMembershipCard(play: PlayMembershipStore, identity: MembershipSession, now: ZonedDateTime) {
    val activity = LocalActivity.current as? ComponentActivity
    val scope = rememberCoroutineScope()
    val observed by play.state.collectAsState()
    val state = observed.takeIf { it.session == identity }
    val idle = state != null && !state.busy
    val sandbox = play.ledger == PlayLedger.SANDBOX

    LaunchedEffect(play, identity) { play.loadOffers(identity) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (sandbox) "Google Play 测试购买" else "Google Play 购买会员",
                style = MaterialTheme.typography.titleMedium,
            )
            if (sandbox) {
                val expiry = state?.sandboxEntitlement?.takeIf { it.isActiveAt(now.toInstant().toEpochMilli()) }?.expiresAtEpochMs?.let {
                    Instant.ofEpochMilli(it).atZone(now.zone).toLocalDate()
                }
                Text(expiry?.let { "测试会员有效期至 $it" } ?: "测试会员权益独立记录。")
            }
            val oneTimeOffers = state?.offers.orEmpty().filter { it.plan.basePlanId == null }
            val subscriptionOffers = state?.offers.orEmpty().filter { it.plan.basePlanId != null }
            if (oneTimeOffers.isNotEmpty()) {
                Text("单次购买，到期后可手动续费。", style = MaterialTheme.typography.bodySmall)
            }
            if (state?.busy == true) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (state?.awaitingCheckout == true) {
                Text("本次付款结果待核对。", style = MaterialTheme.typography.bodySmall)
            }
            if (idle && state.canStartCheckout && state.offers.any { !state.ordersCheckedFor(it.plan) }) {
                Text("部分 Google Play 订单尚未核对，请恢复购买后重试。", style = MaterialTheme.typography.bodySmall)
            }
            for (offer in oneTimeOffers) {
                Button(
                    enabled = idle && state.canPurchase(offer.plan) && activity != null,
                    onClick = { if (activity != null) scope.launch { play.purchase(identity, activity, offer) } },
                ) {
                    Text("${offer.plan.title} · ${offer.formattedPrice}")
                }
            }
            if (state?.supportsSubscriptions == true) {
                Text("自动续费", style = MaterialTheme.typography.titleSmall)
                Text("Google Play 按所选周期扣款。已有效的云乐坊会员时长不会暂停扣款。", style = MaterialTheme.typography.bodySmall)
                val subscription = state.subscription
                if (subscription != null) {
                    Text("${subscription.plan.title} · ${subscription.state.label}")
                    val periodEnd = subscription.periodExpiresAt?.let {
                        Instant.ofEpochMilli(it).atZone(now.zone).toLocalDate()
                    }
                    if (periodEnd != null) Text("Play 账期截止于 $periodEnd", style = MaterialTheme.typography.bodySmall)
                }
                val availableOffers = when {
                    !state.ownsSubscription -> subscriptionOffers
                    state.canChangePlan -> subscriptionOffers.filter { it.plan != subscription?.plan }
                    else -> emptyList()
                }
                if (state.ownsSubscription && availableOffers.isNotEmpty()) {
                    Text("下次 Play 扣款将按新方案。已付会员时长继续有效。", style = MaterialTheme.typography.bodySmall)
                }
                for (offer in availableOffers) {
                    Button(
                        enabled = idle && state.canPurchase(offer.plan) && activity != null,
                        onClick = { if (activity != null) scope.launch { play.purchase(identity, activity, offer) } },
                    ) {
                        val action = if (state.ownsSubscription) "切换为" else ""
                        Text("$action${offer.plan.title} · ${offer.formattedPrice}")
                    }
                }
            }
            if (idle && (state.offers.isEmpty() || state.offerLoadFailed) && !state.awaitingCheckout) {
                TextButton(onClick = { scope.launch { play.loadOffers(identity) } }) { Text("重新加载商品") }
            }
            OutlinedButton(enabled = idle, onClick = { play.requestRestore(identity) }) {
                Text("恢复 Google Play 购买")
            }
            PlaySubscriptionManagement(ownsSubscription = state?.ownsSubscription == true)
            state?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PlaySubscriptionManagement(ownsSubscription: Boolean) {
    val uriHandler = LocalUriHandler.current
    var manageError by remember(ownsSubscription) { mutableStateOf(false) }
    TextButton(onClick = {
        val accountUrl = "https://play.google.com/store/account/subscriptions"
        val url = if (ownsSubscription) "$accountUrl?sku=$SUBSCRIPTION_PRODUCT_ID&package=${PlayLedger.PACKAGE}"
        else accountUrl
        manageError = runCatching { uriHandler.openUri(url) }.isFailure
    }) { Text("管理 Google Play 订阅") }
    if (manageError) Text("暂时无法打开订阅管理。", color = MaterialTheme.colorScheme.error)
}
