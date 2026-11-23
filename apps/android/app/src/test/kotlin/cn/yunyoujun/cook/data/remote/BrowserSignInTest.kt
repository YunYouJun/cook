package cn.yunyoujun.cook.data.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.CookApplication
import cn.yunyoujun.cook.MainActivity
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.data.local.AccountSyncStateEntity
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.DeviceFavoriteEntity
import cn.yunyoujun.cook.data.local.DeviceHistoryEntity
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserSignInTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val key = AndroidKeystoreVault.KEY_YUNLEFUN_PENDING_SSO
    private val profileSubject = "browser-sign-in-fixture"
    private val code = "c".repeat(43)

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun clipboardLoginRejectsAnotherCallbackTypeAndContinuesThePendingSignIn() {
        val fixture = fixture()
        val transport = Transport()
        val sessions = fixture.sessions(transport)
        try {
            val unreadableCache = AccountSyncStateEntity(profileSubject, true, 0.0, """{"version":999}""", 1_000.0)
            runBlocking { fixture.database.accountLibraryDao().upsertSyncState(unreadableCache) }
            val start = Uri.parse(runBlocking { sessions.beginSsoAuthorization() })
            transport.challenge = start.getQueryParameter("code_challenge")
            val nonce = requireNotNull(start.getQueryParameter("nonce"))
            ReflectionHelpers.setField(fixture.app.container, "yunlefunSessionManager\$delegate", lazyOf(sessions))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("我的").performClick()
                val account = "云乐坊账号与跨端同步"
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(account))
                compose.onNodeWithText(account).performScrollTo().performClick()

                fun copy(value: String) = scenario.onActivity { activity ->
                    activity.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Callback fixture", value))
                }
                val paste = compose.onNodeWithText("粘贴并验证登录")
                copy("${YunlefunSSO.REDIRECT_URI}#cook_handoff=${"a".repeat(43)}.${"b".repeat(43)}")
                paste.performScrollTo().performClick()
                compose.onNodeWithText("剪贴板里没有对应的回调链接", substring = true).assertExists()
                assertEquals(0, transport.exchanges.get())
                assertTrue(fixture.vault().readSecret(key) != null)

                copy(callback(nonce))
                paste.performClick()
                transport.finishExchange.complete(Unit)
                compose.waitUntil(5_000) { sessions.state.value.account?.id == profileSubject }
                compose.onNodeWithText("当前账号：登录测试").assertExists()
                assertEquals(1, transport.exchanges.get())
                assertNull(fixture.vault().readSecret(key))
                compose.onNodeWithText("账号缓存无法读取，已暂停同步并保留原记录。当前使用本机收藏和历史。")
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("立即同步").assertIsNotEnabled()
                compose.onNodeWithText("将本机记录复制到当前账号").assertIsNotEnabled()
                assertEquals(unreadableCache, runBlocking { fixture.database.accountLibraryDao().getSyncState(profileSubject) })
            }
        } finally {
            transport.finishExchange.complete(Unit)
            fixture.database.close()
        }
    }

    @Test
    fun browserCallbackUsesEncryptedTransactionAfterProcessRestoreAndSurvivesRotationDuringExchange() {
        val fixture = fixture()
        val transport = Transport()
        try {
            val (oldStart, start) = runBlocking {
                val original = fixture.sessions(transport)
                Uri.parse(original.beginSsoAuthorization()) to Uri.parse(original.beginSsoAuthorization())
            }
            val nonce = requireNotNull(start.getQueryParameter("nonce"))
            transport.challenge = start.getQueryParameter("code_challenge")
            val encrypted = fixture.vaultFile.readText()
            val restored = fixture.sessions(transport)
            val stored = Json.decodeFromString<YunlefunSSO>(requireNotNull(fixture.vault().readSecret(key)))
            assertFalse(encrypted.contains(nonce))
            assertFalse(encrypted.contains(stored.verifier))
            assertFalse(start.toString().contains(stored.verifier))
            val valid = callback(nonce)

            runBlocking {
                val rejected = listOf(
                    callback(requireNotNull(oldStart.getQueryParameter("nonce"))),
                    callback(nonce, """{"invalid":true}"""),
                    callback(nonce, """"true""""),
                )
                for (link in rejected) {
                    assertTrue(restored.handleSsoCallbackUrl(link).exceptionOrNull() is YunlefunAccountException.InvalidCallback)
                    assertEquals(nonce, Json.decodeFromString<YunlefunSSO>(requireNotNull(fixture.vault().readSecret(key))).nonce)
                }
            }
            assertEquals(0, transport.exchanges.get())
            ReflectionHelpers.setField(fixture.app.container, "yunlefunSessionManager\$delegate", lazyOf(restored))
            val incoming = Intent(Intent.ACTION_VIEW, Uri.parse(valid), fixture.app, MainActivity::class.java)
            ActivityScenario.launch<MainActivity>(incoming).use { scenario ->
                await("the exchange to start") { transport.exchanges.get() == 1 }
                assertNull(fixture.vault().readSecret(key))
                scenario.recreate()
                assertNull(restored.state.value.credential)
                transport.finishExchange.complete(Unit)
                await("the original exchange to sign the user in") { restored.state.value.account?.id == profileSubject }
                assertEquals(1, transport.exchanges.get())
                assertNull(fixture.vault().readSecret(key))
            }
            runBlocking {
                restored.signOut()
                assertTrue(restored.handleSsoCallbackUrl(valid).exceptionOrNull() is YunlefunAccountException.InvalidCallback)
            }
            assertEquals(1, transport.exchanges.get())
        } finally {
            transport.finishExchange.complete(Unit)
            fixture.database.close()
        }
    }

    @Test
    fun aMatchingDenialConsumesTheAttemptAndAnExpiredAttemptIsRemoved() = runBlocking {
        val fixture = fixture()
        val transport = Transport()
        try {
            val sessions = fixture.sessions(transport)
            val start = Uri.parse(sessions.beginSsoAuthorization())
            val nonce = requireNotNull(start.getQueryParameter("nonce"))
            val denial = """{"nonce":"$nonce","iss":"${YunlefunSSO.ISSUER}","ok":false,"reason":"access_denied"}"""
            assertTrue(sessions.handleSsoCallbackUrl(link(denial)).exceptionOrNull() is CancellationException)
            assertNull(fixture.vault().readSecret(key))
            assertTrue(sessions.handleSsoCallbackUrl(callback(nonce)).exceptionOrNull() is YunlefunAccountException.InvalidCallback)

            val expired = YunlefunSSO(System.currentTimeMillis() - YunlefunSSO.LIFETIME_MS)
            fixture.vault().writeSecret(key, Json.encodeToString(expired))
            assertTrue(sessions.handleSsoCallbackUrl(callback(expired.nonce)).exceptionOrNull() is YunlefunAccountException.InvalidCallback)
            assertNull(fixture.vault().readSecret(key))
            assertEquals(0, transport.exchanges.get())
        } finally {
            fixture.database.close()
        }
    }

    @Test
    fun appSignInRestoresAfterProcessDeathAndOnlyExchangesTheMatchingCallbackOnce() = runBlocking {
        val fixture = fixture()
        val transport = Transport()
        try {
            val transaction = fixture.sessions(transport).beginAppSsoAuthorization()
            val url = Uri.parse(transaction.appAuthorizationUrl)
            assertEquals("/auth/app", url.path)
            assertEquals(transaction.nonce, url.getQueryParameter("nonce"))
            assertFalse(url.toString().contains(transaction.verifier))
            transport.challenge = url.getQueryParameter("code_challenge")
            val restored = fixture.sessions(transport)
            assertEquals(transaction.nonce, restored.state.value.appSignInNonce)
            assertTrue(restored.handleSsoCallbackUrl(callback(YunlefunSSO.randomToken())).isFailure)
            assertEquals(transaction.nonce, restored.state.value.appSignInNonce)

            val approved = callback(transaction.nonce)
            transport.finishExchange.complete(Unit)
            assertEquals(YunlefunSignInResult.SignedIn, restored.handleSsoCallbackUrl(approved).getOrThrow())
            assertEquals(profileSubject, restored.state.value.account?.id)
            assertNull(restored.state.value.appSignInNonce)
            assertNull(fixture.vault().readSecret(key))
            assertTrue(restored.handleSsoCallbackUrl(approved).isFailure)
            assertEquals(1, transport.exchanges.get())
        } finally {
            transport.finishExchange.complete(Unit)
            fixture.database.close()
        }
    }

    @Test
    fun switchingFromAppToBrowserAlwaysStartsAnotherTransactionAndRejectsTheOldCallback() = runBlocking {
        val fixture = fixture()
        val transport = Transport()
        try {
            val sessions = fixture.sessions(transport)
            for (explicitSwitch in listOf(false, true)) {
                val pending = sessions.beginAppSsoAuthorization()
                val url = if (explicitSwitch) {
                    sessions.continueAppSignInOnWeb(pending.nonce)
                } else {
                    val response = """{"nonce":"${pending.nonce}","iss":"${YunlefunSSO.ISSUER}","ok":false,"reason":"account_selection_required"}"""
                    val result = sessions.handleSsoCallbackUrl(link(response)).getOrThrow()
                    (result as YunlefunSignInResult.ContinueInBrowser).url
                }
                val browser = Uri.parse(url)
                assertEquals("/auth/sso", browser.path)
                assertFalse(browser.getQueryParameter("nonce") == pending.nonce)
                assertFalse(browser.getQueryParameter("code_challenge") ==
                    Uri.parse(pending.appAuthorizationUrl).getQueryParameter("code_challenge"))
                assertNull(sessions.state.value.appSignInNonce)
                assertTrue(sessions.handleSsoCallbackUrl(callback(pending.nonce)).isFailure)
            }
            assertEquals(0, transport.exchanges.get())
        } finally {
            fixture.database.close()
        }
    }

    @Test
    fun appAuthorizationWaitOffersBrowserAndCancelAfterActivityRecreation() {
        val fixture = fixture()
        val transport = Transport()
        try {
            val sessions = fixture.sessions(transport)
            val pending = runBlocking { sessions.beginAppSsoAuthorization() }
            ReflectionHelpers.setField(fixture.app.container, "yunlefunSessionManager\$delegate", lazyOf(sessions))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openAccountSettings()
                scenario.recreate()
                compose.onNodeWithText("改用浏览器").performScrollTo().assertExists()
                compose.onNodeWithText("取消本次登录").performScrollTo().performClick()
                await("cancel to discard the App authorization") { sessions.state.value.appSignInNonce == null }
                assertNull(fixture.vault().readSecret(key))
                runBlocking {
                    assertTrue(sessions.handleSsoCallbackUrl(callback(pending.nonce)).isFailure)
                }
            }
            assertEquals(0, transport.exchanges.get())
        } finally {
            fixture.database.close()
        }
    }

    @Test
    fun coldStartRecoversProfileAndEntitlementOnForegroundAfterOfflineThenRejectedAccess() {
        val fixture = fixture()
        val offline = AtomicBoolean(true)
        val transport = Transport().apply {
            profile = { authorization ->
                when {
                    offline.get() -> HttpResponsePayload(503, "{}")
                    authorization == "Bearer fixture-access" -> HttpResponsePayload(401, """{"error":"invalid_token"}""")
                    else -> {
                        assertEquals("Bearer renewed-access", authorization)
                        HttpResponsePayload(200, """{"sub":"$profileSubject","name":"已恢复账号","status":"ACTIVE"}""")
                    }
                }
            }
            refresh = {
                HttpResponsePayload(200, """{"access_token":"renewed-access","refresh_token":"renewed-refresh","sub":"$profileSubject","expires_in":3600}""")
            }
            membership = { authorization ->
                assertEquals("Bearer renewed-access", authorization)
                HttpResponsePayload(200, """{"membership":{"isActive":true,"expireAt":4102444800000}}""")
            }
        }
        try {
            val cached = runBlocking { fixture.saveAccountFixture() }
            val sessions = fixture.sessions(transport)
            ReflectionHelpers.setField(fixture.app.container, "yunlefunSessionManager\$delegate", lazyOf(sessions))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("offline profile failure and library hydration") {
                    val state = sessions.state.value
                    state.usesAccountLibrary && !state.isRefreshingProfile && state.profileRefreshError != null
                }
                assertEquals(cached, sessions.state.value.accountDocument)
                assertEquals("fixture-access", sessions.state.value.credential?.accessToken)
                assertNull(sessions.state.value.account)
                assertEquals(0, transport.entitlementRequests.get())

                scenario.moveToState(Lifecycle.State.CREATED)
                offline.set(false)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("renewed profile and membership") {
                    val state = sessions.state.value
                    !state.isRefreshingProfile && state.account?.name == "已恢复账号" && state.entitlement?.isActiveAt() == true
                }
                assertTrue(sessions.state.value.usesAccountLibrary)
                assertEquals(cached, sessions.state.value.accountDocument)
                assertNull(sessions.state.value.profileRefreshError)
                assertEquals(1, transport.entitlementRequests.get())
                assertEquals(1, transport.refreshes.get())
                val stored = fixture.vault().readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON)
                assertEquals("renewed-access", Json.decodeFromString<YunlefunCredential>(requireNotNull(stored)).accessToken)
            }
        } finally {
            fixture.database.close()
        }
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun manualSyncPersistsRemoteChangesAfterLeavingAccountSettings() {
        val fixture = fixture()
        val readRemote = AtomicBoolean(false)
        val manualStarted = CompletableDeferred<Unit>()
        val finishManual = CompletableDeferred<Unit>()
        val remote = UserDataDocument(
            favorites = listOf(UserDataDocument.Favorite(588, 3_000.0)),
            histories = listOf(UserDataDocument.Visit(UserDataDocument.RecipeSnapshot("蒸蛋羹"), 3_000.0)),
        )
        val transport = Transport().apply {
            sync = { local ->
                if (readRemote.get()) {
                    manualStarted.complete(Unit)
                    finishManual.await()
                    local.merging(remote)
                } else local
            }
        }
        val cached = runBlocking { fixture.saveAccountFixture() }
        val sessions = fixture.sessions(transport)
        try {
            ReflectionHelpers.setField(fixture.app.container, "yunlefunSessionManager\$delegate", lazyOf(sessions))
            ActivityScenario.launch(MainActivity::class.java).use {
                await("initial foreground sync") {
                    !sessions.state.value.isSyncing && sessions.state.value.lastSyncedAtMs != null
                }
                openAccountSettings()
                readRemote.set(true)
                compose.onNodeWithText("立即同步").performScrollTo().performClick()
                await("manual sync to reach transport") { manualStarted.isCompleted }

                compose.onNodeWithText("返回").performClick()
                compose.onNodeWithText("云乐坊账号、同步与会员").assertDoesNotExist()
                finishManual.complete(Unit)

                val merged = cached.merging(remote)
                await("manual sync to merge the server changes after leaving settings") {
                    !sessions.state.value.isSyncing && sessions.state.value.accountDocument == merged
                }
                val saved = runBlocking { fixture.database.accountLibraryDao().getSyncState(profileSubject) }
                assertEquals(merged, UserDataDocument.decode(requireNotNull(saved?.lastSyncedDocumentJson)))
            }
        } finally {
            finishManual.complete(Unit)
            runBlocking { sessions.setSyncEnabled(false).getOrThrow() }
            fixture.database.close()
        }
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun confirmedSyncConsentSurvivesActivityRecreationAndLeavingTheAccountPage() {
        val fixture = fixture()
        val checkStarted = CompletableDeferred<Unit>()
        val finishCheck = CompletableDeferred<Unit>()
        val checks = AtomicInteger()
        val deviceFavorite = DeviceFavoriteEntity(588, 3_000.0)
        val transport = Transport().apply {
            checkSyncAccess = {
                checks.incrementAndGet()
                checkStarted.complete(Unit)
                finishCheck.await()
                HttpResponsePayload(200, """{"ok":true,"protocolVersion":2}""")
            }
        }
        val cached = runBlocking {
            fixture.database.deviceLibraryDao().upsertFavorite(deviceFavorite)
            fixture.saveAccountFixture(enabled = false)
        }
        val sessions = fixture.sessions(transport)
        try {
            ReflectionHelpers.setField(fixture.app.container, "yunlefunSessionManager\$delegate", lazyOf(sessions))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("account cache to load") { sessions.state.value.isHydrated }
                openAccountSettings()
                compose.onNodeWithContentDescription("账号跨端同步").performScrollTo().performClick()
                compose.onNodeWithText("启用同步").performClick()
                await("the sync eligibility request to start") { checkStarted.isCompleted }
                scenario.recreate()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("云乐坊账号、同步与会员").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("账号跨端同步").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithText("正在核对同步资格…").assertIsDisplayed()
                compose.onNodeWithText("返回").performClick()
                compose.onNodeWithText("云乐坊账号、同步与会员").assertDoesNotExist()
                finishCheck.complete(Unit)

                await("consent and the first sync to be saved") {
                    val state = sessions.state.value
                    state.usesAccountLibrary && !state.isSyncing && state.lastSyncedAtMs != null
                }
                assertEquals(1, checks.get())
                assertEquals(cached, sessions.state.value.accountDocument)
                runBlocking {
                    val saved = requireNotNull(fixture.database.accountLibraryDao().getSyncState(profileSubject))
                    assertTrue(saved.syncEnabled)
                    assertEquals(cached, UserDataDocument.decode(requireNotNull(saved.lastSyncedDocumentJson)))
                    assertEquals(listOf(deviceFavorite), fixture.database.deviceLibraryDao().getAllFavorites())
                }
            }
        } finally {
            finishCheck.complete(Unit)
            if (sessions.state.value.syncEnabled) runBlocking { sessions.setSyncEnabled(false).getOrThrow() }
            fixture.database.close()
        }
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun confirmedDeviceLibraryCopySurvivesActivityRecreationAndLeavingTheAccountPage() {
        val fixture = fixture()
        val copyStarted = CompletableDeferred<Unit>()
        val finishCopy = CompletableDeferred<Unit>()
        val uploads = AtomicInteger()
        val deviceFavorite = DeviceFavoriteEntity(588, 3_000.0)
        val snapshot = UserDataDocument.RecipeSnapshot("蒸蛋羹")
        val deviceHistory = DeviceHistoryEntity(snapshot.name, 3_000.0, Json.encodeToString(snapshot))
        val local = UserDataDocument(
            favorites = listOf(UserDataDocument.Favorite(deviceFavorite.recipeId, deviceFavorite.addedAtMs)),
            histories = listOf(UserDataDocument.Visit(snapshot, deviceHistory.visitedAtMs)),
        )
        val transport = Transport().apply {
            sync = { document ->
                if (document.favorites.any { it.id == deviceFavorite.recipeId }) uploads.incrementAndGet()
                document
            }
        }
        val cached = runBlocking {
            fixture.database.deviceLibraryDao().upsertFavorite(deviceFavorite)
            fixture.database.deviceLibraryDao().upsertHistory(deviceHistory)
            fixture.saveAccountFixture()
        }
        val sessions = fixture.sessions(transport)
        try {
            ReflectionHelpers.setField(fixture.app.container, "yunlefunSessionManager\$delegate", lazyOf(sessions))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("initial foreground sync") {
                    !sessions.state.value.isSyncing && sessions.state.value.lastSyncedAtMs != null
                }
                sessions.beforeAccountPersistCommitHook = {
                    if (copyStarted.complete(Unit)) finishCopy.await()
                }
                openAccountSettings()
                compose.onNodeWithText("将本机记录复制到当前账号").performScrollTo().performClick()
                assertEquals(cached, sessions.state.value.accountDocument)
                assertEquals(0, uploads.get())
                compose.onNodeWithText("复制记录").performClick()
                await("the copy to reach Room") { copyStarted.isCompleted }
                scenario.recreate()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("云乐坊账号、同步与会员").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("正在复制…").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
                compose.onNodeWithText("返回").performClick()
                compose.onNodeWithText("云乐坊账号、同步与会员").assertDoesNotExist()
                finishCopy.complete(Unit)

                val merged = cached.merging(local)
                await("the copy to commit and sync") {
                    uploads.get() > 0 && !sessions.state.value.isSyncing && sessions.state.value.accountDocument == merged
                }
                runBlocking {
                    val saved = requireNotNull(fixture.database.accountLibraryDao().getSyncState(profileSubject))
                    assertEquals(merged, UserDataDocument.decode(requireNotNull(saved.lastSyncedDocumentJson)))
                    assertEquals(listOf(deviceFavorite), fixture.database.deviceLibraryDao().getAllFavorites())
                    assertEquals(listOf(deviceHistory), fixture.database.deviceLibraryDao().getAllHistories())
                }
            }
        } finally {
            finishCopy.complete(Unit)
            sessions.beforeAccountPersistCommitHook = null
            if (sessions.state.value.syncEnabled) runBlocking { sessions.setSyncEnabled(false).getOrThrow() }
            fixture.database.close()
        }
    }

    @Test
    fun lateConsentFromASignedOutSessionCannotEnableSyncOrClearTheNextSessionsProgress() = runBlocking {
        val fixture = fixture()
        val oldCheckStarted = CompletableDeferred<Unit>()
        val newCheckStarted = CompletableDeferred<Unit>()
        val finishOldCheck = CompletableDeferred<Unit>()
        val finishNewCheck = CompletableDeferred<Unit>()
        val checks = AtomicInteger()
        val transport = Transport().apply {
            checkSyncAccess = {
                if (checks.incrementAndGet() == 1) {
                    oldCheckStarted.complete(Unit)
                    finishOldCheck.await()
                } else {
                    newCheckStarted.complete(Unit)
                    finishNewCheck.await()
                }
                HttpResponsePayload(200, """{"ok":true,"protocolVersion":2}""")
            }
        }
        try {
            fixture.saveAccountFixture(enabled = false)
            val sessions = fixture.sessions(transport)
            sessions.awaitInitialHydration()
            val original = sessions.state.value.libraryScope
            val oldConsent = async { sessions.setSyncEnabled(true, original) }
            withTimeout(5_000) { oldCheckStarted.await() }
            try {
                sessions.signOut()
                val start = Uri.parse(sessions.beginSsoAuthorization())
                transport.challenge = start.getQueryParameter("code_challenge")
                transport.finishExchange.complete(Unit)
                sessions.handleSsoCallbackUrl(callback(requireNotNull(start.getQueryParameter("nonce")))).getOrThrow()
                assertFalse(sessions.state.value.syncEnabled)
                assertFalse(sessions.state.value.isSavingSyncConsent)

                val current = sessions.state.value.libraryScope
                val newConsent = async { sessions.setSyncEnabled(true, current) }
                withTimeout(5_000) { newCheckStarted.await() }
                finishOldCheck.complete(Unit)
                assertTrue(withTimeout(5_000) { oldConsent.await() }.exceptionOrNull() is CancellationException)
                assertFalse(sessions.state.value.syncEnabled)
                assertTrue(sessions.state.value.isSavingSyncConsent)
                assertFalse(requireNotNull(fixture.database.accountLibraryDao().getSyncState(profileSubject)).syncEnabled)
                finishNewCheck.complete(Unit)
                withTimeout(5_000) { newConsent.await().getOrThrow() }
                assertTrue(sessions.state.value.syncEnabled)
                assertFalse(sessions.state.value.isSavingSyncConsent)
                assertEquals(2, checks.get())
                sessions.setSyncEnabled(false).getOrThrow()
            } finally {
                finishOldCheck.complete(Unit)
                finishNewCheck.complete(Unit)
            }
        } finally {
            fixture.database.close()
        }
    }

    @Test
    fun coldStartRestoresTheEntireOfflineAccountCacheBeforeSync() = runBlocking {
        val fixture = fixture()
        try {
            val document = UserDataDocument(
                histories = List(UserDataDocument.MERGED_HISTORY_CAP + 1) { index ->
                    UserDataDocument.Visit(UserDataDocument.RecipeSnapshot("蒸蛋羹"), 1_000.0 + index)
                },
                favoriteRemovals = listOf(UserDataDocument.Favorite(250, 1_000.0)),
            )
            fixture.saveAccountFixture(document)
            val sessions = fixture.sessions(Transport())
            sessions.awaitInitialHydration()

            assertTrue(sessions.state.value.usesAccountLibrary)
            assertEquals(document.histories.size, sessions.state.value.accountDocument.histories.size)
            assertEquals(document, sessions.state.value.accountDocument)
        } finally {
            fixture.database.close()
        }
    }

    @Test
    fun refusedRefreshReturnsToDeviceLibraryAndKeepsTheSavedAccountCache() = runBlocking {
        val fixture = fixture()
        val transport = Transport().apply {
            profile = { HttpResponsePayload(401, """{"error":"invalid_token"}""") }
            refresh = { HttpResponsePayload(400, """{"error":"invalid_grant"}""") }
        }
        try {
            val cached = fixture.saveAccountFixture()
            val sessions = fixture.sessions(transport)
            sessions.awaitInitialHydration()
            val original = fixture.database.accountLibraryDao().getSyncState(profileSubject)
            assertTrue(sessions.refreshProfileAndEntitlement().exceptionOrNull() is YunlefunAccountException.ExpiredSession)
            assertFalse(sessions.state.value.usesAccountLibrary)
            assertNull(sessions.state.value.credential)
            assertTrue(sessions.state.value.statusMessage.orEmpty().contains("请重新登录"))
            assertNull(fixture.vault().readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON))
            assertEquals(cached, UserDataDocument.decode(requireNotNull(original?.lastSyncedDocumentJson)))
            assertEquals(original, fixture.database.accountLibraryDao().getSyncState(profileSubject))
            assertNull(fixture.sessions(transport).state.value.credential)
            assertEquals(1, transport.refreshes.get())
            assertEquals(0, transport.entitlementRequests.get())
        } finally {
            fixture.database.close()
        }
    }

    @Test
    fun aProfileReplyAfterSignOutCannotRestoreTheSessionOrCheckMembership() = runBlocking {
        val fixture = fixture()
        val profileStarted = CompletableDeferred<Unit>()
        val finishProfile = CompletableDeferred<Unit>()
        val transport = Transport().apply {
            profile = {
                profileStarted.complete(Unit)
                finishProfile.await()
                HttpResponsePayload(200, """{"sub":"$profileSubject","status":"ACTIVE"}""")
            }
        }
        try {
            fixture.saveAccountFixture()
            val sessions = fixture.sessions(transport)
            sessions.awaitInitialHydration()
            val refresh = async { sessions.refreshProfileAndEntitlement() }
            try {
                withTimeout(5_000) { profileStarted.await() }
                sessions.signOut()
            } finally {
                finishProfile.complete(Unit)
            }
            assertTrue(withTimeout(5_000) { refresh.await() }.exceptionOrNull() is CancellationException)
            assertNull(sessions.state.value.credential)
            assertNull(sessions.state.value.account)
            assertFalse(sessions.state.value.isRefreshingProfile)
            assertEquals(0, transport.entitlementRequests.get())
        } finally {
            fixture.database.close()
        }
    }

    private suspend fun Fixture.saveAccountFixture(
        document: UserDataDocument = UserDataDocument(
            favorites = listOf(UserDataDocument.Favorite(250, 2_000.0)),
            histories = listOf(UserDataDocument.Visit(UserDataDocument.RecipeSnapshot("番茄炒蛋"), 2_000.0)),
        ),
        enabled: Boolean = true,
    ): UserDataDocument {
        val credential = YunlefunCredential("fixture-access", "fixture-refresh", profileSubject, Long.MAX_VALUE, "fixture-device")
        vault().writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, Json.encodeToString(credential))
        database.accountLibraryDao().upsertSyncState(AccountSyncStateEntity(
            profileSubject, enabled, 0.0, UserDataDocument.wireJson.encodeToString(document), 2_000.0,
        ))
        return document
    }

    private fun callback(nonce: String, ok: String = "true"): String =
        link("""{"nonce":"$nonce","iss":"${YunlefunSSO.ISSUER}","ok":$ok,"code":"$code"}""")

    private fun link(payload: String): String =
        "${YunlefunSSO.REDIRECT_URI}#ylf_sso=${YunlefunSSO.encodeBase64Url(payload.toByteArray())}"

    private fun openAccountSettings() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("我的").performClick()
        val account = "云乐坊账号与跨端同步"
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(account))
        compose.onNodeWithText(account).performScrollTo().performClick()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            looper.idle()
            if (condition()) return
            Thread.sleep(10)
        }
        error("Timed out waiting for $description")
    }

    private fun fixture(): Fixture {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        AeadConfig.register()
        val keyset = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val aead = keyset.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        return Fixture(app, database, File(temporaryFolder.newFolder(), "vault.enc"), aead)
    }

    private class Fixture(
        val app: CookApplication,
        val database: CookDatabase,
        val vaultFile: File,
        private val aead: Aead,
    ) {
        fun vault() = AndroidKeystoreVault(vaultFile) { aead }

        fun sessions(transport: HttpTransport) = YunlefunSessionManager(
            vault(), database, accountService = YunlefunAccountService(transport),
            syncService = UserDataSyncService(transport), membershipService = MembershipService(transport),
        )
    }

    private inner class Transport : HttpTransport {
        var challenge: String? = null
        val exchanges = AtomicInteger()
        val finishExchange = CompletableDeferred<Unit>()
        val refreshes = AtomicInteger()
        val entitlementRequests = AtomicInteger()
        var profile: suspend (String?) -> HttpResponsePayload = {
            HttpResponsePayload(200, """{"sub":"$profileSubject","name":"登录测试","status":"ACTIVE"}""")
        }
        var refresh: () -> HttpResponsePayload = { error("Unexpected credential refresh") }
        var membership: (String?) -> HttpResponsePayload = {
            HttpResponsePayload(200, """{"membership":{"isActive":false,"expireAt":null}}""")
        }
        var sync: suspend (UserDataDocument) -> UserDataDocument = { it }
        var checkSyncAccess: suspend () -> HttpResponsePayload = { error("Unexpected sync eligibility check") }

        override suspend fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            jsonBody: String?,
            maxResponseBytes: Int,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponsePayload = when {
            url == YunlefunAccountService.TICKET_URL -> {
                val body = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                val verifier = body.getValue("codeVerifier").jsonPrimitive.content
                assertEquals(challenge, YunlefunSSO.sha256Base64Url(verifier.toByteArray()))
                assertEquals(code, body.getValue("code").jsonPrimitive.content)
                exchanges.incrementAndGet()
                finishExchange.await()
                HttpResponsePayload(200, """{"ok":true,"ticket":"browser-fixture-ticket"}""")
            }
            url.endsWith("signin/custom") -> HttpResponsePayload(
                200, """{"access_token":"fixture-access","refresh_token":"fixture-refresh","sub":"$profileSubject","expires_in":3600}""",
            )
            url.endsWith("user/me") -> profile(headers["Authorization"])
            url == YunlefunAccountService.GATEWAY + "token" -> {
                assertEquals("fixture-device", headers["x-device-id"])
                val fields = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                assertEquals("fixture-refresh", fields.getValue("refresh_token").jsonPrimitive.content)
                refreshes.incrementAndGet()
                refresh()
            }
            url.endsWith("account-api") -> {
                entitlementRequests.incrementAndGet()
                membership(headers["Authorization"])
            }
            url == UserDataSyncService.ENDPOINT -> {
                val fields = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                if (fields.getValue("action").jsonPrimitive.content == "capabilities") checkSyncAccess()
                else {
                    val accepted = sync(UserDataDocument.decode(fields.getValue("document").toString()))
                    HttpResponsePayload(200, """{"ok":true,"protocolVersion":2,"document":${UserDataDocument.wireJson.encodeToString(accepted)}}""")
                }
            }
            url.endsWith("user/signout") -> HttpResponsePayload(200, "{}")
            else -> error("Unexpected endpoint $url")
        }
    }
}
