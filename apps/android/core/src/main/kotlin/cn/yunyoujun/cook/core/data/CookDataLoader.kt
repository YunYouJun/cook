package cn.yunyoujun.cook.core.data

import cn.yunyoujun.cook.core.model.FoodCatalog
import cn.yunyoujun.cook.core.model.IncompatibleRule
import cn.yunyoujun.cook.core.model.Pantry
import cn.yunyoujun.cook.core.model.QueryRules
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.RecipeMeta
import cn.yunyoujun.cook.core.model.ShelfLife
import cn.yunyoujun.cook.core.recommend.StuffNormalizer
import cn.yunyoujun.cook.core.search.CookQueryParser
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

sealed class CookDataException(message: String) : Exception(message) {
    class UnsupportedSchemaVersion(val actual: Int) :
        CookDataException("Unsupported recipe schemaVersion: $actual (expected ${CookData.SUPPORTED_SCHEMA_VERSION})")

    class MissingRequiredAsset(val fileName: String) :
        CookDataException("Missing required CookData asset: $fileName")
}

data class CookData(
    val meta: RecipeMeta,
    val recipes: List<Recipe>,
    val incompatibleRules: List<IncompatibleRule>,
    val aliases: Map<String, String>,
    val food: FoodCatalog,
    val pantry: Pantry,
    val query: QueryRules,
    val shelfLife: ShelfLife = ShelfLife(),
    val recipeAmountsSha256: String? = null,
) {
    val normalizer: StuffNormalizer
        get() = StuffNormalizer(aliases)

    fun makeQueryParser(extraStuff: List<String> = emptyList()): CookQueryParser =
        CookQueryParser(query, food, aliases, extraStuff)

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1

        val coreJson = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

        fun loadFromDirectory(directory: File): CookData =
            loadFromBytes(
                readRequiredBytes = { fileName ->
                    val file = File(directory, fileName)
                    if (!file.isFile) {
                        throw CookDataException.MissingRequiredAsset(fileName)
                    }
                    file.readBytes()
                },
                readOptionalBytes = { fileName ->
                    val file = File(directory, fileName)
                    if (file.isFile) file.readBytes() else null
                },
            )

        fun loadFromBytes(
            readRequiredBytes: (String) -> ByteArray,
            readOptionalBytes: (String) -> ByteArray? = { null },
        ): CookData {
            fun readText(fileName: String): String =
                readRequiredBytes(fileName).toString(Charsets.UTF_8)

            val meta = coreJson.decodeFromString<RecipeMeta>(readText("recipe-meta.json"))
            if (meta.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                throw CookDataException.UnsupportedSchemaVersion(meta.schemaVersion)
            }

            val recipes: List<Recipe> = if (meta.chunked) {
                val chunkRefs = meta.chunks.orEmpty().sortedBy { it.index }
                if (chunkRefs.isEmpty()) {
                    throw CookDataException.MissingRequiredAsset("recipe-chunk-*.json")
                }
                chunkRefs.flatMap { chunk ->
                    val fileName = chunk.file ?: "recipe-chunk-${chunk.index}.json"
                    coreJson.decodeFromString<List<Recipe>>(readText(fileName))
                }
            } else {
                coreJson.decodeFromString<List<Recipe>>(readText("recipe.json"))
            }

            val incompatibleRules =
                coreJson.decodeFromString<List<IncompatibleRule>>(readText("incompatible-foods.json"))
            val aliases = coreJson.decodeFromString<Map<String, String>>(readText("aliases.json"))
            val food = coreJson.decodeFromString<FoodCatalog>(readText("food.json"))
            val pantry = coreJson.decodeFromString<Pantry>(readText("pantry.json"))
            val query = coreJson.decodeFromString<QueryRules>(readText("query.json"))

            val shelfLifeBytes = readOptionalBytes("shelf-life.json")
            val shelfLife = if (shelfLifeBytes != null) {
                coreJson.decodeFromString<ShelfLife>(shelfLifeBytes.toString(Charsets.UTF_8))
            } else {
                ShelfLife()
            }

            val amountsDigest = readOptionalBytes("recipe-amounts.json")?.let(::sha256Hex)

            return CookData(
                meta = meta,
                recipes = recipes,
                incompatibleRules = incompatibleRules,
                aliases = aliases,
                food = food,
                pantry = pantry,
                query = query,
                shelfLife = shelfLife,
                recipeAmountsSha256 = amountsDigest,
            )
        }

        /**
         * Raw-byte SHA-256 hex digest matching Swift CryptoKit & Node crypto.
         */
        fun sha256Hex(rawBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(rawBytes)
            return digest.joinToString(separator = "") { "%02x".format(it) }
        }

        /**
         * 64-bit FNV-1a over UTF-8 bytes separated by NUL, matching CookCore's DietaryGoals / StepGuidance fingerprint.
         */
        fun fnv1a64Hex(parts: Iterable<String>): String {
            var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325UL as signed Long
            val prime = 0x100000001b3L
            var first = true
            for (part in parts) {
                if (!first) {
                    hash = (hash xor 0L) * prime
                }
                first = false
                for (b in part.toByteArray(Charsets.UTF_8)) {
                    hash = (hash xor (b.toLong() and 0xffL)) * prime
                }
            }
            return "%016x".format(hash)
        }
    }
}
