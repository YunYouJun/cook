package cn.yunyoujun.cook.core.dietary

import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.model.DietaryFoodGroup
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.recipe.RecipeAmountAnnotations
import cn.yunyoujun.cook.core.recipe.RecipeText
import cn.yunyoujun.cook.core.recipe.RecipeTextLibrary
import cn.yunyoujun.cook.core.recommend.RecipeMatch
import cn.yunyoujun.cook.core.recommend.RecommendationOptions
import cn.yunyoujun.cook.core.recommend.Recommender
import cn.yunyoujun.cook.core.recommend.StuffNormalizer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

sealed class DietaryCatalogException(message: String) : Exception(message) {
    class MissingData : DietaryCatalogException("Missing dietary catalog data")
    class UnsupportedVersion : DietaryCatalogException("Unsupported dietary catalog version")
    class InvalidVersion : DietaryCatalogException("Invalid dietary catalog version or checksum")
}

class DietaryCatalog private constructor(
    val dataVersion: String,
    val entries: Map<Int, Entry>,
    val availableGoals: List<DietaryGoal>,
    val rejectedEntryCount: Int,
    private val texts: Map<Int, RecipeText>,
) {
    @Serializable
    enum class Status {
        @SerialName("pass")
        PASS,

        @SerialName("fail")
        FAIL,

        @SerialName("unknown")
        UNKNOWN,
    }

    @Serializable
    data class Assessment(
        val status: Status,
        val reason: String,
        val evidence: List<RecipeAmountAnnotations.Anchor>,
    )

    @Serializable
    data class OilRange(
        val lower: Double,
        val upper: Double,
    )

    @Serializable(with = PortionOilSerializer::class)
    data class PortionOil(
        val referenceServings: Int,
        val batchOil: OilRange,
        val unit: UnitType,
        val servingEvidence: List<RecipeAmountAnnotations.Anchor>,
        val oilEvidence: List<RecipeAmountAnnotations.Anchor>,
    ) {
        enum class UnitType(val rawValue: String) {
            GRAMS("g"),
            MILLILITERS("ml"),
        }

        val perServingLower: Double
            get() = batchOil.lower / referenceServings.toDouble()

        val perServingUpper: Double
            get() = batchOil.upper / referenceServings.toDouble()

        val label: String
            get() {
                val lowerText = formatUpTo2(perServingLower)
                val upperText = formatUpTo2(perServingUpper)
                val rangeText = if (lowerText == upperText) lowerText else "$lowerText–$upperText"
                return "每人添加油 $rangeText ${unit.rawValue}"
            }

        companion object {
            private fun formatUpTo2(value: Double): String {
                val df = DecimalFormat("0.##", DecimalFormatSymbols(Locale.US))
                df.isGroupingUsed = false
                return df.format(if (value == -0.0 || value == 0.0) 0.0 else value)
            }
        }
    }

    object PortionOilSerializer : KSerializer<PortionOil> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PortionOil")

        override fun deserialize(decoder: Decoder): PortionOil {
            val jsonDecoder = decoder as? JsonDecoder
                ?: throw SerializationException("PortionOil requires JSON decoder")
            val obj = jsonDecoder.decodeJsonElement().jsonObject
            val hasGrams = obj["batchOilGrams"] != null && obj["batchOilGrams"] !is JsonNull
            val hasMl = obj["batchOilMilliliters"] != null && obj["batchOilMilliliters"] !is JsonNull
            if (hasGrams == hasMl) {
                throw SerializationException("Exactly one source oil unit is required")
            }
            val referenceServings = obj["referenceServings"]?.jsonPrimitive?.int
                ?: throw SerializationException("Missing referenceServings")
            val unit = if (hasGrams) PortionOil.UnitType.GRAMS else PortionOil.UnitType.MILLILITERS
            val oilEl = if (hasGrams) obj["batchOilGrams"]!! else obj["batchOilMilliliters"]!!
            val batchOil = CookData.coreJson.decodeFromJsonElement<OilRange>(oilEl)
            val servingEvidence = CookData.coreJson.decodeFromJsonElement<List<RecipeAmountAnnotations.Anchor>>(
                obj["servingEvidence"] ?: throw SerializationException("Missing servingEvidence"),
            )
            val oilEvidence = CookData.coreJson.decodeFromJsonElement<List<RecipeAmountAnnotations.Anchor>>(
                obj["oilEvidence"] ?: throw SerializationException("Missing oilEvidence"),
            )
            return PortionOil(
                referenceServings = referenceServings,
                batchOil = batchOil,
                unit = unit,
                servingEvidence = servingEvidence,
                oilEvidence = oilEvidence,
            )
        }

        override fun serialize(encoder: Encoder, value: PortionOil) {
            throw UnsupportedOperationException("PortionOil serialization is not used")
        }
    }

    @Serializable
    data class Entry(
        val recipeFingerprint: String,
        val sourceLink: String,
        val sourceFingerprint: String,
        val reviewedAt: String,
        val ingredients: List<String>,
        val avoidIngredients: List<String>,
        val unresolvedIngredients: List<String>,
        val ingredientEvidence: List<RecipeAmountAnnotations.Anchor>,
        val foodGroups: List<DietaryFoodGroup>,
        val assessments: Map<String, Assessment>,
        val portionOil: PortionOil? = null,
    ) {
        fun assessment(goal: DietaryGoal): Assessment? = assessments[goal.rawValue]

        val foodGroupSummary: String
            get() = DietaryFoodGroup.entries.filter { it in foodGroups }.joinToString("、") { it.title }

        val pairingHint: String
            get() {
                val missing = DietaryFoodGroup.entries.filter { it !in foodGroups }.map { it.title }
                return if (missing.isEmpty()) {
                    "做法包含三类食物，可结合实际份量安排这一餐。"
                } else {
                    "这一餐还可以搭配${missing.joinToString("、")}。"
                }
            }

        fun reason(goal: DietaryGoal): String = when (goal) {
            DietaryGoal.WEIGHT_AWARE -> portionOil?.label ?: "份量、用油信息待核对"
            DietaryGoal.LESS_ADDED_SUGAR -> "已核对做法中的原料、调料和步骤，未额外添加糖。"
            DietaryGoal.BALANCED_PAIRING -> "提供$foodGroupSummary。$pairingHint"
        }

        internal fun valid(recipe: Recipe, text: RecipeText, schemaVersion: Int): Boolean {
            if (recipe.id < 0 || recipeFingerprint != fingerprint(recipe)) return false
            if (sourceLink != recipe.link) return false
            val scheme = runCatching { URI(sourceLink).scheme?.lowercase() }.getOrNull()
            if (scheme != "https") return false
            if (sourceFingerprint != RecipeAmountAnnotations.fingerprint(text)) return false
            if (!DATE_REGEX.matches(reviewedAt)) return false
            if (ingredients.isEmpty() || ingredients.distinct().size != ingredients.size) return false
            if (ingredientEvidence.isEmpty() || !ingredientEvidence.all { it.matches(text) }) return false
            if (!ingredients.all { name -> name.isNotEmpty() && ingredientEvidence.any { name in it.text } }) return false
            if (!avoidIngredients.toSet().containsAll(ingredients)) return false
            if (!ingredients.toSet().containsAll(unresolvedIngredients)) return false
            if (foodGroups.isEmpty() || foodGroups.distinct().size != foodGroups.size) return false
            val expectedKeys = DietaryGoal.entries.map { it.rawValue }.toSet()
            if (assessments.keys != expectedKeys) return false
            if (!assessments.values.all { it.evidence.isNotEmpty() && it.evidence.all { anchor -> anchor.matches(text) } }) {
                return false
            }

            for (goal in DietaryGoal.entries) {
                val assessment = assessment(goal) ?: return false
                when {
                    goal == DietaryGoal.LESS_ADDED_SUGAR && assessment.status == Status.PASS -> {
                        if (assessment.reason != "no-added-sugar" || unresolvedIngredients.isNotEmpty()) return false
                        if (ingredients.any { it in ADDED_SUGAR_INGREDIENTS }) return false
                    }
                    goal == DietaryGoal.BALANCED_PAIRING && assessment.status == Status.PASS -> {
                        if (assessment.reason != "food-group-pairing") return false
                    }
                    goal == DietaryGoal.WEIGHT_AWARE && assessment.status == Status.PASS -> {
                        val oil = portionOil ?: return false
                        if (assessment.reason != "portion-oil-reference") return false
                        if (oil.referenceServings !in 1..20) return false
                        if (!oil.batchOil.lower.isFinite() || !oil.batchOil.upper.isFinite()) return false
                        if (oil.batchOil.lower < 0.0 || oil.batchOil.upper < oil.batchOil.lower) return false
                        if (schemaVersion < 2 && oil.unit != PortionOil.UnitType.GRAMS) return false
                        if (oil.servingEvidence.isEmpty() || oil.oilEvidence.isEmpty()) return false
                        if (!(oil.servingEvidence + oil.oilEvidence).all { it.matches(text) }) return false
                    }
                    else -> {
                        if (assessment.reason !in FAILURE_REASONS) return false
                    }
                }
            }
            return true
        }

        companion object {
            private val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
            private val ADDED_SUGAR_INGREDIENTS = setOf("糖", "白糖", "冰糖", "砂糖", "糖浆", "蜂蜜")
            private val FAILURE_REASONS = setOf("added-sugar", "unverified-seasoning", "unverified-portion-oil")
        }
    }

    @Serializable
    private data class Document(
        val schemaVersion: Int,
        val policyVersion: Int,
        val dataVersion: String,
        val amountsFingerprint: String,
        val enabledGoals: List<DietaryGoal>,
        val recipes: Map<String, Entry>,
    )

    fun count(goal: DietaryGoal): Int =
        entries.values.count { it.assessment(goal)?.status == Status.PASS }

    fun text(recipeId: Int): RecipeText? = texts[recipeId]

    fun entry(recipe: Recipe, goal: DietaryGoal): Entry? {
        if (goal !in availableGoals) return null
        val entry = entries[recipe.id] ?: return null
        if (entry.recipeFingerprint != fingerprint(recipe)) return null
        if (entry.assessment(goal)?.status != Status.PASS) return null
        return entry
    }

    fun recommend(
        options: RecommendationOptions,
        goal: DietaryGoal,
        recommender: Recommender,
    ): List<RecipeMatch> =
        recommender.recommend(options)
            .filter { eligible(it.recipe, goal, options, recommender.normalizer) }
            .sortedWith { a, b ->
                if (a.missingStuff.size != b.missingStuff.size) {
                    return@sortedWith a.missingStuff.size.compareTo(b.missingStuff.size)
                }
                if (a.matchedStuff.size != b.matchedStuff.size) {
                    return@sortedWith b.matchedStuff.size.compareTo(a.matchedStuff.size)
                }
                comparePriority(a.recipe, b.recipe, a.sourceIndex, b.sourceIndex)
            }

    fun candidates(
        options: RecommendationOptions,
        goal: DietaryGoal,
        recommender: Recommender,
    ): List<Recipe> {
        val keyword = options.keyword.orEmpty().trim()
        return recommender.candidates(options)
            .filter {
                (keyword.isEmpty() || keyword in it.name) &&
                    eligible(it, goal, options, recommender.normalizer)
            }
            .withIndex()
            .sortedWith { a, b -> comparePriority(a.value, b.value, a.index, b.index) }
            .map { it.value }
    }

    private fun eligible(
        recipe: Recipe,
        goal: DietaryGoal,
        options: RecommendationOptions,
        normalizer: StuffNormalizer,
    ): Boolean {
        val entry = entry(recipe, goal) ?: return false
        val extraChili = if (options.noSpicy == true) listOf("辣椒") else emptyList()
        val avoided = normalizer.unique(options.avoid.orEmpty() + extraChili).toSet()
        if (avoided.isEmpty()) return true
        return entry.unresolvedIngredients.isEmpty() &&
            normalizer.unique(entry.avoidIngredients).none { it in avoided }
    }

    private fun comparePriority(a: Recipe, b: Recipe, aIndex: Int, bIndex: Int): Int {
        val firstScore = a.score ?: Recommender.DEFAULT_SCORE
        val secondScore = b.score ?: Recommender.DEFAULT_SCORE
        return if (firstScore == secondScore) aIndex.compareTo(bIndex) else secondScore.compareTo(firstScore)
    }

    companion object {
        const val FILE_NAME = "recipe-dietary.json"
        private val DATA_VERSION_REGEX = Regex("^[a-f0-9]{12}$")

        fun loadFromDirectory(directory: File, recipes: List<Recipe>): DietaryCatalog {
            val file = File(directory, FILE_NAME)
            if (!file.isFile) throw DietaryCatalogException.MissingData()
            val library = RecipeTextLibrary.loadFromDirectory(directory) ?: throw DietaryCatalogException.MissingData()
            val amountsFile = File(directory, RecipeAmountAnnotations.FILE_NAME)
            if (!amountsFile.isFile) throw DietaryCatalogException.MissingData()
            val amountsDigest = amountsFingerprint(amountsFile.readBytes())
            return fromJson(
                jsonText = file.readText(Charsets.UTF_8),
                recipes = recipes,
                amountsFingerprint = amountsDigest,
                textForRecipe = library::text,
            )
        }

        fun fromJson(
            jsonText: String,
            recipes: List<Recipe>,
            amountsFingerprint: String,
            textForRecipe: (Int) -> RecipeText?,
        ): DietaryCatalog {
            val doc = try {
                CookData.coreJson.decodeFromString<Document>(jsonText)
            } catch (e: Exception) {
                throw DietaryCatalogException.InvalidVersion()
            }
            if (doc.schemaVersion !in listOf(1, 2) || doc.policyVersion != doc.schemaVersion) {
                throw DietaryCatalogException.UnsupportedVersion()
            }
            if (!DATA_VERSION_REGEX.matches(doc.dataVersion) ||
                doc.enabledGoals.distinct().size != doc.enabledGoals.size ||
                doc.amountsFingerprint != amountsFingerprint
            ) {
                throw DietaryCatalogException.InvalidVersion()
            }

            val byId = HashMap<Int, Recipe>(recipes.size)
            for (recipe in recipes) {
                byId.putIfAbsent(recipe.id, recipe)
            }

            val validEntries = LinkedHashMap<Int, Entry>()
            val validTexts = LinkedHashMap<Int, RecipeText>()
            for ((key, entry) in doc.recipes) {
                val id = key.toIntOrNull() ?: continue
                if (id.toString() != key) continue
                val recipe = byId[id] ?: continue
                if (recipe.stuff.distinct().size >= Recommender.CATCH_ALL_MIN_STUFF) continue
                val text = textForRecipe(id) ?: continue
                if (!entry.valid(recipe, text, doc.schemaVersion)) continue
                validEntries[id] = entry
                validTexts[id] = text
            }

            val goals = doc.enabledGoals.filter { goal ->
                validEntries.values.count { it.assessment(goal)?.status == Status.PASS } >= 10
            }
            val covered = validEntries.values.count { entry ->
                goals.any { goal -> entry.assessment(goal)?.status == Status.PASS }
            }
            val availableGoals = if (covered >= 30) goals else emptyList()

            return DietaryCatalog(
                dataVersion = doc.dataVersion,
                entries = validEntries,
                availableGoals = availableGoals,
                rejectedEntryCount = doc.recipes.size - validEntries.size,
                texts = validTexts,
            )
        }

        /**
         * 与构建期及 iOS 一致的 UTF-8、NUL 分隔 FNV-1a 64 指纹，不含展示评分。
         */
        fun fingerprint(recipe: Recipe): String {
            val tokens = ArrayList<String>(16)
            tokens.add(recipe.id.toString())
            tokens.add(recipe.name)
            tokens.add(recipe.link ?: "")
            tokens.add(recipe.bv ?: "")
            tokens.add(recipe.difficulty ?: "")
            for (values in listOf(recipe.stuff, recipe.tools, recipe.methods, recipe.tags)) {
                tokens.add(values.size.toString())
                tokens.addAll(values)
            }
            return CookData.fnv1a64Hex(tokens)
        }

        fun amountsFingerprint(rawBytes: ByteArray): String = CookData.sha256Hex(rawBytes)
    }
}
