package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.ai.StepGuidanceException
import cn.yunyoujun.cook.data.local.AiProviderMode
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.DevicePreferencesStore
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.AccountSyncUiState
import cn.yunyoujun.cook.data.remote.DeepSeekByokClient
import cn.yunyoujun.cook.data.remote.ManagedCookAIException
import cn.yunyoujun.cook.data.remote.ManagedCookAIService
import cn.yunyoujun.cook.data.remote.ManagedStepGuidanceService
import cn.yunyoujun.cook.data.remote.MembershipSession
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiSettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenAccount: (() -> Unit)? = null) {
    val sessions = container.yunlefunSessionManager
    val account by sessions.state.collectAsStateWithLifecycle()
    val search = container.managedAiService
    val guidance = container.managedStepGuidanceService
    AiSettingsScreen(
        preferences = container.preferencesStore,
        vault = container.vault,
        deepSeek = container.deepSeekClient,
        account = account,
        managedConfigured = search.isConfigured || guidance.isConfigured,
        checkAccess = { session -> checkManagedAiAccess(sessions, session, search, guidance) },
        onBack = onBack,
        onOpenAccount = onOpenAccount,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AiSettingsScreen(
    preferences: DevicePreferencesStore,
    vault: SecretVault,
    deepSeek: DeepSeekByokClient,
    account: AccountSyncUiState,
    managedConfigured: Boolean,
    checkAccess: suspend (MembershipSession) -> ManagedAiSettingsAccess,
    onBack: () -> Unit,
    onOpenAccount: (() -> Unit)? = null,
) {
    val prefs by preferences.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val session = account.membershipSession
    var check by remember(session) { mutableIntStateOf(0) }
    var access by remember(session, check, managedConfigured) { mutableStateOf<ManagedAiSettingsAccess?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var hasStoredKey by remember(vault) { mutableStateOf<Boolean?>(null) }
    var vaultMessage by remember { mutableStateOf<String?>(null) }
    var checkingKey by remember { mutableStateOf(false) }
    var savingKey by remember { mutableStateOf(false) }
    var confirmDeleteKey by rememberSaveable { mutableStateOf(false) }
    val validInput = deepSeek.validKey(keyInput.trim())
    val savedKeyName = AndroidKeystoreVault.KEY_DEEPSEEK_API_KEY

    LaunchedEffect(session, check, managedConfigured) {
        if (session != null && managedConfigured) access = checkAccess(session)
    }
    LaunchedEffect(vault, deepSeek) {
        try {
            val found = withContext(Dispatchers.IO) { vault.readSecret(savedKeyName)?.let(deepSeek::validKey) == true }
            hasStoredKey = found
            if (!found && preferences.current.aiProviderMode == AiProviderMode.BYOK_DEEPSEEK) {
                preferences.setAiProviderMode(AiProviderMode.DISABLED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            hasStoredKey = false
            vaultMessage = "暂时无法读取本机 Key。"
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("AI 辅助设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("识别方式", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProviderMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.aiProviderMode == mode,
                        enabled = when (mode) {
                            AiProviderMode.DISABLED -> true
                            AiProviderMode.BYOK_DEEPSEEK -> hasStoredKey == true
                            AiProviderMode.MANAGED_MEMBERSHIP -> access?.canSelect() == true
                        },
                        onClick = {
                            if (mode != AiProviderMode.MANAGED_MEMBERSHIP || access?.canSelect() == true) {
                                preferences.setAiProviderMode(mode)
                            } else {
                                check++
                            }
                        },
                        label = { Text(if (mode == AiProviderMode.DISABLED) "仅本地识别" else mode.title) },
                    )
                }
            }
            Text("普通搜索在本机完成。只有确认使用 AI 时才发送内容。", style = MaterialTheme.typography.bodySmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("会员服务状态", style = MaterialTheme.typography.titleSmall)
                    val result = access
                    when {
                        !managedConfigured -> Text("此构建未配置会员 AI 服务。")
                        !account.isHydrated -> Text("正在读取云乐坊账号…")
                        session == null -> {
                            Text("登录云乐坊后可核验会员资格。")
                            if (onOpenAccount != null) {
                                TextButton(onClick = onOpenAccount) { Text("登录云乐坊") }
                            }
                        }
                        result == null -> Text("正在核验会员服务…")
                        else -> {
                            Text("智能找菜 · ${result.search.displayStatus()}")
                            Text("分步指导 · ${result.guidance.displayStatus()}")
                            TextButton(onClick = { check++ }) { Text("重新核验会员服务") }
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("管理本机 API Key", style = MaterialTheme.typography.titleSmall)
                    if (hasStoredKey == true) Text("Key 已加密保存在本机。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it.take(512) },
                        label = { Text("输入新的 DeepSeek API Key") },
                        singleLine = true,
                        enabled = !checkingKey && !savingKey,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !checkingKey && !savingKey && validInput && hasStoredKey != null,
                            onClick = {
                                val trimmed = keyInput.trim()
                                savingKey = true
                                vaultMessage = null
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { vault.writeSecret(savedKeyName, trimmed) }
                                        if (hasStoredKey != true) preferences.setAiProviderMode(AiProviderMode.BYOK_DEEPSEEK)
                                        hasStoredKey = true
                                        keyInput = ""
                                        vaultMessage = "已加密保存 Key。"
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        vaultMessage = "暂时无法保存 Key，请重试。"
                                    } finally {
                                        savingKey = false
                                    }
                                }
                            },
                        ) { Text("加密保存 Key") }
                        OutlinedButton(
                            enabled = !checkingKey && !savingKey && (if (keyInput.isBlank()) hasStoredKey == true else validInput),
                            onClick = {
                                val input = keyInput.trim()
                                checkingKey = true
                                vaultMessage = null
                                scope.launch {
                                    try {
                                        val active = input.ifEmpty {
                                            withContext(Dispatchers.IO) { vault.readSecret(savedKeyName).orEmpty() }
                                        }
                                        vaultMessage = if (deepSeek.checkConnection(active)) {
                                            "连接成功，当前余额可用。"
                                        } else {
                                            "Key 可用，当前余额不足。"
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: IllegalArgumentException) {
                                        vaultMessage = error.message
                                    } catch (error: IllegalStateException) {
                                        vaultMessage = error.message
                                    } catch (_: Exception) {
                                        vaultMessage = "连接测试失败，请检查网络后重试。"
                                    } finally {
                                        checkingKey = false
                                    }
                                }
                            },
                        ) { Text(if (checkingKey) "测试中…" else "测试连接") }
                        if (hasStoredKey == true) {
                            TextButton(enabled = !checkingKey && !savingKey, onClick = { confirmDeleteKey = true }) { Text("清除 Key") }
                        }
                    }
                    vaultMessage?.let {
                        Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (confirmDeleteKey) {
        AlertDialog(
            onDismissRequest = { confirmDeleteKey = false },
            title = { Text("删除本机 DeepSeek Key？") },
            text = { Text(if (prefs.aiProviderMode == AiProviderMode.BYOK_DEEPSEEK) "删除后将切回本地识别。" else "之后可以重新添加。") },
            confirmButton = {
                TextButton(
                    enabled = !savingKey,
                    onClick = {
                        confirmDeleteKey = false
                        savingKey = true
                        vaultMessage = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { vault.deleteSecret(savedKeyName) }
                                if (preferences.current.aiProviderMode == AiProviderMode.BYOK_DEEPSEEK) {
                                    preferences.setAiProviderMode(AiProviderMode.DISABLED)
                                }
                                hasStoredKey = false
                                vaultMessage = "已清除本机 Key。"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                vaultMessage = "暂时无法删除 Key，请重试。"
                            } finally {
                                savingKey = false
                            }
                        }
                    },
                ) { Text("删除 Key") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteKey = false }) { Text("取消") } },
        )
    }
}

internal data class AiServiceStatus(val message: String, val expiresAt: Long? = null) {
    fun isReady(now: Long) = expiresAt != null && expiresAt > now
    fun displayStatus() = if (expiresAt != null && !isReady(System.currentTimeMillis())) "需要重新核验" else message
}

internal data class ManagedAiSettingsAccess(val search: AiServiceStatus, val guidance: AiServiceStatus) {
    fun canSelect(now: Long = System.currentTimeMillis()) = search.isReady(now) || guidance.isReady(now)
}

internal suspend fun checkManagedAiAccess(
    sessions: YunlefunSessionManager,
    session: MembershipSession,
    search: ManagedCookAIService,
    guidance: ManagedStepGuidanceService,
): ManagedAiSettingsAccess = coroutineScope {
    suspend fun probe(configured: Boolean, expiration: suspend (YunlefunCredential) -> Long): AiServiceStatus {
        if (!configured) return AiServiceStatus("此构建未配置")
        return try {
            val end = sessions.withMembershipSessionRetryingAuth(session) { credential ->
                try {
                    expiration(credential)
                } catch (_: ManagedCookAIException.LoginRequired) {
                    throw YunlefunAccountException.ExpiredSession()
                } catch (_: StepGuidanceException.LoginRequired) {
                    throw YunlefunAccountException.ExpiredSession()
                }
            }
            AiServiceStatus("可用", end)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: YunlefunAccountException.ExpiredSession) {
            AiServiceStatus("请重新登录")
        } catch (_: ManagedCookAIException.MembershipRequired) {
            AiServiceStatus("当前账号没有有效会员")
        } catch (_: StepGuidanceException.MembershipRequired) {
            AiServiceStatus("当前账号没有有效会员")
        } catch (_: Exception) {
            AiServiceStatus("暂时不可用")
        }
    }

    val searchResult = async {
        probe(search.isConfigured) { credential ->
            val access = search.capabilities(credential)
            if (!access.membershipIsActive) throw ManagedCookAIException.MembershipRequired()
            access.membershipExpiresAtEpochMs?.takeIf { access.enabled } ?: throw ManagedCookAIException.Unavailable()
        }
    }
    val guidanceResult = async { probe(guidance.isConfigured, guidance::checkAccess) }
    ManagedAiSettingsAccess(searchResult.await(), guidanceResult.await())
}
