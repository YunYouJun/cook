package cn.yunyoujun.cook.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.data.remote.AccountWebsitePurpose
import cn.yunyoujun.cook.data.remote.MembershipCheckReason
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.platform.canOpenWebMembershipCheckout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MembershipScreen(
    sessions: YunlefunSessionManager,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by sessions.state.collectAsState()
    val subject = state.credential?.subject
    val entitled = state.entitlement?.takeIf { it.userID == subject }
    val currentExpiry = entitled?.takeIf { it.isActiveAt() }?.expiresAtEpochMs?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val canPurchase = remember(context) { context.canOpenWebMembershipCheckout() }
    val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
    val awaitingCallback = state.pendingWebsitePurpose == purchase
    var opening by remember(subject) { mutableStateOf(false) }
    var callback by remember(subject) { mutableStateOf("") }
    var purchaseError by remember(subject) { mutableStateOf<String?>(null) }

    LaunchedEffect(subject) {
        if (subject != null) sessions.checkMembership(MembershipCheckReason.SCREEN)
    }
    LaunchedEffect(awaitingCallback) {
        if (!awaitingCallback) callback = ""
    }

    fun openCheckout(callbackUrl: String? = null) {
        opening = true
        purchaseError = null
        scope.launch {
            try {
                val open: (String) -> Unit = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                if (callbackUrl == null) open(sessions.beginAccountWebsite(purchase))
                else sessions.openAccountWebsiteFromCallback(callbackUrl, purchase, open)
            } catch (error: CancellationException) {
                throw error
            } catch (_: ActivityNotFoundException) {
                sessions.cancelAccountWebsite(purchase)
                purchaseError = "未找到可用的系统浏览器。"
            } catch (_: YunlefunAccountException.InvalidWebsiteCallback) {
                purchaseError = "购买入口授权无效或已过期，请重新打开。"
            } catch (error: YunlefunAccountException) {
                purchaseError = error.message
            } catch (_: Exception) {
                purchaseError = "暂时无法打开会员购买入口，请重试。"
            } finally {
                opening = false
            }
        }
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
                    if (subject == null) {
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
                            onClick = { scope.launch { sessions.checkMembership(MembershipCheckReason.RESTORE) } },
                        ) { Text("核对已购会员") }
                    }
                }
            }

            if (subject != null && canPurchase) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在浏览器中用当前账号选择「去开通」或「续费」。支付后返回云食册即可核对权益。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        enabled = !opening && !awaitingCallback && !state.isCheckingMembership,
                        onClick = { openCheckout() },
                    ) { Text(if (opening) "正在打开…" else if (currentExpiry != null) "前往续费" else "前往购买会员") }
                    if (awaitingCallback) {
                        Text("若浏览器停留在授权回调页，可以复制该链接，在这里继续。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = callback,
                            onValueChange = { callback = it.take(256) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("购买入口回调链接") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = callback.isNotBlank() && !opening, onClick = {
                                openCheckout(callback.trim())
                            }) { Text("继续打开") }
                            TextButton(enabled = !opening, onClick = { scope.launch { sessions.cancelAccountWebsite(purchase) } }) {
                                Text("取消")
                            }
                        }
                    }
                    purchaseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("会员可用功能", style = MaterialTheme.typography.titleMedium)
                Text("收藏和浏览历史跨平台同步")
                Text("会员智能找菜")
                Text("冰箱批量识图")
            }
        }
    }
}
