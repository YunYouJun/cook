package cn.yunyoujun.cook.data.remote

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.platform.canOpenWebMembershipCheckout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountWebHandoffTest {
    private val credential = YunlefunCredential("fixture-access", "fixture-refresh", "fixture-user", Long.MAX_VALUE, "fixture-device")
    private val binding = "b".repeat(43)

    @Test
    fun callbackBindsTheExactRequestAndExpiresAfter45Seconds() {
        val verifier = "a".repeat(43)
        val handoff = YunlefunAccountWebHandoff(startedAtMs = 100, verifier = verifier)
        val request = YunlefunSSO.sha256Base64Url(verifier.toByteArray())
        assertEquals("${YunlefunAccountWebHandoff.ENDPOINT}?request=$request&transport=browser", handoff.startUrl)
        assertFalse(handoff.startUrl.contains(verifier))
        val callback = "${YunlefunSSO.REDIRECT_URI}#cook_handoff=$request.$binding"
        val result = handoff.complete(callback, nowMs = 45_099)
        assertEquals("${YunlefunSSO.ISSUER}/auth/cook-account?request=$request&binding=$binding", result.url)
        assertEquals(verifier, result.approval["verifier"])
        assertFalse(result.url.contains(verifier))
        for (bad in listOf(
            callback.replace("platform=native", "platform=native&next=/my"),
            callback.replace("cook.yunyoujun.cn", "other.example"),
            callback.replace(request, "x".repeat(43)),
            callback.replace("#cook_handoff=", "#ylf_sso="),
            "$callback.extra",
        )) {
            assertTrue(runCatching { handoff.complete(bad, nowMs = 101) }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)
        }
        assertTrue(runCatching { handoff.complete(callback, nowMs = 45_100) }.exceptionOrNull()
            is YunlefunAccountException.InvalidWebsiteCallback)
    }

    @Test
    fun onlyMissingCapabilityFallsBackAndProbeSendsNoCredentials() = runBlocking {
        val transport = Transport()
        val service = YunlefunAccountService(transport)
        transport.capability = HttpResponsePayload(200, """{"supported":true}""")
        assertFalse(service.supportsAccountWebHandoff())
        transport.capability = HttpResponsePayload(404, "")
        assertFalse(service.supportsAccountWebHandoff())
        transport.capability = HttpResponsePayload(503, "")
        assertTrue(runCatching { service.supportsAccountWebHandoff() }.exceptionOrNull() is YunlefunAccountException.Unavailable)
    }

    @Test
    fun validCallbackApprovesOnceAndWrongLinksLeaveTheRealRequestAvailable() = runBlocking {
        val transport = Transport()
        val database = database()
        try {
            val sessions = sessions(database, transport)
            sessions.awaitInitialHydration()
            val callback = callbackFor(sessions.beginAccountWebsite())
            assertTrue(sessions.state.value.awaitingAccountWebsite)
            assertTrue(runCatching { sessions.openAccountWebsiteFromCallback("$callback.extra") {} }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)
            assertTrue(sessions.state.value.awaitingAccountWebsite)
            var opened = ""
            sessions.openAccountWebsiteFromCallback(callback) { opened = it }
            assertTrue(opened.startsWith("${YunlefunSSO.ISSUER}/auth/cook-account?"))
            assertFalse(sessions.state.value.awaitingAccountWebsite)
            assertTrue(runCatching { sessions.openAccountWebsiteFromCallback(callback) {} }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)
            assertEquals(1, transport.approvals)
            val verifier = requireNotNull(transport.approvalBody?.get("verifier")).jsonPrimitive.content
            assertTrue(callback.contains(YunlefunSSO.sha256Base64Url(verifier.toByteArray())))
            assertFalse(opened.contains(credential.accessToken))
        } finally {
            database.close()
        }
    }

    @Test
    fun signOutDuringApprovalPreventsOpeningTheOldAccountsWebsite() = runBlocking {
        val transport = Transport()
        val awaitingApproval = CompletableDeferred<Unit>()
        val resumeApproval = CompletableDeferred<Unit>()
        transport.onApproval = {
            awaitingApproval.complete(Unit)
            resumeApproval.await()
        }
        val database = database()
        try {
            val sessions = sessions(database, transport)
            sessions.awaitInitialHydration()
            val callback = callbackFor(sessions.beginAccountWebsite())
            val result = async { runCatching { sessions.openAccountWebsiteFromCallback(callback) { error("Old account must not open") } } }
            awaitingApproval.await()
            sessions.signOut()
            resumeApproval.complete(Unit)
            assertTrue(result.await().exceptionOrNull() is CancellationException)
            assertFalse(sessions.state.value.awaitingAccountWebsite)
            assertEquals(null, sessions.state.value.credential)
        } finally {
            database.close()
        }
    }

    @Test
    fun membershipCheckoutUsesTheSameAccountAndChecksTheAuthoritativeEntitlementOnceOnReturn() = runBlocking {
        val transport = Transport()
        val database = database()
        try {
            val sessions = sessions(database, transport)
            val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
            val callback = callbackFor(sessions.beginAccountWebsite(purchase))
            assertEquals(purchase, sessions.state.value.pendingWebsitePurpose)
            assertFalse(sessions.state.value.awaitingAccountWebsite)
            assertTrue(runCatching {
                sessions.openAccountWebsiteFromCallback(callback, AccountWebsitePurpose.ACCOUNT_SETTINGS) {}
            }.exceptionOrNull() is YunlefunAccountException.InvalidWebsiteCallback)
            assertEquals(purchase, sessions.state.value.pendingWebsitePurpose)
            sessions.checkMembership(MembershipCheckReason.CHECKOUT_RETURN)
            assertEquals(0, transport.membershipChecks)
            var opened = ""
            sessions.openAccountWebsiteFromCallback(callback, purchase) { opened = it }
            assertTrue(opened.startsWith("${YunlefunSSO.ISSUER}/auth/cook-account?"))
            assertTrue(sessions.state.value.awaitingMembershipReturn)
            assertNull(sessions.state.value.entitlement)

            val expiresAt = System.currentTimeMillis() + 86_400_000L
            transport.membership = HttpResponsePayload(200, """{"membership":{"isActive":true,"expireAt":$expiresAt}}""")
            sessions.checkMembership(MembershipCheckReason.CHECKOUT_RETURN)
            sessions.checkMembership(MembershipCheckReason.CHECKOUT_RETURN)
            assertEquals(1, transport.membershipChecks)
            assertFalse(sessions.state.value.awaitingMembershipReturn)
            assertEquals(expiresAt, sessions.state.value.entitlement?.expiresAtEpochMs)
            assertEquals(credential.subject, sessions.state.value.entitlement?.userID)

            sessions.checkMembership(MembershipCheckReason.RESTORE)
            assertEquals(expiresAt, sessions.state.value.entitlement?.expiresAtEpochMs)
            transport.membership = HttpResponsePayload(503, "{}")
            sessions.checkMembership(MembershipCheckReason.RESTORE)
            assertEquals(3, transport.membershipChecks)
            assertEquals(expiresAt, sessions.state.value.entitlement?.expiresAtEpochMs)
            assertEquals("暂时无法核对会员权益，请重试。", sessions.state.value.membershipMessage)
        } finally {
            database.close()
        }
    }

    @Test
    fun unavailableHandoffPreventsCheckoutWithoutChangingOrdinaryAccountSettings() = runBlocking {
        val transport = Transport().apply { capability = HttpResponsePayload(404, "") }
        val database = database()
        try {
            val sessions = sessions(database, transport)
            assertTrue(runCatching {
                sessions.beginAccountWebsite(AccountWebsitePurpose.MEMBERSHIP_PURCHASE)
            }.exceptionOrNull() is YunlefunAccountException.CheckoutUnavailable)
            assertNull(sessions.state.value.pendingWebsitePurpose)
            assertEquals(YunlefunAccountWebHandoff.SETTINGS_URL, sessions.beginAccountWebsite())
            assertEquals(0, transport.approvals)
        } finally {
            database.close()
        }
    }

    @Test
    fun staleMembershipReplyCannotRestoreEntitlementAfterSigningOut() = runBlocking {
        val transport = Transport()
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        transport.onMembershipCheck = {
            requestStarted.complete(Unit)
            releaseRequest.await()
        }
        val database = database()
        try {
            val sessions = sessions(database, transport)
            val result = async { runCatching { sessions.checkMembership(MembershipCheckReason.RESTORE) } }
            requestStarted.await()
            sessions.signOut()
            releaseRequest.complete(Unit)
            assertTrue(result.await().exceptionOrNull() is CancellationException)
            assertNull(sessions.state.value.entitlement)
            assertFalse(sessions.state.value.isCheckingMembership)
        } finally {
            releaseRequest.complete(Unit)
            database.close()
        }
    }

    @Test
    fun onlyDirectBuildsWithoutPlayAsInstallSourceExposeWebCheckout() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val packages = shadowOf(app.packageManager)
        packages.setInstallSourceInfo(app.packageName, "com.android.shell", "com.android.shell")
        assertEquals(BuildConfig.COOK_WEB_MEMBERSHIP_CHECKOUT, app.canOpenWebMembershipCheckout())
        packages.setInstallSourceInfo(app.packageName, "com.android.shell", "com.android.vending")
        assertFalse(app.canOpenWebMembershipCheckout())
        packages.setInstallSourceInfo(app.packageName, "com.android.vending", "com.android.shell")
        assertFalse(app.canOpenWebMembershipCheckout())
    }

    private fun database(): CookDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Application>(),
        CookDatabase::class.java,
    ).build()

    private fun callbackFor(startUrl: String): String =
        "${YunlefunSSO.REDIRECT_URI}#cook_handoff=${startUrl.substringAfter("request=").substringBefore('&')}.$binding"

    private fun sessions(database: CookDatabase, transport: Transport): YunlefunSessionManager {
        val vault = object : SecretVault {
            private var saved: String? = Json.encodeToString(credential)
            override fun readSecret(name: String): String? = saved.takeIf { name == AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON }
            override fun writeSecret(name: String, value: String) { saved = value }
            override fun deleteSecret(name: String) { saved = null }
        }
        return YunlefunSessionManager(
            vault, database, accountService = YunlefunAccountService(transport), membershipService = MembershipService(transport),
        )
    }

    private inner class Transport : HttpTransport {
        var capability = HttpResponsePayload(200, """{"supported":true,"systemBrowser":true}""")
        var approvals = 0
        var approvalBody: kotlinx.serialization.json.JsonObject? = null
        var onApproval: suspend () -> Unit = {}
        var membershipChecks = 0
        var membership = HttpResponsePayload(200, """{"membership":{"isActive":false,"expireAt":null}}""")
        var onMembershipCheck: suspend () -> Unit = {}

        override suspend fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            jsonBody: String?,
            maxResponseBytes: Int,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponsePayload = when (url) {
            YunlefunAccountWebHandoff.ENDPOINT -> {
                assertEquals("GET", method)
                assertEquals(1024, maxResponseBytes)
                assertTrue(headers.isEmpty())
                assertEquals(null, jsonBody)
                capability
            }
            "${MembershipService.FUNCTIONS_GATEWAY}account-api" -> {
                assertEquals("POST", method)
                assertEquals(mapOf("Authorization" to "Bearer ${credential.accessToken}"), headers)
                assertEquals("""{"action":"getAccount"}""", jsonBody)
                membershipChecks++
                onMembershipCheck()
                membership
            }
            YunlefunAccountService.TICKET_URL -> {
                assertEquals("POST", method)
                assertEquals("Bearer ${credential.accessToken}", headers["Authorization"])
                assertEquals(credential.deviceID, headers["x-device-id"])
                assertEquals(YunlefunAccountService.COOK_ORIGIN, headers["Origin"])
                val body = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                assertEquals(setOf("action", "clientId", "verifier", "binding"), body.keys)
                assertEquals("approveCookWebHandoff", body.getValue("action").jsonPrimitive.content)
                assertEquals(binding, body.getValue("binding").jsonPrimitive.content)
                approvalBody = body
                approvals++
                onApproval()
                HttpResponsePayload(200, """{"ok":true}""")
            }
            "${YunlefunAccountService.GATEWAY}user/signout" -> HttpResponsePayload(200, "{}")
            else -> error("Unexpected endpoint $url")
        }
    }
}
