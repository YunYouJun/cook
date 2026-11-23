package cn.yunyoujun.cook.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserDataDocumentTest {
    private val strictJson = Json { ignoreUnknownKeys = false }

    @Serializable
    private data class SyncFixtureCase(
        val name: String,
        val now: Double,
        val a: UserDataDocument,
        val b: UserDataDocument,
        val expected: UserDataDocument,
    )

    @Serializable
    private data class SyncBoundaryCase(
        val name: String,
        val kind: String,
        val now: Double,
        val leftStart: Int,
        val rightStart: Int,
        val count: Int,
        val expectedStart: Int,
        val expectedCount: Int,
    )

    @Serializable
    private data class FileImportCase(
        val file: JsonObject,
        val expectedFavorites: List<UserDataDocument.Favorite>,
        val expectedHistories: List<UserDataDocument.Removal>,
    )

    @Serializable
    private data class HistoryClearCase(
        val name: String,
        val now: Double,
        val current: UserDataDocument,
        val peer: UserDataDocument,
        val expectedCutoff: Double,
        val expectedRemovals: List<UserDataDocument.Removal>,
        val expectedVisits: List<UserDataDocument.Removal>,
    )

    private fun fixturesDir(): File {
        val path = System.getProperty("cook.fixtures.dir")
            ?: error("Missing system property cook.fixtures.dir")
        return File(path)
    }

    @Test
    fun passesSharedUserDataSyncFixtures() {
        val raw = File(fixturesDir(), "user-data-sync.json").readText(Charsets.UTF_8)
        val cases = strictJson.decodeFromString<List<SyncFixtureCase>>(raw)
        for (case in cases) {
            val ab = case.a.merging(case.b, nowMillis = case.now)
            val ba = case.b.merging(case.a, nowMillis = case.now)
            assertEquals("Case failed (a->b): ${case.name}", case.expected, ab)
            assertEquals("Case failed (b->a): ${case.name}", case.expected, ba)
        }
    }

    @Test
    fun passesSharedHistoryClearFixtures() {
        val raw = File(fixturesDir(), "user-data-history-clear.json").readText(Charsets.UTF_8)
        for (case in strictJson.decodeFromString<List<HistoryClearCase>>(raw)) {
            val cleared = case.current.clearingHistory(case.now)
            assertTrue(case.name, cleared.histories.isEmpty())
            assertEquals(case.name, case.expectedCutoff, cleared.historyClearedAt, 0.0)
            assertEquals(case.name, case.expectedRemovals, cleared.historyRemovals)
            assertEquals(case.name, cleared, cleared.clearingHistory(case.now))
            val merged = cleared.merging(case.peer, nowMillis = case.now)
            val visits = merged.histories.map { UserDataDocument.Removal(it.recipe.name, it.time) }
            assertEquals(case.name, case.expectedVisits, visits)
            assertEquals(case.name, case.current.favorites, merged.favorites)
        }
    }

    @Test
    fun passesSharedUserDataSyncBoundaryFixtures() {
        val raw = File(fixturesDir(), "user-data-sync-boundaries.json").readText(Charsets.UTF_8)
        val cases = strictJson.decodeFromString<List<SyncBoundaryCase>>(raw)
        for (case in cases) {
            when (case.kind) {
                "favorites" -> {
                    val left = UserDataDocument(
                        favoriteRemovals = (0 until case.count).map { i ->
                            UserDataDocument.Favorite(id = case.leftStart + i, time = case.now)
                        },
                    )
                    val right = UserDataDocument(
                        favoriteRemovals = (0 until case.count).map { i ->
                            UserDataDocument.Favorite(id = case.rightStart + i, time = case.now)
                        },
                    )
                    val merged = left.merging(right, nowMillis = case.now)
                    assertEquals(case.name, case.expectedCount, merged.favoriteRemovals.size)
                    assertEquals(case.name, case.expectedStart, merged.favoriteRemovals.first().id)
                }

                "histories" -> {
                    val left = UserDataDocument(
                        historyRemovals = (0 until case.count).map { i ->
                            UserDataDocument.Removal(
                                name = "菜谱-%04d".format(case.leftStart + i),
                                time = case.now,
                            )
                        },
                    )
                    val right = UserDataDocument(
                        historyRemovals = (0 until case.count).map { i ->
                            UserDataDocument.Removal(
                                name = "菜谱-%04d".format(case.rightStart + i),
                                time = case.now,
                            )
                        },
                    )
                    val merged = left.merging(right, nowMillis = case.now)
                    assertEquals(case.name, case.expectedCount, merged.historyRemovals.size)
                    assertEquals(
                        case.name,
                        "菜谱-%04d".format(case.expectedStart),
                        merged.historyRemovals.first().name,
                    )
                }

                else -> error("Unknown boundary kind: ${case.kind}")
            }
        }
    }

    @Test
    fun manualImportCopiesOnlyActiveFileRecords() {
        val fixture = strictJson.decodeFromString<FileImportCase>(File(fixturesDir(), "user-data-file-import.json").readText())
        val file = fixture.file.toString()
        val rawDocument = UserDataExportFile.parse(file)
        assertEquals(4, rawDocument.favoriteRemovals.size)
        assertEquals(1, rawDocument.historyRemovals.size)

        val imported = UserDataDocument().importMerging(rawDocument)
        assertEquals(fixture.expectedFavorites, imported.favorites)
        assertEquals(
            fixture.expectedHistories.map { it.name to it.time },
            imported.histories.map { it.recipe.name to it.time },
        )
        assertEquals(UserDataDocument(), imported.copy(favorites = emptyList(), histories = emptyList()))
    }

    @Test
    fun aFullImportPreservesExistingHistoryAndFavoriteTimestamps() {
        val existing = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot("旧记录"), 1.0)
        val favorite = UserDataDocument.Favorite(3, 2.0)
        val added = List(UserDataDocument.MAX_HISTORIES) { index ->
            UserDataDocument.Visit(UserDataDocument.RecipeSnapshot("新记录"), index + 10.0)
        }
        val merged = UserDataDocument(favorites = listOf(favorite), histories = listOf(existing)).importMerging(
            UserDataDocument(favorites = listOf(favorite.copy(time = 100.0)), histories = added),
        )
        assertEquals(listOf(favorite), merged.favorites)
        assertEquals(listOf(existing) + added.drop(1), merged.histories)
    }

    @Test
    fun wireDecodeAllowsExtraServerFieldsWhileRejectingOversizedOrInvalidPayloads() {
        val decoded = UserDataDocument.decode(
            """{"version":1,"favorites":[{"id":1,"time":10}],"updatedAt":123456}""",
        )
        assertEquals(1, decoded.favorites.size)
    }

    @Test
    fun restoreMergingCapsTombstonesAndPrunesClearedHistoryRemovals() {
        val current = UserDataDocument(
            favoriteRemovals = (1..600).map { UserDataDocument.Favorite(id = it, time = it.toDouble()) },
            historyRemovals = (1..600).map { UserDataDocument.Removal(name = "旧菜-$it", time = (1000 + it).toDouble()) },
            historyClearedAt = 1200.0,
        )
        val incoming = UserDataDocument(
            favoriteRemovals = (601..1200).map { UserDataDocument.Favorite(id = it, time = it.toDouble()) },
            historyRemovals = (601..1200).map { UserDataDocument.Removal(name = "新菜-$it", time = (1000 + it).toDouble()) },
        )
        val restored = current.restoreMerging(incoming)
        restored.validate()
        assertEquals(UserDataDocument.MAX_REMOVALS, restored.favoriteRemovals.size)
        assertEquals(201, restored.favoriteRemovals.first().id)
        assertEquals(1200, restored.favoriteRemovals.last().id)
        assertEquals(1000, restored.historyRemovals.size)
        assertTrue(restored.historyRemovals.all { it.time > 1200.0 })
        assertEquals(1201.0, restored.historyRemovals.first().time, 0.0)
        assertEquals(2200.0, restored.historyRemovals.last().time, 0.0)
    }

    @Test(expected = UserDataDocument.InvalidDocumentException::class)
    fun rejectsOversizedDocument() {
        val largeName = "a".repeat(150)
        val manyHistories = (0 until 1500).map { i ->
            UserDataDocument.Visit(
                recipe = UserDataDocument.RecipeSnapshot(name = "$largeName-$i"),
                time = i.toDouble(),
            )
        }
        UserDataDocument(histories = manyHistories).validate()
    }
}
