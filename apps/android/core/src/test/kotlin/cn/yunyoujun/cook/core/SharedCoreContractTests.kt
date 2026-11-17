package cn.yunyoujun.cook.core

import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.dietary.DietaryCatalog
import cn.yunyoujun.cook.core.dietary.DietaryCatalogException
import cn.yunyoujun.cook.core.dietary.InstitutionalLowGICatalog
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.recipe.RecipeAmountAnnotations
import cn.yunyoujun.cook.core.recipe.RecipeAmounts
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeStepUsagePlan
import cn.yunyoujun.cook.core.recipe.RecipeText
import cn.yunyoujun.cook.core.recipe.RecipeTextBlock
import cn.yunyoujun.cook.core.recipe.RecipeTextLibrary
import cn.yunyoujun.cook.core.recommend.RecommendationOptions
import cn.yunyoujun.cook.core.recommend.Recommender
import cn.yunyoujun.cook.core.sync.PersonalCatalog
import cn.yunyoujun.cook.core.sync.PersonalCatalogException
import cn.yunyoujun.cook.core.sync.PrivateRecipeFile
import cn.yunyoujun.cook.core.sync.PrivateRecipeFileException
import cn.yunyoujun.cook.core.sync.PrivateRecipeImport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedCoreContractTests {
    @Serializable
    private data class RecipeTextBlocksFixtures(
        val description: String? = null,
        val blockDecodeCase: BlockDecodeCase,
        val unknownSectionsCase: UnknownSectionsCase,
        val bundledRecipe250: BundledRecipe250,
    ) {
        @Serializable
        data class BlockDecodeCase(
            val rawJson: String,
            val expectedTypes: List<String>,
            val expectedOrderedListStart: Int,
            val expectedImageCount: Int,
        )

        @Serializable
        data class UnknownSectionsCase(
            val rawBlocks: List<RecipeTextBlock>,
            val expectedDifficultyNull: Boolean,
            val expectedQuantitiesCount: Int,
            val expectedStepIds: List<String>,
            val expectedLastStepText: String,
            val prefixParagraphText: String,
        )

        @Serializable
        data class BundledRecipe250(
            val id: Int,
            val expectedSourceFingerprint: String,
            val expectedDifficulty: Int,
            val expectedCalories: String,
            val expectedIngredientsCount: Int,
            val expectedStepCount: Int,
            val expectedAmounts: Map<String, String>,
            val expectedMissingAmountFor: String,
        )
    }

    @Serializable
    private data class RecipeAmountsScalingFixtures(
        val description: String? = null,
        val bundledTomato250: BundledTomato250,
        val bundledInstructionExamples: List<InstructionExample>,
        val bundledAllocationChecks: List<BundledAllocationCheck>,
        val syntheticUsageCases: List<SyntheticUsageCase>,
        val unsupportedInstructionExpressions: List<String>,
    ) {
        @Serializable
        data class ServingsCase(
            val servings: Int,
            val eggs: String,
            val oil: String,
        )

        @Serializable
        data class BundledTomato250(
            val id: Int,
            val servingsCases: List<ServingsCase>,
            val servings3Details: Map<String, String>,
        )

        @Serializable
        data class InstructionExample(
            val id: Int,
            val servings: Int,
            val expression: String,
            val expected: String,
        )

        @Serializable
        data class BundledAllocationCheck(
            val id: Int,
            val block: Int,
            val item: Int,
            val servings: Int,
            val contains: List<String> = emptyList(),
            val exactText: String? = null,
            val expectUnresolved: Boolean,
        )

        @Serializable
        data class SyntheticUsageCase(
            val servings: Int,
            val quantities: List<String>,
            val steps: List<String>,
            val expectedTexts: List<String>,
            val expectedUnresolvedLast: Boolean,
        )
    }

    @Serializable
    private data class DietaryContractsFixtures(
        val description: String? = null,
        val sha256Vectors: List<Sha256Vector>,
        val recipeFingerprintCases: List<RecipeFingerprintCase>,
        val bundledDietarySummary: BundledDietarySummary,
        val bundledInstitutionalLowGI: BundledInstitutionalLowGI,
    ) {
        @Serializable
        data class Sha256Vector(
            val input: String,
            val sha256: String,
        )

        @Serializable
        data class RecipeFingerprintCase(
            val id: Int,
            val expectedFingerprint: String,
        )

        @Serializable
        data class BundledDietarySummary(
            val entryCount: Int,
            val rejectedEntryCount: Int,
            val availableGoals: List<DietaryGoal>,
            val lessAddedSugarCount: Int,
            val weightAwareCount: Int,
            val keywordSpinachLessAddedSugarIds: List<Int>,
            val noSpicyExcludedBalancedPairingIds: List<Int>,
            val avoidPorkExcludedLessAddedSugarIds: List<Int>,
            val avoidPorkExcludedBalancedPairingIds: List<Int>,
        )

        @Serializable
        data class PermitsCase(
            val id: Int,
            val avoid: List<String>,
            val noSpicy: Boolean,
            val expected: Boolean,
        )

        @Serializable
        data class BundledInstitutionalLowGI(
            val entryCount: Int,
            val expectedIds: List<Int>,
            val permitsCases: List<PermitsCase>,
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

    private inline fun <reified T> loadFixture(name: String): T =
        strictJson.decodeFromString(File(fixturesDir(), name).readText(Charsets.UTF_8))

    @Test
    fun recipeTextBlocksAndPresentationFixturesMatch() {
        val fixtures = loadFixture<RecipeTextBlocksFixtures>("recipe-text-blocks.json")
        val decoded = CookData.coreJson.decodeFromString<RecipeText>(fixtures.blockDecodeCase.rawJson)
        val decodedTypes = decoded.blocks.map { block ->
            when (block) {
                is RecipeTextBlock.Heading -> "heading"
                is RecipeTextBlock.ListBlock -> "list"
                is RecipeTextBlock.ImageBlock -> "image"
                is RecipeTextBlock.Paragraph -> "paragraph"
            }
        }
        assertEquals(fixtures.blockDecodeCase.expectedTypes, decodedTypes)
        assertEquals(
            fixtures.blockDecodeCase.expectedOrderedListStart,
            (decoded.blocks[1] as RecipeTextBlock.ListBlock).start,
        )
        assertEquals(fixtures.blockDecodeCase.expectedImageCount, decoded.imageCount)

        val unknownText = RecipeText(blocks = fixtures.unknownSectionsCase.rawBlocks)
        val unknownPres = RecipePresentation(unknownText)
        if (fixtures.unknownSectionsCase.expectedDifficultyNull) {
            assertNull(unknownPres.difficulty)
        }
        assertEquals(fixtures.unknownSectionsCase.expectedQuantitiesCount, unknownPres.quantities.size)
        assertEquals(fixtures.unknownSectionsCase.expectedStepIds, unknownPres.steps.map { it.id })
        assertEquals(fixtures.unknownSectionsCase.expectedLastStepText, unknownPres.steps.lastOrNull()?.text)

        val prefixedText = RecipeText(
            blocks = listOf(RecipeTextBlock.Paragraph(fixtures.unknownSectionsCase.prefixParagraphText)) + unknownText.blocks,
        )
        assertEquals(unknownPres.steps.map { it.id }, RecipePresentation(prefixedText).steps.map { it.id })

        val library = RecipeTextLibrary.loadFromDirectory(dataDir())
        assertNotNull(library)
        val data = CookData.loadFromDirectory(dataDir())
        val linkedCount = data.recipes.count { it.link?.startsWith("https://github.com/Anduin2017/HowToCook/") == true }
        assertEquals(linkedCount, library!!.count)

        val recipe250 = library.text(fixtures.bundledRecipe250.id)!!
        assertEquals(fixtures.bundledRecipe250.expectedSourceFingerprint, RecipeAmountAnnotations.fingerprint(recipe250))
        val pres250 = RecipePresentation(recipe250)
        assertEquals(fixtures.bundledRecipe250.expectedDifficulty, pres250.difficulty)
        assertEquals(fixtures.bundledRecipe250.expectedCalories, pres250.calories)
        assertEquals(fixtures.bundledRecipe250.expectedIngredientsCount, pres250.ingredients.size)
        assertEquals(fixtures.bundledRecipe250.expectedStepCount, pres250.steps.size)
        for ((ingredient, expectedAmount) in fixtures.bundledRecipe250.expectedAmounts) {
            assertEquals(expectedAmount, pres250.amount(ingredient))
        }
        assertNull(pres250.amount(fixtures.bundledRecipe250.expectedMissingAmountFor))

        // Drifted recipe source must fall back to null annotations
        val drifted = recipe250.copy(blocks = recipe250.blocks + RecipeTextBlock.Paragraph("Drifted"))
        assertNull(RecipePresentation(drifted).amountAnnotations)
    }

    @Test
    fun recipeAmountsScalingAndUsagePlanFixturesMatch() {
        val fixtures = loadFixture<RecipeAmountsScalingFixtures>("recipe-amounts-scaling.json")
        val library = RecipeTextLibrary.loadFromDirectory(dataDir())!!

        val pres250 = RecipePresentation(library.text(fixtures.bundledTomato250.id)!!)
        for (sc in fixtures.bundledTomato250.servingsCases) {
            val amounts = RecipeAmounts(pres250.allQuantities, sc.servings)
            assertTrue(amounts.supportsServings)
            assertEquals(sc.eggs, amounts.amount("鸡蛋")?.text)
            assertEquals(sc.oil, amounts.amount("食用油")?.text)
        }
        val three = RecipeAmounts(pres250.allQuantities, 3)
        for ((key, expected) in fixtures.bundledTomato250.servings3Details) {
            if (key == "eggNote") {
                assertEquals(expected, three.amount("鸡蛋")?.note)
            } else {
                assertEquals(expected, three.amount(key)?.text)
            }
        }

        for (ex in fixtures.bundledInstructionExamples) {
            val pres = RecipePresentation(library.text(ex.id)!!)
            val sourceStep = pres.steps.first { ex.expression in it.text }.text
            val rendered = RecipeAmounts(pres.allQuantities, ex.servings).instruction(sourceStep)
            assertEquals(sourceStep.replace(ex.expression, ex.expected), rendered.text)
            assertTrue(rendered.hasAdjustedAmounts)
            assertFalse(rendered.hasUnresolvedFormula)
        }

        for (check in fixtures.bundledAllocationChecks) {
            val text = library.text(check.id)!!
            val pres = RecipePresentation(text)
            val amounts = RecipeAmounts(pres.allQuantities, check.servings)
            val plan = RecipeStepUsagePlan(pres.steps, amounts, pres.amountAnnotations)
            val step = pres.steps.first { it.sourceBlock == check.block && it.sourceItem == check.item }
            val result = plan.instruction(step)!!
            check.exactText?.let { assertEquals(it, result.text) }
            for (fragment in check.contains) {
                assertTrue("Expected '$fragment' in '${result.text}'", fragment in result.text)
            }
            assertEquals(
                "Mismatch expectUnresolved for id=${check.id} block=${check.block} item=${check.item}: '${result.text}'",
                check.expectUnresolved,
                result.hasUnresolvedFormula,
            )
        }

        for (syn in fixtures.syntheticUsageCases) {
            val fakeText = RecipeText(
                blocks = listOf(
                    RecipeTextBlock.Heading("计算"),
                    RecipeTextBlock.ListBlock(ordered = false, start = 1, items = syn.quantities),
                    RecipeTextBlock.Heading("操作"),
                    RecipeTextBlock.ListBlock(ordered = true, start = 1, items = syn.steps),
                ),
            )
            val pres = RecipePresentation(fakeText)
            val plan = RecipeStepUsagePlan(pres.steps, RecipeAmounts(pres.allQuantities, syn.servings))
            val actualTexts = pres.steps.map { plan.instruction(it)!!.text }
            assertEquals(syn.expectedTexts, actualTexts)
            assertEquals(syn.expectedUnresolvedLast, plan.instruction(pres.steps.last())!!.hasUnresolvedFormula)
        }

        val emptyAmounts = RecipeAmounts(emptyList(), 3)
        for (expr in fixtures.unsupportedInstructionExpressions) {
            val source = "加入 $expr 的原料。"
            val result = emptyAmounts.instruction(source)
            assertEquals(source, result.text)
            assertFalse(result.hasAdjustedAmounts)
            assertTrue(result.hasUnresolvedFormula)
        }
    }

    @Test
    fun dietaryAndInstitutionalLowGIFixturesMatch() {
        val fixtures = loadFixture<DietaryContractsFixtures>("dietary-contracts.json")
        for (vec in fixtures.sha256Vectors) {
            assertEquals(vec.sha256, DietaryCatalog.amountsFingerprint(vec.input.toByteArray(Charsets.UTF_8)))
        }

        val data = CookData.loadFromDirectory(dataDir())
        for (fpCase in fixtures.recipeFingerprintCases) {
            val recipe = data.recipes.first { it.id == fpCase.id }
            assertEquals(fpCase.expectedFingerprint, DietaryCatalog.fingerprint(recipe))
            // Score changes must never mutate identity fingerprint
            assertEquals(fpCase.expectedFingerprint, DietaryCatalog.fingerprint(recipe.copy(score = 0)))
        }

        val dietary = DietaryCatalog.loadFromDirectory(dataDir(), data.recipes)
        val summary = fixtures.bundledDietarySummary
        assertEquals(summary.entryCount, dietary.entries.size)
        assertEquals(summary.rejectedEntryCount, dietary.rejectedEntryCount)
        assertEquals(summary.availableGoals, dietary.availableGoals)
        assertEquals(summary.lessAddedSugarCount, dietary.count(DietaryGoal.LESS_ADDED_SUGAR))
        assertEquals(summary.weightAwareCount, dietary.count(DietaryGoal.WEIGHT_AWARE))

        val recommender = Recommender(data.recipes, data.normalizer)
        val spinachIds = dietary.candidates(
            RecommendationOptions(keyword = "菠菜"),
            DietaryGoal.LESS_ADDED_SUGAR,
            recommender,
        ).map { it.id }
        assertEquals(summary.keywordSpinachLessAddedSugarIds, spinachIds)

        val noSpicyBalanced = dietary.candidates(
            RecommendationOptions(noSpicy = true),
            DietaryGoal.BALANCED_PAIRING,
            recommender,
        ).map { it.id }.toSet()
        for (excludedId in summary.noSpicyExcludedBalancedPairingIds) {
            assertFalse(excludedId in noSpicyBalanced)
        }

        val avoidPorkSugar = dietary.candidates(
            RecommendationOptions(avoid = listOf("猪肉")),
            DietaryGoal.LESS_ADDED_SUGAR,
            recommender,
        ).map { it.id }.toSet()
        for (excludedId in summary.avoidPorkExcludedLessAddedSugarIds) {
            assertFalse(excludedId in avoidPorkSugar)
        }

        val avoidPorkBalanced = dietary.candidates(
            RecommendationOptions(avoid = listOf("猪肉")),
            DietaryGoal.BALANCED_PAIRING,
            recommender,
        ).map { it.id }.toSet()
        for (excludedId in summary.avoidPorkExcludedBalancedPairingIds) {
            assertFalse(excludedId in avoidPorkBalanced)
        }

        // Low-GI contract
        val lowGi = InstitutionalLowGICatalog.loadFromDirectory(dataDir(), data.recipes)
        assertTrue(lowGi.isAvailable)
        assertEquals(fixtures.bundledInstitutionalLowGI.entryCount, lowGi.entries.size)
        assertEquals(fixtures.bundledInstitutionalLowGI.expectedIds, lowGi.entries.keys.sorted())
        assertEquals(InstitutionalLowGICatalog.CHINESE_REFERENCES, lowGi.chineseReferences)
        for (pc in fixtures.bundledInstitutionalLowGI.permitsCases) {
            val recipe = data.recipes.first { it.id == pc.id }
            val actual = lowGi.permits(
                recipe = recipe,
                options = RecommendationOptions(avoid = pc.avoid, noSpicy = pc.noSpicy),
                normalizer = data.normalizer,
            )
            assertEquals("permits case ${pc.id}", pc.expected, actual)
        }

        // Verify fail-closed on drifted amounts fingerprint
        val dietaryRaw = File(dataDir(), DietaryCatalog.FILE_NAME).readText(Charsets.UTF_8)
        val library = RecipeTextLibrary.loadFromDirectory(dataDir())!!
        var threwInvalid = false
        try {
            DietaryCatalog.fromJson(
                jsonText = dietaryRaw,
                recipes = data.recipes,
                amountsFingerprint = "0".repeat(64),
                textForRecipe = library::text,
            )
        } catch (_: DietaryCatalogException.InvalidVersion) {
            threwInvalid = true
        }
        assertTrue(threwInvalid)
    }

    @Test
    fun personalCatalogAndPrivateRecipesFixturesMatch() {
        val data = CookData.loadFromDirectory(dataDir())

        val catalogRaw = File(fixturesDir(), "personal-catalog.json").readText(Charsets.UTF_8)
        val parsedCatalog = PersonalCatalog.parse(catalogRaw)
        assertEquals(4, parsedCatalog.items.size)
        assertEquals(1, parsedCatalog.hidden.size)
        val applied = parsedCatalog.applyingTo(data.food)
        assertTrue(applied.vegetable.none { it.name == "番茄" })
        assertTrue(applied.vegetable.any { it.name == "鹰嘴豆" })
        assertTrue(applied.tools.any { it.name == "塔吉锅" })
        val restored = parsedCatalog.applyingTo(data.food, includeHidden = true)
        assertTrue(restored.vegetable.any { it.name == "番茄" })
        assertEquals(parsedCatalog, PersonalCatalog.parse(parsedCatalog.export()))

        var caughtDuplicate = false
        try {
            parsedCatalog.copy(items = parsedCatalog.items + parsedCatalog.items.first()).validate()
        } catch (_: PersonalCatalogException.Duplicate) {
            caughtDuplicate = true
        }
        assertTrue(caughtDuplicate)

        val privateRaw = File(fixturesDir(), "private-recipes.json").readText(Charsets.UTF_8)
        val parsedPrivate = PrivateRecipeFile.parse(privateRaw)
        assertEquals(2, parsedPrivate.recipes.size)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", parsedPrivate.recipes[0].sourceUrl)
        assertEquals("https://example.com/recipe?a=1&b=2#steps", parsedPrivate.recipes[1].sourceUrl)
        assertEquals(parsedPrivate, PrivateRecipeFile.parse(parsedPrivate.export()))

        // Copy import preserves conflict when keepCopies = true
        val modified = parsedPrivate.recipes[0].copy(name = "改名后的蒸蛋")
        val plan = PrivateRecipeImport(
            current = listOf(parsedPrivate.recipes[0]),
            incoming = listOf(modified),
        )
        assertEquals(1, plan.conflicts.size)
        val copied = plan.recordsToInsert(
            keepCopies = true,
            currentIds = setOf(parsedPrivate.recipes[0].customId),
            createId = { "custom-copy-1" },
        )
        assertEquals(1, copied.size)
        assertEquals("custom-copy-1", copied[0].customId)
        assertEquals(parsedPrivate.recipes[0].customId, copied[0].copiedFrom)

        var rejectedUnknownField = false
        try {
            PrivateRecipeFile.parse(
                """{"app":"cook","kind":"custom-recipes","version":1,"exportedAt":1,"recipes":[{"customId":"a","name":"菜","stuff":["蛋"],"tools":[],"createdAt":1,"updatedAt":1,"extra":true}]}""",
            )
        } catch (_: PrivateRecipeFileException.Invalid) {
            rejectedUnknownField = true
        }
        assertTrue(rejectedUnknownField)
    }
}
