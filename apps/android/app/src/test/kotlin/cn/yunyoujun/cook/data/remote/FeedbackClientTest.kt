package cn.yunyoujun.cook.data.remote

import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.RecommendationMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackClientTest {
    @Test
    fun postsAndroidPlatformAndRoutesTestEnvironment() = runBlocking {
        val capturedUrls = ArrayList<String>()
        val capturedBodies = ArrayList<String>()
        val client = FeedbackClient(
            rawEndpoint = "https://api.yunle.fun/cook-feedback",
            version = "2.0.0-alpha.1",
            build = "1",
            environment = "test",
            sendsSignals = true,
            transport = HttpPostTransport { url, _, jsonBody ->
                capturedUrls.add(url)
                capturedBodies.add(jsonBody)
                HttpResponsePayload(
                    statusCode = 200,
                    bodyText = """{"ok":true,"receipt":{"id":"r-1","ticketId":"tick_001","ticketUrl":"https://support.yunle.fun/tickets/tick_001"}}""",
                )
            },
        )

        assertEquals("https://api.yunle.fun/cook-feedback-test", client.endpoint)
        val receipt = client.sendAppFeedback(
            category = AppFeedbackCategory.IDEA,
            note = "希望支持工作台计时",
            contact = "user@example.com",
            recipeVersion = "abc123",
        )
        assertEquals("r-1", receipt?.id)
        assertEquals("https://support.yunle.fun/tickets/tick_001", receipt?.ticketUrl)

        val payload = Json.parseToJsonElement(capturedBodies.single()).jsonObject
        assertEquals("android", payload["platform"]?.jsonPrimitive?.content)
        assertEquals("test", payload["environment"]?.jsonPrimitive?.content)
        assertEquals("feedback", payload["action"]?.jsonPrimitive?.content)

        // Signal dedup & consent gating
        client.signalEmpty(listOf("番茄"), null, RecommendationMode.LOOSE, "abc123", consentAllowed = false)
        assertEquals(1, capturedBodies.size)
        client.signalEmpty(listOf("番茄"), null, RecommendationMode.LOOSE, "abc123", consentAllowed = true)
        client.signalEmpty(listOf("番茄"), null, RecommendationMode.LOOSE, "abc123", consentAllowed = true)
        assertEquals(2, capturedBodies.size)

        // Recipe report sends android platform too
        val recipe = Recipe(id = 1, name = "番茄炒蛋", stuff = listOf("番茄", "鸡蛋"))
        client.reportRecipeIssue(recipe, RecipeIssueKind.STUFF, "分量描述建议补充", recipeVersion = "abc123")
        assertTrue(capturedBodies.last().contains("\"platform\":\"android\""))
    }

    @Test
    fun rejectsUntrustedTicketUrls() {
        val badHost = Json.parseToJsonElement(
            """{"id":"1","ticketId":"abc","ticketUrl":"https://evil.example/tickets/abc"}""",
        ).jsonObject
        assertNull(FeedbackClient.parseTicketUrl(badHost))

        val badPath = Json.parseToJsonElement(
            """{"id":"1","ticketId":"abc","ticketUrl":"https://support.yunle.fun/other/abc"}""",
        ).jsonObject
        assertNull(FeedbackClient.parseTicketUrl(badPath))
    }

    @Test
    fun contributionCountsTheWholeStructuredNoteAndRejectsAnInvalidWebLink() {
        val draft = RecipeContributionDraft(" 番茄炒蛋 ", " https://example.com/recipe ", "")
        assertTrue(draft.canSubmit)
        assertEquals("菜谱投稿\n菜名：番茄炒蛋\n做法链接：https://example.com/recipe", draft.note)
        assertFalse(draft.copy(name = " ").canSubmit)
        assertFalse(draft.copy(source = "https://example.com/recipe https://example.com/other").canSubmit)
        assertFalse(draft.copy(source = "file:///recipe.html").canSubmit)
        assertFalse(draft.copy(details = "🍅".repeat(250)).canSubmit)
    }
}
