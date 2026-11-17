package cn.yunyoujun.cook.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CookDatabaseSchemaTest {
    @Test
    fun exportsRoomSchemaWithDeviceAndAccountIsolationTables() {
        val schemasDir = File(
            System.getProperty("cook.room.schemas.dir")
                ?: error("Missing cook.room.schemas.dir"),
        )
        val schemaFile = File(schemasDir, "${CookDatabase::class.qualifiedName}/1.json")
        assertTrue("Expected exported Room schema at ${schemaFile.absolutePath}", schemaFile.isFile)
        val schemaText = schemaFile.readText(Charsets.UTF_8)
        assertTrue(schemaText.contains("device_favorites"))
        assertTrue(schemaText.contains("device_histories"))
        assertTrue(schemaText.contains("account_favorites"))
        assertTrue(schemaText.contains("account_sync_state"))
    }

    @Test
    fun opensRealRoomDatabaseAndRollsBackFailedTransactions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(context, CookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val deviceDao = db.deviceLibraryDao()
            deviceDao.addFavoriteTransactional(recipeId = 250, addedAtMs = 1000.0)
            assertEquals(listOf(250), deviceDao.getAllFavorites().map { it.recipeId })

            // Inject failure mid-transaction after clearing; Room must roll back and preserve 250.
            runCatching {
                db.withTransaction {
                    deviceDao.clearAllFavorites()
                    error("simulated write failure")
                }
            }
            assertEquals(listOf(250), deviceDao.getAllFavorites().map { it.recipeId })
        } finally {
            db.close()
        }
    }
}
