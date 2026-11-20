package cn.yunyoujun.cook

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.MembershipService
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunAccountWebHandoff
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.ui.screens.MembershipScreen
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MembershipScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun checkExistingPurchaseOnEveryBuildAndOpenTheAccountHandoffOnDirectBuild() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, "com.android.shell", "com.android.shell")
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        val credential = YunlefunCredential("access", "refresh", "user", Long.MAX_VALUE, "device")
        val vault = object : SecretVault {
            override fun readSecret(key: String): String? =
                Json.encodeToString(credential).takeIf { key == AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON }
            override fun writeSecret(key: String, value: String) = Unit
            override fun deleteSecret(key: String) = Unit
        }
        val expiry = AtomicLong()
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String, url: String, headers: Map<String, String>, jsonBody: String?,
                maxResponseBytes: Int, connectTimeoutMs: Int, readTimeoutMs: Int,
            ): HttpResponsePayload = when (url) {
                YunlefunAccountWebHandoff.ENDPOINT -> HttpResponsePayload(200, """{"supported":true,"systemBrowser":true}""")
                "${MembershipService.FUNCTIONS_GATEWAY}account-api" -> {
                    val value = expiry.get()
                    HttpResponsePayload(200, """{"membership":{"isActive":${value > 0},"expireAt":$value}}""")
                }
                else -> error("Unexpected URL $url")
            }
        }
        val sessions = YunlefunSessionManager(
            vault, database, YunlefunAccountService(transport), membershipService = MembershipService(transport),
        )
        try {
            compose.setContent {
                CookTheme { MembershipScreen(sessions, onBack = {}, onOpenAccount = {}) }
            }
            compose.waitUntil(timeoutMillis = 5_000) { sessions.state.value.entitlement != null }
            compose.onNodeWithText("尚未开通会员或会员已到期").assertIsDisplayed()
            if (BuildConfig.COOK_WEB_MEMBERSHIP_CHECKOUT) {
                compose.onNodeWithText("前往购买会员").performClick()
                compose.waitUntil(timeoutMillis = 5_000) { sessions.state.value.pendingWebsitePurpose != null }
                val intent = shadowOf(compose.activity).nextStartedActivity
                assertEquals("www.yunle.fun", intent.data?.host)
                assertEquals("/api/session/cook-handoff", intent.data?.path)
                assertTrue(intent.dataString.orEmpty().contains("transport=browser"))
            } else {
                compose.onNodeWithText("前往购买会员").assertDoesNotExist()
            }
            val newExpiry = System.currentTimeMillis() + 86_400_000L
            expiry.set(newExpiry)
            compose.onNodeWithText("核对已购会员").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { sessions.state.value.entitlement?.expiresAtEpochMs == newExpiry }
            compose.onNodeWithText("当前账号的会员权益已核对。").assertIsDisplayed()
        } finally {
            database.close()
        }
    }
}
