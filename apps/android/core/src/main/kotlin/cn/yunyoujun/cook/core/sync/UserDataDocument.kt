package cn.yunyoujun.cook.core.sync

import cn.yunyoujun.cook.core.model.Recipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets

/**
 * Cross-platform v1 user data sync document shared with Web and iOS (`UserDataDocument.swift`).
 * All timestamps are Unix milliseconds.
 */
@Serializable
data class UserDataDocument(
    val version: Int = SUPPORTED_VERSION,
    val favorites: List<Favorite> = emptyList(),
    val favoriteRemovals: List<Favorite> = emptyList(),
    val histories: List<Visit> = emptyList(),
    val historyRemovals: List<Removal> = emptyList(),
    val historyClearedAt: Double = 0.0,
) {
    @Serializable
    data class Favorite(
        val id: Int,
        val time: Double,
    )

    @Serializable
    data class RecipeSnapshot(
        val name: String,
        val stuff: List<String> = emptyList(),
        val tools: List<String> = emptyList(),
        val link: String? = null,
        val bv: String? = null,
        val difficulty: String? = null,
        val emojis: List<String>? = null,
        val tags: List<String>? = null,
        val methods: List<String>? = null,
    ) {
        constructor(recipe: Recipe) : this(
            name = recipe.name,
            stuff = recipe.stuff,
            tools = recipe.tools,
            link = recipe.link,
            bv = recipe.bv,
            difficulty = recipe.difficulty,
            emojis = recipe.emojis,
            tags = recipe.tags,
            methods = recipe.methods,
        )

        internal fun orderingKey(): String {
            fun listOrNull(items: List<String>?): JsonElement =
                items?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull

            val fields = JsonArray(
                listOf(
                    JsonPrimitive(name),
                    JsonArray(stuff.map(::JsonPrimitive)),
                    JsonArray(tools.map(::JsonPrimitive)),
                    link?.let(::JsonPrimitive) ?: JsonNull,
                    bv?.let(::JsonPrimitive) ?: JsonNull,
                    difficulty?.let(::JsonPrimitive) ?: JsonNull,
                    listOrNull(emojis),
                    listOrNull(tags),
                    listOrNull(methods),
                ),
            )
            return fields.toString()
        }
    }

    @Serializable
    data class Visit(
        val recipe: RecipeSnapshot,
        val time: Double,
    )

    @Serializable
    data class Removal(
        val name: String,
        val time: Double,
    )

    class InvalidDocumentException(message: String = "Invalid UserDataDocument") : Exception(message)

    fun validate() {
        fun validTime(t: Double): Boolean = t.isFinite() && t >= 0.0
        fun validName(name: String): Boolean = name.isNotEmpty() && name.length <= MAX_NAME_UTF16_LENGTH

        if (version != SUPPORTED_VERSION ||
            favorites.size > MAX_FAVORITES ||
            favoriteRemovals.size > MAX_REMOVALS ||
            histories.size > MAX_HISTORIES ||
            historyRemovals.size > MAX_REMOVALS ||
            !validTime(historyClearedAt) ||
            favorites.any { it.id < 0 || !validTime(it.time) } ||
            favoriteRemovals.any { it.id < 0 || !validTime(it.time) } ||
            histories.any { !validName(it.recipe.name) || !validTime(it.time) } ||
            historyRemovals.any { !validName(it.name) || !validTime(it.time) }
        ) {
            throw InvalidDocumentException("Document constraints violated")
        }

        val encodedBytes = wireJson.encodeToString(this).toByteArray(StandardCharsets.UTF_8)
        if (encodedBytes.size > MAX_DOCUMENT_BYTES) {
            throw InvalidDocumentException("Document exceeds $MAX_DOCUMENT_BYTES bytes")
        }
    }

    fun clearingHistory(nowMillis: Double): UserDataDocument {
        val cutoff = maxOf(nowMillis, historyClearedAt)
        val futureRemovals = histories.filter { it.time > cutoff }.map { Removal(it.recipe.name, it.time) }
        val history = UserDataDocument(
            historyClearedAt = cutoff,
            historyRemovals = historyRemovals + futureRemovals,
        ).merging(UserDataDocument(), nowMillis)
        return copy(
            histories = emptyList(),
            historyClearedAt = cutoff,
            historyRemovals = history.historyRemovals,
        )
    }

    /**
     * Same last-operation, delete-wins tie-break, historyClearedAt watermark, and deterministic
     * tombstone pruning rules as Web `userData.ts` and iOS `UserDataDocument.swift`.
     */
    fun merging(
        other: UserDataDocument,
        nowMillis: Double = System.currentTimeMillis().toDouble(),
    ): UserDataDocument {
        val mergedClearedAt = maxOf(historyClearedAt, other.historyClearedAt)
        val adds = latestById(favorites + other.favorites)
        val removes = latestById(favoriteRemovals + other.favoriteRemovals)

        val mergedFavorites = adds.values
            .filter { (removes[it.id]?.time ?: -1.0) < it.time }
            .sortedWith(compareBy<Favorite> { it.time }.thenBy { it.id })

        val activeFavoriteRemovals = removes.values
            .filter { (adds[it.id]?.time ?: -1.0) <= it.time }
        val mergedFavoriteRemovals = prune(
            values = activeFavoriteRemovals,
            nowMillis = nowMillis,
            timeOf = { it.time },
            ascComparator = compareBy<Favorite> { it.time }.thenBy { it.id },
            tieDescComparator = compareBy<Favorite> { it.id },
        )

        data class VisitKey(val name: String, val time: Double)

        val seenRemovalKeys = HashSet<VisitKey>()
        val filteredRemovals = (historyRemovals + other.historyRemovals).filter {
            it.time > mergedClearedAt && seenRemovalKeys.add(VisitKey(it.name, it.time))
        }
        val mergedHistoryRemovals = prune(
            values = filteredRemovals,
            nowMillis = nowMillis,
            timeOf = { it.time },
            ascComparator = Comparator { a, b ->
                val cmp = a.time.compareTo(b.time)
                if (cmp != 0) cmp else compareUtf16(a.name, b.name)
            },
            tieDescComparator = Comparator { a, b -> compareUtf16(a.name, b.name) },
        )

        val removedSet = mergedHistoryRemovals.map { VisitKey(it.name, it.time) }.toSet()
        val selectedHistories = HashMap<VisitKey, Visit>()
        for (entry in histories + other.histories) {
            if (entry.time <= mergedClearedAt) continue
            val key = VisitKey(entry.recipe.name, entry.time)
            if (key in removedSet) continue
            val previous = selectedHistories[key]
            if (previous != null && compareUtf16(entry.recipe.orderingKey(), previous.recipe.orderingKey()) >= 0) {
                continue
            }
            selectedHistories[key] = entry
        }

        val sortedHistories = selectedHistories.values
            .sortedWith { a, b ->
                val cmp = a.time.compareTo(b.time)
                if (cmp != 0) cmp else compareUtf16(a.recipe.name, b.recipe.name)
            }
            .let { list ->
                if (list.size > MERGED_HISTORY_CAP) list.takeLast(MERGED_HISTORY_CAP) else list
            }

        return UserDataDocument(
            version = SUPPORTED_VERSION,
            favorites = mergedFavorites,
            favoriteRemovals = mergedFavoriteRemovals,
            histories = sortedHistories,
            historyRemovals = mergedHistoryRemovals,
            historyClearedAt = mergedClearedAt,
        )
    }

    /** 用源文件和目标库的删除记录筛选新增条目，优先保留目标库已有的有效记录。 */
    fun importMerging(incoming: UserDataDocument, historyCapacity: Int = MAX_HISTORIES): UserDataDocument =
        mergeFileRecords(incoming, MAX_FAVORITES, historyCapacity)

    /** 保留能阻止旧备份复活记录的删除标记。当前库的活动条目仍优先。 */
    fun restoreMerging(incoming: UserDataDocument): UserDataDocument {
        val combined = mergeFileRecords(incoming, Int.MAX_VALUE, Int.MAX_VALUE)
        val activeFavorites = combined.favorites.mapTo(HashSet()) { it.id }
        val activeVisits = combined.histories.mapTo(HashSet()) { Removal(it.recipe.name, it.time) }
        val incomingCutoff = incoming.historyClearedAt.takeUnless { cutoff ->
            combined.histories.any { it.time <= cutoff }
        } ?: 0.0
        return combined.copy(
            favoriteRemovals = latestById(combined.favoriteRemovals + incoming.favoriteRemovals).values
                .filterNot { it.id in activeFavorites }
                .sortedWith(compareBy<Favorite> { it.time }.thenBy { it.id }),
            historyRemovals = (combined.historyRemovals + incoming.historyRemovals)
                .distinct().filterNot { it in activeVisits }
                .sortedWith(compareBy<Removal> { it.time }.thenBy { it.name }),
            historyClearedAt = maxOf(historyClearedAt, incomingCutoff),
        )
    }

    private fun mergeFileRecords(incoming: UserDataDocument, favoriteCapacity: Int, historyCapacity: Int): UserDataDocument {
        val favoriteOrder = compareBy<Favorite> { it.time }.thenBy { it.id }
        val historyOrder = compareBy<Visit> { it.time }.thenBy { it.recipe.name }
        val existingFavoriteIds = favorites.mapTo(HashSet()) { it.id }
        val removalsById = latestById(favoriteRemovals + incoming.favoriteRemovals)
        val incomingFavorites = incoming.favorites.filter {
            it.id !in existingFavoriteIds && it.time > (removalsById[it.id]?.time ?: -1.0)
        }
        val addedFavorites = latestById(incomingFavorites).values.sortedWith(favoriteOrder)
            .takeLast((favoriteCapacity - favorites.size).coerceAtLeast(0))
        val mergedFavorites = (favorites + addedFavorites).sortedWith(favoriteOrder)
        val retainedFavorites = mergedFavorites.associateBy { it.id }
        val mergedFavoriteRemovals = favoriteRemovals
            .filter { it.time >= (retainedFavorites[it.id]?.time ?: -1.0) }

        val clearedThrough = maxOf(historyClearedAt, incoming.historyClearedAt)
        val removedVisits = (historyRemovals + incoming.historyRemovals).toHashSet()
        val existingVisits = histories.mapTo(HashSet()) { Removal(it.recipe.name, it.time) }
        val incomingHistories = incoming.histories.filter {
            val key = Removal(it.recipe.name, it.time)
            it.time > clearedThrough && key !in removedVisits && key !in existingVisits
        }
        val selectedHistories = LinkedHashMap<Removal, Visit>()
        for (entry in incomingHistories) {
            val key = Removal(entry.recipe.name, entry.time)
            val prev = selectedHistories[key]
            if (prev != null && compareUtf16(entry.recipe.orderingKey(), prev.recipe.orderingKey()) >= 0) {
                continue
            }
            selectedHistories[key] = entry
        }
        val addedHistories = selectedHistories.values.sortedWith(historyOrder)
            .takeLast((historyCapacity - histories.size).coerceAtLeast(0))
        val mergedHistories = (histories + addedHistories).sortedWith(historyOrder)

        return UserDataDocument(
            version = SUPPORTED_VERSION,
            favorites = mergedFavorites,
            favoriteRemovals = mergedFavoriteRemovals,
            histories = mergedHistories,
            historyRemovals = historyRemovals,
            historyClearedAt = historyClearedAt,
        )
    }

    companion object {
        const val SUPPORTED_VERSION = 1
        const val PROTOCOL_VERSION = 2
        const val MAX_DOCUMENT_BYTES = 256 * 1024
        const val MAX_FAVORITES = 5000
        const val MAX_HISTORIES = 500
        const val MAX_REMOVALS = 1000
        const val MAX_NAME_UTF16_LENGTH = 200
        const val MERGED_HISTORY_CAP = 200
        const val PRUNED_REMOVAL_CAP = 500
        const val REMOVAL_RETENTION_MS = 180.0 * 24 * 60 * 60 * 1000

        // Sync service responses may include server metadata (e.g. updatedAt).
        val wireJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(rawJson: String): UserDataDocument {
            val rawBytes = rawJson.toByteArray(StandardCharsets.UTF_8)
            if (rawBytes.size > MAX_DOCUMENT_BYTES) {
                throw InvalidDocumentException("Raw JSON exceeds $MAX_DOCUMENT_BYTES bytes")
            }
            val value = try {
                wireJson.decodeFromString<UserDataDocument>(rawJson)
            } catch (e: Exception) {
                throw InvalidDocumentException(e.message ?: "Malformed JSON")
            }
            value.validate()
            return value
        }

        private fun latestById(items: List<Favorite>): Map<Int, Favorite> {
            val result = HashMap<Int, Favorite>()
            for (item in items) {
                val existing = result[item.id]
                if (existing == null || item.time > existing.time) {
                    result[item.id] = item
                }
            }
            return result
        }

        private fun <T> prune(
            values: List<T>,
            nowMillis: Double,
            timeOf: (T) -> Double,
            ascComparator: Comparator<T>,
            tieDescComparator: Comparator<T>,
        ): List<T> {
            val cutoff = nowMillis - REMOVAL_RETENTION_MS
            return values
                .filter { timeOf(it) >= cutoff }
                .sortedWith { a, b ->
                    val ta = timeOf(a)
                    val tb = timeOf(b)
                    if (ta == tb) tieDescComparator.compare(a, b) else tb.compareTo(ta)
                }
                .take(PRUNED_REMOVAL_CAP)
                .sortedWith(ascComparator)
        }

        private fun compareUtf16(a: String, b: String): Int = a.compareTo(b)
    }
}
