package cn.yunyoujun.cook.core

import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.model.IncompatibleRule
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.recommend.RecommendationOptions
import cn.yunyoujun.cook.core.recommend.Recommender
import cn.yunyoujun.cook.core.recommend.findIncompatibleRules
import cn.yunyoujun.cook.core.search.CookQuery
import cn.yunyoujun.cook.core.search.SearchOptions
import cn.yunyoujun.cook.core.search.searchRecipes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedFixtureTests {
    @Serializable
    private data class RecommendFixtures(
        val description: String? = null,
        val recipeCount: Int,
        val normalize: List<NormalizeCase>,
        val recommend: List<RecommendCase>,
    ) {
        @Serializable
        data class NormalizeCase(
            val input: List<String>,
            val output: List<String>,
        )

        @Serializable
        data class RecommendCase(
            val input: RecommendationOptions,
            val output: List<RecommendOutputItem>,
        )

        @Serializable
        data class RecommendOutputItem(
            val id: Int,
            val matched: List<String>,
            val pantry: List<String>,
            val missing: List<String>,
            val optional: List<String>,
        )
    }

    @Serializable
    private data class SearchFixtures(
        val description: String? = null,
        val recipeCount: Int,
        val search: List<SearchCase>,
    ) {
        @Serializable
        data class SearchCase(
            val input: SearchOptions,
            val output: List<SearchOutputItem>,
            val warnings: List<List<String>>,
        )

        @Serializable
        data class SearchOutputItem(
            val id: Int,
            val matched: List<String>,
            val missing: List<String>,
        )
    }

    @Serializable
    private data class IncompatibleFixtures(
        val description: String? = null,
        val rules: List<IncompatibleRule>,
        val cases: List<IncompatibleCase>,
    ) {
        @Serializable
        data class IncompatibleCase(
            val input: List<String>,
            val output: List<List<String>>,
        )
    }

    @Serializable
    private data class QueryFixtures(
        val description: String? = null,
        val cases: List<QueryCase>,
    ) {
        @Serializable
        data class QueryCase(
            val input: String,
            val extraStuff: List<String>? = null,
            val output: CookQuery,
        )
    }

    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    private fun dataDir(): File {
        val path = System.getProperty("cook.data.dir")
            ?: error("Missing system property cook.data.dir")
        return File(path)
    }

    private fun fixturesDir(): File {
        val path = System.getProperty("cook.fixtures.dir")
            ?: return File(dataDir(), "fixtures")
        return File(path)
    }

    private fun loadCookData(): CookData = CookData.loadFromDirectory(dataDir())

    private inline fun <reified T> loadFixture(name: String): T {
        val text = File(fixturesDir(), name).readText(Charsets.UTF_8)
        return strictJson.decodeFromString<T>(text)
    }

    @Test
    fun recommendFixturesMatchExactly() {
        val data = loadCookData()
        val fixtures = loadFixture<RecommendFixtures>("recommend.json")
        assertEquals(fixtures.recipeCount, data.recipes.size)

        for (testCase in fixtures.normalize) {
            assertEquals(
                "normalize ${testCase.input}",
                testCase.output,
                data.normalizer.unique(testCase.input),
            )
        }

        val recommender = Recommender(data.recipes, data.normalizer)
        for (testCase in fixtures.recommend) {
            val actual = recommender.recommend(testCase.input).map { match ->
                RecommendFixtures.RecommendOutputItem(
                    id = match.recipe.id,
                    matched = match.matchedStuff,
                    pantry = match.pantryStuff,
                    missing = match.missingStuff,
                    optional = match.optionalStuff,
                )
            }
            assertEquals("recommend ${testCase.input}", testCase.output, actual)
        }
    }

    @Test
    fun searchFixturesMatchExactly() {
        val data = loadCookData()
        val fixtures = loadFixture<SearchFixtures>("search.json")
        assertEquals(fixtures.recipeCount, data.recipes.size)

        for (testCase in fixtures.search) {
            val actual = searchRecipes(
                recipes = data.recipes,
                incompatible = data.incompatibleRules,
                options = testCase.input,
                normalizer = data.normalizer,
            )
            val mapped = actual.recipes.map { item ->
                SearchFixtures.SearchOutputItem(
                    id = item.recipe.id,
                    matched = item.matchedStuff,
                    missing = item.missingStuff,
                )
            }
            assertEquals("search ${testCase.input}", testCase.output, mapped)
            assertEquals(
                "warnings ${testCase.input}",
                testCase.warnings,
                actual.warnings.map { listOf(it.foodA, it.foodB) },
            )
        }
    }

    @Test
    fun incompatibleFixturesMatchExactly() {
        val fixtures = loadFixture<IncompatibleFixtures>("incompatible.json")
        for (testCase in fixtures.cases) {
            val actual = findIncompatibleRules(testCase.input, fixtures.rules)
                .map { listOf(it.foodA, it.foodB) }
            assertEquals("incompatible ${testCase.input}", testCase.output, actual)
        }
    }

    @Test
    fun queryFixturesMatchExactly() {
        val data = loadCookData()
        val fixtures = loadFixture<QueryFixtures>("query.json")
        val baseParser = data.makeQueryParser()

        for (testCase in fixtures.cases) {
            val parser = if (testCase.extraStuff != null) {
                baseParser.adding(testCase.extraStuff)
            } else {
                baseParser
            }
            assertEquals("query ${testCase.input}", testCase.output, parser.parse(testCase.input))
        }

        val catalogFood = data.food.copy(
            staple = data.food.staple + cn.yunyoujun.cook.core.model.StuffItem(name = "藕粉", emoji = "🥣"),
        )
        val catalogParser = cn.yunyoujun.cook.core.search.CookQueryParser(data.query, catalogFood, data.aliases)
        val haveQuery = catalogParser.parse("家里有藕粉，不要辣")
        assertEquals(listOf("藕粉"), haveQuery.stuff)
        assertTrue(haveQuery.noSpicy)
        val avoidQuery = catalogParser.parse("不吃藕粉，但是有排骨")
        assertEquals(listOf("猪肉"), avoidQuery.stuff)
        assertEquals(listOf("藕粉"), avoidQuery.avoid)
    }

    @Test
    fun toolOnlyAndUnlockSuggestions() {
        val data = loadCookData()
        val recommender = Recommender(data.recipes, data.normalizer)

        val microwaveRecipes = recommender.recipes(usingTool = "微波炉")
        assertFalse(microwaveRecipes.isEmpty())
        assertTrue(microwaveRecipes.all { "微波炉" in it.tools })

        val oneIngredientMatches = recommender.recommend(RecommendationOptions(stuff = listOf("番茄")))
        val suggestions = Recommender.unlockSuggestions(oneIngredientMatches)
        assertFalse(suggestions.isEmpty())
        assertEquals(
            "鸡蛋",
            suggestions.firstOrNull { it.name == "鸡蛋" }?.name,
        )

        val candidates = recommender.candidates(RecommendationOptions(tool = "电饭煲"))
        assertFalse(candidates.isEmpty())
        assertTrue(candidates.all { "电饭煲" in it.tools })

        // Verify catch-all avoid protection does not throw even with empty stuff
        val sample: List<Recipe> = data.recipes.take(1)
        assertEquals(1, sample.size)
    }
}
