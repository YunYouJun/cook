package cn.yunyoujun.cook.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CookDatabaseSchemaTest {
    @Test
    fun exportsRoomSchemaWithDeviceAndAccountIsolationTables() {
        val schemaFile = exportedSchema(version = 2)
        assertTrue("Expected exported Room schema at ${schemaFile.absolutePath}", schemaFile.isFile)
        val schemaText = schemaFile.readText(Charsets.UTF_8)
        assertTrue(schemaText.contains("device_favorites"))
        assertTrue(schemaText.contains("device_histories"))
        assertTrue(schemaText.contains("account_favorites"))
        assertTrue(schemaText.contains("account_sync_state"))
    }

    @Test
    fun versionOneUpgradePreservesBothLibrariesAndLeavesUnverifiedSyncTimeEmpty() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "cook-room-upgrade-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val schema = JSONObject(exportedSchema(version = 1).readText()).getJSONObject("database")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { oldDatabase ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    oldDatabase.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (entry in 0 until indices.length()) {
                        val indexSql = indices.getJSONObject(entry).getString("createSql")
                        oldDatabase.execSQL(indexSql.replace("\${TABLE_NAME}", table))
                    }
                }
                val setupQueries = schema.getJSONArray("setupQueries")
                for (index in 0 until setupQueries.length()) oldDatabase.execSQL(setupQueries.getString(index))
                oldDatabase.execSQL("INSERT INTO device_favorites VALUES (10, 1000)")
                oldDatabase.execSQL("INSERT INTO account_favorites VALUES ('legacy-account', 250, 2000)")
                oldDatabase.execSQL(
                    "INSERT INTO account_sync_state VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any>("legacy-account", 1, 1800.0, """{"favorites":[{"id":250,"time":2000}]}""", 3000.0),
                )
                oldDatabase.version = 1
            }

            val upgraded = Room.databaseBuilder(context, CookDatabase::class.java, name).build()
            try {
                assertEquals(listOf(10), upgraded.deviceLibraryDao().getAllFavorites().map { it.recipeId })
                val accountDao = upgraded.accountLibraryDao()
                assertEquals(listOf(250), accountDao.getFavorites("legacy-account").map { it.recipeId })
                val account = requireNotNull(accountDao.getSyncState("legacy-account"))
                assertTrue(account.syncEnabled)
                assertEquals(1800.0, account.historyClearedAtMs, 0.0)
                assertEquals("""{"favorites":[{"id":250,"time":2000}]}""", account.lastSyncedDocumentJson)
                assertEquals(3000.0, account.updatedAtMs, 0.0)
                assertNull(account.lastSyncedAtMs)
            } finally {
                upgraded.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
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

    private fun exportedSchema(version: Int): File = File(
        System.getProperty("cook.room.schemas.dir") ?: error("Missing cook.room.schemas.dir"),
        "${CookDatabase::class.qualifiedName}/$version.json",
    )
}
