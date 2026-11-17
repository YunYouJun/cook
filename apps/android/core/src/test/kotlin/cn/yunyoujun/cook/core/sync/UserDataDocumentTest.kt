package cn.yunyoujun.cook.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
    fun wireDecodeAllowsExtraServerFieldsWhileRejectingOversizedOrInvalidPayloads() {
        val decoded = UserDataDocument.decode(
            """{"version":1,"favorites":[{"id":1,"time":10}],"updatedAt":123456}""",
        )
        assertEquals(1, decoded.favorites.size)
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
