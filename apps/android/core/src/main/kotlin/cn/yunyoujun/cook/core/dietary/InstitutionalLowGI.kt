package cn.yunyoujun.cook.core.dietary

import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.recommend.RecommendationOptions
import cn.yunyoujun.cook.core.recommend.StuffNormalizer
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * 机构低 GI 食谱参考目录；仅收编机构来源编目，不表示整道菜的实验室实测 GI 值。
 */
class InstitutionalLowGICatalog private constructor(
    val dataVersion: String,
    val entries: Map<Int, Entry>,
    val chineseReferences: List<ChineseReference>,
) {
    @Serializable
    data class ChineseReference(
        val id: String,
        val publisher: String,
        val title: String,
        val url: String,
        val scope: String,
    )

    @Serializable
    data class Entry(
        val recipeFingerprint: String,
        val sourceURL: String,
        val sourceRecipeTitle: String,
        val classificationTitle: String,
        val author: String,
        val reviewedAt: String,
        val requirements: String,
        val ingredients: List<String>,
        val avoidIngredients: List<String>,
        val unresolvedIngredients: List<String>,
        val ingredientEvidence: String,
        val preparationEvidence: String,
    ) {
        internal fun valid(recipe: Recipe): Boolean {
            if (!isStrictIsoDate(reviewedAt)) return false
            if (recipeFingerprint != DietaryCatalog.fingerprint(recipe)) return false
            if (recipe.link != sourceURL) return false
            if (!recipe.bv.isNullOrEmpty()) return false
            if (TAG !in recipe.tags) return false
            if (!SOURCE_URL_REGEX.matches(sourceURL)) return false
            if (classificationTitle !in ALLOWED_CLASSIFICATIONS) return false
            if (listOf(sourceRecipeTitle, author, requirements, ingredientEvidence, preparationEvidence)
                    .any { it.trim().isEmpty() }
            ) {
                return false
            }
            if (ingredients.isEmpty() || ingredients.distinct().size != ingredients.size) return false
            if (avoidIngredients.isEmpty() || !avoidIngredients.toSet().containsAll(recipe.stuff)) return false
            return true
        }
    }

    @Serializable
    private data class Document(
        val schemaVersion: Int,
        val policyVersion: Int,
        val dataVersion: String,
        val publisher: String,
        val authorityURL: String,
        val claimKind: String,
        val chineseReferences: List<ChineseReference>,
        val recipes: Map<String, Entry>,
    )

    val isAvailable: Boolean
        get() = entries.size >= 10

    fun entry(recipe: Recipe): Entry? {
        if (!isAvailable) return null
        val candidate = entries[recipe.id] ?: return null
        if (candidate.recipeFingerprint != DietaryCatalog.fingerprint(recipe)) return null
        return candidate
    }

    fun permits(
        recipe: Recipe,
        options: RecommendationOptions,
        normalizer: StuffNormalizer,
    ): Boolean {
        val candidate = entry(recipe) ?: return false
        val extraChili = if (options.noSpicy == true) listOf("辣椒") else emptyList()
        val avoided = normalizer.unique(options.avoid.orEmpty() + extraChili).toSet()
        if (avoided.isEmpty()) return true
        return candidate.unresolvedIngredients.isEmpty() &&
            normalizer.unique(candidate.avoidIngredients).none { it in avoided }
    }

    companion object {
        const val FILE_NAME = "recipe-institutional-low-gi.json"
        const val TAG = "机构低 GI 食谱"
        const val PUBLISHER = "GI News / University of Sydney"
        const val AUTHORITY_URL = "https://glycemicindex.com/2026/01/copyright-and-permission-105/"
        const val EXPLANATION =
            "来源为悉尼大学 GI News 的低 GI 食谱栏目，属于机构食谱参考，未提供整道菜的实验室实测 GI 值。原方目前只有英文；详情另列国家卫生健康委中文资料，用于解释 GI 概念与标准测定，不是本批配方的中文版或认证。请按原方的食材品种、比例和做法制作；不表示无糖、低热量或适合个人疾病饮食。"

        val CHINESE_REFERENCES = listOf(
            ChineseReference(
                id = "adult-diabetes-dietary-guide-2023",
                publisher = "国家卫生健康委",
                title = "《成人糖尿病食养指南（2023年版）》问答",
                url = "https://www.nhc.gov.cn/wjw/c100378/202301/7faed9993c9b45aea158c5335346f282/files/1732844468167_10535.pdf",
                scope = "用于核对 GI 的定义和膳食边界；不证明这些配方经过实测，也不代表适合个人疾病饮食。",
            ),
            ChineseReference(
                id = "ws-t-652-2019",
                publisher = "国家卫生健康委",
                title = "食物血糖生成指数测定方法（WS/T 652—2019）",
                url = "https://www.nhc.gov.cn/fzs/c100048/201906/ae057c7a93744972bd7722077c91ca34/files/1733125684903_75354.pdf",
                scope = "规定食物 GI 的标准测定方法；不提供本批次具体配方的 GI 实测值。",
            ),
        )

        private val DATA_VERSION_REGEX = Regex("^[a-f0-9]{12}$")
        private val SOURCE_URL_REGEX =
            Regex("^https://glycemicindex\\.com/\\d{4}/\\d{2}/low-gi-recipes?-of-the-month(?:-\\d+)?/$")
        private val ALLOWED_CLASSIFICATIONS = setOf("Low GI Recipe of the Month", "Low GI Recipes of the Month")
        private val STRICT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US).withResolverStyle(ResolverStyle.STRICT)

        private fun isStrictIsoDate(text: String): Boolean =
            runCatching { LocalDate.parse(text, STRICT_DATE_FORMAT) }.isSuccess

        fun loadFromDirectory(directory: File, recipes: List<Recipe>): InstitutionalLowGICatalog {
            val file = File(directory, FILE_NAME)
            if (!file.isFile) throw DietaryCatalogException.MissingData()
            return fromJson(file.readText(Charsets.UTF_8), recipes)
        }

        fun fromJson(jsonText: String, recipes: List<Recipe>): InstitutionalLowGICatalog {
            val doc = try {
                CookData.coreJson.decodeFromString<Document>(jsonText)
            } catch (e: Exception) {
                throw DietaryCatalogException.InvalidVersion()
            }
            if (doc.schemaVersion != 1 || doc.policyVersion != 1) {
                throw DietaryCatalogException.UnsupportedVersion()
            }
            if (doc.publisher != PUBLISHER ||
                doc.authorityURL != AUTHORITY_URL ||
                doc.claimKind != "institutional-editorial" ||
                doc.chineseReferences != CHINESE_REFERENCES ||
                !DATA_VERSION_REGEX.matches(doc.dataVersion)
            ) {
                throw DietaryCatalogException.InvalidVersion()
            }
            val identities = doc.recipes.values.map { "${it.sourceURL}\n${it.sourceRecipeTitle}" }
            if (identities.distinct().size != identities.size) {
                throw DietaryCatalogException.InvalidVersion()
            }

            val byId = HashMap<Int, Recipe>(recipes.size)
            for (recipe in recipes) {
                byId.putIfAbsent(recipe.id, recipe)
            }
            val validEntries = LinkedHashMap<Int, Entry>()
            for ((key, entry) in doc.recipes) {
                val id = key.toIntOrNull() ?: continue
                if (id.toString() != key) continue
                val recipe = byId[id] ?: continue
                if (entry.valid(recipe)) {
                    validEntries[id] = entry
                }
            }

            return InstitutionalLowGICatalog(
                dataVersion = doc.dataVersion,
                entries = validEntries,
                chineseReferences = doc.chineseReferences,
            )
        }
    }
}
