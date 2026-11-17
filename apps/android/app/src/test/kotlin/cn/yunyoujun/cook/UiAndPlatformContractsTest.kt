package cn.yunyoujun.cook

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.core.recommend.RecommendationOptions
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.FridgeLabelParser
import cn.yunyoujun.cook.data.local.FridgePhotoInspector
import cn.yunyoujun.cook.data.local.RecipeTimerState
import cn.yunyoujun.cook.data.repository.CatalogLoadState
import cn.yunyoujun.cook.platform.AlarmSchedulePrecision
import cn.yunyoujun.cook.platform.FridgeDailyReminderScheduler
import cn.yunyoujun.cook.platform.KitchenNotificationHelper
import cn.yunyoujun.cook.platform.KitchenTimerScheduler
import cn.yunyoujun.cook.ui.screens.KitchenWorkbenchScreen
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiAndPlatformContractsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun loadReadySnapshot(app: CookApplication) = runBlocking {
        app.container.catalogRepository.loadIfNeeded()
        val state = app.container.catalogRepository.state.value
        check(state is CatalogLoadState.Ready) { "Catalog failed to load in Robolectric: $state" }
        state.snapshot
    }

    @Test
    fun notificationPermissionFlow_workbenchShowsDenialWarning_andFridgeReminderGating() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val targetRecipe = snapshot.data.recipes.first { snapshot.recipeText(it.id) != null }

        val shadowApp = Shadows.shadowOf(app)
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = Shadows.shadowOf(notificationManager)
        notificationManager.cancelAll()

        // 1. Denied permission: workbench warns cleanly and never fakes ready confirmation
        shadowApp.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(KitchenNotificationHelper.hasNotificationPermission(app))

        composeRule.setContent {
            CookTheme {
                KitchenWorkbenchScreen(
                    recipeId = targetRecipe.id,
                    snapshot = snapshot,
                    container = app.container,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("开始计时").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("未授予系统通知权限", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("未授予系统通知权限", substring = true).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("已设定后台精确提醒", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )

        // Reset running timer so granting permission can start cleanly
        composeRule.onNodeWithText("暂停").performClick()

        // Fridge daily check stays suppressed when notification permission is denied
        val today = LocalDate.of(2026, 11, 16)
        app.container.fridgeStore.addItem(
            ingredientName = "番茄",
            categoryId = FridgeBatchItem.DEFAULT_CATEGORY_ID,
            quantityText = "2个",
            shelfLifeDays = 1,
            explicitIsoDate = today.plusDays(1).toString(),
        )
        assertFalse(FridgeDailyReminderScheduler.checkAndNotifyIfNeeded(app, today))
        assertTrue(shadowNotificationManager.allNotifications.isEmpty())
        assertNull(app.container.preferencesStore.current.lastFridgeReminderDate)

        // 2. Granted permission: reminder posts once per local date and workbench confirms background alarm
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(KitchenNotificationHelper.hasNotificationPermission(app))

        assertTrue(FridgeDailyReminderScheduler.checkAndNotifyIfNeeded(app, today))
        assertEquals(1, shadowNotificationManager.allNotifications.size)
        assertEquals(today.toString(), app.container.preferencesStore.current.lastFridgeReminderDate)
        // Daily dedupe prevents duplicate post on same calendar day
        assertFalse(FridgeDailyReminderScheduler.checkAndNotifyIfNeeded(app, today))

        composeRule.onNodeWithText("开始计时").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("已设定后台精确提醒", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("当前系统未授权精确闹钟", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun exactAlarmRevocation_reschedulePersistedTimersUsesInexactFallback() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = Shadows.shadowOf(alarmManager)

        val deadline = System.currentTimeMillis() + 180_000L
        app.container.workbenchStore.updateTimer(
            recipeId = 42,
            timer = RecipeTimerState(
                durationSeconds = 300,
                running = true,
                deadlineEpochMs = deadline,
                pausedRemainingSeconds = null,
            ),
        )

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        try {
            assertFalse(KitchenTimerScheduler.canScheduleExactAlarms(app))
            assertEquals(
                AlarmSchedulePrecision.INEXACT_FALLBACK,
                KitchenTimerScheduler.schedule(app, recipeId = 42, recipeName = "菜谱 #42", deadlineEpochMs = deadline),
            )

            // Robolectric returns a copy; cancel the real alarm before restoring it from disk.
            KitchenTimerScheduler.cancel(app, recipeId = 42, recipeName = "菜谱 #42")
            assertFalse(shadowAlarmManager.scheduledAlarms.any { it.triggerAtTime == deadline })
            KitchenTimerScheduler.reschedulePersistedTimers(app)

            val scheduled = shadowAlarmManager.scheduledAlarms
            assertTrue("Expected rescheduled inexact fallback alarm", scheduled.isNotEmpty())
            assertTrue(scheduled.any { it.triggerAtTime == deadline && it.type == AlarmManager.RTC_WAKEUP })
        } finally {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            KitchenTimerScheduler.cancel(app, recipeId = 42, recipeName = "菜谱 #42")
        }
    }

    @Test
    fun fridgePhotoPipeline_inMemoryDecodeOcrParseAndRecipeMatch_leavesZeroTempFiles() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)

        val filesBefore = app.filesDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        val cacheBefore = app.cacheDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()

        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        val pngBytes = ByteArrayOutputStream().use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
        bitmap.recycle()

        var observedDimensions: Pair<Int, Int>? = null
        FridgePhotoInspector.textRecognizerOverride = { bmp, _ ->
            observedDimensions = bmp.width to bmp.height
            "新鲜番茄 250g\n保质期至 2026-11-17"
        }
        try {
            val recognition = FridgePhotoInspector.recognizeInMemoryBytes(pngBytes)
            assertEquals(240 to 160, observedDimensions)
            assertEquals(240, recognition.summary.width)
            assertEquals(160, recognition.summary.height)

            val names = FridgeLabelParser.matchKnownNames(
                text = recognition.recognizedText,
                knownNames = snapshot.allBuiltInStuffNames,
            )
            val scan = FridgeLabelParser.parse(
                text = recognition.recognizedText,
                names = names,
                today = LocalDate.of(2026, 11, 16),
            )
            assertTrue("Expected 番茄 in $names", "番茄" in scan.names)
            assertEquals(LocalDate.of(2026, 11, 17), scan.expiryDay)

            val matched = snapshot.recommender.recommend(
                RecommendationOptions(stuff = scan.names, mode = RecommendationMode.LOOSE),
            )
            assertTrue("Expected recipes using scanned ingredient", matched.isNotEmpty())
            assertTrue(matched.any { "番茄" in it.recipe.stuff })
        } finally {
            FridgePhotoInspector.textRecognizerOverride = null
        }

        val filesAfter = app.filesDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        val cacheAfter = app.cacheDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        assertEquals(filesBefore, filesAfter)
        assertEquals(cacheBefore, cacheAfter)
    }
}
