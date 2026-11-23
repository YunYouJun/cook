package cn.yunyoujun.cook.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.data.local.AccountSyncStateEntity
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.MembershipCheckReason
import cn.yunyoujun.cook.data.remote.MembershipService
import cn.yunyoujun.cook.data.remote.UserDataSyncException
import cn.yunyoujun.cook.data.remote.UserDataSyncService
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.data.repository.UserLibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserDataSyncCredentialTest {
    @Test
    fun verifiedRenewalResumesOfflineSyncEvenIfTheOldDenialArrivesAfterRenewal() = runBlocking {
        for (checkFirst in listOf(false, true)) {
            val firstUpload = CompletableDeferred<Unit>()
            val finishFirstUpload = CompletableDeferred<Unit>()
            val hasMembership = AtomicBoolean(false)
            val firstAttempt = AtomicBoolean(true)
            val remote = UserDataDocument(
                favorites = listOf(UserDataDocument.Favorite(588, 3_000.0)),
                histories = listOf(UserDataDocument.Visit(UserDataDocument.RecipeSnapshot("蒸蛋羹"), 3_000.0)),
            )
            val transport = ScriptedTransport { request ->
                when (request.action) {
                    "getAccount" -> membership(hasMembership.get())
                    "sync" -> if (firstAttempt.getAndSet(false)) {
                        firstUpload.complete(Unit)
                        finishFirstUpload.await()
                        rejected("MEMBERSHIP_REQUIRED", 403)
                    } else {
                        assertTrue(hasMembership.get())
                        success(remote.merging(requireNotNull(request.document)))
                    }
                    else -> error("Unexpected request")
                }
            }
            withSession(transport) { database, manager ->
                val initial = async { manager.syncNow() }
                try {
                    withTimeout(5_000) { firstUpload.await() }
                    if (!checkFirst) {
                        finishFirstUpload.complete(Unit)
                        assertTrue(withTimeout(5_000) { initial.await() }.exceptionOrNull() is UserDataSyncException.MembershipRequired)
                        manager.checkMembership(MembershipCheckReason.RESTORE)
                        assertEquals(false, manager.state.value.entitlement?.isActive)
                        assertEquals(oldLocal, manager.state.value.accountDocument)
                    }
                    hasMembership.set(true)
                    manager.checkMembership(MembershipCheckReason.PLAY_ORDER)
                    finishFirstUpload.complete(Unit)
                    assertTrue(withTimeout(5_000) { initial.await() }.exceptionOrNull() is UserDataSyncException.MembershipRequired)

                    val merged = remote.merging(oldLocal)
                    withTimeout(5_000) { manager.state.first { !it.isSyncing && it.accountDocument == merged } }
                    val stored = requireNotNull(database.accountLibraryDao().getSyncState(account))
                    assertEquals(merged, UserDataDocument.decode(requireNotNull(stored.lastSyncedDocumentJson)))
                    assertEquals(listOf(oldLocal, oldLocal), transport.requests.filter { it.action == "sync" }.map { it.document })
                } finally {
                    finishFirstUpload.complete(Unit)
                }
            }
        }
    }

    @Test
    fun verifyingMembershipDoesNotResumeSyncAfterConsentWasRevoked() = runBlocking {
        val unexpectedUpload = CompletableDeferred<Unit>()
        val firstAttempt = AtomicBoolean(true)
        val transport = ScriptedTransport { request ->
            when (request.action) {
                "getAccount" -> membership(true)
                "sync" -> {
                    if (!firstAttempt.getAndSet(false)) unexpectedUpload.complete(Unit)
                    rejected("MEMBERSHIP_REQUIRED", 403)
                }
                else -> error("Unexpected request")
            }
        }
        withSession(transport) { database, manager ->
            assertTrue(manager.syncNow().exceptionOrNull() is UserDataSyncException.MembershipRequired)
            manager.setSyncEnabled(false).getOrThrow()
            manager.checkMembership(MembershipCheckReason.PLAY_ORDER)

            assertTrue(manager.state.value.entitlement?.isActiveAt() == true)
            assertNull(withTimeoutOrNull(1_200) { unexpectedUpload.await() })
            val stored = requireNotNull(database.accountLibraryDao().getSyncState(account))
            assertFalse(stored.syncEnabled)
            assertEquals(oldLocal, UserDataDocument.decode(requireNotNull(stored.lastSyncedDocumentJson)))
        }
    }

    @Test
    fun syncRefreshesRejectedCredentialAndReplaysWithLatestOfflineEdits() = runBlocking {
        val checkingAccess = CompletableDeferred<Unit>()
        val confirmAccess = CompletableDeferred<Unit>()
        val remote = UserDataDocument(favorites = listOf(UserDataDocument.Favorite(588, 3_000.0)))
        val transport = ScriptedTransport { request ->
            when (request.action) {
                "refresh" -> refreshedCredential()
                "capabilities" -> {
                    checkingAccess.complete(Unit)
                    confirmAccess.await()
                    success()
                }
                "sync" -> if (request.authorization == oldAuthorization) {
                    rejected("LOGIN_REQUIRED")
                } else {
                    success(remote.merging(request.document!!))
                }
                else -> error("Unexpected request")
            }
        }
        withSession(transport) { database, manager ->
            val syncing = async { manager.syncNow().getOrThrow() }
            try {
                withTimeout(5_000) { checkingAccess.await() }
                val library = UserLibraryRepository(database, manager)
                library.toggleFavorite(250, currentlyFavorite = true).getOrThrow()
                val edited = manager.state.value.accountDocument
                confirmAccess.complete(Unit)

                val merged = withTimeout(5_000) { syncing.await() }
                assertEquals(remote.merging(edited), merged)
                assertEquals(listOf("sync", "refresh", "capabilities", "sync"), transport.requests.take(4).map { it.action })
                assertEquals(listOf(oldLocal, null, null, edited), transport.requests.take(4).map { it.document })
                assertEquals(listOf(oldAuthorization, null, renewedAuthorization, renewedAuthorization),
                    transport.requests.take(4).map { it.authorization })
                val stored = requireNotNull(database.accountLibraryDao().getSyncState(account))
                assertEquals(merged, UserDataDocument.decode(requireNotNull(stored.lastSyncedDocumentJson)))
                assertTrue(stored.lastSyncedAtMs != null)
            } finally {
                confirmAccess.complete(Unit)
            }
        }
    }

    @Test
    fun concurrentRenewalChecksTheLatestCredentialBeforeSendingOfflineDeletion() = runBlocking {
        for (firstCheck in listOf(success(), rejected("LOGIN_REQUIRED", 401))) {
            val checkingAccess = CompletableDeferred<Unit>()
            val finishFirstCheck = CompletableDeferred<Unit>()
            val transport = ScriptedTransport { request ->
                when (request.action) {
                    "refresh" -> refreshedCredential(request.refreshToken)
                    "capabilities" -> when (request.authorization) {
                        renewedAuthorization -> {
                            checkingAccess.complete(Unit)
                            finishFirstCheck.await()
                            firstCheck
                        }
                        latestAuthorization -> success()
                        else -> error("Unexpected access check")
                    }
                    "sync" -> when (request.authorization) {
                        oldAuthorization, renewedAuthorization -> rejected("LOGIN_REQUIRED", 401)
                        latestAuthorization -> success(oldLocal.merging(requireNotNull(request.document)))
                        else -> error("Unexpected sync")
                    }
                    else -> error("Unexpected request")
                }
            }
            withSession(transport) { database, manager ->
                val syncing = async { manager.syncNow() }
                try {
                    withTimeout(5_000) { checkingAccess.await() }
                    val identity = requireNotNull(manager.state.value.membershipSession)
                    manager.withMembershipSession(identity, rejectedAccessToken = "new-token") { credential ->
                        assertEquals("latest-token", credential.accessToken)
                    }
                    UserLibraryRepository(database, manager).toggleFavorite(250, currentlyFavorite = true).getOrThrow()
                    val edited = manager.state.value.accountDocument
                    finishFirstCheck.complete(Unit)

                    val merged = withTimeout(5_000) { syncing.await().getOrThrow() }
                    assertTrue(merged.favorites.isEmpty())
                    assertEquals(edited.favoriteRemovals, merged.favoriteRemovals)
                    val calls = transport.requests.take(6)
                    assertEquals(
                        listOf("sync", "refresh", "capabilities", "refresh", "capabilities", "sync"),
                        calls.map { it.action },
                    )
                    assertEquals(
                        listOf(oldAuthorization, null, renewedAuthorization, null, latestAuthorization, latestAuthorization),
                        calls.map { it.authorization },
                    )
                    assertEquals(listOf(oldLocal, edited), calls.mapNotNull { it.document })
                    val stored = requireNotNull(database.accountLibraryDao().getSyncState(account))
                    assertEquals(merged, UserDataDocument.decode(requireNotNull(stored.lastSyncedDocumentJson)))
                    assertTrue(stored.lastSyncedAtMs != null)
                } finally {
                    finishFirstCheck.complete(Unit)
                }
            }
        }
    }

    @Test
    fun concurrentRenewalDoesNotResendTheDocumentIfTheLatestCredentialLacksAccess() = runBlocking {
        val checkingAccess = CompletableDeferred<Unit>()
        val finishFirstCheck = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { request ->
            when (request.action) {
                "refresh" -> refreshedCredential(request.refreshToken)
                "capabilities" -> when (request.authorization) {
                    renewedAuthorization -> {
                        checkingAccess.complete(Unit)
                        finishFirstCheck.await()
                        success()
                    }
                    latestAuthorization -> rejected("MEMBERSHIP_REQUIRED", 403)
                    else -> error("Unexpected access check")
                }
                "sync" -> if (request.authorization == oldAuthorization) rejected("LOGIN_REQUIRED", 401)
                else error("The document must not be sent before confirming access")
                else -> error("Unexpected request")
            }
        }
        withSession(transport) { database, manager ->
            val syncing = async { manager.syncNow() }
            try {
                withTimeout(5_000) { checkingAccess.await() }
                val identity = requireNotNull(manager.state.value.membershipSession)
                manager.withMembershipSession(identity, rejectedAccessToken = "new-token") { }
                finishFirstCheck.complete(Unit)

                val failure = withTimeout(5_000) { syncing.await().exceptionOrNull() }
                assertTrue(failure is UserDataSyncException.MembershipRequired)
                assertEquals(listOf("sync", "refresh", "capabilities", "refresh", "capabilities"),
                    transport.requests.map { it.action })
                assertEquals(listOf(oldLocal), transport.requests.mapNotNull { it.document })
                assertEquals(oldLocal, manager.state.value.accountDocument)
                assertNull(database.accountLibraryDao().getSyncState(account)?.lastSyncedAtMs)
            } finally {
                finishFirstCheck.complete(Unit)
            }
        }
    }

    @Test
    fun enablingSyncCanRefreshAfterGatewayRejectsAnOtherwiseValidToken() = runBlocking {
        val transport = ScriptedTransport { request ->
            when (request.action) {
                "refresh" -> refreshedCredential()
                "capabilities" -> if (request.authorization == oldAuthorization) HttpResponsePayload(401, "Unauthorized") else success()
                "sync" -> success(request.document)
                else -> error("Unexpected request")
            }
        }
        withSession(transport, syncEnabled = false) { _, manager ->
            manager.setSyncEnabled(true).getOrThrow()
            assertTrue(manager.state.value.syncEnabled)
            assertEquals(listOf("capabilities", "refresh", "capabilities"), transport.requests.take(3).map { it.action })
            assertEquals(listOf(oldAuthorization, null, renewedAuthorization), transport.requests.take(3).map { it.authorization })
            assertTrue(transport.requests.take(3).none { it.document != null })
        }
    }

    @Test
    fun ownerMismatchOrStillDeniedAccessStopsWithoutResendingOfflineData() = runBlocking {
        for ((status, code) in listOf(401 to "SESSION_CHANGED", 200 to "LOGIN_REQUIRED")) {
            val transport = ScriptedTransport { request ->
                if (request.action == "refresh") refreshedCredential() else rejected(code, status)
            }
            withSession(transport) { database, manager ->
                val result = manager.syncNow()
                assertTrue(result.exceptionOrNull() is UserDataSyncException.LoginRequired)
                val actions = if (code == "SESSION_CHANGED") listOf("sync") else listOf("sync", "refresh", "capabilities")
                assertEquals(actions, transport.requests.map { it.action })
                assertEquals(listOf(oldLocal), transport.requests.mapNotNull { it.document })
                assertEquals(oldLocal, manager.state.value.accountDocument)
                assertNull(database.accountLibraryDao().getSyncState(account)?.lastSyncedAtMs)
            }
        }
    }

    @Test
    fun disablingSyncDuringRenewalDiscardsTheRetry() = runBlocking {
        val renewing = CompletableDeferred<Unit>()
        val finishRenewal = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { request ->
            if (request.action == "refresh") {
                renewing.complete(Unit)
                withContext(NonCancellable) { finishRenewal.await() }
                refreshedCredential()
            } else {
                rejected("LOGIN_REQUIRED")
            }
        }
        withSession(transport) { database, manager ->
            val syncing = async { manager.syncNow() }
            try {
                withTimeout(5_000) { renewing.await() }
                manager.setSyncEnabled(false).getOrThrow()
                finishRenewal.complete(Unit)
                assertTrue(withTimeout(5_000) { syncing.await() }.isFailure)
                assertEquals(listOf("sync", "refresh"), transport.requests.map { it.action })
                val stored = requireNotNull(database.accountLibraryDao().getSyncState(account))
                assertFalse(stored.syncEnabled)
                assertEquals(oldLocal, UserDataDocument.decode(requireNotNull(stored.lastSyncedDocumentJson)))
            } finally {
                finishRenewal.complete(Unit)
            }
        }
    }

    private suspend fun withSession(
        transport: HttpTransport,
        syncEnabled: Boolean = true,
        test: suspend (CookDatabase, YunlefunSessionManager) -> Unit,
    ) {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(), CookDatabase::class.java,
        ).build()
        val secret = AtomicReference<String?>(Json.encodeToString(YunlefunCredential(
            "old-token", "old-refresh", account, Long.MAX_VALUE, "test-device",
        )))
        val vault = object : SecretVault {
            override fun readSecret(key: String): String? = secret.get().takeIf { key == AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON }
            override fun writeSecret(key: String, value: String) { secret.set(value) }
            override fun deleteSecret(key: String) { secret.set(null) }
        }
        database.accountLibraryDao().upsertSyncState(AccountSyncStateEntity(
            account, syncEnabled, 0.0, UserDataDocument.wireJson.encodeToString(oldLocal), 2_000.0,
        ))
        val manager = YunlefunSessionManager(
            vault, database, YunlefunAccountService(transport), UserDataSyncService(transport), MembershipService(transport),
        )
        try {
            manager.awaitInitialHydration()
            test(database, manager)
        } finally {
            if (manager.state.value.syncEnabled) manager.setSyncEnabled(false).getOrThrow()
            database.close()
        }
    }

    private data class Request(
        val action: String,
        val authorization: String?,
        val document: UserDataDocument?,
        val refreshToken: String? = null,
    )

    private class ScriptedTransport(private val reply: suspend (Request) -> HttpResponsePayload) : HttpTransport {
        val requests = CopyOnWriteArrayList<Request>()

        override suspend fun request(
            method: String, url: String, headers: Map<String, String>, jsonBody: String?,
            maxResponseBytes: Int, connectTimeoutMs: Int, readTimeoutMs: Int,
        ): HttpResponsePayload {
            assertEquals("POST", method)
            val fields = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
            val auth = headers["Authorization"]
            val call = when (url) {
                UserDataSyncService.ENDPOINT -> {
                    assertEquals(JsonPrimitive(UserDataSyncService.sha256Hex(account)), fields["ownerDigest"])
                    assertTrue(auth in listOf(oldAuthorization, renewedAuthorization, latestAuthorization))
                    val action = (fields["action"] as JsonPrimitive).content
                    Request(action, auth, fields["document"]?.let { UserDataDocument.decode(it.toString()) })
                }
                YunlefunAccountService.GATEWAY + "token" -> {
                    assertEquals(setOf("grant_type", "refresh_token"), fields.keys)
                    assertEquals(JsonPrimitive("refresh_token"), fields["grant_type"])
                    val token = fields.getValue("refresh_token").jsonPrimitive.content
                    assertTrue(token == "old-refresh" || token == "new-refresh")
                    Request("refresh", auth, null, refreshToken = token)
                }
                MembershipService.FUNCTIONS_GATEWAY + "account-api" -> {
                    assertEquals(JsonPrimitive("getAccount"), fields["action"])
                    assertEquals(oldAuthorization, auth)
                    Request("getAccount", auth, null)
                }
                else -> error("Unexpected endpoint")
            }
            requests += call
            return reply(call)
        }
    }

    private companion object {
        const val account = "sync-test-account"
        const val oldAuthorization = "Bearer old-token"
        const val renewedAuthorization = "Bearer new-token"
        const val latestAuthorization = "Bearer latest-token"
        val oldLocal = UserDataDocument(
            favorites = listOf(UserDataDocument.Favorite(250, 2_000.0)),
            histories = listOf(UserDataDocument.Visit(UserDataDocument.RecipeSnapshot("番茄炒蛋"), 2_000.0)),
        )

        fun refreshedCredential(token: String? = "old-refresh"): HttpResponsePayload = when (token) {
            "old-refresh" -> HttpResponsePayload(
                200, """{"access_token":"new-token","refresh_token":"new-refresh","sub":"$account","expires_in":3600}""",
            )
            "new-refresh" -> HttpResponsePayload(
                200, """{"access_token":"latest-token","refresh_token":"latest-refresh","sub":"$account","expires_in":3600}""",
            )
            else -> error("Unexpected refresh token")
        }

        fun rejected(code: String, status: Int = 200) = HttpResponsePayload(status, """{"ok":false,"code":"$code"}""")

        fun membership(active: Boolean): HttpResponsePayload {
            val expiry = if (active) System.currentTimeMillis() + 86_400_000L else 0L
            return HttpResponsePayload(200, """{"membership":{"isActive":$active,"expireAt":$expiry}}""")
        }

        fun success(document: UserDataDocument? = null): HttpResponsePayload {
            val encoded = document?.let { ",\"document\":" + UserDataDocument.wireJson.encodeToString(it) }.orEmpty()
            return HttpResponsePayload(200, """{"ok":true,"protocolVersion":2$encoded}""")
        }
    }
}
