package cn.yunyoujun.cook

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.FridgeStore
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.FridgeVisionProvider
import cn.yunyoujun.cook.data.remote.FridgeVisionService
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.MembershipCheckReason
import cn.yunyoujun.cook.data.remote.MembershipService
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.ui.screens.FridgeVisionDialog
import cn.yunyoujun.cook.ui.state.FridgeBatchReview
import cn.yunyoujun.cook.ui.state.FridgePhotoIntake
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FridgeVisionDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "w360dp-h560dp")
    fun photoRecipientAndConsentAreVisibleOutsideTheScrollablePreviewAtLargeTextSize() {
        val database = database()
        val transport = VisionTransport(member = true, changesProvider = false)
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        try {
            val content = dialogContent(sessions, transport)
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    content()
                }
            }
            awaitText("同意并识别")
            val recipient = "确认后，处理后的照片将发送给云乐坊（腾讯云 CloudBase）和 DeepSeek。"
            val canScroll = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)
            compose.onNodeWithContentDescription("将上传的照片").assert(hasAnyAncestor(canScroll))
            compose.onNodeWithText(recipient).assert(!hasAnyAncestor(canScroll)).assertIsDisplayed()
            compose.onNodeWithText("同意并识别").assert(!hasAnyAncestor(canScroll)).assertIsDisplayed()
            assertEquals(0, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun refreshesExpiredSessionAndRequiresNewConsentAfterProviderChange() {
        val database = database()
        val transport = VisionTransport(member = true)
        val sessions = sessions(database, transport, expiresAt = 1)
        var saved: List<String>? = null
        try {
            showDialog(sessions, transport, onSave = { saved = it })
            awaitText("确认后，处理后的照片将发送给云乐坊（腾讯云 CloudBase）和 DeepSeek。")
            assertEquals(1, transport.refreshes.get())
            assertEquals(0, transport.uploads.get())

            compose.onNodeWithText("同意并识别").performClick()
            awaitText("确认后，处理后的照片将发送给云乐坊（腾讯云 CloudBase）和阿里云百炼。")
            assertEquals(1, transport.uploads.get())
            assertEquals(null, saved)

            compose.onNodeWithText("同意并识别").performClick()
            awaitText("核对识别结果")
            compose.onNodeWithContentDescription("选择鸡蛋").performClick()
            compose.onNodeWithText("加入冰箱（1 样）").performClick()
            awaitSaved()
            compose.runOnIdle { assertEquals(listOf("番茄"), saved) }
            assertEquals(2, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun aConsentClickBeforeTheNewRecipientIsDrawnDoesNotUpload() {
        val database = database()
        val transport = VisionTransport(member = true, changesProvider = false)
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        try {
            showDialog(sessions, transport)
            awaitText("确认后，处理后的照片将发送给云乐坊（腾讯云 CloudBase）和 DeepSeek。")
            val consent = compose.onNodeWithText("同意并识别")
            val previousClick = requireNotNull(consent.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val review = compose.runOnIdle {
                val intake = ViewModelProvider(compose.activity)[FridgePhotoIntake::class.java]
                requireNotNull(intake.draft?.batchReview)
            }

            // Finish the provider refresh before Compose can redraw the old consent button.
            compose.mainClock.autoAdvance = false
            transport.providerOverride = FridgeVisionProvider.QWEN.wireName
            compose.runOnUiThread { review.refreshAccess() }
            compose.waitUntil(5_000) { (review.phase as? FridgeBatchReview.Phase.Ready)?.provider == FridgeVisionProvider.QWEN }
            compose.runOnUiThread {
                val refreshed = review.phase
                previousClick()
                assertSame(refreshed, review.phase)
            }
            assertEquals(0, transport.uploads.get())

            compose.mainClock.autoAdvance = true
            awaitText("确认后，处理后的照片将发送给云乐坊（腾讯云 CloudBase）和阿里云百炼。")
            consent.performClick()
            awaitText("核对识别结果")
            assertEquals(1, transport.uploads.get())
        } finally {
            compose.mainClock.autoAdvance = true
            database.close()
        }
    }

    @Test
    fun aCancelledProviderCheckCannotReplaceTheNewRecipientWithAnError() {
        val database = database()
        val firstResponse = AtomicReference<Continuation<HttpResponsePayload?>>()
        val transport = VisionTransport(
            member = true,
            changesProvider = false,
            // Model a response already in delivery as cancellation reaches the old check.
            firstProviderResponse = { suspendCoroutine { firstResponse.set(it) } },
        )
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        try {
            showDialog(sessions, transport)
            compose.waitUntil(5_000) { firstResponse.get() != null }
            compose.runOnIdle {
                val intake = ViewModelProvider(compose.activity)[FridgePhotoIntake::class.java]
                transport.providerOverride = FridgeVisionProvider.QWEN.wireName
                requireNotNull(intake.draft?.batchReview).refreshAccess()
            }
            val recipient = "确认后，处理后的照片将发送给云乐坊（腾讯云 CloudBase）和阿里云百炼。"
            awaitText(recipient)
            compose.runOnIdle {
                requireNotNull(firstResponse.getAndSet(null)).resume(HttpResponsePayload(503, ""))
            }
            compose.waitForIdle()
            compose.onNodeWithText(recipient).assertIsDisplayed()
            assertEquals(2, transport.providerChecks.get())
            assertEquals(0, transport.uploads.get())

            compose.onNodeWithText("同意并识别").performClick()
            awaitText("核对识别结果")
            assertEquals(1, transport.uploads.get())
        } finally {
            compose.runOnUiThread { firstResponse.getAndSet(null)?.resume(null) }
            database.close()
        }
    }

    @Test
    fun batchReviewExplainsInvalidOrUnselectedCandidatesBeforeSaving() {
        val database = database()
        val transport = VisionTransport(member = true, changesProvider = false)
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        var saved: List<String>? = null
        try {
            showDialog(sessions, transport, onSave = { saved = it })
            awaitText("同意并识别")
            compose.onNodeWithText("同意并识别").performClick()
            awaitText("核对识别结果")

            compose.onNodeWithText("食材 1").performTextReplacement("番茄、鸡蛋")
            compose.onNodeWithText("请为每项勾选的食材填写一个有效名称，不超过 30 字。").assertIsDisplayed()
            compose.onNodeWithText("加入冰箱（0 样）").assertIsNotEnabled()
            compose.onNodeWithContentDescription("选择番茄、鸡蛋").performClick()
            compose.onNodeWithText("加入冰箱（1 样）").assertIsEnabled()
            compose.onNodeWithContentDescription("选择番茄").performClick()
            compose.onNodeWithText("请至少勾选一项要加入冰箱的食材。").assertIsDisplayed()
            compose.onNodeWithText("加入冰箱（0 样）").assertIsNotEnabled()

            compose.onNodeWithContentDescription("选择番茄").performClick()
            compose.onNodeWithText("加入冰箱（1 样）").performClick()
            awaitSaved()
            compose.runOnIdle { assertEquals(listOf("番茄"), saved) }
            assertEquals(1, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun batchReviewAcceptsSupplementaryLettersAndKeepsThemIntactWhenEditing() {
        val database = database()
        val transport = VisionTransport(
            member = true, changesProvider = false, ingredients = listOf("𫚕鱼", "番茄"),
        )
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = FridgeStore(app)
        try {
            showDialog(sessions, transport, onSave = { names ->
                store.addItems(names.map { FridgeBatchItem(ingredientName = it, quantityText = "1 份") })
            })
            awaitText("同意并识别")
            compose.onNodeWithText("同意并识别").performClick()
            awaitText("核对识别结果")
            compose.onNodeWithText("𫚕鱼").assertIsDisplayed()

            val fullName = "海".repeat(29) + "𫚕"
            compose.onNodeWithText("食材 1").performTextReplacement(fullName + "鱼")
            compose.onNodeWithText("加入冰箱（2 样）").performClick()
            awaitSaved()

            assertEquals(listOf(fullName, "番茄"), FridgeStore(app).state.value.stock.map { it.ingredientName })
            assertEquals(1, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun editedNamesUseTheServerUnicodeRulesAndKeepTheWholeName() {
        val fixture = File(checkNotNull(System.getProperty("cook.fridge.vision.nameFixtures")))
        val cases = Json.decodeFromString<List<NameCase>>(fixture.readText())
        val database = database()
        val transport = VisionTransport(member = true, changesProvider = false, ingredients = listOf("牛奶"))
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = FridgeStore(app)
        try {
            showDialog(sessions, transport, onSave = { names ->
                store.addItems(names.map { FridgeBatchItem(ingredientName = it, quantityText = "1 份") })
            })
            awaitText("同意并识别")
            compose.onNodeWithText("同意并识别").performClick()
            awaitText("核对识别结果")

            val editor = compose.onNodeWithText("食材 1")
            for ((input, expected) in cases) {
                editor.performTextReplacement(input)
                if (expected == null) {
                    compose.onNodeWithText("加入冰箱（0 样）").assertIsNotEnabled()
                } else {
                    compose.onNodeWithText("加入冰箱（1 样）").assertIsEnabled()
                }
            }
            val composedLengthBoundary = cases.first { it.input.codePointCount(0, it.input.length) > 30 }
            editor.performTextReplacement(composedLengthBoundary.input)
            compose.onNodeWithText("加入冰箱（1 样）").performClick()
            awaitSaved()
            assertEquals(listOf(composedLengthBoundary.expected), FridgeStore(app).state.value.stock.map { it.ingredientName })
            assertEquals(1, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Serializable
    private data class NameCase(val input: String, val expected: String?)

    @Test
    fun rejectedSessionIsRefreshedBeforeShowingPhotoRecipient() {
        val database = database()
        val transport = VisionTransport(member = true, rejectedProviderChecks = 1)
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        try {
            showDialog(sessions, transport)
            awaitText("确认后，处理后的照片将发送给云乐坊（腾讯云 CloudBase）和 DeepSeek。")
            assertEquals(2, transport.providerChecks.get())
            assertEquals(1, transport.refreshes.get())
            assertEquals(0, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun rejectedUploadRequiresConsentAgainAfterSessionRefresh() {
        val database = database()
        val transport = VisionTransport(member = true, rejectFirstUpload = true)
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        var saved: List<String>? = null
        try {
            showDialog(sessions, transport, onSave = { saved = it })
            awaitText("确认后，处理后的照片将发送给云乐坊（腾讯云 CloudBase）和 DeepSeek。")
            assertEquals(0, transport.refreshes.get())

            compose.onNodeWithText("同意并识别").performClick()
            awaitText("确认后，处理后的照片将发送给云乐坊（腾讯云 CloudBase）和阿里云百炼。")
            compose.onNodeWithContentDescription("将上传的照片").assertIsDisplayed()
            assertEquals(2, transport.providerChecks.get())
            assertEquals(1, transport.refreshes.get())
            assertEquals(1, transport.uploads.get())
            assertEquals(null, saved)

            compose.onNodeWithText("同意并识别").performClick()
            awaitText("核对识别结果")
            compose.onNodeWithContentDescription("选择鸡蛋").performClick()
            compose.onNodeWithText("加入冰箱（1 样）").performClick()
            awaitSaved()
            compose.runOnIdle { assertEquals(listOf("番茄"), saved) }
            assertEquals(2, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun rejectedRefreshedSessionOffersAccountSettingsWithoutLooping() {
        val database = database()
        val transport = VisionTransport(member = true, rejectedProviderChecks = 2)
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        var accountOpened = false
        try {
            showDialog(sessions, transport, onAccount = { accountOpened = true })
            awaitText("前往账号设置")
            compose.onNodeWithText("同意并识别").assertDoesNotExist()
            compose.onNodeWithText("前往账号设置").performClick()
            compose.runOnIdle {
                assertEquals(true, accountOpened)
                assertEquals("fixture-member", sessions.state.value.credential?.subject)
            }
            assertEquals(2, transport.providerChecks.get())
            assertEquals(1, transport.refreshes.get())
            assertEquals(0, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun nonMemberCanOpenMembershipOrReturnToOfflineRecognition() {
        val database = database()
        val transport = VisionTransport(member = false)
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        var membership = false
        var back = false
        try {
            showDialog(sessions, transport, onMembership = { membership = true }, onBack = { back = true })
            awaitText("查看会员权益")
            compose.onNodeWithText("同意并识别").assertDoesNotExist()
            compose.onNodeWithText("查看会员权益").performClick()
            compose.onNodeWithText("返回本机识别").performClick()
            compose.runOnIdle {
                assertEquals(true, membership)
                assertEquals(true, back)
            }
            assertEquals(0, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun membershipRestoreRechecksProviderForTheSamePhotoWithoutUploading() {
        val database = database()
        val transport = VisionTransport(member = false, changesProvider = false)
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        try {
            showDialog(sessions, transport)
            awaitText("查看会员权益")
            val initialChecks = transport.providerChecks.get()

            transport.member = true
            runBlocking { sessions.checkMembership(MembershipCheckReason.RESTORE) }

            awaitText("同意并识别")
            compose.onNodeWithContentDescription("将上传的照片").assertIsDisplayed()
            assertEquals(initialChecks + 1, transport.providerChecks.get())
            assertEquals(0, transport.uploads.get())
            compose.onNodeWithText("同意并识别").performClick()
            awaitText("核对识别结果")
            assertEquals(1, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun membershipUpdateDuringProviderCheckIsNotLost() {
        val database = database()
        val finishFirstProviderCheck = CompletableDeferred<Unit>()
        val transport = VisionTransport(member = false, firstProviderResponse = {
            finishFirstProviderCheck.await()
            null
        })
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        try {
            showDialog(sessions, transport)
            compose.waitUntil(timeoutMillis = 5_000) { transport.providerChecks.get() == 1 }
            transport.member = true
            runBlocking { sessions.checkMembership(MembershipCheckReason.RESTORE) }
            finishFirstProviderCheck.complete(Unit)

            awaitText("同意并识别")
            assertEquals(2, transport.providerChecks.get())
            assertEquals(0, transport.uploads.get())
        } finally {
            finishFirstProviderCheck.complete(Unit)
            database.close()
        }
    }

    @Test
    fun membershipStillDeniedByProviderCanBeRecheckedManually() {
        val database = database()
        val transport = VisionTransport(member = false, accountMember = true)
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        try {
            runBlocking { sessions.checkMembership(MembershipCheckReason.RESTORE) }
            assertEquals(true, sessions.state.value.entitlement?.isActiveAt())

            showDialog(sessions, transport)
            awaitText("重新核对会员资格")
            compose.onNodeWithText("同意并识别").assertDoesNotExist()
            val initialChecks = transport.providerChecks.get()

            transport.member = true
            compose.onNodeWithText("重新核对会员资格").performClick()
            awaitText("同意并识别")
            assertEquals(initialChecks + 1, transport.providerChecks.get())
            assertEquals(0, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun recreationKeepsPendingUploadAndEditedResultsWithoutResendingPhoto() {
        val database = database()
        val finishUpload = CompletableDeferred<Unit>()
        val transport = VisionTransport(member = true, changesProvider = false, finishUpload = finishUpload)
        val sessions = sessions(database, transport, expiresAt = 1)
        var saved: List<String>? = null
        try {
            val content = dialogContent(sessions, transport, onSave = { saved = it })
            compose.setContent(content)
            awaitText("同意并识别")
            compose.onNodeWithText("同意并识别").performClick()
            awaitText("正在识别照片…")
            compose.waitUntil(5_000) { transport.uploads.get() == 1 }

            recreate(content)
            awaitText("正在识别照片…")
            finishUpload.complete(Unit)
            awaitText("核对识别结果")
            compose.onNodeWithContentDescription("选择鸡蛋").performClick()
            compose.onNodeWithText("食材 2").performTextReplacement("小番茄")

            recreate(content)
            awaitText("小番茄")
            compose.onNodeWithText("加入冰箱（1 样）").performClick()
            awaitSaved()
            compose.runOnIdle { assertEquals(listOf("小番茄"), saved) }
            assertEquals(1, transport.uploads.get())
            assertEquals(1, transport.providerChecks.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun savingKeepsTheBatchAcrossRotationAndRetriesTheWholeWriteWithoutAnotherUpload() {
        val database = database()
        val transport = VisionTransport(member = true, changesProvider = false)
        val sessions = sessions(database, transport, expiresAt = Long.MAX_VALUE)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = FridgeStore(app)
        val original = FridgeBatchItem(ingredientName = "番茄", quantityText = "200 克", expiresOnIsoDate = "2026-11-22")
        store.addItems(listOf(original))
        val blockingPath = File(app.filesDir, "fridge-store-v1.json.tmp")
        assertTrue(blockingPath.mkdir())
        val resumeWrite = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        try {
            val content = dialogContent(sessions, transport, onSave = { names ->
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                when (attempts.getAndIncrement()) {
                    0 -> resumeWrite.await()
                    1 -> throw CancellationException("Fixture interrupted only the local write")
                }
                store.addItems(names.map { FridgeBatchItem(ingredientName = it, quantityText = "1 份") })
            })
            compose.setContent(content)
            awaitText("同意并识别")
            compose.onNodeWithText("同意并识别").performClick()
            awaitText("核对识别结果")
            compose.onNodeWithText("食材 1").performTextReplacement("牛奶")
            compose.onNodeWithText("加入冰箱（2 样）").performClick()
            awaitText("正在保存…")
            compose.waitUntil(5_000) { attempts.get() == 1 }

            recreate(content)
            awaitText("正在保存…")
            compose.onNodeWithText("正在保存…").assertIsNotEnabled()
            compose.onNodeWithText("返回本机识别").assertIsNotEnabled()
            compose.onNodeWithText("换一张照片").assertIsNotEnabled()
            compose.onNodeWithText("食材 1").assertIsNotEnabled()
            resumeWrite.complete(Unit)

            awaitText("食材未保存，请重试。")
            compose.onNodeWithText("牛奶").assertIsDisplayed()
            assertEquals(listOf(original), FridgeStore(app).state.value.stock)
            assertEquals(1, attempts.get())

            assertTrue(blockingPath.delete())
            compose.onNodeWithText("加入冰箱（2 样）").performClick()
            compose.waitUntil(5_000) { attempts.get() == 2 }
            awaitText("食材未保存，请重试。")
            compose.onNodeWithText("牛奶").assertIsDisplayed()
            assertEquals(listOf(original), FridgeStore(app).state.value.stock)

            compose.onNodeWithText("加入冰箱（2 样）").performClick()
            awaitSaved()
            val stock = FridgeStore(app).state.value.stock
            assertEquals(listOf("牛奶", "番茄", "番茄"), stock.map { it.ingredientName })
            assertEquals(listOf("1 份", "1 份", "200 克"), stock.map { it.quantityText })
            assertEquals(original, stock.last())
            assertEquals(3, stock.map { it.id }.toSet().size)
            assertEquals(3, attempts.get())
            assertEquals(1, transport.uploads.get())
        } finally {
            resumeWrite.complete(Unit)
            if (blockingPath.isDirectory) blockingPath.delete()
            database.close()
        }
    }

    @Test
    fun signingOutCancelsUploadAndReplacingPhotoClosesTheBatchDraft() {
        val database = database()
        val finishUpload = CompletableDeferred<Unit>()
        val transport = VisionTransport(member = true, changesProvider = false, finishUpload = finishUpload)
        val sessions = sessions(database, transport, expiresAt = 1)
        try {
            showDialog(sessions, transport, onSave = { error("A signed-out result must not be saved") })
            awaitText("同意并识别")
            compose.onNodeWithText("同意并识别").performClick()
            compose.waitUntil(5_000) { transport.uploads.get() == 1 }
            runBlocking { sessions.signOut() }
            awaitText("登录云乐坊账号")
            compose.waitUntil(5_000) { transport.cancelledUploads.get() == 1 }
            finishUpload.complete(Unit)
            compose.onNodeWithText("核对识别结果").assertDoesNotExist()
            compose.onNodeWithText("换一张照片").performClick()
            compose.onNodeWithText("批量识图").assertDoesNotExist()
            assertEquals(1, transport.uploads.get())
        } finally {
            database.close()
        }
    }

    private fun recreate(content: @Composable () -> Unit) {
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { it.setContent(content = content) }
    }

    private fun showDialog(
        sessions: YunlefunSessionManager,
        transport: VisionTransport,
        onMembership: () -> Unit = {},
        onBack: () -> Unit = {},
        onSave: suspend (List<String>) -> Unit = {},
        onAccount: () -> Unit = {},
    ) = compose.setContent(dialogContent(sessions, transport, onMembership, onBack, onSave, onAccount))

    private fun dialogContent(
        sessions: YunlefunSessionManager,
        transport: VisionTransport,
        onMembership: () -> Unit = {},
        onBack: () -> Unit = {},
        onSave: suspend (List<String>) -> Unit = {},
        onAccount: () -> Unit = {},
    ): @Composable () -> Unit {
        val service = FridgeVisionService(transport)
        return {
            val intake = viewModel<FridgePhotoIntake>()
            LaunchedEffect(intake) {
                if (intake.draft == null) {
                    intake.open()
                    intake.startBatchReview(requireNotNull(intake.draft), transport.photo, service, sessions, String::trim)
                }
            }
            CookTheme {
                val draft = intake.draft
                draft?.batchReview?.let { batch ->
                    FridgeVisionDialog(
                        review = batch,
                        onBack = {
                            intake.endBatchReview(draft, replacePhoto = false)
                            onBack()
                        },
                        onReplacePhoto = { intake.endBatchReview(draft, replacePhoto = true) },
                        onOpenAccount = onAccount,
                        onOpenMembership = onMembership,
                        onOpenPrivacy = {},
                        writeItems = onSave,
                        onSaved = intake::close,
                    )
                }
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun awaitSaved() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("核对识别结果")).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun database(): CookDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Application>(), CookDatabase::class.java,
    ).build()

    private fun sessions(database: CookDatabase, transport: VisionTransport, expiresAt: Long): YunlefunSessionManager {
        val credential = YunlefunCredential("fixture-token", "fixture-refresh", "fixture-member", expiresAt, "device")
        val secret = AtomicReference(Json.encodeToString(credential))
        val vault = object : SecretVault {
            override fun readSecret(key: String): String? = if (key == AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON) secret.get() else null
            override fun writeSecret(key: String, value: String) { secret.set(value) }
            override fun deleteSecret(key: String) { secret.set(null) }
        }
        return YunlefunSessionManager(
            vault, database, YunlefunAccountService(transport), membershipService = MembershipService(transport),
        )
    }

    private class VisionTransport(
        @Volatile var member: Boolean,
        private val changesProvider: Boolean = true,
        private val finishUpload: CompletableDeferred<Unit>? = null,
        private val rejectedProviderChecks: Int = 0,
        private val rejectFirstUpload: Boolean = false,
        private val firstProviderResponse: suspend () -> HttpResponsePayload? = { null },
        private val accountMember: Boolean? = null,
        private val ingredients: List<String> = listOf("鸡蛋", "番茄"),
    ) : HttpTransport {
        @Volatile var providerOverride: String? = null
        val photo = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", output)
            output.toByteArray()
        }
        val refreshes = AtomicInteger()
        val uploads = AtomicInteger()
        val providerChecks = AtomicInteger()
        val cancelledUploads = AtomicInteger()

        override suspend fun request(
            method: String, url: String, headers: Map<String, String>, jsonBody: String?,
            maxResponseBytes: Int, connectTimeoutMs: Int, readTimeoutMs: Int,
        ): HttpResponsePayload {
            if (url.endsWith("/token")) {
                refreshes.incrementAndGet()
                return HttpResponsePayload(
                    200,
                    """{"access_token":"refreshed-token","refresh_token":"next-refresh","sub":"fixture-member","expires_in":3600}""",
                )
            }
            if (url.endsWith("/user/signout")) return HttpResponsePayload(200, "{}")
            if (url == MembershipService.FUNCTIONS_GATEWAY + "account-api") {
                val active = accountMember ?: member
                val expiry = if (active) "4070908800000" else "null"
                return HttpResponsePayload(200, """{"membership":{"isActive":$active,"expireAt":$expiry}}""")
            }
            assertEquals("https://api.yunle.fun/cook-fridge-vision", url)
            assertEquals(
                if (refreshes.get() == 0) "Bearer fixture-token" else "Bearer refreshed-token",
                headers["Authorization"],
            )
            if (method == "GET") {
                val eligible = member
                val check = providerChecks.incrementAndGet()
                assertEquals(null, jsonBody)
                if (check <= rejectedProviderChecks) return loginRequired()
                if (check == 1) firstProviderResponse()?.let { return it }
                val provider = providerOverride ?: if (changesProvider && uploads.get() > 0) "qwen" else "deepseek"
                return HttpResponsePayload(200, """{"ok":true,"schemaVersion":1,"provider":"$provider","member":$eligible}""")
            }
            val payload = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
            assertEquals(Base64.getEncoder().encodeToString(photo), payload.getValue("image").jsonPrimitive.content)
            val previousUploads = uploads.getAndIncrement()
            val provider = providerOverride ?: if (changesProvider && previousUploads > 0) "qwen" else "deepseek"
            assertEquals(provider, payload.getValue("provider").jsonPrimitive.content)
            if (rejectFirstUpload && previousUploads == 0) return loginRequired()
            if (changesProvider && previousUploads == 0) {
                return HttpResponsePayload(409, """{"ok":false,"code":"PROVIDER_CHANGED"}""")
            }
            try {
                finishUpload?.await()
            } catch (e: CancellationException) {
                cancelledUploads.incrementAndGet()
                throw e
            }
            return HttpResponsePayload(200, """{"ok":true,"schemaVersion":1,"ingredients":${Json.encodeToString(ingredients)}}""")
        }

        private fun loginRequired() = HttpResponsePayload(401, """{"ok":false,"code":"LOGIN_REQUIRED"}""")
    }
}
