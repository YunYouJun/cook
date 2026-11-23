package cn.yunyoujun.cook.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import cn.yunyoujun.cook.core.recipe.RecipeText
import cn.yunyoujun.cook.core.recipe.RecipeTextBlock
import cn.yunyoujun.cook.data.local.kitchenPreparationItems
import cn.yunyoujun.cook.core.recipe.RecipeTextLibrary
import cn.yunyoujun.cook.core.recommend.Recommender
import cn.yunyoujun.cook.core.search.CookQuery
import cn.yunyoujun.cook.core.sync.PrivateRecipe
import cn.yunyoujun.cook.core.sync.PrivateRecipeFileException
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.core.sync.UserDataExportFile
import cn.yunyoujun.cook.data.local.AccountSyncStateEntity
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.AtomicJsonFiles
import cn.yunyoujun.cook.data.local.BoundedIoStreams
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.CredentialVaultException
import cn.yunyoujun.cook.data.local.DevicePreferencesStore
import cn.yunyoujun.cook.data.local.KitchenWorkbenchStore
import cn.yunyoujun.cook.data.local.PrivateRecipeVideoStore
import cn.yunyoujun.cook.data.local.PrivateRecipesStore
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.IsolatedHttpPostTransport
import cn.yunyoujun.cook.data.remote.MembershipService
import cn.yunyoujun.cook.data.remote.UserDataSyncException
import cn.yunyoujun.cook.data.remote.UserDataSyncService
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.data.repository.CookDiscoveryScene
import cn.yunyoujun.cook.data.repository.SharedLinks
import cn.yunyoujun.cook.data.repository.UserLibraryRepository
import cn.yunyoujun.cook.platform.validatedNetworkChanges
import cn.yunyoujun.cook.ui.state.CookSessionController
import cn.yunyoujun.cook.ui.state.SearchFilterConflict
import kotlinx.serialization.encodeToString
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    private suspend fun favoriteIds(repo: UserLibraryRepository): Set<Int> =
        repo.observeFavorites().filterNotNull().first().entries.map { it.recipeId }.toSet()

    private suspend fun histories(repo: UserLibraryRepository) =
        repo.observeHistories().filterNotNull().first().entries

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
            val uploadStarted = CompletableDeferred<Unit>()
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
                    if (jsonBody?.contains("\"document\"") == true) uploadStarted.complete(Unit)
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
            assertEquals(setOf(10), favoriteIds(repo))

            // 2. Enable sync (routes reads/writes to AccountLibrary) & launch a delayed sync, then sign out mid-flight
            manager1.setSyncEnabled(true).getOrThrow()
            withTimeout(5_000) { uploadStarted.await() }
            manager1.signOut()
            // Stale delayed sync response (id=999) MUST NOT leak after signOut
            assertEquals(setOf(10), favoriteIds(repo))
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
            assertEquals(setOf(250), favoriteIds(repo2))
            // DeviceLibrary still only has recipe 10
            assertEquals(listOf(10), db.deviceLibraryDao().getAllFavorites().map { it.recipeId })

            // Process death & restart: fresh YunlefunSessionManager MUST hydrate 250 & syncEnabled=true before any mutation
            val managerRestarted = YunlefunSessionManager(vault = vault, database = db)
            managerRestarted.awaitInitialHydration()
            assertTrue(managerRestarted.state.value.syncEnabled)
            assertNull(managerRestarted.state.value.lastSyncedAtMs)
            assertEquals(listOf(250), managerRestarted.state.value.accountDocument.favorites.map { it.id })
        } finally {
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun offlineChangesAndLastSuccessfulSyncSurviveReopenAndMergeBothWays() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-account-reopen").toFile()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val databaseName = "cook-account-reopen-${UUID.randomUUID()}.db"
        fun openDatabase() = Room.databaseBuilder(context, CookDatabase::class.java, databaseName).build()
        var db = openDatabase()
        val stopped = AtomicBoolean(false)
        try {
            val vault = savedAccountVault(tempDir)
            val (removedRecipe, localRecipe, remoteRecipe, newRemoteFavorite) = cookData.recipes.distinctBy { it.name }.take(4)
            val baseline = System.currentTimeMillis().toDouble() - 10_000
            val oldVisit = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(removedRecipe), baseline)
            val remoteVisit = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(remoteRecipe), baseline + 2_000)
            val server = AtomicReference(UserDataDocument(
                favorites = listOf(
                    UserDataDocument.Favorite(removedRecipe.id, baseline),
                    UserDataDocument.Favorite(remoteRecipe.id, baseline + 1_000),
                ),
                histories = listOf(oldVisit),
            ))
            val connected = AtomicBoolean(true)
            val originalService = UserDataSyncService(syncTransport { request ->
                when {
                    stopped.get() -> throw kotlinx.coroutines.CancellationException()
                    connected.get() -> syncResponse(server.updateAndGet { it.merging(request) })
                    else -> HttpResponsePayload(502, "offline")
                }
            })
            val original = YunlefunSessionManager(vault, db, syncService = originalService)
            val repo = UserLibraryRepository(db, original)
            original.setSyncEnabled(true).getOrThrow()
            val synced = withTimeout(5_000) { original.state.first { !it.isSyncing && it.lastSyncedAtMs != null } }
            val lastSuccess = requireNotNull(synced.lastSyncedAtMs)

            connected.set(false)
            repo.toggleFavorite(removedRecipe.id, currentlyFavorite = true).getOrThrow()
            repo.removeHistory(removedRecipe.name, oldVisit.time).getOrThrow()
            repo.toggleFavorite(localRecipe.id, currentlyFavorite = false).getOrThrow()
            repo.recordVisit(localRecipe).getOrThrow()
            assertTrue(original.syncNow().exceptionOrNull() is UserDataSyncException.Unavailable)
            val offlineDoc = original.state.value.accountDocument
            val localVisit = offlineDoc.histories.single()
            assertEquals(localRecipe.name, localVisit.recipe.name)
            assertEquals(lastSuccess, original.state.value.lastSyncedAtMs)
            val offlineState = requireNotNull(db.accountLibraryDao().getSyncState("sync-test"))
            assertEquals(offlineDoc, UserDataDocument.decode(requireNotNull(offlineState.lastSyncedDocumentJson)))

            stopped.set(true)
            db.close()
            db = openDatabase()
            val reopened = YunlefunSessionManager(
                vault,
                db,
                syncService = UserDataSyncService(syncTransport { request ->
                    syncResponse(server.updateAndGet { it.merging(request) })
                }),
            )
            reopened.awaitInitialHydration()
            assertTrue(reopened.state.value.syncEnabled)
            assertEquals(lastSuccess, reopened.state.value.lastSyncedAtMs)
            assertEquals(offlineDoc, reopened.state.value.accountDocument)
            server.updateAndGet {
                it.merging(UserDataDocument(
                    favorites = listOf(UserDataDocument.Favorite(newRemoteFavorite.id, baseline + 2_000)),
                    favoriteRemovals = listOf(UserDataDocument.Favorite(remoteRecipe.id, baseline + 2_000)),
                    histories = listOf(remoteVisit),
                ))
            }

            val merged = reopened.syncNow().getOrThrow()
            assertEquals(setOf(newRemoteFavorite.id, localRecipe.id), merged.favorites.map { it.id }.toSet())
            assertEquals(setOf(remoteVisit, localVisit), merged.histories.toSet())
            assertEquals(setOf(removedRecipe.id, remoteRecipe.id), merged.favoriteRemovals.map { it.id }.toSet())
            assertEquals(listOf(UserDataDocument.Removal(removedRecipe.name, oldVisit.time)), merged.historyRemovals)
            assertEquals(merged, server.get())
            val saved = requireNotNull(db.accountLibraryDao().getSyncState("sync-test"))
            assertEquals(merged, UserDataDocument.decode(requireNotNull(saved.lastSyncedDocumentJson)))
            assertEquals(reopened.state.value.lastSyncedAtMs, saved.lastSyncedAtMs)

            val reopenedLibrary = UserLibraryRepository(db, reopened)
            val displayedFavorites = reopenedLibrary.observeFavorites().filterNotNull().first().entries
            assertEquals(listOf(localRecipe.id, newRemoteFavorite.id), displayedFavorites.map { it.recipeId })
            assertEquals(listOf(localVisit.time, remoteVisit.time), histories(reopenedLibrary).map { it.visitedAtMs })
        } finally {
            stopped.set(true)
            db.close()
            context.deleteDatabase(databaseName)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun accountHistoryAutomaticallySyncsAndRetriesWithoutAnotherEdit() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-account-retry").toFile()
        val db = testDatabase()
        try {
            val vault = savedAccountVault(tempDir)
            val attempts = Channel<UserDataDocument>(Channel.UNLIMITED)
            val failOnce = AtomicBoolean(false)
            val transport = syncTransport { document ->
                attempts.send(document)
                if (failOnce.compareAndSet(true, false)) HttpResponsePayload(502, "<html>upstream offline</html>")
                else syncResponse(document)
            }
            val manager = YunlefunSessionManager(vault, db, syncService = UserDataSyncService(transport))
            val repo = UserLibraryRepository(db, manager)
            manager.setSyncEnabled(true).getOrThrow()
            assertTrue(withTimeout(5_000) { attempts.receive() }.histories.isEmpty())

            val recipe = cookData.recipes.first()
            failOnce.set(true)
            repo.recordVisit(recipe).getOrThrow()
            val visit = withTimeout(5_000) { attempts.receive() }.histories.single()
            assertEquals(recipe.name, visit.recipe.name)
            assertEquals(listOf(visit), withTimeout(5_000) { attempts.receive() }.histories)
            withTimeout(5_000) { manager.state.first { !it.isSyncing && it.statusMessage?.startsWith("同步成功") == true } }

            repo.removeHistory(recipe.name, visit.time).getOrThrow()
            val deletion = withTimeout(5_000) { attempts.receive() }
            assertTrue(deletion.histories.isEmpty())
            assertEquals(listOf(UserDataDocument.Removal(recipe.name, visit.time)), deletion.historyRemovals)

            repo.clearAllHistory().getOrThrow()
            val cleared = withTimeout(5_000) { attempts.receive() }
            assertTrue(cleared.historyClearedAt >= visit.time)
            assertTrue(cleared.histories.isEmpty())
            withTimeout(5_000) { manager.state.first { !it.isSyncing && it.statusMessage?.startsWith("同步成功") == true } }
            manager.setSyncEnabled(false).getOrThrow()
        } finally {
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun aSuccessfulManualSyncSupersedesThePreviousAutomaticRetry() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-account-manual-sync").toFile()
        val db = testDatabase()
        val attempts = Channel<UserDataDocument>(Channel.UNLIMITED)
        val connected = AtomicBoolean(true)
        val manager = YunlefunSessionManager(
            savedAccountVault(tempDir),
            db,
            syncService = UserDataSyncService(syncTransport { document ->
                val reachable = connected.get()
                attempts.send(document)
                if (reachable) syncResponse(document) else HttpResponsePayload(503, "offline")
            }),
        )
        val repo = UserLibraryRepository(db, manager)
        try {
            manager.setSyncEnabled(true).getOrThrow()
            withTimeout(5_000) { attempts.receive() }
            withTimeout(5_000) { manager.state.first { !it.isSyncing && it.lastSyncedAtMs != null } }

            val recipe = cookData.recipes.first()
            val time = System.currentTimeMillis().toDouble()
            val document = UserDataDocument(
                favorites = listOf(UserDataDocument.Favorite(recipe.id, time)),
                histories = listOf(UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(recipe), time)),
            )
            connected.set(false)
            manager.importIntoAccount(manager.state.value.libraryScope, document)
            repeat(2) {
                assertEquals(document, withTimeout(5_000) { attempts.receive() })
            }
            withTimeout(5_000) {
                manager.state.first { !it.isSyncing && it.statusMessage?.startsWith("暂时无法同步") == true }
            }

            connected.set(true)
            assertEquals(document, manager.syncNow().getOrThrow())
            assertEquals(document, withTimeout(5_000) { attempts.receive() })
            assertNull("The previous timed retry sent another upload", withTimeoutOrNull(2_500) { attempts.receive() })

            repo.removeHistory(recipe.name, time).getOrThrow()
            val deletion = withTimeout(5_000) { attempts.receive() }
            assertEquals(document.favorites, deletion.favorites)
            assertTrue(deletion.histories.isEmpty())
            assertEquals(listOf(UserDataDocument.Removal(recipe.name, time)), deletion.historyRemovals)
            val synced = withTimeout(5_000) {
                manager.state.first { !it.isSyncing && it.statusMessage?.startsWith("同步成功") == true }
            }
            assertEquals(deletion, synced.accountDocument)
        } finally {
            try {
                manager.setSyncEnabled(false).getOrThrow()
            } finally {
                db.close()
                tempDir.deleteRecursively()
            }
        }
    }

    @Test
    fun anOversizedSyncReplyStopsThePreviousRetryAndPreservesOfflineEdits() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-account-sync-response").toFile()
        val db = testDatabase()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = Channel<UserDataDocument>(Channel.UNLIMITED)
        val response = AtomicReference(HttpResponsePayload(503, "{}"))
        server.createContext("/sync") { exchange ->
            exchange.use {
                val body = Json.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject
                val document = body["document"]
                val reply = if (document == null) {
                    HttpResponsePayload(200, """{"ok":true,"protocolVersion":2}""")
                } else {
                    val pending = response.get()
                    requests.trySend(UserDataDocument.decode(document.toString()))
                    pending
                }
                val bytes = reply.bodyText.encodeToByteArray()
                exchange.sendResponseHeaders(reply.statusCode, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        val vault = savedAccountVault(tempDir)
        val baseline = System.currentTimeMillis().toDouble()
        val (localRecipe, remoteRecipe) = cookData.recipes.distinctBy { it.name }.take(2)
        val local = UserDataDocument(
            favorites = listOf(UserDataDocument.Favorite(localRecipe.id, baseline)),
            histories = listOf(UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(localRecipe), baseline)),
        )
        val remote = UserDataDocument(
            favorites = listOf(UserDataDocument.Favorite(remoteRecipe.id, baseline + 1)),
            histories = listOf(UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(remoteRecipe), baseline + 1)),
        )
        // Seed consent so the first automatic upload is caused by the offline change.
        db.accountLibraryDao().upsertSyncState(AccountSyncStateEntity(
            accountUid = "sync-test",
            syncEnabled = true,
            historyClearedAtMs = 0.0,
            lastSyncedDocumentJson = null,
            updatedAtMs = baseline,
        ))
        val manager = YunlefunSessionManager(vault, db, syncService = UserDataSyncService(
            endpoint = "http://127.0.0.1:${server.address.port}/sync",
        ))
        try {
            manager.awaitInitialHydration()
            manager.importIntoAccount(manager.state.value.libraryScope, local)
            assertEquals(local, withTimeout(5_000) { requests.receive() })
            withTimeout(5_000) {
                manager.state.first { !it.isSyncing && it.statusMessage?.startsWith("暂时无法同步") == true }
            }

            response.set(HttpResponsePayload(200, " ".repeat(300 * 1024 + 1)))
            val failure = manager.syncNow().exceptionOrNull()
            assertEquals(UserDataSyncException.InvalidResponse::class, failure?.let { it::class })
            assertEquals(local, withTimeout(5_000) { requests.receive() })
            assertNull("A previous timed retry attempted to upload again", withTimeoutOrNull(1_750) { requests.receive() })
            assertNull(manager.state.value.lastSyncedAtMs)
            val cached = requireNotNull(db.accountLibraryDao().getSyncState("sync-test")?.lastSyncedDocumentJson)
            assertEquals(local, UserDataDocument.decode(cached))

            val merged = local.merging(remote)
            response.set(syncResponse(merged))
            assertEquals(merged, manager.syncNow().getOrThrow())
            assertEquals(local, withTimeout(5_000) { requests.receive() })
            val synced = requireNotNull(db.accountLibraryDao().getSyncState("sync-test")?.lastSyncedDocumentJson)
            assertEquals(merged, UserDataDocument.decode(synced))
        } finally {
            manager.setSyncEnabled(false)
            server.stop(0)
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun validatedNetworkWakesAccountSyncBackoffAndClosingConsentUnregistersTheCallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val shadow = shadowOf(connectivity)
        val existingCallbacks = shadow.networkCallbacks.toSet()
        val network = ShadowNetwork.newInstance(42)
        val unvalidated = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val validated = NetworkCapabilities(unvalidated).also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val tempDir = Files.createTempDirectory("cook-account-reconnect").toFile()
        val db = testDatabase()
        val attempts = Channel<UserDataDocument>(Channel.UNLIMITED)
        val connected = AtomicBoolean(true)
        val server = AtomicReference(UserDataDocument())
        val manager = YunlefunSessionManager(
            savedAccountVault(tempDir),
            db,
            syncService = UserDataSyncService(syncTransport { document ->
                val reachable = connected.get()
                attempts.send(document)
                if (reachable) syncResponse(server.updateAndGet { it.merging(document) })
                else HttpResponsePayload(503, "offline")
            }),
            networkChanges = context.validatedNetworkChanges(),
        )

        try {
            manager.awaitInitialHydration()
            assertEquals(existingCallbacks, shadow.networkCallbacks)
            manager.setSyncEnabled(true).getOrThrow()
            val callback = withTimeout(5_000) {
                while ((shadow.networkCallbacks - existingCallbacks).isEmpty()) delay(10)
                (shadow.networkCallbacks - existingCallbacks).single()
            }
            withTimeout(5_000) { attempts.receive() }
            withTimeout(5_000) { manager.state.first { !it.isSyncing && it.lastSyncedAtMs != null } }

            connected.set(false)
            callback.onCapabilitiesChanged(network, unvalidated)
            val (local, remote) = cookData.recipes.distinctBy { it.name }.take(2)
            val now = System.currentTimeMillis().toDouble()
            manager.importIntoAccount(manager.state.value.libraryScope, UserDataDocument(
                favorites = listOf(UserDataDocument.Favorite(local.id, now)),
                histories = listOf(UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(local), now)),
            ))
            withTimeout(8_000) {
                repeat(3) {
                    val pending = attempts.receive()
                    assertEquals(local.id, pending.favorites.single().id)
                    assertEquals(local.name, pending.histories.single().recipe.name)
                }
            }
            server.set(UserDataDocument(favorites = listOf(UserDataDocument.Favorite(remote.id, 1.0))))

            connected.set(true)
            callback.onCapabilitiesChanged(network, validated)
            callback.onCapabilitiesChanged(network, validated)
            // The next timed retry waits four seconds. Network recovery can send the saved edits sooner.
            withTimeout(2_500) {
                manager.state.first { current ->
                    !current.isSyncing && current.accountDocument.favorites.map { it.id }.toSet() == setOf(local.id, remote.id)
                }
            }
            assertEquals(manager.state.value.accountDocument, server.get())
            assertEquals(local.name, withTimeout(1_000) { attempts.receive() }.histories.single().recipe.name)
            assertNull(withTimeoutOrNull(1_250) { attempts.receive() })

            manager.setSyncEnabled(false).getOrThrow()
            withTimeout(5_000) {
                while (callback in shadow.networkCallbacks) delay(10)
            }
            callback.onLost(network)
            callback.onCapabilitiesChanged(network, validated)
            assertNull(withTimeoutOrNull(1_250) { attempts.receive() })
        } finally {
            if (manager.state.value.syncEnabled) manager.setSyncEnabled(false)
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun closingSyncDuringAnInFlightRequestDiscardsLateResponseAndPersistsConsent() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-account-consent").toFile()
        val db = testDatabase()
        val releaseResponse = CompletableDeferred<Unit>()
        try {
            val vault = savedAccountVault(tempDir)
            val inFlight = CompletableDeferred<Unit>()
            val transport = syncTransport {
                inFlight.complete(Unit)
                withContext(NonCancellable) { releaseResponse.await() }
                syncResponse(UserDataDocument(favorites = listOf(UserDataDocument.Favorite(id = 250, time = 5000.0))))
            }
            val manager = YunlefunSessionManager(vault, db, syncService = UserDataSyncService(transport))
            val enable = async { manager.setSyncEnabled(true) }
            withTimeout(5_000) { enable.await().getOrThrow() }
            withTimeout(5_000) { inFlight.await() }
            assertTrue(manager.state.value.isSyncing)
            manager.setSyncEnabled(false).getOrThrow()
            assertFalse(manager.state.value.usesAccountLibrary)
            assertFalse(manager.state.value.isSyncing)
            releaseResponse.complete(Unit)
            assertTrue(manager.state.value.accountDocument.favorites.isEmpty())
            assertTrue(manager.syncNow().exceptionOrNull() is UserDataSyncException.Disabled)

            val reopened = YunlefunSessionManager(vault, db, syncService = UserDataSyncService(transport))
            reopened.awaitInitialHydration()
            assertFalse(reopened.state.value.syncEnabled)
            assertTrue(reopened.state.value.accountDocument.favorites.isEmpty())
        } finally {
            releaseResponse.complete(Unit)
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun unreadableAccountCacheSurvivesAppRestartAndLeavesDeviceLibraryUsable() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-cache-recovery").toFile()
        val db = testDatabase()
        try {
            val vault = savedAccountVault(tempDir)
            val corrupt = AccountSyncStateEntity(
                accountUid = "sync-test",
                syncEnabled = true,
                historyClearedAtMs = 0.0,
                lastSyncedDocumentJson = """{"version":1,"favorites":[{""",
                updatedAtMs = 10_000.0,
                lastSyncedAtMs = 9_000L,
            )
            db.accountLibraryDao().upsertSyncState(corrupt)
            val started = CompletableDeferred<Unit>()
            val transport = syncTransport { document ->
                started.complete(Unit)
                syncResponse(document)
            }
            val manager = YunlefunSessionManager(vault, db, syncService = UserDataSyncService(transport))
            manager.awaitInitialHydration()
            assertEquals(corrupt.accountUid, manager.state.value.credential?.subject)
            assertTrue(manager.state.value.isHydrated)
            assertTrue(manager.state.value.accountCacheUnreadable)
            assertFalse(manager.state.value.usesAccountLibrary)
            assertFalse(manager.state.value.syncEnabled)

            val repo = UserLibraryRepository(db, manager)
            val recipe = cookData.recipes.first()
            repo.toggleFavorite(recipe.id, currentlyFavorite = false).getOrThrow()
            repo.recordVisit(recipe).getOrThrow()
            assertEquals(setOf(recipe.id), favoriteIds(repo))
            assertEquals(recipe.name, histories(repo).single().snapshot.name)
            assertTrue(manager.setSyncEnabled(enabled = true).exceptionOrNull() is UserDataSyncException.AccountCacheUnreadable)
            assertTrue(manager.setSyncEnabled(enabled = false).exceptionOrNull() is UserDataSyncException.AccountCacheUnreadable)
            assertTrue(manager.syncNow().exceptionOrNull() is UserDataSyncException.AccountCacheUnreadable)
            manager.requestForegroundSync()
            assertNull(withTimeoutOrNull(1_500L) { started.await() })
            assertEquals(corrupt, db.accountLibraryDao().getSyncState(corrupt.accountUid))
        } finally {
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun recipeOpeningDuringInitialHydrationUsesTheRestoredLibraryOfTheSameSession() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-open-before-hydration").toFile()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val executor = Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(context, CookDatabase::class.java).setQueryExecutor(executor).build()
        val readingHeld = CompletableDeferred<Unit>()
        val releaseReads = CompletableDeferred<Unit>()
        try {
            val vault = savedAccountVault(tempDir)
            db.accountLibraryDao().upsertSyncState(AccountSyncStateEntity("sync-test", true, 0.0, null, 0.0))
            executor.execute {
                readingHeld.complete(Unit)
                runBlocking { withTimeout(5_000) { releaseReads.await() } }
            }
            withTimeout(5_000) { readingHeld.await() }
            val manager = YunlefunSessionManager(vault, db, syncService = UserDataSyncService(syncTransport(::syncResponse)))
            val repo = UserLibraryRepository(db, manager)
            val openingScope = manager.state.value.libraryScope
            assertFalse(openingScope.isHydrated)
            assertFalse(openingScope.usesAccountLibrary)
            val recipe = cookData.recipes.first()
            val recording = async { repo.recordVisit(recipe, openingScope) }
            releaseReads.complete(Unit)
            withTimeout(5_000) { recording.await() }.getOrThrow()
            assertEquals(recipe.name, manager.state.value.accountDocument.histories.single().recipe.name)
            assertTrue(db.deviceLibraryDao().getAllHistories().isEmpty())
            manager.setSyncEnabled(false).getOrThrow()
        } finally {
            releaseReads.complete(Unit)
            db.close()
            executor.shutdown()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun libraryActionsCannotCrossLibrariesOrSurviveRenewedSyncConsent() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-history-owner").toFile()
        val db = testDatabase()
        try {
            val transport = syncTransport(::syncResponse)
            val manager = YunlefunSessionManager(savedAccountVault(tempDir), db, syncService = UserDataSyncService(transport))
            val repo = UserLibraryRepository(db, manager)
            manager.awaitInitialHydration()
            val (deviceRecipe, accountRecipe) = cookData.recipes.distinctBy { it.name }.take(2)
            repo.recordVisit(deviceRecipe).getOrThrow()
            repo.toggleFavorite(deviceRecipe.id, currentlyFavorite = false).getOrThrow()
            val renderedDeviceFavorites = repo.observeFavorites().filterNotNull().first()
            val deviceScope = renderedDeviceFavorites.scope
            assertEquals(deviceRecipe.id, renderedDeviceFavorites.entries.single().recipeId)
            val deviceExport = repo.exportUserDataDocumentJson(deviceScope)

            manager.setSyncEnabled(true).getOrThrow()
            repo.recordVisit(accountRecipe).getOrThrow()
            val accountScope = manager.state.value.libraryScope
            val staleFavorite = repo.toggleFavorite(deviceRecipe.id, currentlyFavorite = true, expected = deviceScope)
            assertTrue(staleFavorite.exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertTrue(repo.recordVisit(deviceRecipe, deviceScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertTrue(manager.syncNow(deviceScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            val renderedAccountFavorites = repo.observeFavorites().filterNotNull().first()
            assertEquals(accountScope, renderedAccountFavorites.scope)
            assertTrue(renderedAccountFavorites.entries.isEmpty())
            assertTrue(repo.clearAllHistory(deviceScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertTrue(repo.importUserDataDocumentJson(deviceExport, deviceScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            val staleExport = runCatching { repo.exportUserDataDocumentJson(deviceScope) }
            assertTrue(staleExport.exceptionOrNull() is UserDataSyncException.LibraryChanged)

            manager.setSyncEnabled(false).getOrThrow()
            assertTrue(repo.clearAllHistory(accountScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertTrue(repo.recordVisit(accountRecipe, accountScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertEquals(deviceRecipe.name, histories(repo).single().snapshot.name)
            assertEquals(setOf(deviceRecipe.id), favoriteIds(repo))

            manager.setSyncEnabled(true).getOrThrow()
            assertTrue(repo.clearAllHistory(accountScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertTrue(manager.copyDeviceLibraryToAccount(accountScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertTrue(repo.recordVisit(deviceRecipe, accountScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertTrue(manager.syncNow(accountScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertEquals(accountRecipe.name, histories(repo).single().snapshot.name)
            repo.clearAllHistory(manager.state.value.libraryScope).getOrThrow()
            assertTrue(manager.syncNow().getOrThrow().histories.isEmpty())
            assertTrue(manager.state.value.accountDocument.historyClearedAt > 0)

            manager.setSyncEnabled(false).getOrThrow()
            assertEquals(deviceRecipe.name, histories(repo).single().snapshot.name)
        } finally {
            db.close()
            tempDir.deleteRecursively()
        }
    }

    private fun savedAccountVault(tempDir: File): SecretVault = testTinkVault(File(tempDir, "vault.enc")).apply {
        val credential = YunlefunCredential("test-token", "test-refresh", "sync-test", Long.MAX_VALUE, UUID.randomUUID().toString())
        writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, Json.encodeToString(credential))
    }

    private fun syncTransport(onSync: suspend (UserDataDocument) -> HttpResponsePayload): HttpTransport = object : HttpTransport {
        override suspend fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            jsonBody: String?,
            maxResponseBytes: Int,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponsePayload {
            val body = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
            val document = body["document"]
            return if (document == null) HttpResponsePayload(200, """{"ok":true,"protocolVersion":2}""")
            else onSync(UserDataDocument.decode(document.toString()))
        }
    }

    private fun syncResponse(document: UserDataDocument): HttpResponsePayload {
        val encoded = UserDataDocument.wireJson.encodeToString(document)
        return HttpResponsePayload(200, """{"ok":true,"protocolVersion":2,"document":$encoded}""")
    }

    @Test
    fun historyDeletionIdentity_andClearWatermark_suppressesOlderRemoteVisits() = runBlocking {
        val db = testDatabase()
        try {
            val repo = UserLibraryRepository(db)
            val sample = cookData.recipes.first()
            repo.recordVisit(sample)
            val visitTime = histories(repo).first().visitedAtMs
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
    fun offlineClearKeepsADeletionNewerThanTheClearAndPreservesOnlyNewVisitsAfterSync() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-history-offline-clear").toFile()
        val db = testDatabase()
        val (clearedRecipe, removedRecipe, remoteRecipe) = cookData.recipes.distinctBy { it.name }.take(3)
        val nextHour = System.currentTimeMillis().toDouble() + 3_600_000.0
        val visibleVisit = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(clearedRecipe), nextHour)
        val removedVisit = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(removedRecipe), nextHour + 60_000.0)
        val concurrent = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(remoteRecipe), nextHour - 90_000.0)
        val visibleRemoval = UserDataDocument.Removal(clearedRecipe.name, visibleVisit.time)
        val removal = UserDataDocument.Removal(removedRecipe.name, removedVisit.time)
        val server = AtomicReference(UserDataDocument(histories = listOf(visibleVisit, removedVisit)))
        val online = AtomicBoolean(true)
        val manager = YunlefunSessionManager(
            savedAccountVault(tempDir),
            db,
            syncService = UserDataSyncService(syncTransport { document ->
                if (!online.get()) HttpResponsePayload(503, "")
                else syncResponse(server.updateAndGet { it.merging(document) })
            }),
        )
        val repo = UserLibraryRepository(db, manager)
        try {
            manager.setSyncEnabled(true).getOrThrow()
            assertEquals(listOf(visibleVisit, removedVisit), manager.syncNow().getOrThrow().histories)

            online.set(false)
            repo.removeHistory(removedRecipe.name, removedVisit.time).getOrThrow()
            val clearPressedAt = System.currentTimeMillis().toDouble()
            repo.clearAllHistory().getOrThrow()
            val cleared = UserDataDocument.decode(requireNotNull(db.accountLibraryDao()
                .getSyncState("sync-test")?.lastSyncedDocumentJson))
            assertTrue(cleared.historyClearedAt in clearPressedAt..System.currentTimeMillis().toDouble())
            assertEquals(listOf(visibleRemoval, removal), cleared.historyRemovals)
            assertTrue(cleared.histories.isEmpty())
            assertTrue(manager.syncNow().exceptionOrNull() is UserDataSyncException.Unavailable)
            repo.recordVisit(removedRecipe).getOrThrow()
            val newVisit = manager.state.value.accountDocument.histories.single()
            assertTrue(newVisit.time > removedVisit.time)

            server.updateAndGet { it.copy(histories = it.histories + concurrent) }
            online.set(true)
            val reconnected = manager.syncNow().getOrThrow()
            assertEquals(listOf(concurrent, newVisit), reconnected.histories)
            assertEquals(reconnected, server.get())
        } finally {
            manager.setSyncEnabled(false).getOrThrow()
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun deviceClearAfterRestoringBackupRetainsTheHighestWatermarkAndRecordsANewVisit() = runBlocking {
        val db = testDatabase()
        try {
            val repo = UserLibraryRepository(db)
            val (clearedRecipe, removedRecipe) = cookData.recipes.distinctBy { it.name }.take(2)
            val nextHour = System.currentTimeMillis().toDouble() + 3_600_000.0
            val visibleVisit = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(clearedRecipe), nextHour)
            val removedVisit = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(removedRecipe), nextHour + 60_000.0)
            val removal = UserDataDocument.Removal(removedRecipe.name, removedVisit.time)
            val backup = UserDataDocument(
                histories = listOf(visibleVisit),
                historyRemovals = listOf(removal),
                historyClearedAt = nextHour - 100.0,
            )
            repo.restoreDeviceLibrary(backup)

            repo.clearAllHistory().getOrThrow()
            repo.clearAllHistory().getOrThrow()
            val cleared = repo.snapshotDeviceLibrary()
            assertEquals(backup.historyClearedAt, cleared.historyClearedAt, 0.0)
            assertTrue(cleared.histories.isEmpty())
            val visibleRemoval = UserDataDocument.Removal(clearedRecipe.name, visibleVisit.time)
            assertEquals(setOf(visibleRemoval, removal), cleared.historyRemovals.toSet())

            repo.recordVisit(removedRecipe).getOrThrow()
            val exported = UserDataDocument.decode(repo.exportUserDataDocumentJson())
            val newVisit = exported.histories.single()
            assertTrue(newVisit.time > removedVisit.time)
            val concurrent = visibleVisit.copy(time = nextHour - 50.0)
            val previous = UserDataDocument(histories = listOf(concurrent, visibleVisit, removedVisit))
            assertEquals(listOf(concurrent, newVisit), exported.merging(previous).histories)
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
            val foreign = """{"app":"other","version":1,"favorites":[{"id":11,"time":1000}]}"""
            val unmarked = """{"version":1,"favorites":[{"id":11,"time":1000}]}"""
            val catalog = """{"app":"cook","kind":"personal-catalog","version":1,"items":[],"hidden":[]}"""
            for (invalid in listOf("{not-valid-json", foreign, unmarked, catalog)) {
                assertTrue(repo.importUserDataDocumentJson(invalid).isFailure)
            }
            assertEquals(setOf(42), favoriteIds(repo))
        } finally {
            db.close()
        }
    }

    @Test
    fun manualLibraryImportKeepsNewerDestinationDeletionsInBothLibraries() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-import-destination-test").toFile()
        val db = testDatabase()
        val manager = YunlefunSessionManager(
            savedAccountVault(tempDir),
            db,
            syncService = UserDataSyncService(syncTransport { syncResponse(it) }),
        )
        try {
            val deviceRepo = UserLibraryRepository(db)
            val accountRepo = UserLibraryRepository(db, manager)
            val recipe = cookData.recipes.first()
            val now = System.currentTimeMillis().toDouble()
            val snapshot = UserDataDocument.RecipeSnapshot(recipe)
            val clearedVisit = UserDataDocument.Visit(snapshot, now - 1_000)
            val removedVisit = UserDataDocument.Visit(snapshot, now + 60_000.125)
            val newVisit = removedVisit.copy(time = now + 60_000.375)
            val original = UserDataDocument(
                favorites = listOf(UserDataDocument.Favorite(recipe.id, now + 60_000)),
                histories = listOf(clearedVisit),
            )
            val beforeRemoval = original.copy(histories = listOf(clearedVisit, removedVisit, newVisit))

            manager.setSyncEnabled(true).getOrThrow()
            for (repo in listOf(deviceRepo, accountRepo)) {
                repo.importUserDataDocumentJson(UserDataExportFile.export(original)).getOrThrow()
                repo.toggleFavorite(recipe.id, currentlyFavorite = true).getOrThrow()
                repo.clearAllHistory().getOrThrow()
                repo.importUserDataDocumentJson(UserDataExportFile.export(UserDataDocument(histories = listOf(removedVisit))))
                    .getOrThrow()
                repo.removeHistory(recipe.name, removedVisit.time).getOrThrow()
                val deleted = UserDataExportFile.parse(repo.exportUserDataDocumentJson())
                val favoriteRemoval = deleted.favoriteRemovals.single()
                assertEquals(listOf(UserDataDocument.Removal(recipe.name, removedVisit.time)), deleted.historyRemovals)

                val olderFile = UserDataExportFile.export(beforeRemoval)
                repeat(2) { assertEquals(0 to 1, repo.importUserDataDocumentJson(olderFile).getOrThrow()) }
                val imported = UserDataExportFile.parse(repo.exportUserDataDocumentJson())
                assertEquals(deleted.copy(histories = listOf(newVisit)), imported)

                val readded = favoriteRemoval.copy(time = favoriteRemoval.time + 1)
                val newerFile = UserDataExportFile.export(UserDataDocument(favorites = listOf(readded)))
                assertEquals(1 to 1, repo.importUserDataDocumentJson(newerFile).getOrThrow())
                assertEquals(
                    imported.copy(favorites = listOf(readded), favoriteRemovals = emptyList()),
                    UserDataExportFile.parse(repo.exportUserDataDocumentJson()),
                )
            }
            val synced = manager.syncNow().getOrThrow()
            assertEquals(listOf(newVisit), synced.histories)
            assertEquals(listOf(UserDataDocument.Removal(recipe.name, removedVisit.time)), synced.historyRemovals)
        } finally {
            manager.setSyncEnabled(false).getOrThrow()
            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun libraryTransferExportsWebAndIosEnvelope_andReadsOlderAndroidFiles() = runBlocking {
        val db = testDatabase()
        try {
            val repo = UserLibraryRepository(db)
            val sample = cookData.recipes.first()
            val visit = UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(sample), 1_700_000_000_123.0)
            val legacy = UserDataDocument(favorites = listOf(UserDataDocument.Favorite(sample.id, visit.time)), histories = listOf(visit))
            repo.importUserDataDocumentJson(UserDataDocument.wireJson.encodeToString(legacy)).getOrThrow()

            val export = repo.exportUserDataDocumentJson()
            val root = Json.parseToJsonElement(export).jsonObject
            assertEquals(JsonPrimitive("cook"), root["app"])
            assertEquals(JsonPrimitive(1), root["version"])
            assertTrue(((root["exportedAt"] as? JsonPrimitive)?.longOrNull ?: 0) > 0)
            assertEquals(legacy, UserDataExportFile.parse(export))
            assertEquals(1 to 1, repo.importUserDataDocumentJson(export).getOrThrow())
        } finally {
            db.close()
        }
    }

    @Test
    fun searchIntentRestrictions_andSaverRestoration_preserveAvoidAndNoSpicy() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preferences = DevicePreferencesStore(context)
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
        controller.applySearchIntent(snapshot, confirmed, preferences)
        controller.toggleDiscoveryScene(CookDiscoveryScene.EASY)
        val result = controller.evaluateCandidates(snapshot, preferences.current)
        assertTrue(result.recipes.isNotEmpty())
        assertTrue(result.recipes.none { r -> "番茄" in r.stuff })
        assertTrue(result.recipes.none { r -> "辣椒" in r.stuff || "小米辣" in r.stuff })

        assertEquals(listOf("番茄"), DevicePreferencesStore(context).current.avoid)
        assertTrue(DevicePreferencesStore(context).current.noSpicy)

        // Restore the selected ingredients and scene across activity recreation.
        val savedBundle = with(CookSessionController.Saver) {
            SaverScope { true }.save(controller)
        }!!
        val restored = CookSessionController.Saver.restore(savedBundle)!!
        assertEquals(controller.conditions.value, restored.conditions.value)

        val (activeTool, incompatibleTool) = cookData.food.tools.take(2).map { it.name }
        controller.toggleTool(activeTool)
        val previousConditions = controller.conditions.value
        val previousPreferences = preferences.current
        val conflict = confirmed.copy(query = confirmed.query.copy(tool = incompatibleTool, avoid = listOf("胡萝卜")))
        val failure = runCatching { controller.applySearchIntent(snapshot, conflict, preferences) }.exceptionOrNull()
        assertTrue("Expected a tool conflict, received $failure", failure is SearchFilterConflict)
        assertEquals(previousConditions, controller.conditions.value)
        assertEquals(previousPreferences, preferences.current)
    }

    @Test
    fun privateVideoIsOnlyRemovedAfterTheLastRecipeReferenceIsDeletedOrReplaced() = runBlocking {
        val tempDir = Files.createTempDirectory("cook-private-video-cleanup").toFile()
        try {
            val videos = PrivateRecipeVideoStore(
                localRootDir = File(tempDir, "videos"),
                explicitDeviceId = UUID.randomUUID().toString(),
                inspectVideo = {},
            )
            val source = File(tempDir, "original.mp4").apply { writeBytes(ByteArray(128) { 3 }) }
            val installed = videos.install(videos.stageFromFile(source))
            val managedFile = videos.playableFile(installed)
            val store = PrivateRecipesStore(explicitFile = File(tempDir, "recipes.json"))
            val first = PrivateRecipe(
                customId = "custom-first",
                name = "第一道",
                stuff = listOf("鸡蛋"),
                tools = emptyList(),
                createdAt = 1_000,
                updatedAt = 1_000,
                video = installed,
            )
            val second = first.copy(customId = "custom-second", name = "第二道")
            store.upsertRecipe(first)
            store.upsertRecipe(second)

            val deletedVideo = requireNotNull(store.deleteRecipe(first.customId)?.video)
            assertTrue(store.removeUnreferencedVideo(deletedVideo, videos))
            assertTrue(managedFile.exists())
            val replacedVideo = requireNotNull(store.upsertRecipe(second.copy(video = null))?.video)
            assertTrue(store.removeUnreferencedVideo(replacedVideo, videos))
            assertFalse(managedFile.exists())
            assertTrue(source.exists())
            assertNull(store.state.value.recipes.single().video)
        } finally {
            tempDir.deleteRecursively()
        }
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
                inspectVideo = { stagedFile -> assertEquals("mp4", stagedFile.extension) },
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
    fun workbenchPreparationKeepsLegacyChecksAndDeduplicatesAnExplicitTool() {
        val presentation = RecipePresentation(RecipeText(blocks = listOf(
            RecipeTextBlock.Heading("必备原料和工具"),
            RecipeTextBlock.ListBlock(ordered = false, items = listOf("鸡蛋 2 个", "盐（可选）", "炒锅（带盖）")),
        )))
        val ingredients = listOf("鸡蛋", "盐", "葱")
        val rows = kitchenPreparationItems(
            presentation, ingredients, listOf("一口大锅", "烤箱"), legacyCatalogIngredients = ingredients,
        )
        assertEquals(listOf("炒锅（带盖）", "烤箱"), rows.filter { it.isTool }.flatMap { it.labels })
        assertTrue(rows.single { "鸡蛋 2 个" in it.labels }.isChecked(setOf("catalog:鸡蛋")))
        assertTrue(rows.none { it.key == "catalog:鸡蛋" })
        assertTrue(rows.single { "盐（可选）" in it.labels }.isChecked(setOf("catalog:盐")))
        assertTrue(rows.any { it.key == "catalog:葱" })
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

            db.accountLibraryDao().upsertSyncState(AccountSyncStateEntity("uid-alpha", true, 0.0, null, 0.0))
            val corruptBeta = AccountSyncStateEntity("uid-beta", true, 0.0, """{"version":999}""", 1_000.0)
            db.accountLibraryDao().upsertSyncState(corruptBeta)
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
            val repo = UserLibraryRepository(db, manager)

            // Parallel mutations in Account A both persist under mutationMutex
            awaitAll(
                async { repo.toggleFavorite(recipeOne.id, currentlyFavorite = false).getOrThrow() },
                async { repo.recordVisit(recipeTwo).getOrThrow() },
            )
            val docAfterParallel = manager.state.value.accountDocument
            val alphaScope = manager.state.value.libraryScope
            assertTrue(docAfterParallel.favorites.any { it.id == recipeOne.id })
            assertTrue(docAfterParallel.histories.any { it.recipe.name == recipeTwo.name })

            // Trigger delayed remote signOut while immediately completing SSO login for Account B
            val signOutJob = launch { manager.signOut() }
            try {
                withTimeout(5_000L) { remoteSignOutStarted.await() }
                assertNull(manager.state.value.credential)
                val authUrl = manager.beginSsoAuthorization()
                val nonce = requireNotNull(Uri.parse(authUrl).getQueryParameter("nonce"))
                val code = "B".repeat(43)
                val fragmentJson = """{"nonce":"$nonce","iss":"${YunlefunSSO.ISSUER}","ok":true,"code":"$code"}"""
                val fragmentB64 = YunlefunSSO.encodeBase64Url(fragmentJson.toByteArray(Charsets.UTF_8))
                val callbackUrl = "${YunlefunSSO.REDIRECT_URI}#ylf_sso=$fragmentB64"
                manager.handleSsoCallbackUrl(callbackUrl).getOrThrow()
            } finally {
                finishRemoteSignOut.complete(Unit)
                signOutJob.join()
            }

            // Account B remains signed in and its vault credential was NOT wiped by Account A's delayed signOut
            assertEquals("uid-beta", manager.state.value.credential?.subject)
            assertTrue(manager.state.value.accountCacheUnreadable)
            assertFalse(manager.state.value.usesAccountLibrary)
            assertTrue(manager.syncNow().exceptionOrNull() is UserDataSyncException.AccountCacheUnreadable)
            assertTrue(repo.recordVisit(recipeTwo, alphaScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertTrue(manager.syncNow(alphaScope).exceptionOrNull() is UserDataSyncException.LibraryChanged)
            assertTrue(db.deviceLibraryDao().getAllHistories().isEmpty())
            assertEquals(corruptBeta, db.accountLibraryDao().getSyncState("uid-beta"))
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
            assertEquals(setOf(sampleRecipe.id), favoriteIds(repo))
            assertEquals(1, histories(repo).size)

            // 2. Inject failure before Room import commit -> full rollback leaves device rows intact
            repo.beforeImportCommitHook = { throw IllegalStateException("simulated mid-transaction failure") }
            val rollbackRes = repo.importUserDataDocumentJson(
                UserDataDocument.wireJson.encodeToString(
                    UserDataDocument(favorites = listOf(UserDataDocument.Favorite(id = 999, time = 9000.0))),
                ),
            )
            assertTrue(rollbackRes.isFailure)
            repo.beforeImportCommitHook = null
            assertEquals(setOf(sampleRecipe.id), favoriteIds(repo))
            assertFalse(999 in favoriteIds(repo))

            // 3. Import the active source records and preserve the destination's existing entries.
            val original = UserDataExportFile.parse(repo.exportUserDataDocumentJson())
            val originalVisit = original.histories.single()
            val removedVisit = originalVisit.copy(time = originalVisit.time + 20.125)
            val activeVisit = removedVisit.copy(time = originalVisit.time + 20.375)
            val activeFavorite = UserDataDocument.Favorite(id = 777, time = 9000.0)
            val removedFavorite = UserDataDocument.Favorite(id = 778, time = 9000.0)
            val tombstoneBackup = UserDataDocument(
                favorites = listOf(activeFavorite, removedFavorite),
                favoriteRemovals = listOf(
                    UserDataDocument.Favorite(id = sampleRecipe.id, time = Double.MAX_VALUE / 2),
                    removedFavorite,
                ),
                histories = listOf(originalVisit.copy(time = originalVisit.time - 1), removedVisit, activeVisit),
                historyRemovals = listOf(UserDataDocument.Removal(name = sampleRecipe.name, time = removedVisit.time)),
                historyClearedAt = originalVisit.time + 10,
            )
            val file = UserDataExportFile.export(tombstoneBackup)
            suspend fun assertImportedRecords(library: UserLibraryRepository) {
                val imported = UserDataExportFile.parse(library.exportUserDataDocumentJson())
                assertEquals(setOf(original.favorites.single(), activeFavorite), imported.favorites.toSet())
                assertEquals(listOf(originalVisit, activeVisit), imported.histories.sortedBy { it.time })
                assertEquals(original.favoriteRemovals, imported.favoriteRemovals)
                assertEquals(original.historyRemovals, imported.historyRemovals)
                assertEquals(original.historyClearedAt, imported.historyClearedAt, 0.0)
            }
            repo.importUserDataDocumentJson(file).getOrThrow()
            assertImportedRecords(repo)

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
            db.accountLibraryDao().upsertSyncState(AccountSyncStateEntity("uid-rollback", true, 0.0, null, 0.0))
            val manager = YunlefunSessionManager(
                vault = vault,
                database = db,
                accountService = YunlefunAccountService(okTransport),
                syncService = UserDataSyncService(okTransport),
                membershipService = MembershipService(okTransport),
            )
            manager.awaitInitialHydration()
            val accountRepo = UserLibraryRepository(db, manager)
            manager.beforeAccountPersistCommitHook = { throw IllegalStateException("simulated db failure") }
            val mutateErr = accountRepo.toggleFavorite(555, currentlyFavorite = false).exceptionOrNull()
            assertEquals("simulated db failure", mutateErr?.message)
            assertFalse(manager.state.value.accountDocument.favorites.any { it.id == 555 })

            manager.beforeAccountPersistCommitHook = null
            accountRepo.importUserDataDocumentJson(UserDataExportFile.export(original)).getOrThrow()
            accountRepo.importUserDataDocumentJson(file).getOrThrow()
            assertImportedRecords(accountRepo)
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
            val videoStore = PrivateRecipeVideoStore(
                localRootDir = root,
                explicitDeviceId = UUID.randomUUID().toString().lowercase(),
                inspectVideo = {},
            )
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
