package cn.yunyoujun.cook

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.core.sync.CatalogCategory
import cn.yunyoujun.cook.core.sync.PrivateRecipe
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.data.local.AccountSyncStateEntity
import cn.yunyoujun.cook.data.local.AiProviderMode
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.AppThemeMode
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.CookingJournalStore
import cn.yunyoujun.cook.data.local.DeviceBackupSection
import cn.yunyoujun.cook.data.local.DeviceBackupStore
import cn.yunyoujun.cook.data.local.DeviceHistoryEntity
import cn.yunyoujun.cook.data.local.DevicePreferencesStore
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.FridgeDateKind
import cn.yunyoujun.cook.data.local.FridgeStorageStart
import cn.yunyoujun.cook.data.local.FridgeStore
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.PersonalCatalogStore
import cn.yunyoujun.cook.data.local.PrivateRecipeVideoStore
import cn.yunyoujun.cook.data.local.PrivateRecipesStore
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.local.SignalConsentState
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.data.repository.UserLibraryRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceBackupTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun roundTripIncludesDeviceDataAndKeepsTheSignedInLibraryAndSecretsSeparate() = runBlocking {
        fixture().use { original ->
            fixture().use { destination ->
                original.seedAccount("source-account", 98)
                val account = destination.seedAccount("destination-account", 99)
                original.db.deviceLibraryDao().addFavoriteTransactional(3, 1_000.0)
                val visit = UserDataDocument.RecipeSnapshot("换机前看过的菜")
                original.db.deviceLibraryDao().recordVisitTransactional(
                    DeviceHistoryEntity(
                        recipeName = visit.name,
                        visitedAtMs = 2_000.0,
                        snapshotJson = UserDataDocument.wireJson.encodeToString(visit),
                    ),
                    UserDataDocument.MAX_HISTORIES,
                )
                original.preferences.setPantry(emptyList())
                original.preferences.setAvoid(listOf("香菜"))
                original.preferences.setNoSpicy(true)
                original.preferences.setThemeMode(AppThemeMode.DARK)
                original.preferences.setCloudKitchen(true)
                original.preferences.setSignalConsent(true)
                original.preferences.setAiProviderMode(AiProviderMode.MANAGED_MEMBERSHIP)
                original.preferences.recordSearch("private-search")
                original.context.filesDir.resolve("credentials-fixture").writeText("private-key")

                original.fridge.renameCategory("veg", "下周要用")
                original.fridge.addCategory("预备菜")
                val cutDay = FridgeStorageStart("2026-10-07", wasCut = true)
                original.fridge.addItems(listOf(
                    batch().copy(dateKind = FridgeDateKind.ADVISORY, storageStart = cutDay),
                    batch("finished", consumed = 4_000L),
                ))
                original.personalCatalog.addCustomEntry("会恢复的厨具", CatalogCategory.TOOLS).getOrThrow()
                original.personalCatalog.toggleHiddenEntry(CatalogCategory.VEGETABLE, "小葱")
                original.privateRecipes.upsertRecipe(recipe(video = localVideo()))
                original.privateRecipes.deleteRecipe("custom-deleted")
                original.journal.confirmLearned(JournalRecipeId.Personal("custom-1"), "鸡蛋汤", 3_000L)

                val bytes = original.backup.export()
                assertTrue(listOf("private-search", "private-key", "source-account", "fixture-refresh", "attachment.mp4", "signalConsent")
                    .none { bytes.decodeToString().contains(it) })
                val preview = destination.backup.read(bytes.inputStream())
                assertEquals(listOf(UserDataDocument.Favorite(3, 1_000.0)), preview.library.favorites)
                assertEquals(listOf(UserDataDocument.Visit(visit, 2_000.0)), preview.library.histories)
                assertTrue(destination.library.snapshotDeviceLibrary().favorites.isEmpty())
                assertTrue(destination.fridge.state.value.items.isEmpty())

                destination.preferences.setSignalConsent(false)
                destination.preferences.setAiProviderMode(AiProviderMode.BYOK_DEEPSEEK)
                for (section in DeviceBackupSection.entries) {
                    destination.backup.restore(preview, section)
                    destination.backup.restore(preview, section)
                }
                assertEquals(preview.library, destination.library.snapshotDeviceLibrary())
                assertEquals(listOf(UserDataDocument.Favorite(99, 9_000.0)), account.state.value.accountDocument.favorites)
                assertTrue(account.state.value.usesAccountLibrary)
                val settings = DevicePreferencesStore(destination.context).current
                assertTrue(settings.pantryInitialized)
                assertTrue(settings.pantry.isEmpty())
                assertTrue(settings.noSpicy)
                assertEquals(AppThemeMode.DARK, settings.themeMode)
                assertEquals(listOf("香菜"), settings.avoid)
                assertEquals(SignalConsentState.DECLINED, settings.signalConsent)
                assertEquals(AiProviderMode.BYOK_DEEPSEEK, settings.aiProviderMode)
                assertTrue(settings.recentSearches.isEmpty())
                assertEquals(preview.fridge, FridgeStore(destination.context).state.value)
                assertEquals(cutDay, FridgeStore(destination.context).state.value.stock.single().storageStart)
                assertEquals(preview.personalCatalog, PersonalCatalogStore(destination.context).state.value.toDocument())
                assertEquals(preview.journal, CookingJournalStore(destination.context).state.value)
                val private = PrivateRecipesStore(context = destination.context).state.value
                assertEquals("鸡蛋汤", private.recipes.single().name)
                assertNull(private.recipes.single().video)
                assertEquals(setOf("custom-deleted"), private.deletedIds)
            }
        }
    }

    @Test
    fun restoreLibraryKeepsDestinationDeletionsWhenMergingAnOlderBackup() = runBlocking {
        fixture().use { original ->
            fixture().use { destination ->
                val recipe = UserDataDocument.RecipeSnapshot("蒸蛋羹")
                val cleared = UserDataDocument.Visit(recipe, 2_000.0)
                val removed = UserDataDocument.Visit(recipe, 4_000.125)
                val current = UserDataDocument.Visit(recipe, 4_000.375)
                original.library.restoreDeviceLibrary(UserDataDocument(
                    favorites = listOf(UserDataDocument.Favorite(3, 1_000.0)),
                    histories = listOf(cleared, removed, current),
                ))
                val deletions = UserDataDocument(
                    favoriteRemovals = listOf(UserDataDocument.Favorite(3, 1_000.0)),
                    historyRemovals = listOf(UserDataDocument.Removal(recipe.name, removed.time)),
                    historyClearedAt = cleared.time,
                )
                destination.library.restoreDeviceLibrary(deletions)
                val backup = destination.backup.read(original.backup.export().inputStream())

                destination.backup.restore(backup, DeviceBackupSection.LIBRARY)
                assertEquals(deletions.copy(histories = listOf(current)), destination.library.snapshotDeviceLibrary())
            }
        }
    }

    @Test
    fun restoringAnOlderBackupAfterADeletionBackupDoesNotResurrectRecords() = runBlocking {
        fixture().use { destination ->
            val existing = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot("米饭"), 8_000.0)
            val cleared = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot("蒸蛋羹"), 2_000.0)
            val removed = cleared.copy(time = 4_000.125)
            val favorite = UserDataDocument.Favorite(3, 1_000.0)
            val removals = UserDataDocument(
                favoriteRemovals = listOf(favorite.copy(time = 5_000.0)),
                historyRemovals = listOf(UserDataDocument.Removal(removed.recipe.name, removed.time)),
                historyClearedAt = cleared.time,
            )
            destination.library.restoreDeviceLibrary(UserDataDocument(histories = listOf(existing)))
            destination.library.restoreDeviceLibrary(removals)
            destination.library.restoreDeviceLibrary(
                UserDataDocument(favorites = listOf(favorite), histories = listOf(cleared, removed)),
            )
            assertEquals(removals.copy(histories = listOf(existing)), destination.library.snapshotDeviceLibrary())
        }
    }

    @Test
    fun restoreMergesFridgeByIdentityAndHonorsLocalRecipeDeletions() = runBlocking {
        fixture().use { original ->
            fixture().use { destination ->
                original.fridge.addCategory("Dry")
                destination.fridge.addCategory("dry")
                val originalId = original.fridge.state.value.categories.last().id
                val currentId = destination.fridge.state.value.categories.last().id
                original.fridge.addItems(listOf(batch("repeated"), batch("new").copy(categoryId = originalId)))
                destination.fridge.addItems(listOf(batch("repeated", consumed = 6_000L)))
                original.privateRecipes.upsertRecipe(recipe())
                destination.privateRecipes.upsertRecipe(recipe())
                destination.privateRecipes.deleteRecipe("custom-1")
                val preview = destination.backup.read(original.backup.export().inputStream())

                destination.backup.restore(preview, DeviceBackupSection.FRIDGE)
                destination.backup.restore(preview, DeviceBackupSection.FRIDGE)
                destination.backup.restore(preview, DeviceBackupSection.PRIVATE_RECIPES)

                val restored = destination.fridge.state.value
                assertEquals(2, restored.items.size)
                assertEquals(listOf("dry"), restored.categories.filter { it.name.equals("dry", ignoreCase = true) }.map { it.name })
                assertEquals(currentId, restored.stock.single().categoryId)
                assertEquals(6_000L, restored.consumed.single().consumedAtMs)
                assertTrue(destination.privateRecipes.state.value.recipes.isEmpty())
                assertTrue("custom-1" in destination.privateRecipes.state.value.deletedIds)
            }
        }
    }

    @Test
    fun invalidBackupLeavesDeviceDataAloneAndFailedRoomRestoreCanRetry() = runBlocking {
        fixture().use { original ->
            fixture().use { destination ->
                original.fridge.addItems(listOf(batch()))
                original.db.deviceLibraryDao().addFavoriteTransactional(1, 1_000.0)
                destination.db.deviceLibraryDao().addFavoriteTransactional(2, 2_000.0)
                val text = original.backup.export().decodeToString()
                for (bad in listOf(
                    text.replace("cook.android.device-backup", "unrelated"),
                    text.replace("2026-10-09", "2026-02-30"),
                    text.dropLast(3),
                )) {
                    assertTrue(runCatching { destination.backup.read(bad.byteInputStream()) }.isFailure)
                    assertTrue(destination.fridge.state.value.items.isEmpty())
                }
                val preview = destination.backup.read(text.byteInputStream())
                destination.library.beforeImportCommitHook = { throw IOException("injected failure") }
                assertTrue(runCatching { destination.backup.restore(preview, DeviceBackupSection.LIBRARY) }.isFailure)
                assertEquals(listOf(2), destination.library.snapshotDeviceLibrary().favorites.map { it.id })
                destination.library.beforeImportCommitHook = null
                destination.backup.restore(preview, DeviceBackupSection.LIBRARY)
                assertEquals(setOf(1, 2), destination.library.snapshotDeviceLibrary().favorites.map { it.id }.toSet())
                assertFalse(destination.fridge.state.value.items.any())
            }
        }
    }

    @Test
    fun archiveRestoresSharedVideosOnNewDeviceAndPreservesExistingAndDeletedRecipes() = runBlocking {
        fixture().use { original ->
            fixture().use { destination ->
                val source = temporary.newFile("attachment.mp4").apply { writeBytes(byteArrayOf(2, 5, 8)) }
                val video = original.videos.install(original.videos.stageFromFile(source))
                original.privateRecipes.upsertRecipe(recipe(video).copy(customId = "shared-1"))
                original.privateRecipes.upsertRecipe(recipe(video).copy(customId = "shared-2"))
                original.privateRecipes.upsertRecipe(recipe(video).copy(customId = "existing"))
                original.privateRecipes.upsertRecipe(recipe(video).copy(customId = "deleted"))
                original.privateRecipes.upsertRecipe(recipe(localVideo()).copy(customId = "foreign"))
                destination.privateRecipes.upsertRecipe(recipe().copy(customId = "existing", note = "本机内容"))
                destination.privateRecipes.deleteRecipe("deleted")

                val archive = ByteArrayOutputStream().also { original.backup.writeArchive(it) }.toByteArray()
                val preview = destination.backup.readPreview(archive.inputStream())
                assertEquals("5 道菜谱 · 1 个视频", preview.detail(DeviceBackupSection.PRIVATE_RECIPES))
                assertEquals(setOf("shared-1", "shared-2", "existing", "deleted"), preview.videos.keys)
                assertEquals(1, destination.privateRecipes.state.value.recipes.size)

                repeat(2) {
                    destination.backup.restorePrivateRecipesWithVideos(preview, archive::inputStream)
                }
                val recipes = PrivateRecipesStore(context = destination.context).state.value.recipes.associateBy { it.customId }
                val restored = requireNotNull(recipes.getValue("shared-1").video)
                assertEquals(restored, recipes.getValue("shared-2").video)
                assertEquals(destination.videos.deviceId, restored.deviceID)
                assertTrue(restored.id != video.id)
                assertArrayEquals(source.readBytes(), destination.videos.playableFile(restored).readBytes())
                assertEquals("本机内容", recipes.getValue("existing").note)
                assertNull(recipes.getValue("existing").video)
                assertNull(recipes.getValue("foreign").video)
                assertFalse("deleted" in recipes)
                assertEquals(1, destination.context.filesDir.resolve("PrivateRecipeVideos/Files").listFiles()?.size)
            }
        }
    }

    @Test
    fun archiveReadAndRecipeWriteFailuresLeaveNoPartialRestoreAndCanRetry() = runBlocking {
        fixture().use { original ->
            fixture().use { destination ->
                val first = temporary.newFile("first.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val second = temporary.newFile("second.mp4").apply { writeBytes(byteArrayOf(4, 5, 6)) }
                original.privateRecipes.upsertRecipe(recipe(original.videos.install(original.videos.stageFromFile(first))))
                original.privateRecipes.upsertRecipe(
                    recipe(original.videos.install(original.videos.stageFromFile(second))).copy(customId = "custom-2"),
                )
                val archive = ByteArrayOutputStream().also { original.backup.writeArchive(it) }.toByteArray()
                val preview = destination.backup.readPreview(archive.inputStream())
                val corrupted = ByteArrayOutputStream()
                ZipInputStream(archive.inputStream()).use { input ->
                    ZipOutputStream(corrupted).use { output ->
                        repeat(3) { index ->
                            val entry = requireNotNull(input.nextEntry)
                            val bytes = input.readBytes()
                            output.putNextEntry(ZipEntry(entry.name))
                            output.write(if (index == 2) bytes.copyOf(bytes.size - 1) else bytes)
                            output.closeEntry()
                        }
                    }
                }
                val broken = corrupted.toByteArray()
                assertEquals(preview, destination.backup.readPreview(broken.inputStream()))

                suspend fun expectCleanFailure(bytes: ByteArray) {
                    assertTrue(runCatching {
                        destination.backup.restorePrivateRecipesWithVideos(preview, bytes::inputStream)
                    }.isFailure)
                    assertTrue(destination.privateRecipes.state.value.recipes.isEmpty())
                    for (directory in listOf("Drafts", "Files")) {
                        assertTrue(destination.context.filesDir.resolve("PrivateRecipeVideos/$directory").listFiles().isNullOrEmpty())
                    }
                }
                expectCleanFailure(broken)

                val blockedWrite = destination.context.filesDir.resolve("private-recipes-v1.json.tmp")
                assertTrue(blockedWrite.mkdir())
                expectCleanFailure(archive)
                assertTrue(blockedWrite.delete())

                destination.backup.restorePrivateRecipesWithVideos(preview, archive::inputStream)
                assertEquals(2, destination.privateRecipes.state.value.recipes.count { it.video != null })
            }
        }
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("${root.name}-$name", mode)
        }
        return Fixture(context, Room.inMemoryDatabaseBuilder(base, CookDatabase::class.java).build())
    }

    private class Fixture(val context: Context, val db: CookDatabase) : AutoCloseable {
        val library = UserLibraryRepository(db)
        val preferences = DevicePreferencesStore(context)
        val fridge = FridgeStore(context)
        val privateRecipes = PrivateRecipesStore(context = context)
        val personalCatalog = PersonalCatalogStore(context)
        val journal = CookingJournalStore(context)
        val videos = PrivateRecipeVideoStore(context = context, inspectVideo = {})
        val backup = DeviceBackupStore(library, preferences, fridge, privateRecipes, personalCatalog, journal, videos)

        suspend fun seedAccount(uid: String, favoriteId: Int): YunlefunSessionManager {
            val credential = YunlefunCredential("fixture-token", "fixture-refresh", uid, Long.MAX_VALUE, "fixture")
            val document = UserDataDocument(favorites = listOf(UserDataDocument.Favorite(favoriteId, 9_000.0)))
            db.accountLibraryDao().upsertSyncState(AccountSyncStateEntity(
                accountUid = uid,
                syncEnabled = true,
                historyClearedAtMs = 0.0,
                lastSyncedDocumentJson = UserDataDocument.wireJson.encodeToString(document),
                updatedAtMs = 9_000.0,
            ))
            val vault = object : SecretVault {
                override fun readSecret(key: String): String? =
                    Json.encodeToString(credential).takeIf { key == AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON }
                override fun writeSecret(key: String, value: String) = Unit
                override fun deleteSecret(key: String) = Unit
            }
            return YunlefunSessionManager(vault, db).also {
                it.awaitInitialHydration()
                library.bindSessionManager(it)
            }
        }

        override fun close() = db.close()
    }

    @Test
    fun cookingJournalRecoversFromBackupWhenPrimaryFileIsCorrupt() {
        val dir = temporary.newFolder("journal-recovery")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = dir
        }
        val expected = cn.yunyoujun.cook.data.local.CookingJournal(
            cooked = listOf(
                cn.yunyoujun.cook.data.local.CookingRecord(
                    recipeId = JournalRecipeId.Catalog(250),
                    recipeName = "番茄炒蛋",
                    dateMs = 1_000L,
                    id = "rec-1",
                ),
            ),
        )
        File(dir, "cooking-journal-v1.json").writeText("{corrupt-json")
        File(dir, "cooking-journal-v1.json.bak").writeText(
            cn.yunyoujun.cook.data.local.AtomicJsonFiles.json.encodeToString(
                cn.yunyoujun.cook.data.local.CookingJournal.serializer(),
                expected,
            ),
        )
        val store = CookingJournalStore(context)
        assertFalse(store.loadFailed)
        assertEquals(expected, store.state.value)
    }

    private fun batch(id: String = "batch-1", consumed: Long? = null) = FridgeBatchItem(
        id = id,
        ingredientName = "西红柿",
        categoryId = "veg",
        quantityText = "2 个",
        addedAtMs = 1_000L,
        expiresOnIsoDate = "2026-10-09",
        dateKind = FridgeDateKind.PACKAGE_EXPIRY,
        consumedAtMs = consumed,
        productionIsoDate = "2026-10-04",
    )

    private fun recipe(video: PrivateRecipeVideo? = null) = PrivateRecipe(
        customId = "custom-1",
        name = "鸡蛋汤",
        stuff = listOf("鸡蛋"),
        tools = emptyList(),
        createdAt = 1_000L,
        updatedAt = 2_000L,
        video = video,
    )

    private fun localVideo() = PrivateRecipeVideo(
        fileExtension = "mp4",
        originalName = "attachment.mp4",
        byteCount = 8L,
        location = PrivateRecipeVideo.Location.LOCAL,
        deviceID = "c6b44ebc-e7a4-44dd-902c-220e5895f748",
    )
}
