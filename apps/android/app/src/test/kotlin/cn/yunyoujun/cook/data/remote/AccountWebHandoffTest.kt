package cn.yunyoujun.cook.data.remote

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.CookApplication
import cn.yunyoujun.cook.MainActivity
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.CredentialVaultException
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.platform.canOpenWebMembershipCheckout
import cn.yunyoujun.cook.ui.state.AccountBrowserFlow
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountWebHandoffTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val compose = createEmptyComposeRule()

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
    fun responseLostDuringApprovalCanResumeAfterRestartWithOnlyTheOriginalBrowser() = runBlocking {
        val database = database()
        try {
            for (purpose in AccountWebsitePurpose.entries) {
                val transport = Transport()
                val vault = vault()
                val original = sessions(database, transport, vault)
                val callback = callbackFor(original.beginAccountWebsite(purpose))
                transport.onApproval = { throw IOException("Approval response was lost") }
                assertTrue(runCatching { original.approveAccountWebsiteCallback(callback, purpose) }.exceptionOrNull()
                    is YunlefunAccountException.Unavailable)
                val firstApproval = transport.approvalBody
                assertEquals(2, transport.approvals)

                val relaunched = sessions(database, transport, vault)
                assertEquals(purpose, relaunched.state.value.pendingWebsitePurpose)
                val differentBrowser = callback.substringBeforeLast('.') + "." + "x".repeat(43)
                assertTrue(runCatching { relaunched.approveAccountWebsiteCallback(differentBrowser, purpose) }.exceptionOrNull()
                    is YunlefunAccountException.InvalidWebsiteCallback)

                transport.onApproval = {}
                var opened = ""
                relaunched.openAccountWebsiteFromCallback(callback, purpose) { opened = it }
                assertEquals(3, transport.approvals)
                assertEquals(firstApproval, transport.approvalBody)
                assertTrue(opened.startsWith("${YunlefunSSO.ISSUER}/auth/cook-account?"))
                assertEquals(purpose == AccountWebsitePurpose.MEMBERSHIP_PURCHASE, opened.endsWith("#membership"))
                assertNull(relaunched.state.value.pendingWebsitePurpose)
                assertTrue(runCatching { relaunched.approveAccountWebsiteCallback(callback, purpose) }.exceptionOrNull()
                    is YunlefunAccountException.InvalidWebsiteCallback)
                assertEquals(3, transport.approvals)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun approvalRetriesTheSameBrowserOnceAndCanStillRenewAnExpiredToken() = runBlocking {
        val database = database()
        try {
            for (authFirst in listOf(true, false)) {
                val transport = Transport()
                val payloads = mutableListOf<kotlinx.serialization.json.JsonObject>()
                val authHeaders = mutableListOf<String?>()
                transport.onRefresh = { renewedCredential() }
                transport.approvalResponse = { authorization ->
                    authHeaders += authorization
                    payloads += requireNotNull(transport.approvalBody)
                    when (payloads.size) {
                        if (authFirst) 1 else 2 -> HttpResponsePayload(401, "{}")
                        if (authFirst) 2 else 1 -> {
                            if (authFirst) throw IOException("Connection lost after sending the approval")
                            HttpResponsePayload(503, "{}")
                        }
                        3 -> HttpResponsePayload(200, """{"ok":true}""")
                        else -> error("Unexpected approval retry")
                    }
                }
                val sessions = sessions(database, transport)
                val purpose = if (authFirst) AccountWebsitePurpose.ACCOUNT_SETTINGS else AccountWebsitePurpose.MEMBERSHIP_PURCHASE
                val callback = callbackFor(sessions.beginAccountWebsite(purpose))
                var launched = 0
                sessions.openAccountWebsiteFromCallback(callback, purpose) { launched++ }

                val original = "Bearer ${credential.accessToken}"
                val renewed = "Bearer renewed-access"
                assertEquals(if (authFirst) listOf(original, renewed, renewed) else listOf(original, original, renewed),
                    authHeaders)
                assertEquals(3, payloads.size)
                assertEquals(1, payloads.distinct().size)
                assertEquals(1, transport.refreshes)
                assertEquals(1, launched)
                assertNull(sessions.state.value.pendingWebsitePurpose)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun approvalDoesNotRetryARejectedRequestOrAnExpiredHandoff() = runBlocking {
        val database = database()
        try {
            val purpose = AccountWebsitePurpose.ACCOUNT_SETTINGS
            for (response in listOf(
                HttpResponsePayload(403, """{"ok":false,"reason":"binding_mismatch"}"""),
                HttpResponsePayload(429, "{}"),
                HttpResponsePayload(503, """{"ok":false,"reason":"not_configured"}"""),
                HttpResponsePayload(200, """{"ok":false}"""),
            )) {
                val transport = Transport().apply { approvalResponse = { response } }
                val sessions = sessions(database, transport)
                val callback = callbackFor(sessions.beginAccountWebsite(purpose))
                assertTrue(runCatching { sessions.approveAccountWebsiteCallback(callback, purpose) }.isFailure)
                assertEquals(1, transport.approvals)
            }

            val transport = Transport().apply {
                onApproval = {
                    ShadowSystemClock.advanceBy(Duration.ofMillis(YunlefunAccountWebHandoff.LIFETIME_MS))
                    throw IOException("Response lost at expiration")
                }
            }
            val sessions = sessions(database, transport)
            val callback = callbackFor(sessions.beginAccountWebsite(purpose))
            assertTrue(runCatching { sessions.approveAccountWebsiteCallback(callback, purpose) }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)
            assertEquals(1, transport.approvals)
            assertNull(sessions.state.value.pendingWebsitePurpose)
        } finally {
            database.close()
        }
    }

    @Test
    fun approvedHandoffSurvivesRestartAndBrowserLaunchFailureWithoutAnotherApproval() = runBlocking {
        val database = database()
        try {
            for (purpose in AccountWebsitePurpose.entries) {
                val transport = Transport()
                val vault = vault()
                val original = sessions(database, transport, vault)
                val callback = callbackFor(original.beginAccountWebsite(purpose))
                val approved = original.approveAccountWebsiteCallback(callback, purpose)

                val relaunched = sessions(database, transport, vault)
                assertEquals(purpose, relaunched.state.value.pendingWebsitePurpose)
                val restored = relaunched.approveAccountWebsiteCallback(callback, purpose)
                assertEquals(approved.url, restored.url)
                assertTrue(runCatching {
                    relaunched.openApprovedAccountWebsite(restored) { throw ActivityNotFoundException() }
                }.exceptionOrNull() is ActivityNotFoundException)
                assertEquals(purpose, relaunched.state.value.pendingWebsitePurpose)
                assertFalse(relaunched.state.value.awaitingMembershipReturn)

                val afterFailure = sessions(database, transport, vault)
                val retry = afterFailure.resumeApprovedAccountWebsite(purpose)
                var opened = ""
                afterFailure.openApprovedAccountWebsite(retry) { opened = it }
                assertEquals(approved.url, opened)
                assertEquals(1, transport.approvals)
                assertEquals(purpose == AccountWebsitePurpose.MEMBERSHIP_PURCHASE, afterFailure.state.value.awaitingMembershipReturn)
                assertNull(sessions(database, transport, vault).state.value.pendingWebsitePurpose)
                assertTrue(runCatching {
                    afterFailure.openApprovedAccountWebsite(retry) { error("The delivered website must not reopen") }
                }.exceptionOrNull() is YunlefunAccountException.InvalidWebsiteCallback)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun cancelDuringApprovalDiscardsTheResultAndBlocksADuplicateWhilePending() = runBlocking {
        val database = database()
        val transport = Transport()
        val responsePending = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        try {
            val sessions = sessions(database, transport)
            val callback = callbackFor(sessions.beginAccountWebsite())
            transport.onApproval = {
                responsePending.complete(Unit)
                resume.await()
            }
            val result = async { runCatching { sessions.approveAccountWebsiteCallback(callback) } }
            withTimeout(5_000) { responsePending.await() }
            assertTrue(runCatching { sessions.approveAccountWebsiteCallback(callback) }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)
            sessions.cancelAccountWebsite().getOrThrow()
            resume.complete(Unit)

            assertTrue(result.await().exceptionOrNull() is YunlefunAccountException.InvalidWebsiteCallback)
            assertEquals(1, transport.approvals)
            assertNull(sessions.state.value.pendingWebsitePurpose)
        } finally {
            resume.complete(Unit)
            database.close()
        }
    }

    @Test
    fun expiredAccessTokenRenewsOnceAndReusesTheSameCheckoutApproval() = runBlocking {
        val transport = Transport()
        val sentApprovals = mutableListOf<kotlinx.serialization.json.JsonObject>()
        transport.approvalResponse = { authorization ->
            sentApprovals += requireNotNull(transport.approvalBody)
            when (sentApprovals.size) {
                1 -> {
                    assertEquals("Bearer ${credential.accessToken}", authorization)
                    HttpResponsePayload(401, """{"ok":false,"reason":"not_authenticated"}""")
                }
                2 -> {
                    assertEquals("Bearer renewed-access", authorization)
                    HttpResponsePayload(200, """{"ok":true}""")
                }
                else -> error("Unexpected approval retry")
            }
        }
        transport.onRefresh = { renewedCredential() }
        val database = database()
        val vault = vault()
        try {
            val sessions = sessions(database, transport, vault)
            val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
            val callback = callbackFor(sessions.beginAccountWebsite(purchase))
            var launched = 0
            sessions.openAccountWebsiteFromCallback(callback, purchase) { launched++ }

            assertEquals(1, transport.refreshes)
            assertEquals(2, sentApprovals.size)
            assertEquals(sentApprovals[0], sentApprovals[1])
            assertEquals(1, launched)
            assertTrue(sessions.state.value.awaitingMembershipReturn)
            val relaunched = sessions(database, transport, vault)
            assertEquals("renewed-access", relaunched.state.value.credential?.accessToken)
            assertTrue(relaunched.state.value.awaitingMembershipReturn)
            assertFalse(relaunched.state.value.awaitingAccountWebsite)
            assertTrue(runCatching { relaunched.approveAccountWebsiteCallback(callback, purchase) }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)
        } finally {
            database.close()
        }
    }

    @Test
    fun handoffThatExpiresWhileSavingTheRequestDoesNotOpenTheBrowser() = runBlocking {
        val transport = Transport()
        val database = database()
        val vault = vaultExpiringHandoffOnWrite("pendingAccountWebsite")
        try {
            val sessions = sessions(database, transport, vault)
            val launch = sessions.prepareAccountWebsite(AccountWebsitePurpose.ACCOUNT_SETTINGS)
            var opened = false
            val result = runCatching { sessions.launchAccountWebsite(launch) { opened = true } }

            assertTrue(result.exceptionOrNull() is YunlefunAccountException.InvalidWebsiteCallback)
            assertFalse(opened)
            assertNull(sessions.state.value.pendingWebsitePurpose)
            assertNull(sessions(database, transport, vault).state.value.pendingWebsitePurpose)
            val saved = requireNotNull(vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON))
            assertFalse(Json.parseToJsonElement(saved).jsonObject.containsKey("pendingAccountWebsite"))
        } finally {
            database.close()
        }
    }

    @Test
    fun approvedCheckoutThatExpiresWhileSavingTheReturnDoesNotOpenOrScheduleVerification() = runBlocking {
        val transport = Transport()
        val database = database()
        val vault = vaultExpiringHandoffOnWrite("awaitingMembershipReturn")
        try {
            val sessions = sessions(database, transport, vault)
            val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
            val callback = callbackFor(sessions.beginAccountWebsite(purchase))
            val approved = sessions.approveAccountWebsiteCallback(callback, purchase)
            var opened = false
            val result = runCatching { sessions.openApprovedAccountWebsite(approved) { opened = true } }

            assertTrue(result.exceptionOrNull() is YunlefunAccountException.InvalidWebsiteCallback)
            assertFalse(opened)
            assertNull(sessions.state.value.membershipWebsiteReturn)
            assertEquals(0L, sessions.state.value.membershipReturnGeneration)
            assertNull(sessions(database, transport, vault).state.value.membershipWebsiteReturn)
            sessions.checkMembership(MembershipCheckReason.CHECKOUT_RETURN)
            assertEquals(0, transport.membershipChecks)
            assertEquals(1, transport.approvals)
            assertNull(sessions.state.value.pendingWebsitePurpose)
        } finally {
            database.close()
        }
    }

    @Test
    fun handoffThatExpiresDuringRenewalCannotSubmitAnotherApproval() = runBlocking {
        val transport = Transport()
        transport.approvalResponse = { HttpResponsePayload(401, """{"ok":false,"reason":"not_authenticated"}""") }
        transport.onRefresh = {
            ShadowSystemClock.advanceBy(Duration.ofMillis(YunlefunAccountWebHandoff.LIFETIME_MS))
            renewedCredential()
        }
        val database = database()
        try {
            val sessions = sessions(database, transport)
            val callback = callbackFor(sessions.beginAccountWebsite())
            val result = runCatching { sessions.openAccountWebsiteFromCallback(callback) { error("Expired approval opened a page") } }
            assertTrue(result.exceptionOrNull() is YunlefunAccountException.InvalidWebsiteCallback)
            assertEquals(1, transport.refreshes)
            assertEquals(1, transport.approvals)
            assertFalse(sessions.state.value.awaitingAccountWebsite)
        } finally {
            database.close()
        }
    }

    @Test
    fun signingOutDuringRenewalCannotSubmitAnotherApproval() = runBlocking {
        val renewing = CompletableDeferred<Unit>()
        val finishRenewal = CompletableDeferred<Unit>()
        val transport = Transport()
        transport.approvalResponse = { HttpResponsePayload(401, """{"ok":false,"reason":"not_authenticated"}""") }
        transport.onRefresh = {
            renewing.complete(Unit)
            finishRenewal.await()
            renewedCredential()
        }
        val database = database()
        try {
            val sessions = sessions(database, transport)
            val callback = callbackFor(sessions.beginAccountWebsite())
            val opening = async { runCatching { sessions.openAccountWebsiteFromCallback(callback) { error("Signed-out account opened a page") } } }
            withTimeout(5_000) { renewing.await() }
            sessions.signOut()
            finishRenewal.complete(Unit)

            assertTrue(opening.await().exceptionOrNull() is CancellationException)
            assertEquals(1, transport.refreshes)
            assertEquals(1, transport.approvals)
            assertNull(sessions.state.value.credential)
        } finally {
            finishRenewal.complete(Unit)
            database.close()
        }
    }

    @Test
    fun accountHandoffSurvivesProcessRestartUsingTheEncryptedSessionAndIsConsumedOnce() = runBlocking {
        val database = database()
        val transport = Transport()
        AeadConfig.register()
        val aead = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        val vaultFile = temporaryFolder.newFolder().resolve("vault.enc")
        fun openVault() = AndroidKeystoreVault(vaultFile, aeadProvider = { aead })
        val vault = openVault().apply {
            writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, Json.encodeToString(credential))
        }
        try {
            val original = sessions(database, transport, vault)
            val start = original.beginAccountWebsite()
            val callback = callbackFor(start)
            val captured = vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON).orEmpty()
            val pending = Json.parseToJsonElement(captured).jsonObject.getValue("pendingAccountWebsite").jsonObject
            val verifier = pending.getValue("handoff").jsonObject.getValue("verifier").jsonPrimitive.content
            assertFalse(start.contains(verifier))
            assertFalse(vaultFile.readText().contains(verifier))

            val relaunched = sessions(database, transport, openVault())
            assertTrue(relaunched.state.value.awaitingAccountWebsite)
            assertEquals(credential.subject, relaunched.state.value.credential?.subject)
            assertTrue(runCatching { relaunched.approveAccountWebsiteCallback("$callback.extra") }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)
            assertTrue(relaunched.state.value.awaitingAccountWebsite)

            val opened = relaunched.approveAccountWebsiteCallback(callback, AccountWebsitePurpose.ACCOUNT_SETTINGS)
            assertEquals(credential.subject, opened.session.subject)
            assertEquals(verifier, transport.approvalBody?.get("verifier")?.jsonPrimitive?.content)
            assertFalse(opened.url.contains(verifier))
            relaunched.openApprovedAccountWebsite(opened) { assertEquals(opened.url, it) }
            assertFalse(vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON).orEmpty().contains(verifier))

            val afterApproval = sessions(database, transport, openVault())
            assertFalse(afterApproval.state.value.awaitingAccountWebsite)
            assertTrue(runCatching { afterApproval.approveAccountWebsiteCallback(callback) }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)
            assertEquals(1, transport.approvals)
        } finally {
            database.close()
        }
    }

    @Test
    fun processRestartRejectsCancelledExpiredAndPreviousBootRequests() = runBlocking {
        val database = database()
        val transport = Transport()
        val vault = vault()
        try {
            val original = sessions(database, transport, vault)
            val callback = callbackFor(original.beginAccountWebsite(AccountWebsitePurpose.MEMBERSHIP_PURCHASE))
            val afterRestart = sessions(database, transport, vault)
            assertEquals(AccountWebsitePurpose.MEMBERSHIP_PURCHASE, afterRestart.state.value.pendingWebsitePurpose)

            val afterReboot = sessions(database, transport, vault, bootCount = 8)
            assertNull(afterReboot.state.value.pendingWebsitePurpose)
            assertEquals(AccountWebsitePurpose.MEMBERSHIP_PURCHASE, afterReboot.state.value.expiredWebsitePurpose)
            assertTrue(runCatching { afterReboot.approveAccountWebsiteCallback(callback) }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)

            afterRestart.cancelAccountWebsite(AccountWebsitePurpose.MEMBERSHIP_PURCHASE).getOrThrow()
            val afterCancel = sessions(database, transport, vault)
            assertNull(afterCancel.state.value.pendingWebsitePurpose)
            assertNull(afterCancel.state.value.expiredWebsitePurpose)
            assertTrue(runCatching { afterCancel.approveAccountWebsiteCallback(callback) }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)

            val expiring = callbackFor(afterCancel.beginAccountWebsite())
            ShadowSystemClock.advanceBy(Duration.ofMillis(YunlefunAccountWebHandoff.LIFETIME_MS))
            val afterExpiry = sessions(database, transport, vault)
            assertNull(afterExpiry.state.value.pendingWebsitePurpose)
            assertEquals(AccountWebsitePurpose.ACCOUNT_SETTINGS, afterExpiry.state.value.expiredWebsitePurpose)
            assertTrue(runCatching { afterExpiry.approveAccountWebsiteCallback(expiring) }.exceptionOrNull()
                is YunlefunAccountException.InvalidWebsiteCallback)
            assertEquals(0, transport.approvals)
        } finally {
            database.close()
        }
    }

    @Test
    fun returningFromDeepSleepClearsTheExpiredAccountWebsiteAndShowsHowToRetry() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val database = database()
        val transport = Transport()
        val vault = vault()
        val sessions = sessions(database, transport, vault)
        try {
            ReflectionHelpers.setField(app.container, "yunlefunSessionManager\$delegate", lazyOf(sessions))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(timeoutMillis = 15_000) {
                    compose.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("我的").performClick()
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("云乐坊账号与跨端同步"))
                compose.onNodeWithText("云乐坊账号与跨端同步").performClick()
                val callback = runBlocking { callbackFor(sessions.beginAccountWebsite()) }
                compose.onNodeWithText("粘贴并继续").assertExists()

                scenario.moveToState(Lifecycle.State.CREATED)
                val uptime = SystemClock.uptimeMillis()
                ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(YunlefunAccountWebHandoff.LIFETIME_MS))
                assertEquals(uptime, SystemClock.uptimeMillis())
                assertTrue(sessions.state.value.awaitingAccountWebsite)
                scenario.moveToState(Lifecycle.State.RESUMED)

                compose.waitUntil(timeoutMillis = 5_000) {
                    sessions.state.value.expiredWebsitePurpose == AccountWebsitePurpose.ACCOUNT_SETTINGS
                }
                compose.onNodeWithText("账号管理授权已过期，请重新打开账号管理。").performScrollTo().assertExists()
                compose.onNodeWithText("粘贴并继续").assertDoesNotExist()
                compose.onNodeWithText("管理云乐坊账号").performScrollTo().assertIsEnabled()
                assertFalse(vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON).orEmpty().contains("pendingAccountWebsite"))
                runBlocking {
                    assertTrue(runCatching { sessions.approveAccountWebsiteCallback(callback) }.exceptionOrNull()
                        is YunlefunAccountException.InvalidWebsiteCallback)
                    assertEquals(0, transport.approvals)
                    val freshCallback = callbackFor(sessions.beginAccountWebsite())
                    assertNull(sessions.state.value.expiredWebsitePurpose)
                    sessions.approveAccountWebsiteCallback(freshCallback)
                    assertEquals(1, transport.approvals)
                }
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun callbackCannotApproveUntilItsBrowserBindingCanBeSaved() = runBlocking {
        val database = database()
        val transport = Transport()
        val backing = vault()
        var canPersist = true
        val vault = object : SecretVault by backing {
            override fun writeSecret(key: String, value: String) {
                if (!canPersist) throw CredentialVaultException.Unavailable()
                backing.writeSecret(key, value)
            }
        }
        try {
            val original = sessions(database, transport, vault)
            val callback = callbackFor(original.beginAccountWebsite())
            canPersist = false
            assertTrue(original.cancelAccountWebsite().isFailure)
            assertTrue(runCatching { original.approveAccountWebsiteCallback(callback) }.exceptionOrNull()
                is CredentialVaultException.Unavailable)
            assertTrue(original.state.value.awaitingAccountWebsite)
            assertEquals(0, transport.approvals)

            canPersist = true
            original.openAccountWebsiteFromCallback(callback) {}
            assertFalse(original.state.value.awaitingAccountWebsite)
            assertEquals(1, transport.approvals)
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
    fun accountWebsiteStartsOnceWhenTheRecreatedActivityResumes() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val enteredCapabilityCheck = CompletableDeferred<Unit>()
        val finishCapabilityCheck = CompletableDeferred<Unit>()
        val transport = Transport().apply {
            onCapabilityCheck = {
                enteredCapabilityCheck.complete(Unit)
                finishCapabilityCheck.await()
            }
        }
        val database = database()
        try {
            val sessions = sessions(database, transport)
            ReflectionHelpers.setField(app.container, "yunlefunSessionManager\$delegate", lazyOf(sessions))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val looper = shadowOf(Looper.getMainLooper())
                scenario.onActivity {
                    ViewModelProvider(it)[AccountBrowserFlow::class.java].beginWebsite(sessions, AccountWebsitePurpose.ACCOUNT_SETTINGS)
                }
                looper.idle()
                runBlocking { withTimeout(5_000) { enteredCapabilityCheck.await() } }
                scenario.recreate()
                scenario.moveToState(Lifecycle.State.CREATED)
                finishCapabilityCheck.complete(Unit)
                ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
                looper.idle()
                scenario.onActivity {
                    assertNull(shadowOf(it).nextStartedActivity)
                    assertNull(sessions.state.value.pendingWebsitePurpose)
                }

                scenario.moveToState(Lifecycle.State.RESUMED)
                val deadline = System.nanoTime() + 5_000_000_000L
                var launched: Intent? = null
                while (launched == null && System.nanoTime() < deadline) {
                    looper.idle()
                    scenario.onActivity { launched = shadowOf(it).nextStartedActivity }
                    if (launched == null) Thread.sleep(10)
                }
                val start = requireNotNull(launched).dataString.orEmpty()
                assertTrue(start.startsWith("${YunlefunAccountWebHandoff.ENDPOINT}?request="))
                assertTrue(sessions.state.value.awaitingAccountWebsite)
                assertEquals(1, transport.capabilityChecks)
                assertEquals(0, transport.approvals)
                scenario.recreate()
                scenario.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
            }
        } finally {
            finishCapabilityCheck.complete(Unit)
            database.close()
        }
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun accountDeletionEntryUsesTheCurrentAccountHandoff() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val transport = Transport()
        val database = database()
        try {
            val sessions = sessions(database, transport)
            ReflectionHelpers.setField(app.container, "yunlefunSessionManager\$delegate", lazyOf(sessions))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("我的").performClick()
                val account = "云乐坊账号与跨端同步"
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(account))
                compose.onNodeWithText(account).performScrollTo().performClick()
                val deletion = compose.onNodeWithText("申请注销账号")
                deletion.performScrollTo().performClick()

                var startUrl: String? = null
                compose.waitUntil(5_000) {
                    scenario.onActivity { startUrl = startUrl ?: shadowOf(it).nextStartedActivity?.dataString }
                    startUrl != null
                }
                val start = requireNotNull(startUrl)
                assertTrue(start.startsWith("${YunlefunAccountWebHandoff.ENDPOINT}?request="))
                assertEquals(AccountWebsitePurpose.ACCOUNT_SETTINGS, sessions.state.value.pendingWebsitePurpose)
                deletion.assertIsNotEnabled()

                scenario.onActivity {
                    it.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Callback fixture", callbackFor(start)))
                }
                compose.onNodeWithText("粘贴并继续").performScrollTo().performClick()
                var approvedUrl: String? = null
                compose.waitUntil(5_000) {
                    scenario.onActivity { approvedUrl = approvedUrl ?: shadowOf(it).nextStartedActivity?.dataString }
                    approvedUrl != null
                }
                val request = Uri.parse(start).getQueryParameter("request")
                assertEquals("${YunlefunSSO.ISSUER}/auth/cook-account?request=$request&binding=$binding", approvedUrl)
                assertEquals(1, transport.approvals)
                assertEquals(credential.subject, sessions.state.value.credential?.subject)
                assertNull(sessions.state.value.pendingWebsitePurpose)
            }
        } finally {
            database.close()
        }
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun approvedAccountWebsiteShowsAContinueButtonAfterProcessRestart() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val transport = Transport()
        val database = database()
        val vault = vault()
        val purpose = AccountWebsitePurpose.ACCOUNT_SETTINGS
        try {
            val destination = runBlocking {
                val original = sessions(database, transport, vault)
                val callback = callbackFor(original.beginAccountWebsite(purpose))
                original.approveAccountWebsiteCallback(callback, purpose).url
            }
            val relaunched = sessions(database, transport, vault)
            ReflectionHelpers.setField(app.container, "yunlefunSessionManager\$delegate", lazyOf(relaunched))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("我的").performClick()
                val account = "云乐坊账号与跨端同步"
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(account))
                compose.onNodeWithText(account).performScrollTo().performClick()
                compose.onNodeWithText("继续打开账号管理").performScrollTo().assertIsEnabled().performClick()

                var opened: String? = null
                compose.waitUntil(5_000) {
                    scenario.onActivity { opened = opened ?: shadowOf(it).nextStartedActivity?.dataString }
                    opened != null && relaunched.state.value.pendingWebsitePurpose == null
                }
                assertEquals(destination, opened)
                assertEquals(1, transport.approvals)
                compose.onNodeWithText("继续打开账号管理").assertDoesNotExist()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun preparedWebsiteCannotOpenAfterSignOutAndFailedLaunchRestoresTheOriginalRequest() = runBlocking {
        val transport = Transport()
        val database = database()
        try {
            val sessions = sessions(database, transport)
            val settings = AccountWebsitePurpose.ACCOUNT_SETTINGS
            val checkout = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
            val current = requireNotNull(sessions.state.value.membershipSession)
            val oldSession = current.copy(revision = current.revision - 1)
            assertTrue(runCatching { sessions.prepareAccountWebsite(settings, oldSession) }.exceptionOrNull()
                is CancellationException)
            assertEquals(0, transport.capabilityChecks)
            val original = callbackFor(sessions.beginAccountWebsite(settings))
            val prepared = sessions.prepareAccountWebsite(checkout)
            assertEquals(settings, sessions.state.value.pendingWebsitePurpose)
            assertTrue(runCatching {
                sessions.launchAccountWebsite(prepared) { throw ActivityNotFoundException() }
            }.exceptionOrNull() is ActivityNotFoundException)
            assertEquals(settings, sessions.state.value.pendingWebsitePurpose)
            sessions.openAccountWebsiteFromCallback(original) {}

            val stale = sessions.prepareAccountWebsite(settings)
            sessions.signOut()
            assertTrue(runCatching {
                sessions.launchAccountWebsite(stale) { error("Signed-out session must not open its website") }
            }.exceptionOrNull() is CancellationException)
            assertNull(sessions.state.value.pendingWebsitePurpose)
        } finally {
            database.close()
        }
    }

    @Test
    fun browserReturnAfterProcessRelaunchResumesAcrossActivityRecreationAndOpensOnce() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val transport = Transport()
        val approve = CompletableDeferred<Unit>()
        val enteredApproval = CompletableDeferred<Unit>()
        transport.onApproval = {
            enteredApproval.complete(Unit)
            approve.await()
        }
        val database = database()
        try {
            val vault = vault()
            val callback = runBlocking {
                val original = sessions(database, transport, vault)
                callbackFor(original.beginAccountWebsite(AccountWebsitePurpose.MEMBERSHIP_PURCHASE))
            }
            val sessions = sessions(database, transport, vault)
            ReflectionHelpers.setField(app.container, "yunlefunSessionManager\$delegate", lazyOf(sessions))
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(callback), app, MainActivity::class.java)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val looper = shadowOf(Looper.getMainLooper())
                looper.idle()
                runBlocking { withTimeout(5_000) { enteredApproval.await() } }
                scenario.recreate()
                scenario.moveToState(Lifecycle.State.CREATED)
                approve.complete(Unit)
                looper.idle()
                scenario.onActivity {
                    assertNull(shadowOf(it).nextStartedActivity)
                    assertFalse(sessions.state.value.awaitingMembershipReturn)
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                val deadline = System.nanoTime() + 5_000_000_000L
                var launched: Intent? = null
                while (launched == null && System.nanoTime() < deadline) {
                    looper.idle()
                    scenario.onActivity { launched = shadowOf(it).nextStartedActivity }
                    if (launched == null) Thread.sleep(10)
                }
                val destination = requireNotNull(launched).data
                assertEquals("www.yunle.fun", destination?.host)
                assertEquals("/auth/cook-account", destination?.path)
                assertEquals(binding, destination?.getQueryParameter("binding"))
                assertEquals(1, transport.approvals)
                assertTrue(sessions.state.value.awaitingMembershipReturn)
                scenario.recreate()
                scenario.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
            }
        } finally {
            approve.complete(Unit)
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
            sessions.openAccountWebsiteFromCallback(callback, purchase) {
                assertTrue(sessions.state.value.awaitingMembershipReturn)
                opened = it
            }
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
    fun websiteReturnSurvivesProcessRestartAndRetriesAfterOfflineOrCancelledChecks() = runBlocking {
        val transport = Transport()
        val database = database()
        val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
        AeadConfig.register()
        val aead = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        val vaultFile = temporaryFolder.newFolder().resolve("vault.enc")
        fun openVault() = AndroidKeystoreVault(vaultFile, aeadProvider = { aead })
        val vault = openVault()
        vault.writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, Json.encodeToString(credential))
        try {
            val original = sessions(database, transport, vault)
            original.awaitInitialHydration()
            original.openAccountWebsiteFromCallback(callbackFor(original.beginAccountWebsite(purchase)), purchase) {}

            val relaunched = sessions(database, transport, openVault())
            relaunched.awaitInitialHydration()
            assertTrue(relaunched.state.value.awaitingMembershipReturn)
            assertNull(relaunched.state.value.entitlement)
            assertEquals(0, transport.membershipChecks)

            transport.membership = HttpResponsePayload(503, "{}")
            relaunched.checkMembership(MembershipCheckReason.CHECKOUT_RETURN)
            assertTrue(relaunched.state.value.awaitingMembershipReturn)
            assertEquals("暂时无法核对会员权益，请重试。", relaunched.state.value.membershipMessage)

            val paused = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            transport.onMembershipCheck = {
                paused.complete(Unit)
                hold.await()
            }
            val interrupted = async { relaunched.checkMembership(MembershipCheckReason.CHECKOUT_RETURN) }
            paused.await()
            interrupted.cancel()
            interrupted.join()
            assertTrue(relaunched.state.value.awaitingMembershipReturn)
            assertFalse(relaunched.state.value.isCheckingMembership)

            val expiry = System.currentTimeMillis() + 86_400_000L
            transport.onMembershipCheck = {}
            transport.membership = HttpResponsePayload(200, """{"membership":{"isActive":true,"expireAt":$expiry}}""")
            val first = async { relaunched.checkMembership(MembershipCheckReason.CHECKOUT_RETURN) }
            val second = async { relaunched.checkMembership(MembershipCheckReason.CHECKOUT_RETURN) }
            first.await()
            second.await()
            assertEquals(3, transport.membershipChecks)
            assertEquals(expiry, relaunched.state.value.entitlement?.expiresAtEpochMs)
            assertFalse(relaunched.state.value.awaitingMembershipReturn)

            val afterCheck = sessions(database, transport, openVault())
            afterCheck.awaitInitialHydration()
            afterCheck.checkMembership(MembershipCheckReason.CHECKOUT_RETURN)
            assertEquals(credential.subject, afterCheck.state.value.credential?.subject)
            assertFalse(afterCheck.state.value.awaitingMembershipReturn)
            assertEquals(3, transport.membershipChecks)
        } finally {
            database.close()
        }
    }

    @Test
    fun unchangedRenewalExpirySurvivesRestartAndIsRecheckedWithoutAnotherNetworkEvent() = runBlocking {
        val previousExpiry = System.currentTimeMillis() + 86_400_000L
        val renewedExpiry = previousExpiry + 31 * 86_400_000L
        val transport = Transport().apply {
            membership = HttpResponsePayload(200, """{"membership":{"isActive":true,"expireAt":$previousExpiry}}""")
        }
        val database = database()
        val vault = vault()
        val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
        try {
            val original = sessions(database, transport, vault)
            original.checkMembership(MembershipCheckReason.SCREEN)
            original.openAccountWebsiteFromCallback(callbackFor(original.beginAccountWebsite(purchase)), purchase) {}

            val relaunched = sessions(database, transport, vault)
            val returned = requireNotNull(relaunched.state.value.membershipWebsiteReturn)
            relaunched.checkMembership(MembershipCheckReason.CHECKOUT_RETURN, returned.session, returned)
            assertEquals(returned, relaunched.state.value.membershipWebsiteReturn)
            assertEquals(previousExpiry, relaunched.state.value.entitlement?.expiresAtEpochMs)
            assertEquals("会员有效期尚未更新。若已支付，可稍后再核对。", relaunched.state.value.membershipMessage)

            transport.membership = HttpResponsePayload(200, """{"membership":{"isActive":true,"expireAt":$renewedExpiry}}""")
            withTimeout(5_000) {
                relaunched.recoverMembershipWebsiteReturn(returned, emptyFlow(), retryDelaysMs = listOf(10, 500))
            }
            assertEquals(3, transport.membershipChecks)
            assertEquals(renewedExpiry, relaunched.state.value.entitlement?.expiresAtEpochMs)
            assertEquals("当前账号的会员权益已核对。", relaunched.state.value.membershipMessage)
            assertFalse(sessions(database, transport, vault).state.value.awaitingMembershipReturn)
        } finally {
            database.close()
        }
    }

    @Test
    fun uncompletedWebsitePurchaseStopsAutomaticChecksAfterTheLastUnchangedReply() = runBlocking {
        val transport = Transport()
        val database = database()
        val vault = vault()
        val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
        try {
            val sessions = sessions(database, transport, vault)
            sessions.openAccountWebsiteFromCallback(callbackFor(sessions.beginAccountWebsite(purchase)), purchase) {}
            val returned = requireNotNull(sessions.state.value.membershipWebsiteReturn)
            sessions.checkMembership(MembershipCheckReason.RESTORE)
            assertEquals(returned, sessions.state.value.membershipWebsiteReturn)

            withTimeout(5_000) {
                sessions.recoverMembershipWebsiteReturn(returned, emptyFlow(), retryDelaysMs = listOf(10, 10))
            }
            assertEquals(3, transport.membershipChecks)
            assertEquals(false, sessions.state.value.entitlement?.isActiveAt())
            assertEquals("会员有效期尚未更新。若已支付，可稍后再核对。", sessions.state.value.membershipMessage)
            assertFalse(sessions.state.value.awaitingMembershipReturn)
            assertFalse(sessions(database, transport, vault).state.value.awaitingMembershipReturn)
        } finally {
            database.close()
        }
    }

    @Test
    fun relaunchedActivityRetriesTheCheckoutWhenNetworkReturnsDuringTheFirstCheck() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val connectivity = shadowOf(requireNotNull(app.getSystemService(ConnectivityManager::class.java)))
        val initialCallbacks = connectivity.networkCallbacks.toSet()
        val network = ShadowNetwork.newInstance(42)
        val internet = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val validated = NetworkCapabilities(internet).also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val transport = Transport().apply {
            membership = HttpResponsePayload(503, "{}")
            onMembershipCheck = {
                started.complete(Unit)
                finish.await()
            }
        }
        val database = database()
        val vault = vault()
        val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
        try {
            runBlocking {
                val original = sessions(database, transport, vault)
                original.openAccountWebsiteFromCallback(callbackFor(original.beginAccountWebsite(purchase)), purchase) {}
            }
            val relaunched = sessions(database, transport, vault)
            ReflectionHelpers.setField(app.container, "yunlefunSessionManager\$delegate", lazyOf(relaunched))
            ActivityScenario.launch(MainActivity::class.java).use {
                val looper = shadowOf(Looper.getMainLooper())
                val firstDeadline = System.nanoTime() + 5_000_000_000L
                while (!started.isCompleted && System.nanoTime() < firstDeadline) {
                    looper.idle()
                    Thread.sleep(10)
                }
                assertTrue(started.isCompleted)
                assertTrue(relaunched.state.value.awaitingMembershipReturn)
                assertNull(relaunched.state.value.entitlement)
                val callback = (connectivity.networkCallbacks - initialCallbacks).single()

                val expiry = System.currentTimeMillis() + 86_400_000L
                transport.membership = HttpResponsePayload(200, """{"membership":{"isActive":true,"expireAt":$expiry}}""")
                transport.onMembershipCheck = {}
                callback.onCapabilitiesChanged(network, internet)
                callback.onCapabilitiesChanged(network, validated)
                callback.onCapabilitiesChanged(network, validated)
                finish.complete(Unit)

                val retryDeadline = System.nanoTime() + 5_000_000_000L
                while ((callback in connectivity.networkCallbacks || relaunched.state.value.isCheckingMembership) &&
                    System.nanoTime() < retryDeadline
                ) {
                    looper.idle()
                    Thread.sleep(10)
                }
                assertEquals(initialCallbacks, connectivity.networkCallbacks)
                assertEquals(2, transport.membershipChecks)
                assertEquals(expiry, relaunched.state.value.entitlement?.expiresAtEpochMs)
                assertFalse(relaunched.state.value.awaitingMembershipReturn)
                assertFalse(vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON).orEmpty().contains("awaitingMembershipReturn"))
            }
        } finally {
            finish.complete(Unit)
            database.close()
        }
    }

    @Test
    fun networkRetryForAnOlderCheckoutCannotConsumeTheNewReturn() = runBlocking {
        val transport = Transport().apply { membership = HttpResponsePayload(503, "{}") }
        val database = database()
        val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
        val network = Channel<Unit>(Channel.CONFLATED)
        val requestStarted = CompletableDeferred<Unit>()
        val finishOldCheck = CompletableDeferred<Unit>()
        try {
            val sessions = sessions(database, transport)
            sessions.openAccountWebsiteFromCallback(callbackFor(sessions.beginAccountWebsite(purchase)), purchase) {}
            val first = requireNotNull(sessions.state.value.membershipWebsiteReturn)
            val recovery = async { sessions.recoverMembershipWebsiteReturn(first, network.receiveAsFlow()) }
            network.send(Unit)
            withTimeout(5_000) { sessions.state.first { !it.isCheckingMembership && it.membershipMessage != null } }

            val expiry = System.currentTimeMillis() + 86_400_000L
            transport.membership = HttpResponsePayload(200, """{"membership":{"isActive":true,"expireAt":$expiry}}""")
            transport.onMembershipCheck = {
                requestStarted.complete(Unit)
                finishOldCheck.await()
            }
            val inFlight = async { sessions.checkMembership(MembershipCheckReason.CHECKOUT_RETURN, first.session, first) }
            withTimeout(5_000) { requestStarted.await() }

            sessions.openAccountWebsiteFromCallback(callbackFor(sessions.beginAccountWebsite(purchase)), purchase) {}
            val second = requireNotNull(sessions.state.value.membershipWebsiteReturn)
            assertEquals(first.session, second.session)
            assertTrue(first.generation < second.generation)
            network.send(Unit)
            withTimeout(5_000) { recovery.await() }
            finishOldCheck.complete(Unit)
            withTimeout(5_000) { inFlight.await() }
            sessions.checkMembership(MembershipCheckReason.CHECKOUT_RETURN, first.session, first)
            assertEquals(2, transport.membershipChecks)
            assertEquals(second, sessions.state.value.membershipWebsiteReturn)
            assertNull(sessions.state.value.entitlement)
            assertNull(sessions.state.value.membershipMessage)
        } finally {
            finishOldCheck.complete(Unit)
            network.close()
            database.close()
        }
    }

    @Test
    fun earlierRestoreCannotClearANewCheckoutAndSignOutRemovesTheSavedReturn() = runBlocking {
        val transport = Transport()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        transport.onMembershipCheck = {
            started.complete(Unit)
            finish.await()
        }
        val database = database()
        val vault = vault()
        val purchase = AccountWebsitePurpose.MEMBERSHIP_PURCHASE
        try {
            val sessions = sessions(database, transport, vault)
            sessions.awaitInitialHydration()
            val restore = async { sessions.checkMembership(MembershipCheckReason.RESTORE) }
            started.await()

            val failed = callbackFor(sessions.beginAccountWebsite(purchase))
            assertTrue(runCatching {
                sessions.openAccountWebsiteFromCallback(failed, purchase) { throw ActivityNotFoundException() }
            }.exceptionOrNull() is ActivityNotFoundException)
            assertFalse(sessions.state.value.awaitingMembershipReturn)
            assertFalse(vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON).orEmpty().contains("awaitingMembershipReturn"))

            sessions.openAccountWebsiteFromCallback(callbackFor(sessions.beginAccountWebsite(purchase)), purchase) {}
            finish.complete(Unit)
            restore.await()
            assertTrue(sessions.state.value.awaitingMembershipReturn)

            sessions.signOut()
            val relaunched = sessions(database, transport, vault)
            relaunched.checkMembership(MembershipCheckReason.CHECKOUT_RETURN)
            assertNull(relaunched.state.value.credential)
            assertFalse(relaunched.state.value.awaitingMembershipReturn)
            assertEquals(1, transport.membershipChecks)
        } finally {
            finish.complete(Unit)
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

    private suspend fun YunlefunSessionManager.beginAccountWebsite(
        purpose: AccountWebsitePurpose = AccountWebsitePurpose.ACCOUNT_SETTINGS,
    ): String {
        lateinit var startUrl: String
        launchAccountWebsite(prepareAccountWebsite(purpose)) { startUrl = it }
        return startUrl
    }

    private suspend fun YunlefunSessionManager.openAccountWebsiteFromCallback(
        callback: String,
        expectedPurpose: AccountWebsitePurpose? = null,
        open: (String) -> Unit,
    ) {
        val website = approveAccountWebsiteCallback(callback, expectedPurpose)
        openApprovedAccountWebsite(website, open)
    }

    private fun vault(): SecretVault = object : SecretVault {
        private val saved = java.util.concurrent.ConcurrentHashMap(
            mapOf(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON to Json.encodeToString(credential)),
        )
        override fun readSecret(key: String): String? = saved[key]
        override fun writeSecret(key: String, value: String) { saved[key] = value }
        override fun deleteSecret(key: String) { saved.remove(key) }
    }

    private fun vaultExpiringHandoffOnWrite(field: String): SecretVault {
        val storage = vault()
        return object : SecretVault by storage {
            override fun writeSecret(key: String, value: String) {
                if (key == AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON &&
                    Json.parseToJsonElement(value).jsonObject.containsKey(field)
                ) {
                    ShadowSystemClock.advanceBy(Duration.ofMillis(YunlefunAccountWebHandoff.LIFETIME_MS))
                }
                storage.writeSecret(key, value)
            }
        }
    }

    private fun sessions(
        database: CookDatabase,
        transport: Transport,
        vault: SecretVault = vault(),
        bootCount: Int? = 7,
    ): YunlefunSessionManager =
        YunlefunSessionManager(
            vault, database, accountService = YunlefunAccountService(transport), membershipService = MembershipService(transport),
            accountWebsiteBootCount = bootCount,
        )

    private inner class Transport : HttpTransport {
        var capability = HttpResponsePayload(200, """{"supported":true,"systemBrowser":true}""")
        var capabilityChecks = 0
        var onCapabilityCheck: suspend () -> Unit = {}
        var approvals = 0
        var approvalBody: kotlinx.serialization.json.JsonObject? = null
        var onApproval: suspend () -> Unit = {}
        var approvalResponse: (String?) -> HttpResponsePayload = { authorization ->
            assertEquals("Bearer ${credential.accessToken}", authorization)
            HttpResponsePayload(200, """{"ok":true}""")
        }
        var refreshes = 0
        var onRefresh: suspend () -> HttpResponsePayload = { error("Unexpected credential refresh") }
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
                capabilityChecks++
                onCapabilityCheck()
                capability
            }
            "${MembershipService.FUNCTIONS_GATEWAY}account-api" -> {
                assertEquals("POST", method)
                assertEquals(mapOf("Authorization" to "Bearer ${credential.accessToken}"), headers)
                assertEquals("""{"action":"getAccount"}""", jsonBody)
                membershipChecks++
                val response = membership
                onMembershipCheck()
                response
            }
            YunlefunAccountService.TICKET_URL -> {
                assertEquals("POST", method)
                assertEquals(credential.deviceID, headers["x-device-id"])
                assertEquals(YunlefunAccountService.COOK_ORIGIN, headers["Origin"])
                val body = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                assertEquals(setOf("action", "clientId", "verifier", "binding"), body.keys)
                assertEquals("approveCookWebHandoff", body.getValue("action").jsonPrimitive.content)
                assertEquals(binding, body.getValue("binding").jsonPrimitive.content)
                approvalBody = body
                approvals++
                onApproval()
                approvalResponse(headers["Authorization"])
            }
            "${YunlefunAccountService.GATEWAY}token" -> {
                assertEquals("POST", method)
                assertEquals(mapOf("x-device-id" to credential.deviceID), headers)
                val body = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                assertEquals(setOf("grant_type", "refresh_token"), body.keys)
                assertEquals("refresh_token", body.getValue("grant_type").jsonPrimitive.content)
                assertEquals(credential.refreshToken, body.getValue("refresh_token").jsonPrimitive.content)
                refreshes++
                onRefresh()
            }
            "${YunlefunAccountService.GATEWAY}user/me" ->
                HttpResponsePayload(200, """{"sub":"${credential.subject}","status":"ACTIVE"}""")
            "${YunlefunAccountService.GATEWAY}user/signout" -> HttpResponsePayload(200, "{}")
            else -> error("Unexpected endpoint $url")
        }
    }

    private fun renewedCredential() = HttpResponsePayload(
        200,
        """{"access_token":"renewed-access","refresh_token":"renewed-refresh","sub":"${credential.subject}","expires_in":3600}""",
    )
}
