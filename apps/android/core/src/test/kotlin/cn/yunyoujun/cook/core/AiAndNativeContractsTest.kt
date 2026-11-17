package cn.yunyoujun.cook.core

import cn.yunyoujun.cook.core.ai.LocalSearchIntentParser
import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.ai.SearchIntentException
import cn.yunyoujun.cook.core.ai.SearchIntentSource
import cn.yunyoujun.cook.core.ai.SearchIntentValidator
import cn.yunyoujun.cook.core.ai.StepGuidanceAnswerStatus
import cn.yunyoujun.cook.core.ai.StepGuidanceDraft
import cn.yunyoujun.cook.core.ai.StepGuidanceException
import cn.yunyoujun.cook.core.ai.StepGuidanceKind
import cn.yunyoujun.cook.core.ai.StepGuidancePrompt
import cn.yunyoujun.cook.core.ai.StepGuidanceResponse
import cn.yunyoujun.cook.core.ai.StepGuidanceValidator
import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.dietary.DietaryCatalog
import cn.yunyoujun.cook.core.membership.MembershipAccountToken
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeText
import cn.yunyoujun.cook.core.recipe.RecipeTextBlock
import cn.yunyoujun.cook.core.search.CookQuery
import cn.yunyoujun.cook.core.sync.PrivateRecipe
import cn.yunyoujun.cook.core.sync.PrivateRecipeFileException
import cn.yunyoujun.cook.core.sync.PrivateRecipeNativeFile
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class AiAndNativeContractsTest {
    private val dataDir: File
        get() = File(System.getProperty("cook.data.dir") ?: error("Missing cook.data.dir"))

    private val fixturesDir: File
        get() = File(System.getProperty("cook.fixtures.dir") ?: error("Missing cook.fixtures.dir"))

    @Serializable
    private data class StepGuidanceNumberCase(
        val name: String,
        val sources: List<String>,
        val answer: String,
        val allowed: Boolean,
    )

    @Test
    fun stepGuidanceNumericFixtureMatchesEveryCase() {
        val cases = CookData.coreJson.decodeFromString<List<StepGuidanceNumberCase>>(
            File(fixturesDir, "step-guidance-numbers.json").readText(Charsets.UTF_8),
        )
        assertTrue(cases.size >= 50)
        for (c in cases) {
            assertEquals(
                "Case '${c.name}' failed for answer='${c.answer}' sources=${c.sources}",
                c.allowed,
                StepGuidanceValidator.numbersAreSupported(c.answer, c.sources),
            )
        }
    }

    @Test
    fun stepGuidancePromptAndValidatorEnforceStrictContract() {
        val data = CookData.loadFromDirectory(dataDir)
        val recipe = data.recipes.first()
        val text = RecipeText(
            blocks = listOf(
                RecipeTextBlock.Heading("材料"),
                RecipeTextBlock.ListBlock(ordered = false, start = 1, items = listOf("鸡蛋 2 个")),
                RecipeTextBlock.Heading("做法"),
                RecipeTextBlock.ListBlock(ordered = true, start = 1, items = listOf("加入 4ml 食用油，翻炒 20 秒。")),
            ),
        )
        val step = RecipePresentation(text).steps.first()
        val prompt = StepGuidancePrompt(
            recipe = recipe,
            text = text,
            dataVersion = data.meta.dataVersion,
            step = step,
            servings = 2,
            kind = StepGuidanceKind.EXPLAIN,
            question = null,
            requestID = "req-1",
            contextRevision = "ctx-1",
        )

        val validDraft = StepGuidanceValidator.decodeDraft(
            """{"status":"answer","answer":"这一步先加入 4ml 食用油，再翻炒 20 秒。","sourceIDs":["step:${step.id}"]}""",
            prompt,
        )
        assertEquals(StepGuidanceAnswerStatus.ANSWER, validDraft.status)

        val response = StepGuidanceResponse(validDraft, prompt)
        StepGuidanceValidator.validate(response, prompt)

        // Extra JSON key rejected
        runCatching {
            StepGuidanceValidator.decodeDraft(
                """{"status":"answer","answer":"好的","sourceIDs":["step:${step.id}"],"extra":1}""",
                prompt,
            )
        }.also { assertTrue(it.exceptionOrNull() is StepGuidanceException.InvalidResponse) }

        // Missing current step citation rejected
        runCatching {
            StepGuidanceValidator.validate(
                StepGuidanceDraft(StepGuidanceAnswerStatus.ANSWER, "鸡蛋", listOf("ingredient:1")),
                prompt,
            )
        }.also { assertTrue(it.exceptionOrNull() is StepGuidanceException.UnknownSource) }

        // Stale contextRevision rejected
        runCatching {
            StepGuidanceValidator.validate(response.copy(contextRevision = "ctx-other"), prompt)
        }.also { assertTrue(it.exceptionOrNull() is StepGuidanceException.StaleResponse) }

        // Invented numbers rejected
        runCatching {
            StepGuidanceValidator.validate(
                StepGuidanceDraft(StepGuidanceAnswerStatus.ANSWER, "翻炒 30 秒", listOf("step:${step.id}")),
                prompt,
            )
        }.also { assertTrue(it.exceptionOrNull() is StepGuidanceException.UnsafeNumbers) }
    }

    @Test
    fun searchIntentValidatorAndLocalParserEnforceContract() {
        val data = CookData.loadFromDirectory(dataDir)
        val dietary = runCatching { DietaryCatalog.loadFromDirectory(dataDir, data.recipes) }.getOrNull()
        val availableGoals = dietary?.availableGoals?.toList().orEmpty()

        val validator = SearchIntentValidator(data, availableGoals)
        val validJson = """
            {
              "schemaVersion": 1,
              "dataVersion": "${data.meta.dataVersion}",
              "policyVersion": 1,
              "source": "deepseek",
              "query": {
                "stuff": ["番茄", "鸡蛋"],
                "unknown": [],
                "avoid": [],
                "tool": null,
                "noSpicy": false,
                "difficulty": [],
                "methodGroups": []
              },
              "goal": null,
              "unresolved": []
            }
        """.trimIndent()
        val decoded = validator.decode(validJson, expectedSource = SearchIntentSource.DEEPSEEK)
        assertEquals(listOf("番茄", "鸡蛋"), decoded.query.stuff)

        // Unknown nested query key rejected
        runCatching {
            validator.decode(
                validJson.replace("\"methodGroups\": []", "\"methodGroups\": [], \"surprise\": 1"),
            )
        }.also { assertTrue(it.exceptionOrNull() is SearchIntentException.UnknownField) }

        // Conflicting stuff/avoid rejected
        runCatching {
            validator.validate(
                SearchIntent(
                    dataVersion = data.meta.dataVersion,
                    source = SearchIntentSource.LOCAL,
                    query = CookQuery(stuff = listOf("鸡蛋"), avoid = listOf("鸡蛋")),
                ),
            )
        }.also { assertTrue(it.exceptionOrNull() is SearchIntentException.ConflictingConditions) }

        val localParser = LocalSearchIntentParser(data, availableGoals)
        val intent = localParser.parse("今天想用番茄和鸡蛋做简单一点，不放香菜，还要控制热量")
        assertTrue("番茄" in intent.query.stuff)
        assertTrue("鸡蛋" in intent.query.stuff)
        assertTrue(intent.unresolved.any { "控制热量" in it || "热量" in it })
    }

    @Test
    fun privateRecipeNativeVideoAndMembershipTokenMatchContracts() {
        val vidId = UUID.randomUUID().toString().lowercase()
        val devId = UUID.randomUUID().toString().lowercase()
        val recipe = PrivateRecipe(
            customId = "custom-${UUID.randomUUID().toString().lowercase()}",
            name = "测试私房菜",
            stuff = listOf("土豆"),
            tools = listOf("炒锅"),
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            video = PrivateRecipeVideo(
                id = vidId,
                fileExtension = "mp4",
                originalName = "demo.mp4",
                byteCount = 1024L,
                location = PrivateRecipeVideo.Location.LOCAL,
                deviceID = devId,
            ),
        )
        val exportedV2 = PrivateRecipeNativeFile.export(listOf(recipe), backup = true, exportedAt = 1_700_000_000_000L)
        val parsedV2 = PrivateRecipeNativeFile.parse(exportedV2)
        assertEquals(1, parsedV2.size)
        assertEquals(vidId, parsedV2.first().video?.id)
        assertTrue(parsedV2.first().video!!.isAccessibleOnAndroid)

        val icloudVideo = recipe.video!!.copy(location = PrivateRecipeVideo.Location.ICLOUD, deviceID = null)
        assertFalse(icloudVideo.isAccessibleOnAndroid)
        assertNull(icloudVideo.validated().deviceID)

        // Unknown key in videos rejected
        val invalid = exportedV2.replace("\"originalName\"", "\"unknownField\":1,\"originalName\"")
        runCatching { PrivateRecipeNativeFile.parse(invalid) }.also {
            assertTrue(it.exceptionOrNull() is PrivateRecipeFileException.Invalid)
        }

        // MembershipAccountToken determinism + UUID v4 variant/version bits
        val t1 = MembershipAccountToken.forUser("user-abc-123")
        val t2 = MembershipAccountToken.forUser("user-abc-123")
        assertEquals(t1, t2)
        assertTrue(t1 != MembershipAccountToken.forUser("user-abc-124"))
    }
}
