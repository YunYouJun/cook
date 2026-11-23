package cn.yunyoujun.cook

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.core.dietary.InstitutionalLowGICatalog
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.core.recipe.RecipeAmounts
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeStepUsagePlan
import cn.yunyoujun.cook.core.recommend.RecipeMatch
import cn.yunyoujun.cook.core.recommend.RecommendationOptions
import cn.yunyoujun.cook.core.sync.CatalogCategory
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.DevicePreferencesStore
import cn.yunyoujun.cook.data.local.FridgeDateKind
import cn.yunyoujun.cook.data.local.FridgeExpiryBasis
import cn.yunyoujun.cook.data.local.FridgeLabelParser
import cn.yunyoujun.cook.data.local.FridgePhotoFocus
import cn.yunyoujun.cook.data.local.FridgePhotoInspector
import cn.yunyoujun.cook.data.local.FridgeProduceClassifier
import cn.yunyoujun.cook.data.local.FridgeProduceMatch
import cn.yunyoujun.cook.data.local.FridgeStorageStart
import cn.yunyoujun.cook.data.local.FridgeStore
import cn.yunyoujun.cook.data.local.KitchenClockReading
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.RecipeTimerState
import cn.yunyoujun.cook.data.local.ShowcaseKind
import cn.yunyoujun.cook.data.local.preparationKey
import cn.yunyoujun.cook.data.remote.FridgeVisionService
import cn.yunyoujun.cook.data.repository.CatalogLoadState
import cn.yunyoujun.cook.data.repository.CookDiscoveryScene
import cn.yunyoujun.cook.platform.AlarmSchedulePrecision
import cn.yunyoujun.cook.platform.CookSceneCamera
import cn.yunyoujun.cook.platform.FridgeDailyReminderScheduler
import cn.yunyoujun.cook.platform.KitchenNotificationHelper
import cn.yunyoujun.cook.platform.KitchenTimerScheduler
import cn.yunyoujun.cook.ui.components.DecideRandomDialog
import cn.yunyoujun.cook.ui.components.RandomRecipePages
import cn.yunyoujun.cook.ui.components.rememberCookSceneCamera
import cn.yunyoujun.cook.ui.screens.CloudKitchenCard
import cn.yunyoujun.cook.ui.screens.CookDiscoveryLayout
import cn.yunyoujun.cook.ui.screens.CookTabScreen
import cn.yunyoujun.cook.ui.screens.CookingJournalScreen
import cn.yunyoujun.cook.ui.screens.FridgeTabScreen
import cn.yunyoujun.cook.ui.screens.KitchenWorkbenchScreen
import cn.yunyoujun.cook.ui.screens.RecipeDetailScreen
import cn.yunyoujun.cook.ui.screens.RecipeDataSettingsScreen
import cn.yunyoujun.cook.ui.screens.recipeStepDestination
import cn.yunyoujun.cook.ui.state.CookSessionController
import cn.yunyoujun.cook.ui.state.FridgePhotoIntake
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.TimeZone

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
    fun sceneCameraRestoresDragAndZoomAndContinuesTurningFromThatPose() {
        lateinit var camera: MutableState<CookSceneCamera>
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { camera = rememberCookSceneCamera() }
        val moved = CookSceneCamera.Initial.orbit(90f, -24f).zoom(1.4f).turn(-1)
        val original = composeRule.runOnIdle {
            assertEquals(CookSceneCamera.Initial, camera.value)
            camera.value = moved
            camera
        }

        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            assertTrue(camera !== original)
            assertEquals(moved, camera.value)
            assertEquals(6, camera.value.angleDegrees)
            camera.value = camera.value.turn(1)
            assertEquals(36, camera.value.angleDegrees)
            assertEquals(moved.elevation, camera.value.elevation, 0.0)
            assertEquals(moved.magnification, camera.value.magnification, 0.0)
            camera.value = CookSceneCamera.Initial
            assertEquals(0, camera.value.angleDegrees)
        }
    }

    @Test
    fun cloudKitchenEditsTheBasketAndCancelsAnOutdatedMenu() {
        val selected = mutableStateOf(setOf("洋葱"))
        val recipes = mutableStateOf((1..7).map { Recipe(it, "菜谱 $it") })
        val menuAvailable = mutableStateOf(true)
        var openedPicker = 0
        var openedResults = 0
        composeRule.setContent {
            CookTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CloudKitchenCard(
                        selected = selected.value,
                        hidden = emptySet(),
                        normalize = { it },
                        allowRotation = false,
                        camera = rememberCookSceneCamera(),
                        onRotationChanged = {},
                        onPick = { selected.value = selected.value - it },
                        onShowAllIngredients = { openedPicker++ },
                        recipes = recipes.value,
                        menuAvailable = menuAvailable.value,
                        onShowResults = { openedResults++ },
                    )
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("番茄")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("全部食材").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, openedPicker) }
        val results = composeRule.onNodeWithText("查看 7 道菜")
        results.performScrollTo()

        composeRule.mainClock.autoAdvance = false
        try {
            results.performClick()
            composeRule.mainClock.advanceTimeBy(150)
            composeRule.onNodeWithText("正在准备菜单…").assertIsNotEnabled()
            composeRule.onNodeWithText("洋葱").performScrollTo().performClick()
            composeRule.mainClock.advanceTimeBy(900)
            composeRule.onNodeWithText("食材篮 · 已选 0 样").assertExists()
            composeRule.onNodeWithText("洋葱").assertDoesNotExist()
            composeRule.runOnIdle { assertEquals(0, openedResults) }

            results.performScrollTo().performClick()
            composeRule.mainClock.advanceTimeBy(150)
            composeRule.onNodeWithText("正在准备菜单…").assertIsNotEnabled()
            composeRule.runOnIdle { recipes.value = (8..14).map { Recipe(it, "菜谱 $it") } }
            composeRule.mainClock.advanceTimeBy(900)
            composeRule.runOnIdle { assertEquals(0, openedResults) }

            results.performScrollTo().performClick()
            composeRule.mainClock.advanceTimeBy(150)
            composeRule.onNodeWithText("正在准备菜单…").assertIsNotEnabled()
            composeRule.runOnIdle { menuAvailable.value = false }
            composeRule.mainClock.advanceTimeBy(900)
            results.assertIsNotEnabled()
            composeRule.runOnIdle {
                assertEquals(0, openedResults)
                menuAvailable.value = true
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        results.performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(5_000) { openedResults == 1 }
    }

    @Test
    fun cookingSearchRefillsDraftAndAppliesAnOfflineSentenceWithoutLosingExistingConditions() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val prefs = app.container.preferencesStore
        val previousSearch = "待重新搜索的菜名"
        prefs.recordSearch(previousSearch)
        val controller = CookSessionController().apply {
            setKeyword("红烧肉")
            setSelectedStuff(listOf("青椒"))
            toggleDiscoveryScene(CookDiscoveryScene.EASY)
        }
        composeRule.setContent {
            CookTheme { CookTabScreen(snapshot, controller, app.container, onOpenRecipe = {}) }
        }

        val search = composeRule.onNodeWithText("搜索菜名或食材")
        search.performClick()
        composeRule.onNodeWithText(previousSearch).performClick()
        composeRule.onNode(hasSetTextAction() and hasText(previousSearch)).assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        assertEquals("红烧肉", controller.conditions.value.keyword)

        search.performClick()
        val clearHistory = composeRule.onNodeWithText("清空搜索历史")
        clearHistory.performClick()
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(listOf(previousSearch), prefs.state.value.recentSearches)

        val sentence = "冰箱里有土豆和鸡蛋"
        composeRule.onNodeWithText(sentence).performClick()
        composeRule.onNodeWithText("搜索找菜").assertDoesNotExist()
        assertEquals("", controller.conditions.value.keyword)
        assertEquals(listOf("青椒", "土豆", "鸡蛋"), controller.conditions.value.selectedStuff)
        assertEquals(CookDiscoveryScene.EASY, controller.conditions.value.discoveryScene)
        assertEquals(listOf(sentence, previousSearch), prefs.state.value.recentSearches)
    }

    @Test
    fun cookingFilterKeepsTheFullResultCountVisibleWhileChangingAndClearingSelections() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val prefs = app.container.preferencesStore
        prefs.ensureDefaultPantry(snapshot.data.pantry.defaults)
        val controller = CookSessionController()
        composeRule.setContent {
            CookTheme { CookTabScreen(snapshot, controller, app.container, onOpenRecipe = {}) }
        }
        composeRule.onNodeWithText("筛选与模式").performClick()
        val count = composeRule.onNodeWithTag("cook.filter.resultCount")
        val catalogCount = controller.evaluateCandidates(snapshot, prefs.current).recipes.size
        assertTrue(catalogCount > 0)
        count.assertTextEquals("符合条件 $catalogCount 道菜").assertIsDisplayed()
        composeRule.onNodeWithText("完成").performClick()

        composeRule.runOnIdle { controller.setSelectedStuff(listOf("番茄", "鸡蛋")) }
        val originalCount = controller.evaluateCandidates(snapshot, prefs.current).recipes.size
        composeRule.onNodeWithText("筛选与模式").performClick()
        count.assertTextEquals("符合条件 $originalCount 道菜").assertIsDisplayed()
        composeRule.onNodeWithText("炒菜").performClick().assertIsSelected()
        val stirFryCount = controller.evaluateCandidates(snapshot, prefs.current).recipes.size
        assertTrue(stirFryCount < originalCount)
        count.assertTextEquals("符合条件 $stirFryCount 道菜").assertIsDisplayed()
        composeRule.onNodeWithText("完成").assertIsDisplayed()
        composeRule.onNodeWithText("清除").performClick().assertIsNotEnabled()
        count.assertTextEquals("符合条件 $originalCount 道菜").assertIsDisplayed()
        assertEquals(listOf("番茄", "鸡蛋"), controller.conditions.value.selectedStuff)
        composeRule.onNodeWithText("完成").performClick()
        count.assertDoesNotExist()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("为你匹配到 $originalCount 道菜"))
    }

    @Test
    fun cookingPreferencesPersistAndKeepAvoidanceRemovableAcrossAliasSearches() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val prefs = app.container.preferencesStore
        prefs.ensureDefaultPantry(snapshot.data.pantry.defaults)
        // Devices can retain a choice imported from an older ingredient catalog.
        prefs.setAvoid(listOf("旧食材"))
        val controller = CookSessionController()
        composeRule.setContent {
            CookTheme { CookTabScreen(snapshot, controller, app.container, onOpenRecipe = {}) }
        }
        composeRule.onNodeWithText("筛选与模式").performClick()
        val options = composeRule.onNodeWithTag("cook.filter.options")
        options.performScrollToNode(hasText("家里常备"))
        composeRule.onNodeWithText("家里常备").performClick()
        composeRule.onNodeWithText("油").assertIsSelected().performClick()
        composeRule.onNodeWithText("胡椒粉").performClick().assertIsSelected()
        composeRule.onNodeWithText("姜").performClick().assertIsSelected()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("盐、胡椒粉等 3 种").assertIsDisplayed()
        assertEquals(listOf("盐", "胡椒粉", "姜"), DevicePreferencesStore(app).current.pantry)
        composeRule.onNodeWithText("家里常备").performClick()
        composeRule.onNodeWithText("胡椒粉").assertIsSelected()
        composeRule.onNodeWithText("恢复默认").performClick().assertIsNotEnabled()
        assertEquals(snapshot.data.pantry.defaults, DevicePreferencesStore(app).current.pantry)
        composeRule.onNodeWithText("返回").performClick()

        options.performScrollToNode(hasText("忌口"))
        composeRule.onNodeWithText("忌口").performClick()
        val search = composeRule.onNode(hasSetTextAction())
        search.performTextInput("西红柿")
        composeRule.onNodeWithText("番茄").performClick()
        composeRule.onNodeWithContentDescription("移除忌口 番茄").assertIsDisplayed()
        search.performTextReplacement("不存在的食材")
        composeRule.onNodeWithText("没有找到「不存在的食材」").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("移除忌口 番茄").performClick().assertDoesNotExist()
        composeRule.onNodeWithContentDescription("移除忌口 旧食材").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("旧食材").assertIsDisplayed()
        assertEquals(listOf("旧食材"), DevicePreferencesStore(app).current.avoid)
        composeRule.onNodeWithText("忌口").performClick()
        composeRule.onNodeWithText("清空忌口").performClick().assertIsNotEnabled()
        composeRule.onNodeWithText("还没有设置忌口").assertIsDisplayed()
        assertTrue(DevicePreferencesStore(app).current.avoid.isEmpty())
    }

    @Test
    fun cookingFootprintGroupsByDayAndUpdatesCountsForTheSelectedRecipe() {
        val container = ApplicationProvider.getApplicationContext<CookApplication>().container
        val journal = container.cookingJournalStore
        val tomatoEgg = JournalRecipeId.Catalog(250)
        val steamedEgg = JournalRecipeId.Catalog(588)
        val today = LocalDate.of(2026, 2, 4)
        val yesterday = today.minusDays(1)
        val zone = ZoneId.systemDefault()
        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val todayHeading = dateFormat.format(today)
        val yesterdayHeading = dateFormat.format(yesterday)
        val oldRecord = yesterday.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val newRecord = today.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        journal.recordCooked(tomatoEgg, "番茄炒蛋", oldRecord)
        journal.confirmLearned(tomatoEgg, "番茄炒蛋", newRecord)
        journal.recordCooked(steamedEgg, "蒸蛋羹", newRecord + 3_600_000)
        val recipe = mutableStateOf<JournalRecipeId?>(null)
        val titles = mapOf<JournalRecipeId, String>(tomatoEgg to "番茄炒蛋", steamedEgg to "蒸蛋羹")

        composeRule.setContent {
            CookTheme {
                CookingJournalScreen(journal, container.showcaseAssets, recipe.value, titles::get, onBack = {}, onOpenRecipe = {})
            }
        }
        composeRule.onNodeWithText("累计足迹").assertIsDisplayed()
        composeRule.onNodeWithText("做过 3 次").assertIsDisplayed()
        composeRule.onNodeWithText("学会 1 道").assertIsDisplayed()
        composeRule.onNodeWithText("仅此设备").assertIsDisplayed()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(todayHeading))
        composeRule.onAllNodesWithText(todayHeading).assertCountEquals(1)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(yesterdayHeading))
        composeRule.onNodeWithText(yesterdayHeading).assertIsDisplayed()

        composeRule.runOnIdle { recipe.value = tomatoEgg }
        val list = composeRule.onNode(hasScrollToNodeAction())
        list.performScrollToIndex(0)
        composeRule.onNodeWithText("做过 2 次").assertIsDisplayed()
        composeRule.onNodeWithText("学会 1 道").assertIsDisplayed()
        composeRule.onNodeWithText("蒸蛋羹").assertDoesNotExist()
        composeRule.runOnIdle {
            journal.removeCooked(journal.state.value.cooked.single { it.dateMs == oldRecord }.id)
        }
        composeRule.onNodeWithText("做过 1 次").assertIsDisplayed()
        composeRule.onNodeWithText("学会 1 道").assertIsDisplayed()
        composeRule.onNodeWithText(yesterdayHeading).assertDoesNotExist()
    }

    @Test
    fun recipeDataScreenCopiesTheExactVersionAndOpensBundledSources() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val source = requireNotNull(snapshot.textLibrary).source
        composeRule.setContent {
            CookTheme { RecipeDataSettingsScreen(snapshot, onBack = {}) }
        }

        composeRule.onNodeWithText("复制版本标识").performClick()
        composeRule.onNodeWithText("已复制版本标识").assertIsDisplayed()
        composeRule.runOnIdle {
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            assertEquals(snapshot.data.meta.dataVersion, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }

        val activity = Shadows.shadowOf(composeRule.activity)
        composeRule.onNodeWithText("菜谱与食材数据").performScrollTo().performClick()
        assertEquals("https://github.com/YunYouJun/cook/tree/main/packages/data", activity.nextStartedActivity.dataString)
        composeRule.onNodeWithText("图文做法").performScrollTo().performClick()
        assertEquals(source.url, activity.nextStartedActivity.dataString)
        composeRule.onNodeWithText(source.name).assertIsDisplayed()
    }

    @Test
    fun learnedDishGallerySwitchesBetweenIndependentPacksAndKeepsUnavailableBadges() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val journal = app.container.cookingJournalStore
        val exhibits = app.container.showcaseAssets
        val tomatoEgg = JournalRecipeId.Catalog(250)
        val steamedEgg = JournalRecipeId.Catalog(588)
        val deletedPrivate = JournalRecipeId.Personal("lost-cookbook-recipe")
        journal.confirmLearned(tomatoEgg, "番茄炒蛋", 1_000L)
        journal.confirmLearned(steamedEgg, "蒸蛋羹", 2_000L)
        journal.confirmLearned(deletedPrivate, "外婆的汤", 3_000L)
        val available = mapOf<JournalRecipeId, String>(tomatoEgg to "番茄炒蛋", steamedEgg to "蒸蛋羹")
        val tomatoModel = exhibits.dishes.getValue(250).single { it.asset.kind == ShowcaseKind.Model }
        val steamedModel = exhibits.dishes.getValue(588).single { it.asset.kind == ShowcaseKind.Model }
        composeRule.setContent {
            CookTheme {
                CookingJournalScreen(journal, exhibits, null, available::get, onBack = {}, onOpenRecipe = {})
            }
        }

        composeRule.onNodeWithText("我的拿手菜").performClick()
        composeRule.onNodeWithText("私人菜谱已删除或暂不可用").assertIsDisplayed()
        composeRule.onNodeWithText("展柜").performClick()
        composeRule.onNodeWithText("展出菜品 · 蒸蛋羹").performClick()
        composeRule.onNodeWithText("外婆的汤").assertDoesNotExist()
        composeRule.onNodeWithText("番茄炒蛋").performClick()
        val gallery = composeRule.onNode(hasScrollToNodeAction())
        gallery.performScrollToNode(hasText("安装内置模型"))
        composeRule.onNodeWithText("安装内置模型").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) { tomatoModel.state.value.installation != null }
        composeRule.onNodeWithText("查看 3D").assertExists()

        composeRule.onNodeWithText("展出菜品 · 番茄炒蛋").performClick()
        composeRule.onNodeWithText("蒸蛋羹").performClick()
        composeRule.onNodeWithText("查看 3D").assertDoesNotExist()
        assertNull(steamedModel.state.value.installation)

        composeRule.onNodeWithText("展出菜品 · 蒸蛋羹").performClick()
        composeRule.onNodeWithText("番茄炒蛋").performClick()
        composeRule.onNodeWithText("查看 3D").assertExists()
        assertEquals(1_000L, journal.state.value.learned.single { it.recipeId == tomatoEgg }.dateMs)
        assertTrue(journal.state.value.learned.any { it.recipeId == deletedPrivate })
    }

    @Test
    @Config(qualifiers = "w880dp-h400dp")
    fun showcaseModelUsesTheShortWindowAndReturnsToThePositionInTheGallery() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val journal = app.container.cookingJournalStore
        val assets = app.container.showcaseAssets
        val tomato = JournalRecipeId.Catalog(250)
        journal.confirmLearned(tomato, "番茄炒蛋", 1_000L)
        val model = assets.dishes.getValue(250).single { it.asset.kind == ShowcaseKind.Model }
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                CookTheme {
                    CookingJournalScreen(
                        journal, assets, null, { id -> "番茄炒蛋".takeIf { id == tomato } },
                        onBack = {}, onOpenRecipe = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("我的拿手菜").performClick()
        composeRule.onNodeWithText("展柜").performClick()
        val gallery = composeRule.onNode(hasScrollToNodeAction())
        gallery.performScrollToNode(hasText("安装内置模型"))
        composeRule.onNodeWithText("安装内置模型").performClick()
        composeRule.waitUntil(8_000) { model.state.value.installation != null }
        val openModel = composeRule.onNodeWithText("查看 3D")
        openModel.performClick()
        val viewport = composeRule.onNodeWithContentDescription("番茄炒蛋 3D 展示")
        viewport.assert(!hasAnyAncestor(hasScrollToNodeAction())).assertIsDisplayed()
        composeRule.onNodeWithText("返回成就").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        viewport.assertIsDisplayed()
        composeRule.onNodeWithText("返回成就").performClick()
        viewport.assertDoesNotExist()
        openModel.assertIsDisplayed()
        assertEquals(1_000L, journal.state.value.learned.single().dateMs)
    }

    @Test
    fun showcaseRechecksACorruptModelOnResumeAndReinstallWaitsForExplicitOpen() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val journal = app.container.cookingJournalStore
        val assets = app.container.showcaseAssets
        val tomato = JournalRecipeId.Catalog(250)
        val firstLearned = 1_000L
        journal.confirmLearned(tomato, "番茄炒蛋", firstLearned)
        val model = assets.dishes.getValue(250).single { it.asset.kind == ShowcaseKind.Model }
        composeRule.setContent {
            CookTheme {
                CookingJournalScreen(
                    journal, assets, null, { id -> "番茄炒蛋".takeIf { id == tomato } },
                    onBack = {}, onOpenRecipe = {},
                )
            }
        }

        composeRule.onNodeWithText("我的拿手菜").performClick()
        composeRule.onNodeWithText("展柜").performClick()
        val gallery = composeRule.onNode(hasScrollToNodeAction())
        fun installModel() {
            gallery.performScrollToNode(hasText("安装内置模型"))
            composeRule.onNodeWithText("安装内置模型").performClick()
            composeRule.waitUntil(8_000) {
                composeRule.onAllNodesWithText("查看 3D").fetchSemanticsNodes().isNotEmpty()
            }
        }

        installModel()
        composeRule.onNodeWithText("查看 3D").performClick()
        composeRule.onNodeWithText("返回成就").assertExists()
        val modelFile = requireNotNull(model.state.value.installation).file

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        modelFile.writeBytes(byteArrayOf(0))
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodesWithText("安装内置模型").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("返回成就").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("番茄炒蛋徽章").assertExists()
        assertNull(model.state.value.installation)
        assertEquals(firstLearned, journal.state.value.learned.single().dateMs)
        assertEquals(1, journal.state.value.cooked.size)

        installModel()
        composeRule.onNodeWithText("返回成就").assertDoesNotExist()
        composeRule.onNodeWithText("查看 3D").performClick()
        composeRule.onNodeWithText("返回成就").assertExists()
    }

    @Test
    fun searchPreviewKeepsEditsThroughSettingsAndRequiresReparseAndPartialConfirmation() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val prefs = app.container.preferencesStore
        prefs.setAvoid(listOf("香菜"))
        val controller = CookSessionController().apply {
            setKeyword("红烧肉")
            setSelectedStuff(listOf("土豆"))
            toggleDiscoveryScene(CookDiscoveryScene.EASY)
        }
        val initialConditions = controller.conditions.value
        composeRule.setContent {
            CookTheme { CookTabScreen(snapshot, controller, app.container, onOpenRecipe = {}) }
        }

        composeRule.onNodeWithText("搜索菜名或食材").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("菜名、食材或一句话"))
            .performTextInput("鸡蛋和西葫芦，不要辣")
        composeRule.onNodeWithText("预览条件").performClick()
        composeRule.onNodeWithContentDescription("移除食材西葫芦").performScrollTo().performClick()
        composeRule.onNodeWithText("AI 设置").performClick()
        composeRule.onNodeWithText("AI 辅助设置").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithContentDescription("移除食材西葫芦").assertDoesNotExist()
        assertEquals(initialConditions, controller.conditions.value)
        assertTrue(prefs.current.recentSearches.isEmpty())

        val revisedText = "鸡蛋，不要辣，孕期饮食"
        val apply = composeRule.onNodeWithText("应用条件")
        composeRule.onNode(hasSetTextAction() and hasText("这次想怎么做")).performTextReplacement(revisedText)
        apply.assertIsNotEnabled()
        composeRule.onNodeWithText("重新识别本地条件").performClick()
        apply.assertIsNotEnabled()
        composeRule.onNodeWithText("仅应用上方条件").performScrollTo().performClick()
        apply.performClick()

        assertEquals(initialConditions.copy(selectedStuff = listOf("土豆", "鸡蛋")), controller.conditions.value)
        assertEquals(listOf("香菜"), prefs.current.avoid)
        assertTrue(prefs.current.noSpicy)
        assertEquals(listOf(revisedText), prefs.current.recentSearches)
    }

    @Test
    fun manualFridgeIntakeSavesAliasesAndBatchesTogetherAndRetainsDraftAfterWriteFailure() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val fridge = app.container.fridgeStore
        app.container.personalCatalogStore.addCustomEntry("自家卤牛肉", CatalogCategory.MEAT).getOrThrow()
        val existing = FridgeBatchItem(ingredientName = "牛奶")
        fridge.addItems(listOf(existing))
        val photoIntake = FridgePhotoIntake()
        composeRule.setContent {
            CookTheme {
                FridgeTabScreen(snapshot, app.container, photoIntake, {}, {}, {}, onChooseGalleryPhoto = {})
            }
        }

        composeRule.onNodeWithText("入库").performClick()
        composeRule.onNodeWithText("蓝莓").performClick()
        composeRule.onNodeWithText("搜索或输入食材").performTextInput("西红柿,番茄,桂花布丁")
        composeRule.onNodeWithText("添加").performClick()
        composeRule.onNodeWithText("搜索或输入食材").performTextInput("自家卤")
        composeRule.onNodeWithText("自家卤牛肉").performClick()
        composeRule.onNodeWithText("放入（4 样）").assertIsEnabled()

        val form = composeRule.onNodeWithTag("manual-fridge-intake-form")
        form.performScrollToNode(hasContentDescription("调整番茄的数量或食用时间"))
        composeRule.onNodeWithContentDescription("调整番茄的数量或食用时间").performClick()
        form.performScrollToNode(hasText("数量说明（选填）"))
        composeRule.onNodeWithText("数量说明（选填）").performTextInput("500 克")
        form.performScrollToNode(hasText("自设食用天数（选填）"))
        composeRule.onNodeWithText("自设食用天数（选填）").performTextInput("3")

        val blockingPath = File(app.filesDir, "fridge-store-v1.json.tmp")
        assertTrue(blockingPath.mkdir())
        try {
            composeRule.onNodeWithText("放入（4 样）").performClick()
            composeRule.onNodeWithText("食材未保存，请重试。").assertIsDisplayed()
            assertEquals(listOf(existing), fridge.state.value.stock)
        } finally {
            assertTrue(blockingPath.delete())
        }
        composeRule.onNodeWithText("放入（4 样）").performClick()
        val saved = FridgeStore(app).state.value.stock.associateBy { it.ingredientName }
        val today = LocalDate.now()
        assertEquals(setOf("牛奶", "蓝莓", "番茄", "桂花布丁", "自家卤牛肉"), saved.keys)
        assertEquals(existing, saved.getValue("牛奶"))
        assertEquals("veg", saved.getValue("蓝莓").categoryId)
        assertEquals(FridgeDateKind.ADVISORY, saved.getValue("蓝莓").dateKind)
        assertEquals(today.plusDays(2).toString(), saved.getValue("蓝莓").expiresOnIsoDate)
        assertEquals("500 克", saved.getValue("番茄").quantityText)
        assertEquals(FridgeDateKind.MANUAL, saved.getValue("番茄").dateKind)
        assertEquals(today.plusDays(3).toString(), saved.getValue("番茄").expiresOnIsoDate)
        assertEquals(FridgeDateKind.ESTIMATED, saved.getValue("桂花布丁").dateKind)
        assertEquals(snapshot.data.shelfLife.defaultDays.toLong(), saved.getValue("桂花布丁").daysRemaining(today))
        assertEquals("meat", saved.getValue("自家卤牛肉").categoryId)
        assertEquals(1, saved.values.filterNot { it.id == existing.id }.map { it.addedAtMs }.distinct().size)
    }

    @Test
    fun fridgePhotoExamplesAreReadOnlyAndConfirmedCutDateIsSavedWithTheEstimate() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val intake = FridgePhotoIntake()
        val today = LocalDate.now()
        val cutDay = today.minusDays(1)
        composeRule.setContent {
            CookTheme { FridgeTabScreen(snapshot, app.container, intake, {}, {}, {}, onChooseGalleryPhoto = {}) }
        }

        composeRule.onNodeWithText("拍照识别").performClick()
        composeRule.onNodeWithText("蓝莓没有生产日期怎么记？").performScrollTo().performClick()
        composeRule.onNodeWithText("购买日期填实际买回的那天", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("完整和切开的西瓜怎么记？").performScrollTo().performClick()
        composeRule.onNodeWithText("实际切开的那天", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("确认入库").assertDoesNotExist()
        assertTrue(app.container.fridgeStore.state.value.stock.isEmpty())

        composeRule.onNodeWithText("手动录入包装或食材").performScrollTo().performClick()
        composeRule.runOnIdle {
            requireNotNull(intake.draft).produceMatch = FridgeProduceMatch(listOf("watermelon" to 0.90f))
        }
        val nameField = hasText("核对食材名称") and hasSetTextAction()
        composeRule.onNode(nameField and hasText("西瓜")).assertExists()
        composeRule.onNodeWithText("确认入库").assertIsEnabled()
        composeRule.onNode(nameField).performScrollTo().performTextReplacement("")
        composeRule.onNodeWithText("确认入库").assertIsNotEnabled()
        assertTrue(app.container.fridgeStore.state.value.stock.isEmpty())
        composeRule.onNodeWithText("西瓜").performScrollTo().performClick()
        composeRule.onNodeWithText("已经切开或去皮").performScrollTo().performClick()
        composeRule.runOnIdle {
            val draft = requireNotNull(intake.draft)
            draft.dates = draft.dates.copy(startDay = cutDay)
        }
        composeRule.onNodeWithText("切开日期 $cutDay").assertExists()

        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val reminder = composeRule.onNode(hasText("冰箱临期提醒") and hasAnyAncestor(isDialog()))
        reminder.performScrollTo().assertIsOff().performClick().assertIsOn()
        assertTrue(DevicePreferencesStore(app).current.fridgeRemindersEnabled)

        composeRule.onNodeWithText("确认入库").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { app.container.fridgeStore.state.value.stock.isNotEmpty() }

        val saved = FridgeStore(app).state.value.stock.single()
        assertEquals("西瓜", saved.ingredientName)
        assertEquals("veg", saved.categoryId)
        assertEquals(FridgeDateKind.ADVISORY, saved.dateKind)
        assertEquals(cutDay.plusDays(3).toString(), saved.expiresOnIsoDate)
        assertEquals(FridgeStorageStart(cutDay.toString(), wasCut = true), saved.storageStart)
    }

    @Test
    fun uncertainProduceCandidatesRequireSelectionAndAreClearedWithThePhoto() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val intake = FridgePhotoIntake()
        composeRule.setContent {
            CookTheme { FridgeTabScreen(snapshot, app.container, intake, {}, {}, {}, onChooseGalleryPhoto = {}) }
        }

        composeRule.onNodeWithText("拍照识别").performClick()
        composeRule.runOnIdle {
            val draft = requireNotNull(intake.draft)
            draft.photo = FridgePhotoIntake.Photo(byteArrayOf(1))
            draft.produceMatch = FridgeProduceMatch(suggestions = listOf("apple", "banana"))
        }
        composeRule.onNodeWithText("可能是 苹果").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("可能是 香蕉").performScrollTo().assertIsDisplayed()
        if (BuildConfig.COOK_FRIDGE_VISION_ENABLED) {
            composeRule.onNodeWithText("确认入库").assertDoesNotExist()
            composeRule.onNodeWithText("按一份食材录入").performScrollTo().performClick()
        }
        composeRule.onNodeWithText("确认入库").assertIsNotEnabled()
        composeRule.onNodeWithText("可能是 香蕉").performScrollTo().performClick()
        composeRule.onNode(hasText("核对食材名称") and hasSetTextAction() and hasText("香蕉")).assertExists()
        composeRule.onNodeWithText("确认入库").assertIsEnabled()
        assertTrue(app.container.fridgeStore.state.value.stock.isEmpty())
        composeRule.runOnIdle { requireNotNull(intake.draft).clearPhoto() }
        composeRule.onNodeWithText("可能是 苹果").assertDoesNotExist()
        composeRule.onNodeWithText("可能是 香蕉").assertDoesNotExist()
        composeRule.onNodeWithText("确认入库").assertDoesNotExist()
    }

    @Test
    fun aNewPhotoResultCanReplaceThePreviousReviewAndSaveItsOwnIngredientAndDate() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val intake = FridgePhotoIntake()
        val photo = FridgePhotoIntake.Photo(byteArrayOf(1))
        val focus = FridgePhotoFocus(horizontalPosition = .8f)
        val previousDate = LocalDate.now().plusDays(4)
        val recognizedDate = previousDate.plusDays(3)
        val correctedLabel = "西红柿\n保质期至 $previousDate"
        val recognizedLabel = "牛奶\n保质期至 $recognizedDate"
        val name = hasSetTextAction() and hasText("核对食材名称")
        val label = hasSetTextAction() and hasText("包装标签文字（支持生产日期、保质期、到期日）")
        composeRule.setContent {
            CookTheme { FridgeTabScreen(snapshot, app.container, intake, {}, {}, {}, onChooseGalleryPhoto = {}) }
        }

        composeRule.onNodeWithText("拍照识别").performClick()
        composeRule.runOnIdle {
            val draft = requireNotNull(intake.draft)
            draft.photo = photo
            draft.reviewing = true
            draft.setRecognizedLabel("西红柿")
            draft.labelText = correctedLabel
            draft.ingredientNameOverride = "小番茄"
            draft.dates = draft.dates.copy(basis = FridgeExpiryBasis.MANUAL, customExpiry = previousDate)
            draft.selectedCategoryId = "veg"
            draft.recognitionFocus = focus
            draft.setRecognizedLabel(recognizedLabel, keepEdits = true)
        }

        val adopt = composeRule.onNodeWithText("使用本次识别结果")
        adopt.performScrollTo().performClick()
        composeRule.onNodeWithText("保留修改").performClick()
        composeRule.onNode(name and hasText("小番茄")).assertExists()
        composeRule.onNode(label and hasText(correctedLabel)).assertExists()
        composeRule.runOnIdle { assertEquals(previousDate, intake.draft?.dates?.customExpiry) }

        adopt.performScrollTo().performClick()
        composeRule.onNodeWithText("使用识别结果").performClick()
        composeRule.onNode(name and hasText("牛奶")).assertExists()
        composeRule.onNode(label and hasText(recognizedLabel)).assertExists()
        composeRule.onNodeWithText("到期日 $recognizedDate（包装保质期）").assertExists()
        adopt.assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(intake.draft?.photo === photo)
            assertEquals(focus, intake.draft?.recognitionFocus)
        }

        composeRule.onNodeWithText("确认入库").assertIsEnabled().performClick()
        composeRule.waitUntil(5_000) { app.container.fridgeStore.state.value.stock.isNotEmpty() }
        val saved = FridgeStore(app).state.value.stock.single()
        assertEquals("牛奶", saved.ingredientName)
        assertEquals("dairy", saved.categoryId)
        assertEquals(FridgeDateKind.PACKAGE_EXPIRY, saved.dateKind)
        assertEquals(recognizedDate.toString(), saved.expiresOnIsoDate)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun focusingOfflineRecognitionPreservesTheWholePhotoForBatchReview() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val intake = FridgePhotoIntake()
        val raster = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        val photo = try {
            FridgePhotoIntake.Photo(ByteArrayOutputStream().use { output ->
                assertTrue(raster.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }, captureRotation = 90)
        } finally {
            raster.recycle()
        }
        val ocr = FridgePhotoInspector.textRecognizerOverride
        FridgePhotoInspector.textRecognizerOverride = { bitmap, rotation ->
            assertEquals(80 to 80, bitmap.width to bitmap.height)
            assertEquals(0, rotation)
            "香蕉"
        }
        try {
            composeRule.setContent {
                CookTheme {
                    FridgeTabScreen(snapshot, app.container, intake, {}, {}, {}, onChooseGalleryPhoto = {})
                }
            }
            composeRule.onNodeWithText("拍照识别").performClick()
            composeRule.runOnIdle {
                val draft = requireNotNull(intake.draft)
                draft.photo = photo
                draft.setRecognizedLabel("苹果")
                draft.reviewing = true
            }
            composeRule.onNodeWithText("圈选照片区域").performScrollTo().performClick()
            composeRule.onNodeWithText("取消").performClick()
            composeRule.runOnIdle {
                assertEquals("苹果", intake.draft?.recognizedLabelText)
                assertNull(intake.draft?.recognitionFocus)
            }

            composeRule.onNodeWithText("圈选照片区域").performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasContentDescription("所选照片")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("左右位置").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
            composeRule.onNodeWithText("识别框内食材").assertIsEnabled().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("正在读取并识别照片…").fetchSemanticsNodes().isEmpty()
            }
            composeRule.runOnIdle {
                assertEquals(intake.draft?.photoError, "香蕉", intake.draft?.recognizedLabelText)
            }
            composeRule.onNode(hasText("核对食材名称") and hasSetTextAction() and hasText("香蕉")).assertExists()
            composeRule.onNodeWithText("确认入库").assertIsEnabled()
            composeRule.runOnIdle {
                assertTrue(intake.draft?.photo === photo)
                assertEquals(.75f, requireNotNull(intake.draft?.recognitionFocus).horizontalPosition, .001f)
            }
            if (BuildConfig.COOK_FRIDGE_VISION_ENABLED) {
                composeRule.onNodeWithText("批量识图").performScrollTo().performClick()
                composeRule.waitUntil(5_000) {
                    composeRule.onAllNodesWithText("返回本机识别").fetchSemanticsNodes().isNotEmpty()
                }
                val jpeg = requireNotNull(intake.draft?.batchReview).jpeg
                val upload = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                try {
                    assertEquals(160 to 240, upload.width to upload.height)
                } finally {
                    upload.recycle()
                }
            } else {
                composeRule.onNodeWithText("批量识图", substring = true).assertDoesNotExist()
                assertNull(intake.draft?.batchReview)
            }
            assertTrue(app.container.fridgeStore.state.value.stock.isEmpty())
        } finally {
            FridgePhotoInspector.textRecognizerOverride = ocr
            composeRule.runOnIdle { intake.close() }
        }
    }

    @Test
    fun unclearFridgePhotoAllowsLocalReviewWithOrWithoutBatchRecognition() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val intake = FridgePhotoIntake()
        val photo = FridgePhotoIntake.Photo(byteArrayOf(1))
        composeRule.setContent {
            CookTheme { FridgeTabScreen(snapshot, app.container, intake, {}, {}, {}, onChooseGalleryPhoto = {}) }
        }

        composeRule.onNodeWithText("拍照识别").performClick()
        composeRule.runOnIdle {
            val draft = requireNotNull(intake.draft)
            draft.labelText = "蓝莓、鸡蛋"
            draft.photo = photo
        }
        composeRule.onNodeWithText("选相册图片").assertIsEnabled()
        if (BuildConfig.COOK_FRIDGE_VISION_ENABLED) {
            composeRule.onNodeWithText("选择录入方式").assertIsDisplayed()
            composeRule.onNodeWithText("批量识图（会员）").assertIsEnabled()
            composeRule.onNodeWithText("确认入库").assertDoesNotExist()
            composeRule.onNodeWithText("按一份食材录入").performClick()
        } else {
            assertNull(app.container.fridgeVisionService)
            composeRule.onNodeWithText("选择录入方式").assertDoesNotExist()
            composeRule.onNodeWithText("批量识图", substring = true).assertDoesNotExist()
        }
        composeRule.onNodeWithText("确认入库").assertIsNotEnabled()
        composeRule.runOnIdle {
            val draft = requireNotNull(intake.draft)
            assertEquals(photo, draft.photo)
            assertNull(draft.batchReview)
        }
        composeRule.onNodeWithText("蓝莓").performScrollTo().performClick()
        composeRule.onNodeWithText("确认入库").assertIsEnabled().performClick()
        composeRule.waitUntil(5_000) { app.container.fridgeStore.state.value.stock.isNotEmpty() }
        assertEquals("蓝莓", FridgeStore(app).state.value.stock.single().ingredientName)
    }

    @Test
    fun fridgeCookingUsesUsableBatchesAndOffersPriorityOnly() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val today = LocalDate.now()
        app.container.fridgeStore.addItems(listOf(
            FridgeBatchItem(ingredientName = "牛奶", expiresOnIsoDate = today.minusDays(1).toString()),
            FridgeBatchItem(ingredientName = "牛奶", expiresOnIsoDate = today.toString()),
            FridgeBatchItem(ingredientName = "番茄", expiresOnIsoDate = today.plusDays(2).toString()),
            FridgeBatchItem(ingredientName = "鸡蛋", expiresOnIsoDate = today.plusDays(3).toString()),
            FridgeBatchItem(ingredientName = "酸奶", expiresOnIsoDate = today.minusDays(2).toString()),
        ))
        val selections = mutableListOf<List<String>>()
        val intake = FridgePhotoIntake()
        composeRule.setContent {
            CookTheme {
                FridgeTabScreen(snapshot, app.container, intake, selections::add, {}, {}, onChooseGalleryPhoto = {})
            }
        }

        composeRule.onNodeWithText("用冰箱里的 3 种食材选菜").performClick()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("只用这几样做菜"))
        composeRule.onNodeWithText("只用这几样做菜").performClick()
        assertEquals(listOf(listOf("牛奶", "番茄", "鸡蛋"), listOf("牛奶", "番茄")), selections)

        val list = composeRule.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("建议后天吃完", substring = true))
        composeRule.onNodeWithText("建议后天吃完", substring = true).assertIsDisplayed()
        list.performScrollToNode(hasText("已过记录日期"))
        composeRule.onNodeWithText("已过记录日期").assertIsDisplayed()
    }

    @Test
    fun fridgeCategoryOverviewShowsAllGroupsAndKeepsItsSelectionOnRestore() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val store = app.container.fridgeStore
        val today = LocalDate.now()
        store.addCategory("备菜盒")
        val custom = store.state.value.categories.single { it.name == "备菜盒" }
        store.addItems(listOf(
            FridgeBatchItem(ingredientName = "番茄", categoryId = "veg", expiresOnIsoDate = today.minusDays(1).toString()),
            FridgeBatchItem(ingredientName = "豆腐", categoryId = custom.id, expiresOnIsoDate = today.toString()),
            FridgeBatchItem(ingredientName = "牛奶", categoryId = "dairy"),
        ))
        val selection = mutableListOf<List<String>>()
        val intake = FridgePhotoIntake()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            CookTheme {
                FridgeTabScreen(snapshot, app.container, intake, selection::add, {}, {}, onChooseGalleryPhoto = {})
            }
        }

        composeRule.onNodeWithText("按临期").assertIsSelected()
        composeRule.onNodeWithText("按分类").performClick().assertIsSelected()
        val list = composeRule.onNode(hasScrollToNodeAction())
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        for (title in listOf("蔬菜水果", "乳品熟食", "备菜盒")) {
            val categoryHeading = hasText(title) and heading
            list.performScrollToNode(categoryHeading)
            composeRule.onNode(categoryHeading).assertIsDisplayed()
        }

        restoration.emulateSavedInstanceStateRestore()
        list.performScrollToIndex(0)
        composeRule.onNodeWithText("按分类").assertIsSelected()
        composeRule.onNodeWithText("用冰箱里的 2 种食材选菜").performClick()
        assertEquals(listOf(listOf("豆腐", "牛奶")), selection)

        val tab = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        composeRule.onNode(hasText("蔬菜水果") and tab).performClick().assertIsSelected()
        list.performScrollToNode(hasText("番茄"))
        composeRule.onNodeWithText("番茄").assertIsDisplayed()
        composeRule.onNodeWithText("豆腐").assertDoesNotExist()
        assertEquals(setOf("番茄", "豆腐", "牛奶"), store.state.value.stock.map { it.ingredientName }.toSet())
    }

    @Test
    fun fridgePhotoReviewCanCreateAndAssignACategoryBeforeConfirmingIntake() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val store = app.container.fridgeStore
        val intake = FridgePhotoIntake()
        composeRule.setContent {
            CookTheme { FridgeTabScreen(snapshot, app.container, intake, {}, {}, {}, onChooseGalleryPhoto = {}) }
        }
        composeRule.onNodeWithText("拍照识别").performClick()
        composeRule.runOnIdle {
            val draft = requireNotNull(intake.draft)
            draft.photo = FridgePhotoIntake.Photo(byteArrayOf(1))
            draft.setRecognizedLabel("蓝莓")
            draft.reviewing = true
        }
        composeRule.onNodeWithText("新建分类").performScrollTo().performClick()
        composeRule.onNode(hasText("分类名称") and hasSetTextAction()).performTextInput("下午茶")
        composeRule.onNodeWithText("保存分类").performClick()
        composeRule.onNode(hasText("核对食材名称") and hasText("蓝莓") and hasSetTextAction()).assertExists()
        composeRule.onNode(hasText("下午茶") and hasAnyAncestor(isDialog())).assertIsSelected()
        assertTrue(store.state.value.stock.isEmpty())

        composeRule.onNodeWithText("确认入库").performClick()
        composeRule.waitUntil(5_000) { store.state.value.stock.isNotEmpty() }
        val batch = store.state.value.stock.single()
        assertEquals("蓝莓", batch.ingredientName)
        assertEquals(store.state.value.categories.single { it.name == "下午茶" }.id, batch.categoryId)
    }

    @Test
    fun fridgeCategoryDeletionRequiresConfirmationAndKeepsStockAndHistory() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val store = app.container.fridgeStore
        val stock = FridgeBatchItem(ingredientName = "番茄", categoryId = "veg")
        val history = FridgeBatchItem(ingredientName = "鸡蛋", categoryId = "meat", consumedAtMs = 1)
        store.addItems(listOf(stock, history))
        val intake = FridgePhotoIntake()
        composeRule.setContent {
            CookTheme { FridgeTabScreen(snapshot, app.container, intake, {}, {}, {}, onChooseGalleryPhoto = {}) }
        }

        val list = composeRule.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("编辑"))
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.onNodeWithText("新建分类").performScrollTo().performClick()
        composeRule.onNode(hasText("分类名称") and hasSetTextAction()).performTextInput("周末备菜")
        composeRule.onNodeWithText("保存分类").performClick()
        composeRule.onNode(hasText("周末备菜") and hasAnyAncestor(isDialog())).assertIsSelected()
        composeRule.onNodeWithText("保存").performClick()
        val custom = store.state.value.categories.single { it.name == "周末备菜" }
        assertEquals(stock.copy(categoryId = custom.id), store.state.value.stock.single())
        val groupedHistory = history.copy(id = "grouped-history", categoryId = custom.id)
        store.addItems(listOf(groupedHistory))

        list.performScrollToIndex(0)
        composeRule.onNodeWithText("分类").performClick()
        val delete = composeRule.onNodeWithContentDescription("删除周末备菜分类")
        delete.performScrollTo().performClick()
        composeRule.onNodeWithText("删除分类「周末备菜」？").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        assertTrue(custom in store.state.value.categories)
        assertEquals(custom.id, store.state.value.stock.single().categoryId)

        delete.performClick()
        composeRule.onNodeWithText("确认删除分类").performClick()
        assertFalse(custom in store.state.value.categories)
        assertEquals(stock, store.state.value.stock.single())
        assertEquals(setOf(history, groupedHistory.copy(categoryId = "meat")), store.state.value.consumed.toSet())
    }

    @Test
    fun fridgeUpdatesOpenScreenWhenTimeZoneMovesPastRecordedDate() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val originalZone = TimeZone.getDefault()
        val west = TimeZone.getTimeZone("Etc/GMT+12")
        val east = TimeZone.getTimeZone("Pacific/Kiritimati")
        val intake = FridgePhotoIntake()
        try {
            TimeZone.setDefault(west)
            val localDate = LocalDate.now()
            app.container.fridgeStore.addItems(listOf(
                FridgeBatchItem(ingredientName = "牛奶", expiresOnIsoDate = localDate.toString(), dateKind = FridgeDateKind.PACKAGE_EXPIRY),
            ))
            composeRule.setContent {
                CookTheme {
                    FridgeTabScreen(snapshot, app.container, intake, {}, {}, {}, onChooseGalleryPhoto = {})
                }
            }

            composeRule.onNodeWithText("用冰箱里的 1 种食材选菜").assertIsEnabled()
            composeRule.runOnIdle {
                TimeZone.setDefault(east)
                app.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
            }
            composeRule.onNodeWithText("先核对冰箱里的食材").assertIsNotEnabled()
            composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("过期", substring = true))
            composeRule.onNodeWithText("做菜").assertIsNotEnabled()
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun cookShortcutAddsOnlyUsableFridgeIngredients() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val today = LocalDate.now()
        app.container.fridgeStore.addItems(listOf(
            FridgeBatchItem(ingredientName = "酸奶", expiresOnIsoDate = today.minusDays(1).toString()),
            FridgeBatchItem(ingredientName = "番茄", expiresOnIsoDate = today.plusDays(2).toString()),
            FridgeBatchItem(ingredientName = "鸡蛋", expiresOnIsoDate = today.plusDays(4).toString()),
        ))
        val controller = CookSessionController()
        controller.setSelectedStuff(listOf("土豆"))
        composeRule.setContent {
            CookTheme {
                CookTabScreen(snapshot, controller, app.container, onOpenRecipe = {})
            }
        }

        val list = composeRule.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("一键选入"))
        composeRule.onNodeWithText("优先食用 番茄").assertIsDisplayed()
        composeRule.onNodeWithText("一键选入").performClick()
        assertEquals(listOf("土豆", "番茄", "鸡蛋"), controller.conditions.value.selectedStuff)
        list.performScrollToNode(hasText("已选入"))
        composeRule.onNodeWithText("已选入").assertIsNotEnabled()
    }

    @Test
    fun notificationPermissionFlow_workbenchShowsDenialWarningAndSchedulesTimer() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val targetRecipe = snapshot.data.recipes.first { snapshot.recipeText(it.id) != null }

        val shadowApp = Shadows.shadowOf(app)
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

        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(KitchenNotificationHelper.hasNotificationPermission(app))

        composeRule.onNodeWithText("继续").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("已设定后台精确提醒", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("当前系统未授权精确闹钟", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun workbenchTimerConfigures180MinutesAndSupportsResumeResetAndRestart() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val snapshot = loadReadySnapshot(app)
        val recipe = snapshot.data.recipes.first { snapshot.recipeText(it.id) != null }
        val store = app.container.workbenchStore
        composeRule.setContent {
            CookTheme {
                KitchenWorkbenchScreen(recipe.id, snapshot, app.container, onBack = {})
            }
        }

        composeRule.onNodeWithText("时长 5 分钟").performClick()
        val input = composeRule.onNodeWithText("分钟（1–180）")
        input.performTextReplacement("181")
        composeRule.onNodeWithText("完成").assertIsNotEnabled()
        input.performTextReplacement("180")
        composeRule.onNodeWithText("完成").performClick()
        composeRule.onNodeWithText("3:00:00").assertIsDisplayed()
        assertEquals(10_800, store.entry(recipe.id).timer.durationSeconds)

        composeRule.onNodeWithText("开始计时").performClick()
        composeRule.onNodeWithText("暂停").performClick()
        composeRule.onNodeWithText("已暂停").assertIsDisplayed()
        composeRule.onNodeWithText("继续").performClick()
        composeRule.onNodeWithText("计时中").assertIsDisplayed()
        composeRule.onNodeWithText("重置").performClick()
        composeRule.onNodeWithText("时长 180 分钟").assertIsDisplayed()

        composeRule.runOnIdle {
            val past = KitchenClockReading(System.currentTimeMillis() - 11_000_000L)
            store.updateTimer(recipe.id, store.entry(recipe.id).timer.start(past, recipe.name))
            KitchenTimerScheduler.reschedulePersistedTimers(app)
        }
        composeRule.onNodeWithText("计时结束").assertIsDisplayed()
        composeRule.onNodeWithText("再计一次").performClick()
        assertTrue(store.entry(recipe.id).timer.running)
        assertTrue(store.entry(recipe.id).timer.deadlineEpochMs!! > System.currentTimeMillis() + 10_790_000L)
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    fun workbenchKeepsCheckedStepsWhenSwitchingBetweenCompactAndTabletop() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val recipe = snapshot.data.recipes.first { snapshot.recipeText(it.id) != null }
        composeRule.setContent {
            CookTheme {
                KitchenWorkbenchScreen(
                    recipeId = recipe.id,
                    snapshot = snapshot,
                    container = app.container,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("本菜计时器").assertIsDisplayed()
        composeRule.onNodeWithText("做法").performClick()
        composeRule.onNodeWithText("制作步骤").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 步：", substring = true).performClick()

        composeRule.onNodeWithText("布局").performClick()
        composeRule.onNodeWithText("台面").performClick()
        val instructions = composeRule.onNodeWithText("已完成 1 / ", substring = true).assertIsDisplayed().fetchSemanticsNode()
        val timer = composeRule.onNodeWithText("本菜计时器").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(instructions.boundsInRoot.bottom < timer.boundsInRoot.top)
        assertEquals(1, app.container.workbenchStore.state.value.entries[recipe.id]?.checkedSteps?.size)

        composeRule.onNodeWithText("布局").performClick()
        composeRule.onNodeWithText("自动").performClick()
        composeRule.onNodeWithText("已完成 1 / ", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("本菜计时器").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    fun recipeStepMenuCopiesTheCurrentServingTextFromDetailAndWorkbench() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val recipeId = 680
        val source = RecipePresentation(requireNotNull(snapshot.recipeText(recipeId)))
        val step = source.steps.first { it.sourceBlock == 14 && it.sourceItem == 0 }
        val amounts = RecipeAmounts(source.allQuantities, 3)
        val display = requireNotNull(RecipeStepUsagePlan(source.steps, amounts, source.amountAnnotations).instruction(step)).text
        assertTrue("本步 低筋面粉 120 g" in display)
        val store = app.container.workbenchStore
        store.updateServings(recipeId, 3, source)
        val workbench = mutableStateOf(false)
        val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
        composeRule.setContent {
            CookTheme {
                if (workbench.value) KitchenWorkbenchScreen(recipeId, snapshot, app.container, onBack = {})
                else RecipeDetailScreen(recipeId, snapshot, app.container, onBack = {}, onOpenWorkbench = { workbench.value = true })
            }
        }
        val stepText = "第 ${step.number} 步：$display"
        val list = composeRule.onNode(hasScrollToNodeAction())
        val row = composeRule.onNodeWithText(stepText)
        fun copyWith(action: () -> Unit) {
            composeRule.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("Fixture", "unchanged")) }
            list.performScrollToNode(hasText(stepText))
            row.performScrollTo().assertIsOff()
            action()
            composeRule.runOnIdle { assertEquals("unchanged", clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
            composeRule.onNodeWithText("复制步骤").assertIsDisplayed().performClick()
            composeRule.runOnIdle {
                assertEquals(display, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                assertTrue(store.entry(recipeId).checkedSteps.isEmpty())
            }
        }

        copyWith { row.performTouchInput { longClick() } }
        list.performScrollToNode(hasText("进入厨房工作台"))
        composeRule.onNodeWithText("进入厨房工作台").performClick()
        composeRule.onNodeWithText("做法").performClick()
        copyWith { row.performSemanticsAction(SemanticsActions.OnLongClick) { it() } }
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    fun recipeDetailCanResetOnlyStepsAfterConfirmation() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val recipeId = 250
        val steps = RecipePresentation(requireNotNull(snapshot.recipeText(recipeId))).steps
        val store = app.container.workbenchStore
        store.toggleStep(recipeId, steps.first().id)
        store.toggleIngredient(recipeId, "catalog:鸡蛋")
        store.updateTimer(recipeId, RecipeTimerState().start(KitchenClockReading(System.currentTimeMillis()), "番茄炒蛋"))
        val original = store.entry(recipeId)
        composeRule.setContent {
            CookTheme { RecipeDetailScreen(recipeId, snapshot, app.container, onBack = {}, onOpenWorkbench = {}) }
        }
        val reset = composeRule.onNodeWithText("重置步骤")
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("已完成 1 / ${steps.size} 步"))
        reset.performScrollTo().performClick()
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(original, store.entry(recipeId))

        reset.performClick()
        composeRule.onNodeWithText("清除步骤勾选").performClick()
        composeRule.onNodeWithText("已完成 0 / ${steps.size} 步").assertIsDisplayed()
        assertEquals(original.copy(checkedSteps = emptySet()), store.entry(recipeId))
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    fun supplementaryReferencesJumpWithoutCompletingTheStepAndShareProgressBetweenScreens() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val recipeId = 250
        val steps = RecipePresentation(requireNotNull(snapshot.recipeText(recipeId))).steps
        val workbench = mutableStateOf(false)
        val store = app.container.workbenchStore
        steps.take(9).forEach { store.toggleStep(recipeId, it.id) }
        val checkedBefore = store.entry(recipeId).checkedSteps
        composeRule.setContent {
            CookTheme {
                if (workbench.value) KitchenWorkbenchScreen(recipeId, snapshot, app.container, onBack = {})
                else RecipeDetailScreen(recipeId, snapshot, app.container, onBack = {}, onOpenWorkbench = { workbench.value = true })
            }
        }
        val list = composeRule.onNode(hasScrollToNodeAction())
        val reference = "继续 第 10 步"
        val target = composeRule.onNodeWithText("第 10 步：", substring = true)
        val located = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已定位"))

        list.performScrollToNode(hasText(reference))
        composeRule.onNodeWithText(reference).performClick()
        target.assertIsDisplayed().assertIsOff()
        located.assertIsDisplayed()
        assertEquals(checkedBefore, store.entry(recipeId).checkedSteps)

        list.performScrollToIndex(0)
        composeRule.onNodeWithText("进入厨房工作台").performClick()
        composeRule.onNodeWithText("做法").performClick()
        composeRule.onNodeWithText("已完成 9 / 11 步").assertIsDisplayed()
        list.performScrollToNode(hasText(reference))
        composeRule.onNodeWithText(reference).performClick()
        target.assertIsDisplayed().assertIsOff()
        located.assertIsDisplayed()
        assertEquals(checkedBefore, store.entry(recipeId).checkedSteps)

        target.performClick()
        composeRule.onNodeWithText("已完成 10 / 11 步").assertIsDisplayed()
        composeRule.onNodeWithText("第 11 步：", substring = true).assertIsDisplayed().assertIsOff()
        assertEquals(checkedBefore + steps[9].id, store.entry(recipeId).checkedSteps)
        composeRule.onNodeWithText("继续第 11 步").performClick()
        located.assertIsDisplayed()
        assertEquals(checkedBefore + steps[9].id, store.entry(recipeId).checkedSteps)
    }

    @Test
    fun stepReferencesRequireOneWholeLineAndAnUnambiguousSourceNumber() {
        val step = RecipePresentation.Step("1|盛盘", 10, "盛盘", 3, 0)
        assertEquals(step, recipeStepDestination("• 继续　第 10 步。", listOf(step)))
        assertNull(recipeStepDestination("先调味再继续第 10 步", listOf(step)))
        assertNull(recipeStepDestination("第 11 步", listOf(step)))
        assertNull(recipeStepDestination("第 10 步", listOf(step, step.copy(id = "2|盛盘"))))
    }

    @Test
    fun ordinaryRecipeIgnoresTheBrowsingGoalAndUnavailableReviewHasAnExplicitFallback() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val unavailable = snapshot.copy(dietaryCatalog = null)
        val goal = DietaryGoal.LESS_ADDED_SUGAR
        app.container.preferencesStore.selectDietaryGoal(goal)
        val requestedGoal = mutableStateOf<DietaryGoal?>(null)
        composeRule.setContent {
            CookTheme {
                val activeGoal = requestedGoal.value
                RecipeDetailScreen(
                    333, if (activeGoal == null) snapshot else unavailable, app.container,
                    onBack = {}, onOpenWorkbench = {}, dietaryGoal = activeGoal,
                )
            }
        }
        val list = composeRule.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("图文做法正文"))
        composeRule.onNodeWithText("图文做法正文").assertIsDisplayed()
        list.performScrollToNode(hasText("预估卡路里：224 大卡"))
        composeRule.onNodeWithText("预估卡路里：224 大卡").assertIsDisplayed()
        composeRule.onNodeWithText("查看已核对做法").assertDoesNotExist()

        composeRule.runOnIdle { requestedGoal.value = goal }
        val fallback = "这道菜的饮食目标核对当前不可用。仍可查看普通菜谱。"
        list.performScrollToNode(hasText(fallback))
        composeRule.onNodeWithText(fallback).assertIsDisplayed()
        composeRule.onNodeWithText("查看已核对做法").assertDoesNotExist()
        list.performScrollToNode(hasText("图文做法正文"))
        composeRule.onNodeWithText("图文做法正文").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    fun institutionalRecipeExposesExactSourcesAndVerifiedPreparationAtLargeFontSize() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val catalog = requireNotNull(snapshot.institutionalLowGICatalog)
        val recipe = requireNotNull(snapshot.recipe(881))
        val entry = requireNotNull(catalog.entry(recipe))
        val controller = CookSessionController().apply { setKeyword(recipe.name) }
        val opened = mutableStateOf(false)
        val workbench = mutableStateOf(false)
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                CookTheme {
                    when {
                        workbench.value -> KitchenWorkbenchScreen(recipe.id, snapshot, app.container, onBack = {})
                        opened.value -> RecipeDetailScreen(recipe.id, snapshot, app.container, onBack = {}, onOpenWorkbench = { workbench.value = true })
                        else -> CookTabScreen(snapshot, controller, app.container, onOpenRecipe = {
                            assertEquals(recipe.id, it.recipeId)
                            opened.value = true
                        })
                    }
                }
            }
        }
        val list = composeRule.onNode(hasScrollToNodeAction())
        fun reveal(label: String): SemanticsNodeInteraction {
            list.performScrollToNode(hasText(label))
            return composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        fun opens(label: String, url: String) {
            reveal(label).performClick()
            val intent = Shadows.shadowOf(composeRule.activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
        }

        reveal("📖 机构低 GI 食谱").performClick()
        reveal(entry.sourceRecipeTitle).performClick()
        reveal("发布机构 ${InstitutionalLowGICatalog.PUBLISHER}")
        reveal("作者 ${entry.author}")
        reveal("核对日期 ${entry.reviewedAt}")
        reveal(entry.requirements)
        reveal(InstitutionalLowGICatalog.EXPLANATION)
        opens("打开机构原方（英文，需联网）", entry.sourceURL)
        opens("机构版权与食谱分析范围（英文）", InstitutionalLowGICatalog.AUTHORITY_URL)
        reveal("中文权威参考")
        catalog.chineseReferences.forEach { reference ->
            opens(reference.title, reference.url)
            reveal("${reference.publisher} · ${reference.scope}")
        }

        reveal("进入厨房工作台").performClick()
        opens("打开机构原方（英文，需联网）", entry.sourceURL)
        composeRule.onNodeWithText("备料与计时").performClick()
        reveal("原方备料")
        val ingredient = "卡菲尔青柠叶"
        assertTrue(ingredient in entry.ingredients && ingredient !in recipe.stuff)
        reveal(ingredient).assertIsOff().performClick()
        reveal(ingredient).assertIsOn()
        assertTrue("catalog:$ingredient" in app.container.workbenchStore.entry(recipe.id).checkedIngredients)
    }

    @Test
    fun absentInstitutionalCatalogLeavesOnlyTheOrdinaryRecipeLink() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app).copy(institutionalLowGICatalog = null)
        val recipe = requireNotNull(snapshot.recipe(881))
        assertTrue(InstitutionalLowGICatalog.TAG in recipe.tags)
        composeRule.setContent {
            CookTheme { RecipeDetailScreen(recipe.id, snapshot, app.container, onBack = {}, onOpenWorkbench = {}) }
        }
        composeRule.onNodeWithText(InstitutionalLowGICatalog.TAG).assertDoesNotExist()
        composeRule.onNodeWithText("打开机构原方（英文，需联网）").assertDoesNotExist()
        val label = "打开图文原链接"
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).performClick()
        assertEquals(recipe.link, Shadows.shadowOf(composeRule.activity).nextStartedActivity.dataString)
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    fun workbenchKeepsSourcePreparationAndResetsChecklistsIndependently() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val recipeId = 680
        val source = RecipePresentation(requireNotNull(snapshot.recipeText(recipeId)))
        val flour = source.allQuantities.first { it.name == "低筋面粉" }
        val chili = source.ingredients.first { it.startsWith("辣椒粉") }
        val thermometer = "厨房温度计"
        val deepPot = source.ingredients.first { it.startsWith("深锅") }
        val store = app.container.workbenchStore
        val timer = RecipeTimerState().start(KitchenClockReading(System.currentTimeMillis()), "炸洋葱花")
        store.updateTimer(recipeId, timer)
        store.toggleIngredient(recipeId, flour.preparationKey)
        store.toggleIngredient(recipeId, "catalog:$chili")
        val workbench = mutableStateOf(true)
        composeRule.setContent {
            CookTheme {
                if (workbench.value) KitchenWorkbenchScreen(recipeId, snapshot, app.container, onBack = { workbench.value = false })
                else RecipeDetailScreen(recipeId, snapshot, app.container, onBack = {}, onOpenWorkbench = { workbench.value = true })
            }
        }
        val list = composeRule.onNode(hasScrollToNodeAction())
        fun reveal(label: String, substring: Boolean = false): SemanticsNodeInteraction {
            list.performScrollToNode(hasText(label, substring = substring))
            return composeRule.onNodeWithText(label, substring = substring).performScrollTo().assertIsDisplayed()
        }

        reveal("已备好 2 / ", substring = true)
        reveal("低筋面粉").assertIsOn()
        reveal(chili).assertIsOn()
        reveal("厨具")
        reveal(deepPot).assertIsOff()
        reveal(thermometer).assertIsOff().performClick()
        reveal("漏勺").assertIsOff()
        composeRule.onNodeWithText("炒锅").assertDoesNotExist()
        reveal("已备好 3 / ", substring = true)
        list.performScrollToNode(hasContentDescription("增加份数"))
        composeRule.onNodeWithContentDescription("增加份数").performClick()
        reveal("工作台份数 2 份")
        reveal("低筋面粉").assertIsOff()
        reveal(chili).assertIsOff()
        reveal(thermometer).assertIsOn()
        assertEquals(setOf("catalog:$thermometer"), store.entry(recipeId).checkedIngredients)

        composeRule.onNodeWithText("返回").performClick()
        reveal("当前：2 份", substring = true)
        reveal("进入厨房工作台").performClick()
        val step = source.steps.first()
        composeRule.runOnIdle { store.toggleStep(recipeId, step.id) }
        reveal("重新备料").performClick()
        composeRule.onNodeWithText("清除备料勾选").performClick()
        reveal("已备好 0 / ", substring = true)
        assertEquals(setOf(step.id), store.entry(recipeId).checkedSteps)
        assertEquals(timer, store.entry(recipeId).timer)

        reveal(thermometer).performClick()
        composeRule.onNodeWithText("布局").performClick()
        composeRule.onNodeWithText("重置步骤").performClick()
        composeRule.onNodeWithText("清除步骤勾选").performClick()
        reveal(thermometer).assertIsOn()
        assertTrue(store.entry(recipeId).checkedSteps.isEmpty())
        assertEquals(timer, store.entry(recipeId).timer)
    }

    @Test
    fun workbenchOriginalStepOpensGuidanceSettingsWithoutChangingCookingState() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val recipe = snapshot.data.recipes.first { it.id == 680 }
        val initial = app.container.workbenchStore.entry(recipe.id)
        var openedSettings = false
        composeRule.setContent {
            CookTheme {
                KitchenWorkbenchScreen(
                    recipe.id, snapshot, app.container, onBack = {},
                    onOpenAiSettings = { openedSettings = true },
                )
            }
        }

        composeRule.onNodeWithText("做法").performClick()
        composeRule.onAllNodesWithText("原文核对")[0].performScrollTo().performClick()
        composeRule.onNodeWithText("第 1 步 · 分步指导").assertIsDisplayed()
        composeRule.onNodeWithText("讲解这一步").assertDoesNotExist()
        composeRule.onNodeWithText("AI 设置").performClick()
        composeRule.runOnIdle {
            assertTrue(openedSettings)
            assertEquals(initial, app.container.workbenchStore.entry(recipe.id))
        }
    }

    @Test
    fun fridgeReminderWaitsForOptInAndPermissionAndPostsOnceWithCurrentStock() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val clock = ZonedDateTime.parse("2026-11-16T08:00+08:00[Asia/Shanghai]")
        val today = clock.toLocalDate()
        val minuteNine = clock.plusHours(1)
        val alarms = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java))
        val notifications = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        val prefs = app.container.preferencesStore
        val fridge = app.container.fridgeStore
        fridge.addItems(listOf(
            FridgeBatchItem(ingredientName = "牛奶", expiresOnIsoDate = today.toString(), dateKind = FridgeDateKind.PACKAGE_EXPIRY),
            FridgeBatchItem(ingredientName = "番茄", expiresOnIsoDate = today.plusDays(1).toString()),
            FridgeBatchItem(ingredientName = "胡萝卜", expiresOnIsoDate = today.plusDays(1).toString()),
        ))
        val shadowApp = Shadows.shadowOf(app)
        assertFalse(prefs.current.fridgeRemindersEnabled)
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        FridgeDailyReminderScheduler.reschedule(app, clock)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        shadowApp.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs.setFridgeRemindersEnabled(true)
        FridgeDailyReminderScheduler.reschedule(app, clock)
        assertTrue(alarms.scheduledAlarms.isEmpty())

        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        FridgeDailyReminderScheduler.reschedule(app, clock)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(minuteNine.toInstant().toEpochMilli(), alarm.triggerAtTime)
        val intent = Intent(Shadows.shadowOf(alarm.operation).savedIntent)

        prefs.setFridgeReminderMinutes(10 * 60)
        FridgeDailyReminderScheduler.onAlarm(app, intent, minuteNine)
        assertTrue(notifications.allNotifications.isEmpty())
        val updated = alarms.scheduledAlarms.single()
        val minuteTen = clock.plusHours(2)
        assertEquals(minuteTen.toInstant().toEpochMilli(), updated.triggerAtTime)
        val updatedIntent = Intent(Shadows.shadowOf(updated.operation).savedIntent)
        fridge.markConsumed(fridge.state.value.stock.single { it.ingredientName == "胡萝卜" }.id)

        shadowApp.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        FridgeDailyReminderScheduler.onAlarm(app, updatedIntent, minuteTen)
        assertNull(prefs.current.lastFridgeReminderDate)
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        FridgeDailyReminderScheduler.reschedule(app, minuteTen.plusMinutes(12))
        assertEquals(minuteTen.toInstant().toEpochMilli(), alarms.scheduledAlarms.single().triggerAtTime)
        FridgeDailyReminderScheduler.onAlarm(app, updatedIntent, minuteTen.plusMinutes(12))
        val notification = notifications.allNotifications.single()
        assertEquals("牛奶今天到期，番茄建议明天吃完", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(KitchenNotificationHelper.ACTION_OPEN_FRIDGE, Shadows.shadowOf(notification.contentIntent).savedIntent.action)
        assertEquals(today.toString(), prefs.current.lastFridgeReminderDate)
        assertEquals(minuteTen.plusDays(1).toInstant().toEpochMilli(), alarms.scheduledAlarms.single().triggerAtTime)

        app.getSystemService(NotificationManager::class.java).cancelAll()
        FridgeDailyReminderScheduler.onAlarm(app, updatedIntent, minuteTen.plusMinutes(18))
        assertTrue(notifications.allNotifications.isEmpty())
        prefs.setFridgeRemindersEnabled(false)
        FridgeDailyReminderScheduler.reschedule(app, minuteTen)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun fridgeReminderPlansFutureLocalDaysAcrossTimeZoneChangesAndDst() {
        val now = ZonedDateTime.parse("2026-11-16T09:01+08:00[Asia/Shanghai]")
        val expiry = now.toLocalDate().plusDays(1)
        val stock = listOf(
            FridgeBatchItem(ingredientName = "番茄", expiresOnIsoDate = expiry.toString()),
            FridgeBatchItem(ingredientName = "梨", expiresOnIsoDate = now.toLocalDate().toString(), consumedAtMs = 1L),
            FridgeBatchItem(ingredientName = "米饭", expiresOnIsoDate = "invalid"),
            FridgeBatchItem(ingredientName = "鸡蛋", expiresOnIsoDate = now.toLocalDate().minusDays(1).toString()),
        )
        val shanghai = FridgeDailyReminderScheduler.nextReminderAt(stock, 540, now, null)
        assertEquals(ZonedDateTime.parse("2026-11-16T09:00+08:00[Asia/Shanghai]"), shanghai)
        assertEquals(
            ZonedDateTime.parse("2026-11-17T09:00+08:00[Asia/Shanghai]"),
            FridgeDailyReminderScheduler.nextReminderAt(stock, 540, now, now.toLocalDate().toString()),
        )
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(
            ZonedDateTime.parse("2026-11-16T09:00+09:00[Asia/Tokyo]"),
            FridgeDailyReminderScheduler.nextReminderAt(stock, 540, now.withZoneSameInstant(tokyo), null),
        )
        assertEquals(shanghai, FridgeDailyReminderScheduler.nextReminderAt(stock, 540, now, expiry.toString()))
        assertNull(FridgeDailyReminderScheduler.nextReminderAt(stock.drop(1), 540, now.minusHours(1), null))

        val spring = ZonedDateTime.parse("2026-03-08T01:15-05:00[America/New_York]")
        val apple = FridgeBatchItem(ingredientName = "苹果", expiresOnIsoDate = spring.toLocalDate().toString())
        assertEquals(
            ZonedDateTime.parse("2026-03-08T03:00-04:00[America/New_York]"),
            FridgeDailyReminderScheduler.nextReminderAt(listOf(apple), 150, spring, null),
        )
    }

    @Test
    fun exactAlarmRevocation_reschedulePersistedTimersUsesInexactFallback() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = Shadows.shadowOf(alarmManager)

        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val deadline = System.currentTimeMillis() + 180_000L
        val timer = RecipeTimerState(
            running = true,
            deadlineEpochMs = deadline,
            recipeName = "测试菜谱",
        )
        app.container.workbenchStore.updateTimer(42, timer)
        val key = requireNotNull(timer.alarmKey)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        try {
            assertFalse(KitchenTimerScheduler.canScheduleExactAlarms(app))
            assertEquals(
                AlarmSchedulePrecision.INEXACT_FALLBACK,
                KitchenTimerScheduler.schedule(app, recipeId = 42, recipeName = "菜谱 #42", timer = timer),
            )

            // Robolectric returns a copy; cancel the real alarm before restoring it from disk.
            KitchenTimerScheduler.cancel(app, recipeId = 42, key = key)
            assertFalse(shadowAlarmManager.scheduledAlarms.any { it.triggerAtTime == deadline })
            KitchenTimerScheduler.KitchenTimerReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

            val scheduled = shadowAlarmManager.scheduledAlarms
            assertTrue("Expected rescheduled inexact fallback alarm", scheduled.isNotEmpty())
            val restored = scheduled.single { it.triggerAtTime == deadline && it.type == AlarmManager.RTC_WAKEUP }
            assertEquals("测试菜谱", Shadows.shadowOf(restored.operation).savedIntent.getStringExtra("recipe_name"))
        } finally {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            KitchenTimerScheduler.cancel(app, recipeId = 42, key = key)
        }
    }

    @Test
    fun scheduledBackupCanFinishWhenTheSystemRemovesTheExactAlarm() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 4)
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        val alarms = Shadows.shadowOf(alarmManager)
        val notifications = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        val store = app.container.workbenchStore
        val now = KitchenTimerScheduler.clockReading(app)
        val timer = RecipeTimerState(durationSeconds = 60).start(now, "测试菜谱")
        store.updateTimer(42, timer)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        try {
            assertEquals(AlarmSchedulePrecision.EXACT, KitchenTimerScheduler.schedule(app, 42, "测试菜谱", timer, now))
            val registered = alarms.scheduledAlarms.filter { it.triggerAtTime == timer.deadlineElapsedMs }
            assertEquals(2, registered.size)
            assertTrue(registered.all { it.type == AlarmManager.ELAPSED_REALTIME_WAKEUP })
            val (backups, primary) = registered.partition {
                Shadows.shadowOf(it.operation).savedIntent.data?.schemeSpecificPart?.endsWith("/backup") == true
            }
            val backup = Intent(Shadows.shadowOf(backups.single().operation).savedIntent)
            val exact = Intent(Shadows.shadowOf(primary.single().operation).savedIntent)

            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            alarmManager.cancel(requireNotNull(primary.single().operation))
            assertEquals(listOf(backups.single().operation), alarms.scheduledAlarms.map { it.operation })
            ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
            val receiver = KitchenTimerScheduler.KitchenTimerReceiver()
            receiver.onReceive(app, backup)
            assertEquals(RecipeTimerState.Phase.FINISHED, store.entry(42).timer.phase(KitchenTimerScheduler.clockReading(app)))
            assertEquals("计时结束：测试菜谱", notifications.allNotifications.single().extras.getString(Notification.EXTRA_TITLE))
            assertTrue(alarms.scheduledAlarms.isEmpty())

            app.getSystemService(NotificationManager::class.java).cancelAll()
            receiver.onReceive(app, exact)
            assertTrue(notifications.allNotifications.isEmpty())
        } finally {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            KitchenTimerScheduler.cancel(app, 42, requireNotNull(timer.alarmKey))
        }
    }

    @Test
    fun staleTimerBroadcastCannotFinishAnotherRunAndRestorePostsOnlyOnce() {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val alarms = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java))
        val store = app.container.workbenchStore
        val now = System.currentTimeMillis()
        val recipeId = 42
        val oldDeadline = now - 120_000
        val currentDeadline = now - 30_000
        val previous = RecipeTimerState(running = true, deadlineEpochMs = oldDeadline)
        KitchenTimerScheduler.schedule(app, recipeId, "已重置的计时", previous)
        val stale = Intent(Shadows.shadowOf(alarms.scheduledAlarms.first { it.triggerAtTime == oldDeadline }.operation).savedIntent)
        KitchenTimerScheduler.cancel(app, recipeId, requireNotNull(previous.alarmKey))

        val active = RecipeTimerState(
            running = true,
            deadlineEpochMs = currentDeadline,
            recipeName = "测试菜谱",
        )
        store.updateTimer(recipeId, active)
        val receiver = KitchenTimerScheduler.KitchenTimerReceiver()
        receiver.onReceive(app, stale)
        assertEquals(active, store.entry(recipeId).timer)
        assertTrue(Shadows.shadowOf(manager).allNotifications.isEmpty())

        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(RecipeTimerState.Phase.FINISHED, store.entry(recipeId).timer.phase(KitchenClockReading(now)))
        assertEquals("计时结束：测试菜谱", Shadows.shadowOf(manager).allNotifications.single().extras.getString(Notification.EXTRA_TITLE))
        manager.cancelAll()
        receiver.onReceive(app, stale)
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(Shadows.shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun fridgePhotoPipeline_inMemoryDecodeOcrParseAndRecipeMatch_leavesZeroTempFiles() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<CookApplication>()
        val snapshot = loadReadySnapshot(app)
        val knownIngredients = FridgeLabelParser.ingredientVocabulary(snapshot.data.food, snapshot.data.normalizer)

        val filesBefore = app.filesDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        val cacheBefore = app.cacheDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()

        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        val pngBytes = ByteArrayOutputStream().use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
        bitmap.recycle()

        var observedDimensions: Pair<Int, Int>? = null
        var observedRotation: Int? = null
        FridgePhotoInspector.textRecognizerOverride = { bmp, rotation ->
            observedDimensions = bmp.width to bmp.height
            observedRotation = rotation
            "新鲜西红柿 250g\n保质期至 2026-11-17\n请勿放入微波炉"
        }
        try {
            val recognition = FridgePhotoInspector.recognizeInMemoryBytes(pngBytes, knownIngredients, captureRotation = 90) { _, _ ->
                error("Packaging with a known ingredient should bypass the image model")
            }
            assertEquals(240 to 160, observedDimensions)
            assertEquals(90, observedRotation)
            assertEquals(240, recognition.summary.width)
            assertEquals(160, recognition.summary.height)
            val jpeg = FridgeVisionService().prepare(pngBytes, captureRotation = 90)
            val upload = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            assertEquals(160 to 240, upload.width to upload.height)
            upload.recycle()

            val names = snapshot.data.normalizer.normalize(
                FridgeLabelParser.matchKnownNames(recognition.recognizedText, knownIngredients),
            )
            val scan = FridgeLabelParser.parse(
                text = recognition.recognizedText,
                names = names,
                today = LocalDate.of(2026, 11, 16),
            )
            assertEquals(listOf("番茄"), scan.names)
            assertEquals(LocalDate.of(2026, 11, 17), scan.expiryDay)
            assertFalse(scan.suggestsBatchReview)

            val matched = snapshot.recommender.recommend(
                RecommendationOptions(stuff = scan.names, mode = RecommendationMode.LOOSE),
            )
            assertTrue("Expected recipes using scanned ingredient", matched.isNotEmpty())
            assertTrue(matched.any { "番茄" in it.recipe.stuff })

            FridgePhotoInspector.textRecognizerOverride = { _, _ -> "请勿放入微波炉\n保质期至 2026-11-17" }
            val produce = FridgePhotoInspector.recognizeInMemoryBytes(pngBytes, knownIngredients) { _, _ ->
                FridgeProduceMatch(listOf("fruit" to .99f, "apple" to .92f, "banana" to .12f))
            }
            val produceNames = FridgeLabelParser.matchKnownNames(
                produce.recognizedText, knownIngredients, produce.produce.classifications,
            )
            assertEquals(listOf("苹果"), produceNames)
            assertEquals(LocalDate.of(2026, 11, 17), FridgeLabelParser.parse(produce.recognizedText, produceNames).expiryDay)

            val noMatch = FridgePhotoInspector.recognizeInMemoryBytes(pngBytes, knownIngredients) { _, _ ->
                FridgeProduceMatch(suggestions = listOf("apple"))
            }
            val unavailable = FridgePhotoInspector.recognizeInMemoryBytes(pngBytes, knownIngredients) { _, _ ->
                throw FridgeProduceClassifier.ModelUnavailable()
            }
            assertFalse(noMatch.modelUnavailable)
            assertTrue(noMatch.produce.classifications.isEmpty())
            assertEquals(listOf("苹果"), FridgeLabelParser.suggestedProduceNames(noMatch.produce.suggestions))
            assertTrue(unavailable.modelUnavailable)
            assertTrue(unavailable.produce.classifications.isEmpty())
            assertEquals(LocalDate.of(2026, 11, 17), FridgeLabelParser.parse(unavailable.recognizedText, emptyList()).expiryDay)

            FridgePhotoInspector.textRecognizerOverride = { _, _ -> error("Fixture OCR failure") }
            val fallback = FridgePhotoInspector.recognizeInMemoryBytes(pngBytes, knownIngredients) { _, _ ->
                FridgeProduceMatch(listOf("watermelon" to .95f))
            }
            assertEquals("", fallback.recognizedText)
            assertEquals(listOf("西瓜"), FridgeLabelParser.imageNames(fallback.produce.classifications))
        } finally {
            FridgePhotoInspector.textRecognizerOverride = null
        }

        val filesAfter = app.filesDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        val cacheAfter = app.cacheDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        assertEquals(filesBefore, filesAfter)
        assertEquals(cacheBefore, cacheAfter)
    }

    @Test
    fun decideRandomSheet_keepsConstantPagerHeightAcrossShortAndLongRecipes_andSupportsHistory() {
        val shortRecipe = Recipe(
            id = 101,
            name = "白灼菜心",
            stuff = listOf("菜心"),
            emojis = listOf("🥬"),
        )
        val longRecipe = Recipe(
            id = 102,
            name = "秘制超级豪华全家福麻辣香锅炖杂烩煲",
            stuff = listOf(
                "土豆", "莲藕", "木耳", "腐竹", "午餐肉",
                "肥牛", "大虾", "鱿鱼", "千叶豆腐", "宽粉",
                "金针菇", "香菜", "葱", "姜", "蒜",
            ),
            emojis = listOf("🍲"),
            difficulty = "困难",
            tools = listOf("一口大锅", "砂锅"),
        )
        val matchesById = mapOf(
            longRecipe.id to RecipeMatch(
                recipe = longRecipe,
                sourceIndex = 0,
                requiredStuff = longRecipe.stuff,
                matchedStuff = listOf("土豆"),
                pantryStuff = emptyList(),
                missingStuff = longRecipe.stuff.drop(1),
                optionalStuff = emptyList(),
            ),
        )
        var openedId: Int? = null

        composeRule.setContent {
            CookTheme {
                DecideRandomDialog(
                    recipes = listOf(shortRecipe, longRecipe),
                    matchesById = matchesById,
                    contextSummary = "已有食材：土豆",
                    onOpenRecipe = { openedId = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()
        composeRule.onNodeWithText("上一道").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("下一道").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("换一道").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("看做法").performScrollTo().assertIsDisplayed().assertIsEnabled()

        val pager0 = composeRule.onNodeWithTag("decide.pager").performScrollTo().fetchSemanticsNode()
        val heightPage0 = pager0.size.height
        assertTrue("Expected positive pager height, got $heightPage0", heightPage0 > 0)
        val page0IsShort = pager0.config[SemanticsProperties.ContentDescription].single().startsWith(shortRecipe.name)

        composeRule.onNodeWithText("下一道").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("上一道").assertIsEnabled()

        val heightPage1 = composeRule.onNodeWithTag("decide.pager").fetchSemanticsNode().size.height
        assertEquals(heightPage0, heightPage1)

        composeRule.onNodeWithText("换一道").performScrollTo().performClick()
        composeRule.onNodeWithText("3 / 3").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("上一道").performScrollTo().performClick()
        composeRule.onNodeWithText("2 / 3").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("上一道").performScrollTo().performClick()
        composeRule.onNodeWithText("1 / 3").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("下一道").performScrollTo().performClick()
        composeRule.onNodeWithText("2 / 3").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("上一道").performScrollTo().performClick()
        composeRule.onNodeWithText("1 / 3").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("看做法").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(if (page0IsShort) shortRecipe.id else longRecipe.id, openedId)
    }

    @Test
    fun decideRandomSheet_accessibleCardUsesOneNodeAndCanRevealMoreResults() {
        val firstRecipe = Recipe(id = 1, name = "蒸蛋羹", stuff = listOf("鸡蛋"))
        val nextRecipe = Recipe(id = 2, name = "清炒丝瓜", stuff = listOf("丝瓜"), tools = listOf("一口大锅"))
        val filtersOpen = mutableStateOf(false)
        val recipes = mutableStateOf(listOf(firstRecipe, nextRecipe))
        composeRule.setContent {
            CookTheme {
                DecideRandomDialog(
                    recipes = recipes.value,
                    avoidFirstId = nextRecipe.id,
                    filtersOpen = filtersOpen.value,
                    onOpenRecipe = {},
                    onDismiss = {},
                )
            }
        }
        val card = composeRule.onNodeWithTag("decide.pager")
        val firstCard = card.fetchSemanticsNode()
        assertEquals(listOf("蒸蛋羹，用料：鸡蛋"), firstCard.config[SemanticsProperties.ContentDescription])
        assertEquals("第 1 道，共 1 道", firstCard.config[SemanticsProperties.StateDescription])
        val next = firstCard.config[SemanticsActions.CustomActions].single()
        assertEquals("下一道", next.label)
        composeRule.runOnIdle { assertTrue(next.action()) }

        val secondCard = card.fetchSemanticsNode()
        assertEquals(firstCard.id, secondCard.id)
        assertEquals(listOf("清炒丝瓜，炒锅，用料：丝瓜"), secondCard.config[SemanticsProperties.ContentDescription])
        assertEquals("第 2 道，共 2 道", secondCard.config[SemanticsProperties.StateDescription])
        composeRule.onAllNodesWithText(firstRecipe.name).assertCountEquals(0)
        composeRule.onAllNodesWithText(nextRecipe.name).assertCountEquals(0)

        val previous = secondCard.config[SemanticsActions.CustomActions].first { it.label == "上一道" }
        composeRule.runOnIdle { assertTrue(previous.action()) }
        assertEquals("第 1 道，共 2 道", card.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        composeRule.runOnIdle { filtersOpen.value = true }
        assertTrue(card.fetchSemanticsNode().config[SemanticsActions.CustomActions].isEmpty())
        composeRule.onNodeWithText("下一道").assertIsNotEnabled()
        composeRule.runOnIdle {
            filtersOpen.value = false
            recipes.value = listOf(firstRecipe)
        }
        assertTrue(card.fetchSemanticsNode().config[SemanticsActions.CustomActions].isEmpty())
        composeRule.onNodeWithText("下一道").assertIsNotEnabled()
        composeRule.onNodeWithText("看做法").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w1100dp-h960dp")
    fun decideRandomSheet_keepsTheCurrentCardAcrossDiscoveryLayouts() {
        val recipes = listOf(Recipe(id = 1, name = "蒸蛋羹"), Recipe(id = 2, name = "清炒丝瓜"))
        val fontScale = mutableStateOf(1.5f)
        composeRule.setContent {
            CookTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                    CookDiscoveryLayout(overlay = {
                        DecideRandomDialog(
                            recipes = recipes,
                            avoidFirstId = 2,
                            onOpenRecipe = {},
                            onDismiss = {},
                        )
                    }) { sidebarWidth, _ -> Text(if (sidebarWidth == null) "单栏" else "双栏") }
                }
            }
        }
        composeRule.onNodeWithText("单栏").assertExists()
        composeRule.onNodeWithText("下一道").performScrollTo().performClick()
        val card = composeRule.onNodeWithTag("decide.pager")
        assertEquals(listOf("清炒丝瓜"), card.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals("第 2 道，共 2 道", card.fetchSemanticsNode().config[SemanticsProperties.StateDescription])

        composeRule.runOnIdle { fontScale.value = 1f }
        composeRule.onNodeWithText("双栏").assertExists()
        assertEquals(listOf("清炒丝瓜"), card.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals("第 2 道，共 2 道", card.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        composeRule.onNodeWithText("上一道").performScrollTo().performClick()
        assertEquals(listOf("蒸蛋羹"), card.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals("第 1 道，共 2 道", card.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test
    fun decideRandomSheet_stagesANewRecipeWhenThePagerSettlesBeforeHistoryUpdates() {
        val pool = listOf(Recipe(id = 1, name = "蒸蛋羹"), Recipe(id = 2, name = "清炒丝瓜")).associateBy { it.id }
        composeRule.runOnIdle {
            val deck = mutableStateOf(intArrayOf(1, 2))
            val pager = PagerState(currentPage = 1, pageCount = { deck.value.size })
            val history = mutableIntStateOf(1)
            val pages = RandomRecipePages(deck, pager, history)

            assertEquals(2, pages.stageReroll(pool))
            assertEquals(listOf(1, 2, 1), deck.value.toList())
            assertEquals(1, pager.settledPage)
        }
    }

    @Test
    @Config(qualifiers = "w375dp-h820dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun decideRandomSheet_keepsTheFullFilterSummaryAtTheLargestFontSize() {
        composeRule.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        composeRule.activityRule.scenario.recreate()
        val summary = "食材：熟番茄、鸡蛋、洋葱、胡萝卜、玉米、茄子 · 厨具：电饭煲、炒锅 · 忌口：芝麻、花生、香菜 · 不吃辣 · 清蒸、水煮 · 难度：简单"
        val recipes = mutableStateOf(listOf(Recipe(id = 250, name = "番茄炒蛋")))
        var openedId: Int? = null
        composeRule.setContent {
            CookTheme {
                DecideRandomDialog(
                    recipes = recipes.value,
                    contextSummary = summary,
                    onOpenRecipe = { openedId = it },
                    onDismiss = {},
                )
            }
        }
        fun assertSummaryFits() {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(summary).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                assertTrue(it(layouts))
            }
            val layout = layouts.single()
            assertEquals(2f, layout.layoutInput.density.fontScale, 0f)
            assertTrue("Expected multiple wrapped lines, got $layout", layout.lineCount > 2)
            assertFalse(layout.hasVisualOverflow)
        }
        assertSummaryFits()
        composeRule.onNodeWithText("看做法").performScrollTo().performClick()
        assertEquals(250, openedId)

        composeRule.runOnIdle { recipes.value = emptyList() }
        assertSummaryFits()
        composeRule.onNodeWithText("当前候选池为空，请放宽条件后再随机决定。").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun decideRandomSheet_emptyPoolShowsEmptyMessageAndHidesOpenAction() {
        composeRule.setContent {
            CookTheme {
                DecideRandomDialog(
                    recipes = emptyList(),
                    contextSummary = "已有食材：龙虾",
                    onOpenRecipe = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前候选池为空，请放宽条件后再随机决定。").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("看做法").fetchSemanticsNodes().isEmpty())
    }
}
