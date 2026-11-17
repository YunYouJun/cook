package cn.yunyoujun.cook.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.saveable.SaverScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.ai.SearchIntentSource
import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeTextLibrary
import cn.yunyoujun.cook.core.recommend.Recommender
import cn.yunyoujun.cook.core.search.CookQuery
import cn.yunyoujun.cook.core.sync.PrivateRecipe
import cn.yunyoujun.cook.core.sync.PrivateRecipeFileException
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.AtomicJsonFiles
import cn.yunyoujun.cook.data.local.BoundedIoStreams
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.CredentialVaultException
import cn.yunyoujun.cook.data.local.DevicePreferencesSnapshot
import cn.yunyoujun.cook.data.local.KitchenWorkbenchStore
import cn.yunyoujun.cook.data.local.PrivateRecipeVideoStore
import cn.yunyoujun.cook.data.local.PrivateRecipesStore
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.IsolatedHttpPostTransport
import cn.yunyoujun.cook.data.remote.MembershipService
import cn.yunyoujun.cook.data.remote.UserDataSyncService
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.data.repository.CookDiscoveryScene
import cn.yunyoujun.cook.data.repository.SharedLinks
import cn.yunyoujun.cook.data.repository.UserLibraryRepository
import cn.yunyoujun.cook.ui.state.CookSessionController
import kotlinx.serialization.encodeToString
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppIntegrationContractsTest {
    private val dataDir = File("../../../packages/data").canonicalFile
    private val cookData: CookData by lazy { CookData.loadFromDirectory(dataDir) }
    private val textLibrary: RecipeTextLibrary by lazy {
        RecipeTextLibrary.loadFromDirectory(dataDir)!!
    }

    private fun testDatabase(): CookDatabase {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Room.inMemoryDatabaseBuilder(context, CookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun testAead(): Aead {
        AeadConfig.register()
        val keyset = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        return keyset.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun testTinkVault(vaultFile: File, aead: Aead = testAead()): SecretVault =
        AndroidKeystoreVault(vaultFile = vaultFile, aeadProvider = { aead })

    @Test
    fun accountSyncIsolation_processRestartHydration_andDelayedCrossAccountRace() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-session-test").toFile()
        val db = testDatabase()
        try {
            val vault = testTinkVault(File(tempDir, "vault.enc"))
            val credA = YunlefunCredential(
                accessToken = "tok-a",
                refreshToken = "ref-a",
                subject = "uid-alpha",
                expiresAtEpochMs = System.currentTimeMillis() + 3600_000L,
                deviceID = UUID.randomUUID().toString().lowercase(),
            )
            vault.writeSecret(
                AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON,
                """{"accessToken":"tok-a","refreshToken":"ref-a","subject":"uid-alpha","expiresAtEpochMs":${credA.expiresAtEpochMs},"deviceID":"${credA.deviceID}"}""",
            )

            // Delayed sync transport simulates account switch / signOut during in-flight sync
            val remoteDocJson = UserDataDocument.wireJson.encodeToString(
                UserDataDocument(favorites = listOf(UserDataDocument.Favorite(id = 999, time = 5000.0))),
            )
            val delayedTransport = object : HttpTransport {
                override suspend fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    jsonBody: String?,
                    maxResponseBytes: Int,
                    connectTimeoutMs: Int,
                    readTimeoutMs: Int,
                ): HttpResponsePayload {
                    delay(150L)
                    return HttpResponsePayload(
                        statusCode = 200,
                        bodyText = """{"ok":true,"protocolVersion":${UserDataDocument.PROTOCOL_VERSION},"document":$remoteDocJson}""",
                    )
                }
            }
            val fastCheckTransport = object : HttpTransport {
                override suspend fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    jsonBody: String?,
                    maxResponseBytes: Int,
                    connectTimeoutMs: Int,
                    readTimeoutMs: Int,
                ): HttpResponsePayload {
                    if (jsonBody?.contains("\"capabilities\"") == true) {
                        return HttpResponsePayload(200, """{"ok":true,"protocolVersion":${UserDataDocument.PROTOCOL_VERSION}}""")
                    }
                    return delayedTransport.request(method, url, headers, jsonBody, maxResponseBytes, connectTimeoutMs, readTimeoutMs)
                }
            }

            val manager1 = YunlefunSessionManager(
                vault = vault,
                database = db,
                accountService = YunlefunAccountService(delayedTransport),
                syncService = UserDataSyncService(fastCheckTransport),
                membershipService = MembershipService(delayedTransport),
            )
            val repo = UserLibraryRepository(db, initialSessionManager = manager1)

            // 1. Offline device write stays in DeviceLibrary
            repo.toggleFavorite(recipeId = 10, currentlyFavorite = false)
            assertEquals(setOf(10), repo.observeDeviceFavoriteIds().first())

            // 2. Enable sync (routes reads/writes to AccountLibrary) & launch a delayed sync, then sign out mid-flight
            val syncJob = launch {
                manager1.setSyncEnabled(true)
            }
            delay(40L)
            manager1.signOut()
            syncJob.join()
            // Stale delayed sync response (id=999) MUST NOT leak after signOut
            assertEquals(setOf(10), repo.observeDeviceFavoriteIds().first())
            assertNull(manager1.state.value.credential)

            // 3. Re-login / persist offline account edit -> simulate process restart
            vault.writeSecret(
                AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON,
                """{"accessToken":"tok-a","refreshToken":"ref-a","subject":"uid-alpha","expiresAtEpochMs":${credA.expiresAtEpochMs},"deviceID":"${credA.deviceID}"}""",
            )
            val offlineSyncTransport = object : HttpTransport {
                override suspend fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    jsonBody: String?,
                    maxResponseBytes: Int,
                    connectTimeoutMs: Int,
                    readTimeoutMs: Int,
                ): HttpResponsePayload {
                    if (jsonBody?.contains("\"capabilities\"") == true) {
                        return HttpResponsePayload(200, """{"ok":true,"protocolVersion":${UserDataDocument.PROTOCOL_VERSION}}""")
                    }
                    throw IllegalStateException("offline")
                }
            }
            val manager2 = YunlefunSessionManager(
                vault = vault,
                database = db,
                syncService = UserDataSyncService(offlineSyncTransport),
            )
            val repo2 = UserLibraryRepository(db, initialSessionManager = manager2)
            manager2.awaitInitialHydration()
            runCatching { manager2.setSyncEnabled(true) }
            repo2.toggleFavorite(recipeId = 250, currentlyFavorite = false)
            assertEquals(setOf(250), repo2.observeDeviceFavoriteIds().first())
            // DeviceLibrary still only has recipe 10
            assertEquals(listOf(10), db.deviceLibraryDao().getAllFavorites().map { it.recipeId })

            // Process death & restart: fresh YunlefunSessionManager MUST hydrate 250 & syncEnabled=true before any mutation
            val managerRestarted = YunlefunSessionManager(vault = vault, database = db)
            managerRestarted.awaitInitialHydration()
            assertTrue(managerRestarted.state.value.syncEnabled)
            assertEquals(listOf(250), managerRestarted.state.value.accountDocument.favorites.map { it.id })
        } finally {
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun historyDeletionIdentity_andClearWatermark_suppressesOlderRemoteVisits() = runBlocking {
        val db = testDatabase()
        try {
            val repo = UserLibraryRepository(db)
            val sample = cookData.recipes.first()
            repo.recordVisit(sample)
            val visitTime = repo.observeDeviceHistories().first().first().visitedAtMs
            repo.removeHistory(sample.name, visitTime)

            val docAfterSingleRemoval = UserDataDocument.decode(repo.exportUserDataDocumentJson())
            assertEquals(1, docAfterSingleRemoval.historyRemovals.size)
            assertEquals(visitTime, docAfterSingleRemoval.historyRemovals.first().time, 0.001)

            // Re-visit & clear all history -> sets durable historyClearedAt watermark
            repo.recordVisit(sample)
            repo.clearAllHistory()
            val clearedDoc = UserDataDocument.decode(repo.exportUserDataDocumentJson())
            assertTrue(clearedDoc.historyClearedAt > 0.0)

            // Older remote visit before historyClearedAt MUST NOT resurrect on merge
            val olderRemote = UserDataDocument(
                histories = listOf(
                    UserDataDocument.Visit(
                        recipe = UserDataDocument.RecipeSnapshot(sample),
                        time = clearedDoc.historyClearedAt - 5_000.0,
                    ),
                ),
            )
            val merged = clearedDoc.merging(olderRemote)
            assertTrue(merged.histories.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun importUserDataDocumentJson_rejectsMalformedWithoutMutatingExistingRows() = runBlocking {
        val db = testDatabase()
        try {
            val repo = UserLibraryRepository(db)
            repo.toggleFavorite(recipeId = 42, currentlyFavorite = false)
            val failed = repo.importUserDataDocumentJson("{not-valid-json")
            assertTrue(failed.isFailure)
            assertEquals(setOf(42), repo.observeDeviceFavoriteIds().first())
        } finally {
            db.close()
        }
    }

    @Test
    fun searchIntentRestrictions_andSaverRestoration_preserveAvoidAndNoSpicy() {
        val snapshot = CookCatalogSnapshot(
            data = cookData,
            recommender = Recommender(cookData.recipes, cookData.normalizer),
            textLibrary = textLibrary,
            dietaryCatalog = null,
            dietaryError = null,
            institutionalLowGICatalog = null,
            institutionalLowGIError = null,
            links = SharedLinks(),
        )
        val controller = CookSessionController()
        val confirmed = SearchIntent(
            schemaVersion = 1,
            dataVersion = cookData.meta.dataVersion,
            policyVersion = 1,
            source = SearchIntentSource.LOCAL,
            query = CookQuery(
                stuff = listOf("鸡蛋"),
                avoid = listOf("番茄"),
                noSpicy = true,
                tool = null,
                difficulty = emptyList(),
                methodGroups = emptyList(),
            ),
            goal = null,
        )
        controller.applySearchIntent(confirmed)
        controller.toggleDiscoveryScene(CookDiscoveryScene.EASY)
        val result = controller.evaluateCandidates(snapshot, DevicePreferencesSnapshot())
        assertTrue(result.recipes.isNotEmpty())
        assertTrue(result.recipes.none { r -> "番茄" in r.stuff })
        assertTrue(result.recipes.none { r -> "辣椒" in r.stuff || "小米辣" in r.stuff })

        // Verify SavedState Saver preserves avoid, noSpicy, stuff, and scene across activity recreation
        val savedBundle = with(CookSessionController.Saver) {
            SaverScope { true }.save(controller)
        }!!
        val restored = CookSessionController.Saver.restore(savedBundle)!!
        assertEquals(controller.conditions.value, restored.conditions.value)
    }

    @Test
    fun privateRecipesStore_preservesVideoMetadataAcrossStoreRestart_andResolvesContentUriMime() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-private-video-restart").toFile()
        try {
            val storeFile = File(tempDir, "private-recipes-v1.json")
            val deviceId = UUID.randomUUID().toString().lowercase()
            val videoStore = PrivateRecipeVideoStore(
                context = ApplicationProvider.getApplicationContext<Application>(),
                localRootDir = File(tempDir, "videos"),
                explicitDeviceId = deviceId,
            )

            // Test opaque content:// URI without filename in path (resolved via MIME / OpenableColumns)
            Robolectric.buildContentProvider(OpaqueMp4ContentProvider::class.java).create(OpaqueMp4ContentProvider.AUTHORITY)
            val contentUri = Uri.parse("content://${OpaqueMp4ContentProvider.AUTHORITY}/opaque/987654")
            val draft = videoStore.stageFromUri(contentUri, displayName = null)
            assertEquals("mp4", draft.video.fileExtension)
            val installedVideo = videoStore.install(draft)

            val recipeStore1 = PrivateRecipesStore(explicitFile = storeFile)
            val recipe = PrivateRecipe(
                customId = "custom-${UUID.randomUUID().toString().lowercase()}",
                name = "视频测试菜",
                stuff = listOf("鸡蛋"),
                tools = listOf("炒锅"),
                createdAt = 1000L,
                updatedAt = 1000L,
                video = installedVideo,
            ).validated()
            recipeStore1.upsertRecipe(recipe)

            // Reconstruct store from disk as on cold start -> video metadata & playable file must still exist
            val recipeStore2 = PrivateRecipesStore(explicitFile = storeFile)
            val restored = recipeStore2.state.value.recipes.single()
            assertEquals(installedVideo, restored.video)
            assertTrue(videoStore.playableFile(restored.video!!).isFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun workbenchServingChange_clearsCheckedStepWhenUsagePlanChanges_onAnnotatedRecipe680() {
        val tempDir = Files.createTempDirectory("cook-workbench-680").toFile()
        try {
            val presentation = RecipePresentation(textLibrary.text(680)!!)
            val flourStep = presentation.steps.first { "低筋面粉" in it.text }
            val store = KitchenWorkbenchStore(explicitFile = File(tempDir, "workbench.json"))
            store.toggleStep(680, flourStep.id)
            assertTrue(flourStep.id in store.entry(680).checkedSteps)

            store.updateServings(680, newServings = 3, presentation = presentation)
            assertEquals(3, store.entry(680).servings)
            assertFalse(
                "Step ${flourStep.id} annotation allocation changes from 1 to 3 servings so check must clear",
                flourStep.id in store.entry(680).checkedSteps,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun atomicJsonAndTinkVault_recoverBackupAndProtectAgainstOversizedStreams() {
        val tempDir = Files.createTempDirectory("cook-vault-atomic-test").toFile()
        try {
            val sharedAead = testAead()
            val target = File(tempDir, "vault.enc")
            val vault = testTinkVault(target, sharedAead)
            vault.writeSecret("k1", "secret-1")
            assertEquals("secret-1", vault.readSecret("k1"))

            // Simulate corrupted main file with valid .bak -> recovers without data loss
            val bak = File(tempDir, "vault.enc.bak")
            Files.copy(target.toPath(), bak.toPath())
            target.writeBytes(ByteArray(0))
            assertEquals("secret-1", testTinkVault(target, sharedAead).readSecret("k1"))

            // Unrecoverable corruption throws Corrupted instead of silent plaintext fallback
            target.writeText("{corrupt-json", Charsets.UTF_8)
            bak.delete()
            val err = runCatching { testTinkVault(target, sharedAead).readSecret("k1") }.exceptionOrNull()
            assertTrue(err is CredentialVaultException.Corrupted)

            // Unavailable keystore throws Unavailable without mutating existing file
            val lockedTarget = File(tempDir, "vault-locked.enc")
            testTinkVault(lockedTarget, sharedAead).writeSecret("k1", "secret-intact")
            val beforeBytes = lockedTarget.readBytes()
            val brokenVault = AndroidKeystoreVault(vaultFile = lockedTarget, aeadProvider = { error("keystore locked") })
            val availErr = runCatching { brokenVault.writeSecret("k2", "val") }.exceptionOrNull()
            assertTrue(availErr is CredentialVaultException.Unavailable)
            assertTrue(lockedTarget.readBytes().contentEquals(beforeBytes))
            assertEquals("secret-intact", testTinkVault(lockedTarget, sharedAead).readSecret("k1"))

            // Bounded stream aborts before exceeding maxBytes
            val oversized = ByteArrayInputStream(ByteArray(2048))
            val tooLarge = runCatching { BoundedIoStreams.readBytesBounded(oversized, maxBytes = 1024) }.exceptionOrNull()
            assertTrue(tooLarge is BoundedIoStreams.TooLargeException)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun accountConcurrency_delayedSignOutDoesNotWipeNewLoginVault_andParallelMutationsSurvive() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-concurrency-test").toFile()
        val db = testDatabase()
        try {
            val vault = testTinkVault(File(tempDir, "vault.enc"))
            val deviceIdA = UUID.randomUUID().toString().lowercase()
            val credA = YunlefunCredential(
                accessToken = "tok-alpha",
                refreshToken = "ref-alpha",
                subject = "uid-alpha",
                expiresAtEpochMs = System.currentTimeMillis() + 3600_000L,
                deviceID = deviceIdA,
            )
            vault.writeSecret(
                AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON,
                """{"accessToken":"tok-alpha","refreshToken":"ref-alpha","subject":"uid-alpha","expiresAtEpochMs":${credA.expiresAtEpochMs},"deviceID":"$deviceIdA"}""",
            )

            val remoteSignOutStarted = CompletableDeferred<Unit>()
            val finishRemoteSignOut = CompletableDeferred<Unit>()
            val mockTransport = object : HttpTransport {
                override suspend fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    jsonBody: String?,
                    maxResponseBytes: Int,
                    connectTimeoutMs: Int,
                    readTimeoutMs: Int,
                ): HttpResponsePayload = when {
                    url.endsWith("user/signout") -> {
                        remoteSignOutStarted.complete(Unit)
                        finishRemoteSignOut.await()
                        HttpResponsePayload(200, """{"ok":true}""")
                    }
                    url.contains("sso-ticket") -> {
                        HttpResponsePayload(200, """{"ok":true,"ticket":"ticket-beta"}""")
                    }
                    url.endsWith("signin/custom") -> {
                        HttpResponsePayload(
                            200,
                            """{"access_token":"tok-beta","refresh_token":"ref-beta","sub":"uid-beta","expires_in":3600}""",
                        )
                    }
                    url.endsWith("user/me") -> {
                        val sub = if (headers["Authorization"] == "Bearer tok-beta") "uid-beta" else "uid-alpha"
                        HttpResponsePayload(200, """{"sub":"$sub","name":"用户-$sub","status":"ACTIVE"}""")
                    }
                    url.endsWith("account-api") -> {
                        HttpResponsePayload(200, """{"membership":{"isActive":true,"expireAt":${System.currentTimeMillis() + 86_400_000L}}}""")
                    }
                    jsonBody?.contains("\"capabilities\"") == true -> {
                        HttpResponsePayload(200, """{"ok":true,"protocolVersion":${UserDataDocument.PROTOCOL_VERSION}}""")
                    }
                    else -> {
                        HttpResponsePayload(
                            200,
                            """{"ok":true,"protocolVersion":${UserDataDocument.PROTOCOL_VERSION},"document":{"version":1,"favorites":[],"favoriteRemovals":[],"histories":[],"historyRemovals":[],"historyClearedAt":0}}""",
                        )
                    }
                }
            }

            val manager = YunlefunSessionManager(
                vault = vault,
                database = db,
                accountService = YunlefunAccountService(mockTransport),
                syncService = UserDataSyncService(mockTransport),
                membershipService = MembershipService(mockTransport),
            )
            manager.awaitInitialHydration()
            val recipeOne = cookData.recipes.first { it.id == 1 }
            val recipeTwo = cookData.recipes.first { it.id == 2 }

            // Parallel mutations in Account A both persist under mutationMutex
            awaitAll(
                async { manager.toggleAccountFavorite(recipeOne.id, currentlyFavorite = false) },
                async { manager.recordAccountVisit(recipeTwo) },
            )
            val docAfterParallel = manager.state.value.accountDocument
            assertTrue(docAfterParallel.favorites.any { it.id == recipeOne.id })
            assertTrue(docAfterParallel.histories.any { it.recipe.name == recipeTwo.name })

            // Trigger delayed remote signOut while immediately completing SSO login for Account B
            val authUrl = manager.beginSsoAuthorization()
            val nonce = Regex("nonce=([^&]+)").find(authUrl)!!.groupValues[1]
            val code = "B".repeat(43)
            val fragmentJson = """{"nonce":"$nonce","iss":"${YunlefunSSO.ISSUER}","ok":true,"code":"$code"}"""
            val fragmentB64 = YunlefunSSO.encodeBase64Url(fragmentJson.toByteArray(Charsets.UTF_8))
            val callbackUrl = "${YunlefunSSO.REDIRECT_URI}#ylf_sso=$fragmentB64"

            val signOutJob = launch { manager.signOut() }
            try {
                withTimeout(5_000L) { remoteSignOutStarted.await() }
                assertNull(manager.state.value.credential)
                manager.handleSsoCallbackUrl(callbackUrl).getOrThrow()
            } finally {
                finishRemoteSignOut.complete(Unit)
                signOutJob.join()
            }

            // Account B remains signed in and its vault credential was NOT wiped by Account A's delayed signOut
            assertEquals("uid-beta", manager.state.value.credential?.subject)
            val storedAfter = vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON)
            assertNotNull(storedAfter)
            assertTrue(storedAfter!!.contains("uid-beta"))
        } finally {
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rollbackAndNonDestructiveImport_preservesDeviceAndAccountDocuments() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-import-rollback-test").toFile()
        val db = testDatabase()
        try {
            val vault = testTinkVault(File(tempDir, "vault.enc"))
            val repo = UserLibraryRepository(db)

            // 1. Seed DeviceLibrary favorite + history
            val sampleRecipe = cookData.recipes.first { it.id == 1 }
            repo.toggleFavorite(recipeId = sampleRecipe.id, currentlyFavorite = false)
            repo.recordVisit(sampleRecipe)
            assertEquals(setOf(sampleRecipe.id), repo.observeDeviceFavoriteIds().first())
            assertEquals(1, repo.observeDeviceHistories().first().size)

            // 2. Inject failure before Room import commit -> full rollback leaves device rows intact
            repo.beforeImportCommitHook = { throw IllegalStateException("simulated mid-transaction failure") }
            val rollbackRes = repo.importUserDataDocumentJson(
                UserDataDocument.wireJson.encodeToString(
                    UserDataDocument(favorites = listOf(UserDataDocument.Favorite(id = 999, time = 9000.0))),
                ),
            )
            assertTrue(rollbackRes.isFailure)
            repo.beforeImportCommitHook = null
            assertEquals(setOf(sampleRecipe.id), repo.observeDeviceFavoriteIds().first())
            assertFalse(999 in repo.observeDeviceFavoriteIds().first())

            // 3. Non-destructive import: tombstones and newer historyClearedAt in backup must NOT delete local records
            val tombstoneBackup = UserDataDocument(
                favorites = listOf(UserDataDocument.Favorite(id = 777, time = 9000.0)),
                favoriteRemovals = listOf(UserDataDocument.Favorite(id = sampleRecipe.id, time = Double.MAX_VALUE / 2)),
                historyRemovals = listOf(UserDataDocument.Removal(name = sampleRecipe.name, time = 1_000_000.0)),
                historyClearedAt = 9_999_999_999_999.0,
            )
            val importOk = repo.importUserDataDocumentJson(UserDataDocument.wireJson.encodeToString(tombstoneBackup))
            assertTrue(importOk.isSuccess)
            assertEquals(setOf(sampleRecipe.id, 777), repo.observeDeviceFavoriteIds().first())
            assertEquals(1, repo.observeDeviceHistories().first().size)

            // 4. Account persistence rollback: hook failure must not update in-memory AccountSyncUiState
            val deviceId = UUID.randomUUID().toString().lowercase()
            val cred = YunlefunCredential(
                accessToken = "tok-rollback",
                refreshToken = "ref-rollback",
                subject = "uid-rollback",
                expiresAtEpochMs = System.currentTimeMillis() + 3600_000L,
                deviceID = deviceId,
            )
            vault.writeSecret(
                AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON,
                """{"accessToken":"tok-rollback","refreshToken":"ref-rollback","subject":"uid-rollback","expiresAtEpochMs":${cred.expiresAtEpochMs},"deviceID":"$deviceId"}""",
            )
            val okTransport = object : HttpTransport {
                override suspend fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    jsonBody: String?,
                    maxResponseBytes: Int,
                    connectTimeoutMs: Int,
                    readTimeoutMs: Int,
                ) = HttpResponsePayload(200, """{"sub":"uid-rollback","status":"ACTIVE"}""")
            }
            val manager = YunlefunSessionManager(
                vault = vault,
                database = db,
                accountService = YunlefunAccountService(okTransport),
                syncService = UserDataSyncService(okTransport),
                membershipService = MembershipService(okTransport),
            )
            manager.awaitInitialHydration()
            manager.beforeAccountPersistCommitHook = { throw IllegalStateException("simulated db failure") }
            val mutateErr = runCatching { manager.toggleAccountFavorite(555, currentlyFavorite = false) }.exceptionOrNull()
            assertNotNull(mutateErr)
            assertFalse(manager.state.value.accountDocument.favorites.any { it.id == 555 })
        } finally {
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun credentialVault_corruptPrimaryRecoversAndRepairsFromBackup_andKeystoreFailureIsFatal() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-vault-repair-test").toFile()
        try {
            val sharedAead = testAead()
            val primary = File(tempDir, "vault.enc")
            val backup = File(tempDir, "vault.enc.bak")
            val vault = testTinkVault(primary, sharedAead)
            vault.writeSecret("k1", "first-secret")
            val intact = primary.readBytes()
            vault.writeSecret("k2", "second-secret")
            assertFalse(backup.exists())

            // An interrupted non-atomic replacement can leave the previous ciphertext as a backup.
            backup.writeBytes(intact)
            primary.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x7F))
            val reader = testTinkVault(primary, sharedAead)
            assertEquals("first-secret", reader.readSecret("k1"))
            // Reading valid backup repaired primary file
            assertEquals("first-secret", testTinkVault(primary, sharedAead).readSecret("k1"))
            assertFalse(backup.exists())

            // Corrupt both primary and backup -> Corrupt exception, no silent reset
            primary.writeBytes(byteArrayOf(0x09, 0x08))
            backup.writeBytes(byteArrayOf(0x09, 0x08))
            val err = runCatching { reader.readSecret("k1") }.exceptionOrNull()
            assertTrue(err is CredentialVaultException.Corrupted)

            // An empty primary without a backup must not be treated as a new vault.
            backup.delete()
            primary.writeBytes(byteArrayOf())
            assertTrue(runCatching { reader.writeSecret("new", "value") }.exceptionOrNull() is CredentialVaultException.Corrupted)
            assertEquals(0L, primary.length())

            // With valid ciphertext, unavailable Android Keystore fails closed on read and write.
            primary.writeBytes(intact)
            val brokenVault = AndroidKeystoreVault(vaultFile = primary, aeadProvider = { error("Keystore unavailable") })
            assertTrue(runCatching { brokenVault.readSecret("k1") }.exceptionOrNull() is CredentialVaultException.Unavailable)
            assertTrue(runCatching { brokenVault.writeSecret("k3", "v3") }.exceptionOrNull() is CredentialVaultException.Unavailable)
            assertTrue(primary.readBytes().contentEquals(intact))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun isolatedHttpPostTransport_cancellationAbortsBlockedSocketImmediately() = runBlocking {
        ServerSocket(0).use { server ->
            val port = server.localPort
            val acceptThread = Thread {
                runCatching {
                    server.accept().use { client ->
                        Thread.sleep(5_000L)
                        client.close()
                    }
                }
            }.apply { isDaemon = true; start() }

            val startMs = System.currentTimeMillis()
            val job = launch {
                IsolatedHttpPostTransport.request(
                    method = "GET",
                    url = "http://127.0.0.1:$port/hung",
                    readTimeoutMs = 15_000,
                )
            }
            delay(150L)
            job.cancel()
            withTimeout(2_000L) {
                job.join()
            }
            val elapsed = System.currentTimeMillis() - startMs
            assertTrue(job.isCancelled)
            assertTrue("Expected fast cancel (<2000ms), got ${elapsed}ms", elapsed < 2_000L)
            acceptThread.interrupt()
        }
    }

    @Test
    fun oversizedCatalogStream_andInvalidPrivateRecipePreserveVideoDraftWithoutDiskMutation() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-stream-private-test").toFile()
        try {
            // 1. Personal catalog 2 MiB limit
            val tooLargeCatalog = ByteArrayInputStream(ByteArray(2 * 1024 * 1024 + 16) { 'a'.code.toByte() })
            val catalogErr = runCatching {
                BoundedIoStreams.readUtf8Bounded(tooLargeCatalog, maxBytes = 2 * 1024 * 1024)
            }.exceptionOrNull()
            assertTrue(catalogErr is BoundedIoStreams.TooLargeException)

            // 2. Stage video draft, attempt invalid blank-name recipe -> fails validation before copying to Files/
            val root = File(tempDir, "videos")
            val videoStore = PrivateRecipeVideoStore(localRootDir = root, explicitDeviceId = UUID.randomUUID().toString().lowercase())
            val sourceMp4 = File(tempDir, "clip.mp4").apply { writeBytes(ByteArray(256) { 0x42 }) }
            val draft = videoStore.stageFromFile(sourceMp4)
            assertTrue(draft.file.exists())

            val err = runCatching {
                PrivateRecipe(
                    customId = UUID.randomUUID().toString().lowercase(),
                    name = "   ",
                    stuff = listOf("番茄"),
                    tools = emptyList(),
                    createdAt = 1000L,
                    updatedAt = 1000L,
                    video = draft.video,
                ).validated()
            }.exceptionOrNull()
            assertTrue(err is PrivateRecipeFileException.Invalid)
            assertTrue(draft.file.exists())
            assertTrue(File(root, "Files").listFiles().isNullOrEmpty())

            // Valid recipe installWithoutDiscarding + discard semantics preserve clean recovery on DB failure
            val installedVideo: PrivateRecipeVideo = videoStore.installWithoutDiscarding(draft)
            assertTrue(File(root, "Files/${installedVideo.id}.${installedVideo.fileExtension}").exists())
            assertTrue(draft.file.exists())
            videoStore.remove(installedVideo)
            videoStore.discard(draft)
            assertFalse(draft.file.exists())
            assertTrue(File(root, "Files").listFiles().isNullOrEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

class OpaqueMp4ContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
        addRow(arrayOf("opaque_clip_no_ext"))
    }

    override fun getType(uri: Uri): String = "video/mp4"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
            out.write(ByteArray(512) { 0x2A })
        }
        return pipe[0]
    }

    companion object {
        const val AUTHORITY = "fun.yunle.cook.test.opaqueprovider"
    }
}
