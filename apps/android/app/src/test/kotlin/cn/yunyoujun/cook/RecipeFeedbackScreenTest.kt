package cn.yunyoujun.cook

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.data.remote.FeedbackClient
import cn.yunyoujun.cook.data.remote.HttpPostTransport
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.ui.screens.RecipeFeedbackScreen
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipeFeedbackScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val recipe = Recipe(
        id = 250,
        name = "番茄炒蛋",
        bv = "BV1AB411C7DE",
        link = "https://example.com/tomato-eggs",
    )

    @Test
    fun recreatingDuringAReportKeepsTheRequestAndPreservesTheDraftForMailAndRetry() {
        val firstResponse = CompletableDeferred<HttpResponsePayload>()
        val requests = mutableListOf<JsonObject>()
        val client = FeedbackClient(
            rawEndpoint = "https://example.com/cook-feedback",
            environment = "production",
            transport = HttpPostTransport { _, _, body ->
                requests.add(Json.parseToJsonElement(body).jsonObject)
                if (requests.size == 1) firstResponse.await() else HttpResponsePayload(200, """{"ok":true}""")
            },
        )
        val content: @Composable () -> Unit = { CookTheme { RecipeFeedbackScreen(recipe, client, "fixture", onBack = {}) } }
        compose.setContent(content)

        val linkKind = compose.onNodeWithText("图文链接打不开")
        val reason = compose.onNodeWithText("打开的不是这道菜")
        val note = compose.onNodeWithTag("recipe-feedback.note")
        linkKind.performScrollTo().performClick()
        reason.performScrollTo().performClick()
        note.performScrollTo().performTextReplacement("链接打开后是另一道菜")
        compose.onNodeWithText("提交反馈").performScrollTo().performClick()
        compose.onNodeWithText("正在提交…").assertIsNotEnabled()

        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { it.setContent(content = content) }
        compose.onNodeWithText("正在提交…").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, requests.size) }
        firstResponse.complete(HttpResponsePayload(503, """{"ok":false}"""))

        compose.onNodeWithText("暂时没能提交，内容已保留，可以重试。").performScrollTo().assertIsDisplayed()
        linkKind.performScrollTo().assertIsSelected()
        reason.performScrollTo().assertIsSelected()
        note.performScrollTo().assertTextContains("链接打开后是另一道菜")
        compose.onNodeWithText("打开带菜谱信息的邮件草稿").performScrollTo().performClick()
        val mail = requireNotNull(shadowOf(compose.activity).nextStartedActivity)
        assertEquals(Intent.ACTION_SENDTO, mail.action)
        assertEquals("mailto", mail.data?.scheme)
        val mailDraft = requireNotNull(mail.data?.schemeSpecificPart)
        assertTrue(mailDraft.contains("菜谱：番茄炒蛋（#250）"))
        assertTrue(mailDraft.contains("链接打开后是另一道菜"))
        assertTrue(mailDraft.contains("打开的不是这道菜"))
        assertTrue(mailDraft.contains(recipe.link!!))
        assertTrue(mailDraft.contains(recipe.videoUrl!!))

        compose.onNodeWithText("提交反馈").performScrollTo().performClick()
        compose.onNodeWithText("收到了，谢谢反馈").assertIsDisplayed()
        compose.onNodeWithText("提交反馈").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1])
            val sent = requests.first()
            assertEquals(250, sent.getValue("recipeId").jsonPrimitive.int)
            assertEquals("text", sent.getValue("kind").jsonPrimitive.content)
            assertEquals("wrong-content", sent.getValue("linkIssue").jsonPrimitive.content)
            assertEquals(recipe.link, sent.getValue("sourceLink").jsonPrimitive.content)
        }
    }

    @Test
    fun testReportsRequireAnExplanationForOtherIssuesAndDoNotOfferProductionMail() {
        val client = FeedbackClient(
            rawEndpoint = "https://api.yunle.fun/cook-feedback",
            environment = "test",
            transport = HttpPostTransport { url, _, body ->
                assertEquals("https://api.yunle.fun/cook-feedback-test", url)
                val report = Json.parseToJsonElement(body).jsonObject
                assertEquals("other", report.getValue("kind").jsonPrimitive.content)
                assertEquals("", report.getValue("linkIssue").jsonPrimitive.content)
                HttpResponsePayload(503, """{"ok":false}""")
            },
        )
        compose.setContent { CookTheme { RecipeFeedbackScreen(recipe, client, "fixture", onBack = {}) } }

        compose.onNodeWithText("其他问题").performScrollTo().performClick()
        val submit = compose.onNodeWithText("提交测试反馈")
        val note = compose.onNodeWithTag("recipe-feedback.note")
        submit.performScrollTo().assertIsNotEnabled()
        note.performScrollTo().performTextReplacement(" ")
        submit.performScrollTo().assertIsNotEnabled()
        note.performScrollTo().performTextReplacement("需要核对原文")
        submit.performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("暂时没能提交，内容已保留，可以重试。").performScrollTo().assertIsDisplayed()
        note.performScrollTo().assertTextContains("需要核对原文")
        compose.onNodeWithText("打开带菜谱信息的邮件草稿").assertDoesNotExist()
    }
}
