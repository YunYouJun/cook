package cn.yunyoujun.cook

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cn.yunyoujun.cook.data.remote.FeedbackClient
import cn.yunyoujun.cook.data.remote.HttpPostTransport
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.ui.screens.AppFeedbackPurpose
import cn.yunyoujun.cook.ui.screens.AppFeedbackScreen
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppFeedbackScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun contributionValidatesTheSourceAndRetriesTheSameSubmissionAfterAnAmbiguousFailure() {
        val sent = mutableListOf<JsonObject>()
        val client = FeedbackClient(
            rawEndpoint = "https://example.com/cook-feedback",
            environment = "production",
            transport = HttpPostTransport { _, _, body ->
                sent.add(Json.parseToJsonElement(body).jsonObject)
                if (sent.size == 1) HttpResponsePayload(503, """{"ok":false,"code":"SUPPORT_UNAVAILABLE"}""")
                else HttpResponsePayload(
                    200,
                    """{"ok":true,"receipt":{"id":"r-1","ticketId":"qa_fixture","ticketUrl":"https://support.yunle.fun/tickets/qa_fixture"}}""",
                )
            },
        )
        show(AppFeedbackPurpose.CONTRIBUTION, client)

        val submit = compose.onNodeWithText("提交菜谱")
        val name = compose.onNodeWithTag("contribution.name")
        val source = compose.onNodeWithTag("contribution.source")
        val details = compose.onNodeWithTag("contribution.details")
        compose.onNodeWithText("反馈类型").assertDoesNotExist()
        submit.assertIsNotEnabled()
        name.performTextReplacement("Tomato eggs")
        source.performTextReplacement("example.com/recipe")
        submit.assertIsNotEnabled()
        source.performTextReplacement("https://example.com/recipe")
        details.performScrollTo().performTextReplacement("Easy to cook")
        submit.performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("暂时没能提交").assertIsDisplayed()
        compose.onNodeWithText("去支持中心").assertIsDisplayed()
        compose.onNodeWithText("打开邮件草稿").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()

        name.assertTextContains("Tomato eggs")
        source.assertTextContains("https://example.com/recipe")
        details.assertTextContains("Easy to cook")
        submit.performScrollTo().performClick()
        compose.onNodeWithText("投稿已收到").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("反馈编号：r-1").assertIsDisplayed()
        compose.onNodeWithText("查看私密工单与回复").assertIsDisplayed()
        submit.assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(2, sent.size)
            assertEquals("recipe", sent.first().getValue("category").jsonPrimitive.content)
            assertEquals("菜谱投稿\n菜名：Tomato eggs\n做法链接：https://example.com/recipe\n补充说明：Easy to cook",
                sent.first().getValue("note").jsonPrimitive.content)
            assertEquals(sent[0].getValue("submissionId"), sent[1].getValue("submissionId"))
        }
    }

    @Test
    fun debugFeedbackKeepsDishRequestsInTheTestQueueWithoutOpeningProductionFallbacks() {
        val client = FeedbackClient(
            rawEndpoint = "https://api.yunle.fun/cook-feedback",
            environment = "test",
            transport = HttpPostTransport { url, _, _ ->
                assertEquals("https://api.yunle.fun/cook-feedback-test", url)
                HttpResponsePayload(503, """{"ok":false}""")
            },
        )
        show(AppFeedbackPurpose.FEEDBACK, client)

        compose.onNodeWithText("想要的菜没有").performClick()
        compose.onNodeWithTag("contribution.name").assertDoesNotExist()
        val note = compose.onNodeWithTag("feedback.note")
        note.performTextReplacement("Keep this test draft")
        val submit = compose.onNodeWithText("发送反馈")
        submit.performScrollTo().performClick()
        compose.onNodeWithText("测试内容已保留，可以稍后重试。").assertIsDisplayed()
        compose.onNodeWithText("去支持中心").assertDoesNotExist()
        compose.onNodeWithText("打开邮件草稿").assertDoesNotExist()
        compose.onNodeWithText("复制邮箱和内容").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        note.assertTextContains("Keep this test draft")
        submit.assertIsEnabled()
    }

    private fun show(purpose: AppFeedbackPurpose, client: FeedbackClient) {
        compose.setContent {
            CookTheme { AppFeedbackScreen(purpose, client, "fixture", onOpenPrivacy = {}, onBack = {}) }
        }
    }
}
