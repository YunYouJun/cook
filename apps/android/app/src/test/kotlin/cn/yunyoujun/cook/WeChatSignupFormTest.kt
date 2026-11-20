package cn.yunyoujun.cook

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.ui.screens.WeChatSignupForm
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeChatSignupFormTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun phoneVerificationOnlyEnablesAnExplicitCreateAccountAction() {
        val devices = mutableListOf<String>()
        var registration: Pair<String, String>? = null
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                jsonBody: String?,
                maxResponseBytes: Int,
                connectTimeoutMs: Int,
                readTimeoutMs: Int,
            ): HttpResponsePayload {
                devices += requireNotNull(headers["x-device-id"])
                val body = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                return when (body.getValue("action").jsonPrimitive.content) {
                    "sendCookSignupSms" -> HttpResponsePayload(200, """{"ok":true,"challenge":"sms"}""")
                    "verifyCookSignupSms" -> HttpResponsePayload(200, """{"ok":true,"next":"confirm","proof":"verified"}""")
                    else -> error("Sign-up must wait for the explicit action")
                }
            }
        }
        compose.setContent {
            CookTheme {
                WeChatSignupForm(
                    service = YunlefunAccountService(transport),
                    register = { proof, id -> registration = proof to id },
                    onBack = {},
                    onCancelAuthorization = null,
                )
            }
        }
        compose.onNodeWithText("中国大陆手机号").performTextInput("13900000000")
        compose.onNodeWithText("发送短信验证码").performClick()
        awaitText("短信验证码")
        compose.onNodeWithText("短信验证码").performTextInput("123456")
        compose.onNodeWithText("验证手机号").performClick()
        awaitText("创建账号并授权微信")
        compose.runOnIdle {
            assertNull(registration)
            assertEquals(2, devices.size)
            assertEquals(devices.first(), devices.last())
        }
        compose.onNodeWithText("创建账号并授权微信").performClick()
        compose.runOnIdle { assertEquals("verified" to devices.first(), registration) }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
