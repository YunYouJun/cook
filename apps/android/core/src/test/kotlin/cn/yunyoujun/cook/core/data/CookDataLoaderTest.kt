package cn.yunyoujun.cook.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CookDataLoaderTest {
    private fun dataDir(): File {
        val path = System.getProperty("cook.data.dir")
            ?: error("Missing system property cook.data.dir")
        return File(path)
    }

    @Test
    fun loadsBundledCookDataFromPackagesData() {
        val data = CookData.loadFromDirectory(dataDir())
        assertEquals(CookData.SUPPORTED_SCHEMA_VERSION, data.meta.schemaVersion)
        assertTrue(data.recipes.isNotEmpty())
        assertEquals(data.meta.total, data.recipes.size)
        assertTrue(data.food.vegetable.isNotEmpty())
        assertTrue(data.pantry.options.isNotEmpty())
        assertTrue(data.query.avoid.isNotEmpty())
        assertTrue(data.shelfLife.items.isNotEmpty())
        assertNotNull(data.recipeAmountsSha256)
        assertEquals(64, data.recipeAmountsSha256!!.length)
    }

    @Test(expected = CookDataException.UnsupportedSchemaVersion::class)
    fun rejectsFutureSchemaVersion() {
        val tempDir = kotlin.io.path.createTempDirectory("cook-data-schema").toFile()
        try {
            File(tempDir, "recipe-meta.json").writeText(
                """{"schemaVersion":2,"dataVersion":"future","chunked":false,"total":0,"version":1}""",
            )
            CookData.loadFromDirectory(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun normalizerDeduplicatesAliasesPreservingOrder() {
        val data = CookData.loadFromDirectory(dataDir())
        val normalized = data.normalizer.unique(listOf(" 西红柿 ", "番茄", "鸡蛋"))
        assertFalse(normalized.isEmpty())
        assertEquals(normalized.distinct(), normalized)
    }

    @Test
    fun fnv1a64IsDeterministicOverUtf8AndNul() {
        val a = CookData.fnv1a64Hex(listOf("番茄炒蛋", "切块"))
        val b = CookData.fnv1a64Hex(listOf("番茄炒蛋", "切块"))
        val c = CookData.fnv1a64Hex(listOf("番茄炒蛋切块"))
        assertEquals(16, a.length)
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
