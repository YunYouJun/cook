package cn.yunyoujun.cook.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.util.UUID

sealed class PrivateRecipeFileException(message: String) : Exception(message) {
    class Invalid : PrivateRecipeFileException("无法识别这份私人菜谱备份，原有菜谱未改变。")
    class TooLarge : PrivateRecipeFileException("文件超过 5 MB，原有菜谱未改变。")
    class DuplicateID : PrivateRecipeFileException("文件里同一个编号有不同内容，原有菜谱未改变。")
    class TooMany : PrivateRecipeFileException("合并后超过 5000 道菜谱，原有菜谱未改变。")
}

@Serializable
data class PrivateRecipe(
    var customId: String,
    val name: String,
    val stuff: List<String>,
    val tools: List<String>,
    val bv: String? = null,
    val link: String? = null,
    val note: String? = null,
    val tags: List<String>? = null,
    val methods: List<String>? = null,
    val difficulty: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    var copiedFrom: String? = null,
    @kotlinx.serialization.Transient
    val video: PrivateRecipeVideo? = null,
) {
    val sourceUrl: String?
        get() {
            if (!bv.isNullOrEmpty()) return "https://www.bilibili.com/video/$bv"
            return link?.takeIf { PrivateRecipeFile.isSafeUrl(it) }
        }

    fun hasSameContent(other: PrivateRecipe, ignoringMissingVideo: Boolean = false): Boolean =
        name == other.name &&
            stuff == other.stuff &&
            tools == other.tools &&
            (bv ?: "") == (other.bv ?: "") &&
            (link ?: "") == (other.link ?: "") &&
            (note ?: "") == (other.note ?: "") &&
            (tags ?: emptyList<String>()) == (other.tags ?: emptyList<String>()) &&
            (methods ?: emptyList<String>()) == (other.methods ?: emptyList<String>()) &&
            (difficulty ?: "") == (other.difficulty ?: "") &&
            (video == other.video || (ignoringMissingVideo && other.video == null))

    fun validated(): PrivateRecipe {
        video?.validated()
        if (!PrivateRecipeFile.validId(customId)) throw PrivateRecipeFileException.Invalid()
        if (name.trim().isEmpty() || name.length > 30) throw PrivateRecipeFileException.Invalid()
        if (stuff.isEmpty() || !isValidList(stuff) || !isValidList(tools)) throw PrivateRecipeFileException.Invalid()
        if (createdAt < 0 || updatedAt < createdAt || updatedAt > MAX_SAFE_INTEGER) {
            throw PrivateRecipeFileException.Invalid()
        }
        if ((note?.length ?: 0) > 20_000) throw PrivateRecipeFileException.Invalid()
        if (difficulty != null && difficulty !in ALLOWED_DIFFICULTIES) throw PrivateRecipeFileException.Invalid()
        if (tags != null && !isValidList(tags)) throw PrivateRecipeFileException.Invalid()
        if (methods != null && !isValidList(methods)) throw PrivateRecipeFileException.Invalid()
        if (bv != null && bv.isNotEmpty() && !BV_REGEX.matches(bv)) throw PrivateRecipeFileException.Invalid()
        if (link != null && link.isNotEmpty() && !PrivateRecipeFile.isSafeUrl(link)) {
            throw PrivateRecipeFileException.Invalid()
        }
        if (copiedFrom != null && !PrivateRecipeFile.validId(copiedFrom!!)) {
            throw PrivateRecipeFileException.Invalid()
        }
        return this
    }

    companion object {
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private val ALLOWED_DIFFICULTIES = setOf("", "简单", "普通", "困难")
        private val BV_REGEX = Regex("^BV[0-9a-zA-Z]{10}$")

        private fun isValidList(list: List<String>): Boolean =
            list.size <= 64 && list.all { it.trim().isNotEmpty() && it.length <= 100 }
    }
}

@Serializable
data class PrivateRecipeFile(
    val app: String = "cook",
    val kind: String = "custom-recipes",
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val recipes: List<PrivateRecipe>,
) {
    fun export(): String {
        val text = exportJson.encodeToString(this)
        parse(text)
        return text
    }

    companion object {
        const val BYTE_LIMIT = 5 * 1024 * 1024
        const val RECIPE_LIMIT = 5000
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private val ID_REGEX = Regex("^[A-Za-z0-9_:-]{1,160}$")
        private val ALLOWED_FIELDS = setOf(
            "customId", "name", "stuff", "tools", "bv", "link", "note",
            "tags", "methods", "difficulty", "createdAt", "updatedAt", "copiedFrom",
        )
        private val strictJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val exportJson = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun validId(id: String): Boolean = ID_REGEX.matches(id)

        fun isSafeUrl(text: String): Boolean {
            if (text.length > 4096 || text.any { it.isWhitespace() || Character.isISOControl(it.code) }) {
                return false
            }
            return runCatching {
                val uri = URI(text)
                val scheme = uri.scheme?.lowercase().orEmpty()
                val host = uri.host
                (scheme == "http" || scheme == "https") && !host.isNullOrEmpty() && uri.userInfo == null
            }.getOrDefault(false)
        }

        fun parse(text: String): PrivateRecipeFile {
            if (text.toByteArray(Charsets.UTF_8).size > BYTE_LIMIT) {
                throw PrivateRecipeFileException.TooLarge()
            }
            val obj = try {
                strictJson.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw PrivateRecipeFileException.Invalid()
            }
            val records = runCatching { obj["recipes"]?.jsonArray }.getOrNull() ?: throw PrivateRecipeFileException.Invalid()
            for (el in records) {
                val recObj = runCatching { el.jsonObject }.getOrNull() ?: throw PrivateRecipeFileException.Invalid()
                if (!ALLOWED_FIELDS.containsAll(recObj.keys)) throw PrivateRecipeFileException.Invalid()
                if (recObj.values.any { it is JsonNull }) throw PrivateRecipeFileException.Invalid()
            }
            val file = try {
                strictJson.decodeFromString<PrivateRecipeFile>(text)
            } catch (e: Exception) {
                throw PrivateRecipeFileException.Invalid()
            }
            if (file.app != "cook" ||
                file.kind != "custom-recipes" ||
                file.version != 1 ||
                file.exportedAt < 0 ||
                file.exportedAt > MAX_SAFE_INTEGER ||
                file.recipes.size > RECIPE_LIMIT
            ) {
                throw PrivateRecipeFileException.Invalid()
            }
            val seen = HashMap<String, PrivateRecipe>()
            val validatedRecipes = ArrayList<PrivateRecipe>(file.recipes.size)
            for (item in file.recipes) {
                val recipe = item.validated()
                val prior = seen[recipe.customId]
                if (prior != null) {
                    if (prior != recipe) throw PrivateRecipeFileException.DuplicateID()
                } else {
                    seen[recipe.customId] = recipe
                    validatedRecipes.add(recipe)
                }
            }
            return PrivateRecipeFile(
                app = file.app,
                kind = file.kind,
                version = file.version,
                exportedAt = file.exportedAt,
                recipes = validatedRecipes,
            )
        }
    }
}

class PrivateRecipeImport(
    current: List<PrivateRecipe>,
    incoming: List<PrivateRecipe>,
    deletedIds: Set<String> = emptySet(),
) {
    val additions = ArrayList<PrivateRecipe>()
    val conflicts = ArrayList<PrivateRecipe>()
    val duplicates = ArrayList<PrivateRecipe>()
    private val currentCount = current.size

    init {
        for (recipe in incoming) {
            val previous = current.firstOrNull { it.customId == recipe.customId }
            val copied = current.any {
                it.copiedFrom == recipe.customId && it.hasSameContent(recipe, ignoringMissingVideo = true)
            }
            when {
                copied || previous?.hasSameContent(recipe, ignoringMissingVideo = true) == true -> {
                    duplicates.add(recipe)
                }
                previous != null || recipe.customId in deletedIds -> {
                    conflicts.add(recipe)
                }
                else -> {
                    additions.add(recipe)
                }
            }
        }
    }

    fun recordsToInsert(
        keepCopies: Boolean,
        currentIds: Set<String>,
        createId: () -> String = { "custom-${UUID.randomUUID().toString().lowercase()}" },
    ): List<PrivateRecipe> {
        val records = ArrayList(additions)
        val ids = currentIds + additions.map { it.customId }
        val mutableIds = ids.toMutableSet()
        if (keepCopies) {
            for (recipe in conflicts) {
                val copy = recipe.copy(customId = createId(), copiedFrom = recipe.customId)
                if (!PrivateRecipeFile.validId(copy.customId) || copy.customId in mutableIds) {
                    throw PrivateRecipeFileException.Invalid()
                }
                records.add(copy)
                mutableIds.add(copy.customId)
            }
        }
        if (currentCount + records.size > PrivateRecipeFile.RECIPE_LIMIT) {
            throw PrivateRecipeFileException.TooMany()
        }
        return records
    }
}
