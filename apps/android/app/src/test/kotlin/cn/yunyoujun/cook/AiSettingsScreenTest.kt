package cn.yunyoujun.cook

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cn.yunyoujun.cook.data.local.AiProviderMode
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.DevicePreferencesStore
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.AccountSyncUiState
import cn.yunyoujun.cook.data.remote.DeepSeekByokClient
import cn.yunyoujun.cook.data.remote.MembershipSession
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.ui.screens.AiServiceStatus
import cn.yunyoujun.cook.ui.screens.AiSettingsScreen
import cn.yunyoujun.cook.ui.screens.ManagedAiSettingsAccess
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiSettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun providerAvailabilityFollowsTheCurrentAccountAndEitherManagedServiceCanEnableSelection() {
        val preferences = preferences()
        val account = mutableStateOf(AccountSyncUiState())
        val firstResult = CompletableDeferred<ManagedAiSettingsAccess>()
        val firstStarted = CompletableDeferred<Unit>()
        val ready = AiServiceStatus("可用", System.currentTimeMillis() + 60_000)
        var openedAccount = false
        show(preferences, TestVault(), account, onOpenAccount = { openedAccount = true }) { session ->
            if (session.subject == "first-account") {
                firstStarted.complete(Unit)
                withContext(NonCancellable) { firstResult.await() }
            } else {
                ManagedAiSettingsAccess(AiServiceStatus("暂时不可用"), ready)
            }
        }
        val managed = compose.onNodeWithText("云乐坊会员托管 DeepSeek")
        managed.assertIsNotEnabled()
        compose.onNodeWithText("登录云乐坊").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(openedAccount)
            account.value = signedIn("first-account", 1)
        }
        compose.onNodeWithText("正在核验会员服务…").assertExists()
        compose.runOnIdle { assertTrue(firstStarted.isCompleted) }
        managed.assertIsNotEnabled()

        compose.runOnIdle { account.value = signedIn("second-account", 2) }
        compose.onNodeWithText("智能找菜 · 暂时不可用").assertExists()
        compose.onNodeWithText("分步指导 · 可用").assertExists()
        firstResult.complete(ManagedAiSettingsAccess(ready, AiServiceStatus("暂时不可用")))
        compose.onNodeWithText("分步指导 · 可用").assertExists()
        managed.assertIsEnabled().performClick().assertIsSelected()
        assertEquals(AiProviderMode.MANAGED_MEMBERSHIP, preferences.current.aiProviderMode)

        compose.runOnIdle { account.value = AccountSyncUiState() }
        managed.assertIsNotEnabled()
        compose.onNodeWithText("登录云乐坊").assertExists()
    }

    @Test
    fun savingFirstPersonalKeyEnablesItAndConfirmedRemovalRestoresLocalRecognition() {
        val preferences = preferences()
        val vault = TestVault()
        val keyName = AndroidKeystoreVault.KEY_DEEPSEEK_API_KEY
        show(preferences, vault, mutableStateOf(AccountSyncUiState())) { error("Cannot probe without an account") }
        val personal = compose.onNodeWithText("个人 DeepSeek Key")
        val local = compose.onNodeWithText("仅本地识别")
        personal.assertIsNotEnabled()

        compose.onNodeWithText("输入新的 DeepSeek API Key").performScrollTo().performTextReplacement("sk-fixture")
        compose.onNodeWithText("加密保存 Key").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            preferences.current.aiProviderMode == AiProviderMode.BYOK_DEEPSEEK && vault.readSecret(keyName) == "sk-fixture"
        }
        personal.performScrollTo().assertIsSelected()
        assertEquals("sk-fixture", vault.readSecret(keyName))
        assertEquals(AiProviderMode.BYOK_DEEPSEEK, preferences.current.aiProviderMode)

        val clear = compose.onNodeWithText("清除 Key")
        clear.performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals("sk-fixture", vault.readSecret(keyName))

        clear.performScrollTo().performClick()
        compose.onNodeWithText("删除 Key").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            preferences.current.aiProviderMode == AiProviderMode.DISABLED && vault.readSecret(keyName) == null
        }
        assertNull(vault.readSecret(keyName))
        assertEquals(AiProviderMode.DISABLED, preferences.current.aiProviderMode)
        local.performScrollTo().assertIsSelected()
        personal.assertIsNotEnabled()
    }

    private fun preferences() = DevicePreferencesStore(compose.activity.applicationContext).apply {
        setAiProviderMode(AiProviderMode.DISABLED)
    }

    private fun show(
        preferences: DevicePreferencesStore,
        vault: SecretVault,
        account: MutableState<AccountSyncUiState>,
        onOpenAccount: () -> Unit = {},
        checkAccess: suspend (MembershipSession) -> ManagedAiSettingsAccess,
    ) {
        val deepSeek = DeepSeekByokClient()
        compose.setContent {
            CookTheme {
                AiSettingsScreen(preferences, vault, deepSeek, account.value, true, checkAccess, {}, onOpenAccount)
            }
        }
    }

    private fun signedIn(subject: String, revision: Long) = AccountSyncUiState(
        credential = YunlefunCredential(
            accessToken = "fixture",
            refreshToken = "fixture-refresh",
            subject = subject,
            expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            deviceID = "fixture-device",
        ),
        revision = revision,
    )

    private class TestVault : SecretVault {
        private val values = ConcurrentHashMap<String, String>()
        override fun readSecret(key: String): String? = values[key]
        override fun writeSecret(key: String, value: String) { values[key] = value }
        override fun deleteSecret(key: String) { values.remove(key) }
    }
}
