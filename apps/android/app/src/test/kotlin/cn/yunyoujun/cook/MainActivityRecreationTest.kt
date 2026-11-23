package cn.yunyoujun.cook

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.LinkAnnotation
import androidx.lifecycle.ViewModelProvider
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.sync.CatalogCategory
import cn.yunyoujun.cook.core.sync.CatalogEntry
import cn.yunyoujun.cook.core.sync.PersonalCatalog
import cn.yunyoujun.cook.core.sync.PrivateRecipe
import cn.yunyoujun.cook.core.sync.PrivateRecipeFile
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.FridgeDateKind
import cn.yunyoujun.cook.data.local.FridgeExpiryBasis
import cn.yunyoujun.cook.data.local.FridgeStore
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.FridgePhotoInspector
import cn.yunyoujun.cook.data.local.KitchenClockReading
import cn.yunyoujun.cook.data.local.RecipeTimerState
import cn.yunyoujun.cook.data.remote.LoadedRecipeImage
import cn.yunyoujun.cook.data.repository.CatalogLoadState
import cn.yunyoujun.cook.data.repository.CookDiscoveryScene
import cn.yunyoujun.cook.platform.KitchenTimerScheduler
import cn.yunyoujun.cook.ui.screens.recipePhotos
import cn.yunyoujun.cook.ui.state.CookSessionConditions
import cn.yunyoujun.cook.ui.state.CookSessionController
import cn.yunyoujun.cook.ui.state.FridgePhotoIntake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityRecreationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun personalCatalogFindsAndHidesBuiltInsBeyondTheFirstTwelve() {
        val container = (composeRule.activity.application as CookApplication).container
        openPersonalCatalog()
        val snapshot = (container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        val store = container.personalCatalogStore
        val search = composeRule.onNodeWithText("搜索当前分类")
        val target = "菌菇"
        assertTrue(snapshot.data.food.vegetable.indexOfFirst { it.name == target } >= 12)
        val list = composeRule.onNodeWithTag("personalCatalog.entries")
        list.performScrollToNode(hasContentDescription("隐藏$target"))
        composeRule.onNodeWithContentDescription("隐藏$target").assertIsDisplayed()
        list.performScrollToIndex(0)
        search.performTextReplacement(target)
        composeRule.onNodeWithContentDescription("隐藏$target").assertIsDisplayed().performClick()
        assertTrue(store.state.value.toDocument().applyingTo(snapshot.data.food).vegetable.none { it.name == target })

        composeRule.activityRule.scenario.recreate()
        awaitText("搜索当前分类")
        composeRule.onNodeWithContentDescription("恢复$target").assertIsDisplayed().performClick()
        assertTrue(store.state.value.toDocument().applyingTo(snapshot.data.food).vegetable.any { it.name == target })

        search.performTextReplacement("西红柿")
        composeRule.onNodeWithContentDescription("隐藏番茄").assertIsDisplayed()
        composeRule.onNodeWithText("添加").performClick()
        awaitText("添加自定义词条")
        composeRule.onNodeWithText("名称").performTextReplacement("西红柿")
        composeRule.onNodeWithText("添加词条").performClick()
        awaitText("这个名称已经存在；隐藏的内置条目可在下方恢复。")
        assertTrue(store.state.value.customEntries.isEmpty())
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun personalCatalogPreviewsFileBeforeMergingAndKeepsLocalCategories() {
        val activity = composeRule.activity
        val store = (activity.application as CookApplication).container.personalCatalogStore
        val saved = CatalogEntry(CatalogCategory.MEAT, "自家卤牛肉")
        val tool = CatalogEntry(CatalogCategory.TOOLS, "蒸笼垫")
        val hidden = CatalogEntry(CatalogCategory.VEGETABLE, "菌菇")
        store.addCustomEntry(saved.name, saved.category).getOrThrow()
        val initial = store.state.value
        val doc = PersonalCatalog(
            items = listOf(saved.copy(category = CatalogCategory.VEGETABLE), tool),
            hidden = listOf(hidden),
        )
        val file = File.createTempFile("catalog-import-", ".json", activity.cacheDir)
        try {
            file.writeText(doc.export())
            openPersonalCatalog()
            fun selectFile() {
                composeRule.onNodeWithText("导入词典 JSON").performClick()
                val current = composeRule.activity
                val request = requireNotNull(shadowOf(current).nextStartedActivityForResult)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
                composeRule.runOnIdle {
                    current.activityResultRegistry.dispatchResult(
                        request.requestCode,
                        Activity.RESULT_OK,
                        Intent().setData(Uri.fromFile(file)),
                    )
                }
            }

            selectFile()
            awaitText("将添加 1 个自定义条目和 1 个隐藏偏好。")
            assertEquals(initial, store.state.value)
            composeRule.onNodeWithText("取消").performClick()
            assertEquals(initial, store.state.value)

            selectFile()
            awaitText("合并食材和厨具？")
            composeRule.activityRule.scenario.recreate()
            awaitText("将添加 1 个自定义条目和 1 个隐藏偏好。")
            assertEquals(initial, store.state.value)
            composeRule.onNodeWithText("确认合并").performClick()
            awaitText("已合并个人食材与厨具词典。")
            assertEquals(listOf(saved, tool), store.state.value.customEntries)
            assertEquals(listOf(hidden), store.state.value.hiddenEntries)
        } finally {
            file.delete()
        }
    }

    private fun openPersonalCatalog() {
        awaitText("菜谱")
        composeRule.onNodeWithText("菜谱").performClick()
        composeRule.onNodeWithText("个人食材词典 (", substring = true).performClick()
        awaitText("搜索当前分类")
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun aboutShowsTheInstalledVersionAndOpensTheDataDetailsAndChangelog() {
        openSettingsItem("关于云食册")
        awaitText("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
        composeRule.onNodeWithText(BuildConfig.APPLICATION_ID).assertIsDisplayed()
        composeRule.onNodeWithText("菜谱数据版本").performScrollTo().performClick()
        awaitText("内置菜谱数据说明")
        composeRule.onNodeWithText("数据包指纹版本", substring = true).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        awaitText("内置菜谱数据说明")
        composeRule.onNodeWithText("返回").performClick()
        awaitText("关于云食册")
        composeRule.onNodeWithText("更新日志").performScrollTo().performClick()
        val browser = requireNotNull(shadowOf(composeRule.activity).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, browser.action)
        assertEquals("https://docs.yunyoujun.cn/projects/cook/changelog", browser.dataString)
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun aboutOpensSystemLinkSettingsForTheInstalledPackage() {
        openSettingsItem("关于云食册")
        composeRule.onNodeWithText("在云食册中打开链接").performScrollTo().performClick()

        val settings = requireNotNull(shadowOf(composeRule.activity).nextStartedActivity)
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, settings.action)
        assertEquals("package:${BuildConfig.APPLICATION_ID}", settings.dataString)
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun privacyPolicyIsBundledAndReachableFromSettingsAndFeedback() {
        openSettingsItem("隐私政策")
        awaitText("云食册 Android 隐私政策")
        composeRule.onNodeWithText("推荐统计与反馈").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()

        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("帮助与反馈"))
        composeRule.onNodeWithText("帮助与反馈").performScrollTo().performClick()
        composeRule.onNodeWithText("意见反馈").performClick()
        composeRule.onNodeWithText("隐私政策").performScrollTo().performClick()
        awaitText("云食册 Android 隐私政策")
        composeRule.onNodeWithText("拍照识别与本地提醒").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun membershipOffersBundledPrivacyPolicyBeforeSignIn() {
        openSettingsItem("云乐坊会员")
        awaitText("登录云乐坊账号")
        composeRule.onNodeWithText("隐私政策").performScrollTo().performClick()
        awaitText("云食册 Android 隐私政策")
        composeRule.onNodeWithText("云乐坊账号、同步与会员").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        awaitText("登录云乐坊账号")
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun membershipTermsOpenBundledAndroidPrivacyAndReturnToTheSamePage() {
        openSettingsItem("云乐坊会员")
        composeRule.onNodeWithText("使用条款").performScrollTo().performClick()
        awaitText("云食册用户协议")
        composeRule.onNodeWithText("云食册原生 iOS 与 Android 应用", substring = true)
            .performScrollTo().assertIsDisplayed()

        val privacyLabel = "《云食册 Android 隐私政策》"
        val privacyLink = composeRule.onNodeWithText(privacyLabel, substring = true)
        privacyLink.performScrollTo().assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        awaitText(privacyLabel, substring = true)
        privacyLink.assertIsDisplayed().performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == "/privacy-android" }
        awaitText("云食册 Android 隐私政策")
        assertNull(shadowOf(composeRule.activity).nextStartedActivity)

        composeRule.onNodeWithText("返回").performClick()
        awaitText(privacyLabel, substring = true)
        privacyLink.assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        awaitText("登录云乐坊账号")
    }

    private fun openSettingsItem(title: String) {
        awaitText("我的")
        composeRule.onNodeWithText("我的").performClick()
        awaitText("设置与我的")
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
        composeRule.onNodeWithText(title).performScrollTo().performClick()
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun deviceBackupKeepsFileSelectionPreviewAndRestoredSectionsAcrossRecreation() {
        val activity = composeRule.activity
        val container = (activity.application as CookApplication).container
        val preferences = container.preferencesStore
        val file = File.createTempFile("device-backup-", ".json", activity.cacheDir)
        val list = composeRule.onNodeWithTag("deviceBackup.sections")
        val restorePreferences = composeRule.onNodeWithContentDescription("恢复做菜偏好")
        try {
            preferences.setNoSpicy(true)
            openSettingsItem("本机备份与换机")

            fun selectThroughRecreation(button: String, action: String) {
                composeRule.onNodeWithText(button).performClick()
                val request = requireNotNull(shadowOf(composeRule.activity).nextStartedActivityForResult)
                assertEquals(action, request.intent.action)
                composeRule.activityRule.scenario.recreate()
                awaitText("本机备份与换机")
                composeRule.onNodeWithText(button).assertIsNotEnabled()
                val recreated = composeRule.activity
                composeRule.runOnIdle {
                    recreated.activityResultRegistry.dispatchResult(
                        request.requestCode,
                        Activity.RESULT_OK,
                        Intent().setData(Uri.fromFile(file)),
                    )
                }
            }

            selectThroughRecreation("保存备份", Intent.ACTION_CREATE_DOCUMENT)
            awaitText("已保存本机备份。")
            assertTrue(file.inputStream().use(container.deviceBackupStore::read).preferences.noSpicy)

            preferences.setNoSpicy(false)
            selectThroughRecreation("选择备份恢复", Intent.ACTION_OPEN_DOCUMENT)
            awaitText("备份已检查，可以分项恢复。")
            assertFalse(preferences.current.noSpicy)
            list.performScrollToNode(hasContentDescription("恢复做菜偏好"))
            restorePreferences.performClick()
            awaitText("恢复做菜偏好？")
            composeRule.activityRule.scenario.recreate()
            awaitText("恢复做菜偏好？")
            assertFalse(preferences.current.noSpicy)

            composeRule.onNodeWithText("确认恢复").performClick()
            list.performScrollToIndex(0)
            awaitText("已恢复做菜偏好。")
            assertTrue(preferences.current.noSpicy)
            composeRule.activityRule.scenario.recreate()
            list.performScrollToNode(hasContentDescription("恢复做菜偏好"))
            restorePreferences.assertIsNotEnabled()
            composeRule.onNodeWithText("已恢复").assertIsDisplayed()
        } finally {
            file.delete()
        }
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun privateRecipesSearchesByNameOrIngredientAndKeepsTheQueryOnRecreation() {
        val store = (composeRule.activity.application as CookApplication).container.privateRecipesStore
        store.upsertRecipe(privateRecipe("fixture-curry", "咖喱午饭", "马铃薯"))
        store.upsertRecipe(privateRecipe("fixture-tofu", "红烧豆腐", "北豆腐"))
        openPrivateRecipes()

        val search = composeRule.onNodeWithText("搜索菜名或食材")
        search.performTextReplacement("  马铃  ")
        composeRule.onNodeWithText("咖喱午饭").assertIsDisplayed()
        composeRule.onNodeWithText("红烧豆腐").assertDoesNotExist()

        composeRule.activityRule.scenario.recreate()
        awaitText("咖喱午饭")
        composeRule.onNodeWithText("咖喱午饭").assertIsDisplayed()
        composeRule.onNodeWithText("红烧豆腐").assertDoesNotExist()
        search.performTextReplacement("红烧")
        composeRule.onNodeWithText("红烧豆腐").assertIsDisplayed()
        composeRule.onNodeWithText("咖喱午饭").assertDoesNotExist()
        search.performTextReplacement("鱼")
        composeRule.onNodeWithText("没有符合搜索的私房菜").assertIsDisplayed()
        composeRule.onNodeWithText("清除搜索").performClick()
        composeRule.onNodeWithText("咖喱午饭").assertIsDisplayed()
        composeRule.onNodeWithText("红烧豆腐").assertIsDisplayed()
    }

    @Test
    fun privateRecipeVideoSharesTheInstalledFileWithAReadOnlyGrant() {
        val activity = composeRule.activity
        val container = (activity.application as CookApplication).container
        val bytes = "private recipe video fixture".encodeToByteArray()
        val video = PrivateRecipeVideo(
            id = "1d9c92da-a99d-4f19-a37f-91023b9f8ea2",
            fileExtension = "mp4",
            originalName = "other-device\\演示视频.mov",
            byteCount = bytes.size.toLong(),
            location = PrivateRecipeVideo.Location.LOCAL,
            deviceID = container.videoStore.deviceId,
        )
        val root = File(activity.filesDir, "PrivateRecipeVideos/Files").apply { mkdirs() }
        val file = File(root, video.fileName).also { it.writeBytes(bytes) }
        container.privateRecipesStore.upsertRecipe(privateRecipe("fixture-video", "带视频的菜谱", "番茄").copy(video = video))
        try {
            openPrivateRecipes()
            val shareButton = composeRule.onNodeWithText("分享视频文件").performScrollTo()
            shareButton.performClick()
            val chooser = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val share = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals("video/mp4", share.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, share.flags)
            val stream = requireNotNull(share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            assertEquals("content", stream.scheme)
            assertEquals("${activity.packageName}.private-recipe-video-shares", stream.authority)
            assertEquals(stream, share.clipData?.getItemAt(0)?.uri)
            val resolver = activity.contentResolver
            resolver.query(stream, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                assertTrue(it.moveToFirst())
                assertEquals("演示视频.mp4", it.getString(0))
            } ?: error("Expected the shared filename")
            assertTrue(resolver.openInputStream(stream)!!.use { it.readBytes() }.contentEquals(bytes))

            assertTrue(file.delete())
            shareButton.performClick()
            composeRule.onNodeWithText("视频尚未同步到这台设备，或已被移动、删除。请稍后重试，或在编辑中重新选择视频。")
                .assertExists()
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            file.delete()
        }
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun privateRecipeClipboardPreviewSurvivesRecreationAndRequiresExplicitImport() {
        val activity = composeRule.activity
        val store = (activity.application as CookApplication).container.privateRecipesStore
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val original = privateRecipe("fixture-tofu", "红烧豆腐", "北豆腐")
        val changed = original.copy(
            name = "家常炖豆腐",
            tools = listOf("砂锅"),
            note = "小火慢炖，让豆腐入味。",
        )
        val incoming = privateRecipe("fixture-egg", "菌菇炒蛋", "鸡蛋")
        store.upsertRecipe(original)
        openPrivateRecipes()
        val paste = composeRule.onNodeWithText("从剪贴板导入")
        fun setClipboard(text: String) = composeRule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("Private recipes fixture", text))
        }
        setClipboard("{invalid")
        paste.performClick()
        awaitText("无法识别这份私人菜谱备份，原有菜谱未改变。")
        assertEquals(listOf(original), store.state.value.recipes)

        setClipboard(PrivateRecipeFile(recipes = listOf(changed, incoming)).export())
        paste.performClick()
        awaitText("编号冲突 / 曾删除记录：1 道")
        composeRule.onNodeWithText("家常炖豆腐").assertIsDisplayed()
        composeRule.onNode(
            hasText("食材：北豆腐") and hasAnyAncestor(hasTestTag("privateRecipeImport.recipes")),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("厨具：砂锅").assertIsDisplayed()
        composeRule.onNodeWithText("小火慢炖，让豆腐入味。").assertIsDisplayed()
        composeRule.onNodeWithTag("privateRecipeImport.recipes").performScrollToNode(hasText(incoming.name))
        composeRule.onNodeWithText(incoming.name).assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(listOf(original), store.state.value.recipes)

        paste.performClick()
        awaitText("编号冲突 / 曾删除记录：1 道")
        val copies = composeRule.onNodeWithText("冲突菜谱另存副本")
        copies.assertIsOff()
        composeRule.onNodeWithText("导入 1 道菜谱").assertIsEnabled()
        copies.performClick()
        composeRule.activityRule.scenario.recreate()
        awaitText("导入 2 道菜谱")
        copies.assertIsOn()
        assertEquals(listOf(original), store.state.value.recipes)
        composeRule.onNodeWithText("导入 2 道菜谱").performClick()
        awaitText("已导入 2 道私房菜。")
        val recipes = store.state.value.recipes.associateBy { it.name }
        assertEquals(original, recipes[original.name])
        assertEquals(incoming, recipes[incoming.name])
        assertEquals(original.customId, recipes[changed.name]?.copiedFrom)
        assertTrue(recipes[changed.name]?.customId != original.customId)

        paste.performClick()
        awaitText("同内容跳过：2 道")
        composeRule.onNodeWithText("导入 0 道菜谱").assertIsNotEnabled()
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(3, store.state.value.recipes.size)
    }

    @Test
    @Config(qualifiers = "w380dp-h640dp")
    fun privateRecipeImportReviewsUpdatedConflictsBeforeSavingALongList() {
        val activity = composeRule.activity
        val store = (activity.application as CookApplication).container.privateRecipesStore
        val incoming = (1..30).map { privateRecipe("fixture-batch-$it", "试做菜谱 $it", "鸡蛋") }
        openPrivateRecipes()
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        composeRule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("Private recipes fixture", PrivateRecipeFile(recipes = incoming).export()))
        }
        composeRule.onNodeWithText("从剪贴板导入").performClick()
        awaitText("新增菜谱：30 道")
        val last = incoming.last()
        composeRule.onNodeWithTag("privateRecipeImport.recipes").performScrollToNode(hasText(last.name))
        composeRule.onNodeWithText(last.name).assertIsDisplayed()
        assertTrue(store.state.value.recipes.isEmpty())

        val local = last.copy(name = "刚保存的菜谱")
        store.upsertRecipe(local)
        composeRule.onNodeWithText("导入 30 道菜谱").assertIsDisplayed().performClick()
        awaitText("本机私房菜已变化，请核对更新后的预览再确认。")
        assertEquals(listOf(local), store.state.value.recipes)
        composeRule.onNodeWithText("冲突菜谱另存副本").assertIsOff()
        composeRule.onNodeWithText("导入 29 道菜谱").performClick()
        awaitText("已导入 29 道私房菜。")
        assertEquals(incoming.dropLast(1) + local, store.state.value.recipes)
    }

    private fun openPrivateRecipes() {
        awaitText("菜谱")
        composeRule.onNodeWithText("菜谱").performClick()
        composeRule.onNodeWithText("我的私房菜 (", substring = true).performClick()
        awaitText("搜索菜名或食材")
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun privateRecipe(id: String, name: String, ingredient: String) = PrivateRecipe(
        customId = id,
        name = name,
        stuff = listOf(ingredient),
        tools = emptyList(),
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    @Test
    fun mainActivity_activityRecreation_preservesSelectedIngredientsAndFilters() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("今天做什么菜").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("今天做什么菜").assertIsDisplayed()

        // 1. Pick an ingredient via "选食材" dialog
        composeRule.onNodeWithText("选食材").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("选择现有食材或调料").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("土豆").performClick()
        composeRule.onNodeWithText("完成").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("选食材 (1)").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("选食材 (1)").assertIsDisplayed()

        // 2. Enable difficulty filter & no-spicy preference via "筛选与模式" dialog
        composeRule.onNodeWithText("筛选与模式").performClick()
        val filters = "cook.filter.options"
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag(filters)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("简单").performClick()
        composeRule.onNodeWithTag(filters).performScrollToNode(hasText("不吃辣"))
        composeRule.onNode(
            hasText("不吃辣") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
        ).performClick()
        composeRule.onNodeWithText("完成").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("本次限制：不吃辣").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("本次限制：不吃辣").assertIsDisplayed()
        composeRule.onNodeWithText("筛选 (", substring = true).assertIsDisplayed()

        // 3. Recreate real MainActivity (configuration / process state saveable check)
        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("今天做什么菜").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("选食材 (1)").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("选食材 (1)").assertIsDisplayed()
        composeRule.onNodeWithText("本次限制：不吃辣").assertIsDisplayed()
        composeRule.onNodeWithText("筛选 (", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun libraryTransferCopiesPastesAndSharesTheSamePortableDocument() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("菜谱").fetchSemanticsNodes().isNotEmpty()
        }
        val activity = composeRule.activity
        val container = (activity.application as CookApplication).container
        val snapshot = (container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        val (original, incomingRecipe) = snapshot.data.recipes.distinctBy { it.name }.take(2)
        val repo = container.userLibraryRepository
        runBlocking {
            repo.toggleFavorite(original.id, currentlyFavorite = false).getOrThrow()
            repo.recordVisit(original).getOrThrow()
        }
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        composeRule.onNodeWithText("菜谱").performClick()
        composeRule.onNodeWithText("导入与导出").performClick()
        composeRule.onNodeWithText("当前库：本机设备").assertIsDisplayed()

        composeRule.onNodeWithText("复制 JSON").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("已复制本机库的 JSON。").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            val clip = requireNotNull(clipboard.primaryClip)
            assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
            val copied = UserDataDocument.decode(clip.getItemAt(0).text.toString())
            assertEquals(original.id, copied.favorites.single().id)
            assertEquals(original.name, copied.histories.single().recipe.name)
        }

        val changedAt = System.currentTimeMillis().toDouble() + 1_000
        val incoming = UserDataDocument(
            favorites = listOf(UserDataDocument.Favorite(incomingRecipe.id, changedAt)),
            favoriteRemovals = listOf(UserDataDocument.Favorite(original.id, changedAt)),
            histories = listOf(UserDataDocument.Visit(UserDataDocument.RecipeSnapshot(incomingRecipe), changedAt)),
        )
        composeRule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("Web fixture", UserDataDocument.wireJson.encodeToString(incoming)))
        }
        composeRule.onNodeWithText("从剪贴板导入").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("已合并导入，本机库共 2 条收藏、2 条浏览历史。").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("已合并导入，本机库共 2 条收藏、2 条浏览历史。").assertIsDisplayed()

        val recreated = composeRule.activity
        var chooser: Intent? = null
        composeRule.onNodeWithText("分享 JSON 文件").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            chooser = shadowOf(recreated).nextStartedActivity ?: chooser
            val errors = composeRule.onAllNodesWithText("失败", substring = true).fetchSemanticsNodes() +
                composeRule.onAllNodesWithText("无法分享", substring = true).fetchSemanticsNodes()
            check(errors.isEmpty()) { errors.flatMap { it.config[SemanticsProperties.Text] }.joinToString() }
            chooser != null
        }
        val share = requireNotNull(chooser?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("application/json", share.type)
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        val uri = requireNotNull(share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        val exported = recreated.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        val doc = UserDataDocument.decode(requireNotNull(exported))
        assertEquals(setOf(original.id, incomingRecipe.id), doc.favorites.map { it.id }.toSet())
        assertEquals(setOf(original.name, incomingRecipe.name), doc.histories.map { it.recipe.name }.toSet())
    }

    @Test
    @Config(qualifiers = "w1000dp-h850dp")
    fun wideDiscoveryKeepsTheFullCatalogBesideIngredientsAndReplacesRecipeDetails() {
        val results = composeRule.onNodeWithTag("cook.results")
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodes(hasTestTag("cook.results")).fetchSemanticsNodes().isNotEmpty()
        }
        val container = (composeRule.activity.application as CookApplication).container
        val snapshot = (container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        val prefs = container.preferencesStore.current
        val catalog = CookSessionController().evaluateCandidates(snapshot, prefs).recipes
        val pastFirstPage = catalog[150]
        results.performScrollToIndex(151)
        composeRule.onNodeWithTag("cook.recipe.${pastFirstPage.id}").assertIsDisplayed()
        results.performScrollToIndex(0)

        val tomato = composeRule.onNodeWithTag("cook.ingredient.番茄")
        tomato.assertIsDisplayed().performClick().assertIsSelected()
        composeRule.onNodeWithText("已选：番茄").assertIsDisplayed()
        val recipes = CookSessionController(CookSessionConditions(selectedStuff = listOf("番茄")))
            .evaluateCandidates(snapshot, prefs).recipes
        val first = recipes[0]
        val second = recipes.first { it.name != first.name }
        composeRule.onNodeWithText("为你匹配到 ${recipes.size} 道菜").assertIsDisplayed()
        val firstRow = composeRule.onNodeWithTag("cook.recipe.${first.id}")
        assertTrue(firstRow.fetchSemanticsNode().boundsInRoot.left > tomato.fetchSemanticsNode().boundsInRoot.right)
        firstRow.performClick()
        composeRule.onNodeWithTag("recipe.detail.${first.id}").assertIsDisplayed()
        firstRow.assertIsSelected()

        val sidebar = composeRule.onNodeWithTag("cook.sidebar.recipes")
        sidebar.performScrollToNode(hasTestTag("cook.recipe.${second.id}"))
        composeRule.onNodeWithTag("cook.recipe.${second.id}").performClick().assertIsSelected()
        composeRule.onNodeWithTag("recipe.detail.${first.id}").assertDoesNotExist()
        composeRule.onNodeWithTag("recipe.detail.${second.id}").assertIsDisplayed()
        val histories = container.userLibraryRepository.observeHistories().filterNotNull()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { histories.first().entries.map { it.snapshot.name }.toSet() == setOf(first.name, second.name) }
        }
        val recorded = runBlocking { histories.first().entries }
        composeRule.activityRule.scenario.recreate()
        val detailTag = "recipe.detail.${second.id}"
        composeRule.onNodeWithTag(detailTag).assertIsDisplayed()
        sidebar.assertIsDisplayed()

        composeRule.onNodeWithText("反馈", substring = false).performClick()
        composeRule.onNodeWithText("菜谱 #${second.id}", substring = true).assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(detailTag).assertIsDisplayed()
        sidebar.assertIsDisplayed()

        val detailContent = composeRule.onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(detailTag)))
        detailContent.performScrollToNode(hasText("进入厨房工作台"))
        composeRule.onNodeWithText("进入厨房工作台").performClick()
        composeRule.onNodeWithText("工作台 · ${second.name}").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(detailTag).assertIsDisplayed()
        assertNull(runBlocking { withTimeoutOrNull(750) { histories.first { it.entries != recorded } } })

        val detail = composeRule.onNode(SemanticsMatcher("菜谱详情") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("recipe.detail.") == true
        })
        fun decide(): Int {
            composeRule.onNodeWithText("随机一道").performClick()
            composeRule.onNodeWithText("看做法").performClick()
            sidebar.assertIsDisplayed()
            return detail.fetchSemanticsNode().config[SemanticsProperties.TestTag].substringAfterLast('.').toInt()
        }
        val firstRandom = decide()
        val secondRandom = decide()
        assertTrue(firstRandom != secondRandom)
        assertTrue(snapshot.recipe(firstRandom)?.name != snapshot.recipe(secondRandom)?.name)

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tomato.assertIsSelected()
        results.assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1000dp-h850dp")
    fun wideRecipeDetailUpdatesIngredientGroupsAsTheSelectionChanges() {
        val results = composeRule.onNodeWithTag("cook.results")
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodes(hasTestTag("cook.results")).fetchSemanticsNodes().isNotEmpty()
        }
        val prefs = (composeRule.activity.application as CookApplication).container.preferencesStore
        composeRule.runOnIdle { prefs.setPantry(emptyList()) }
        val tomato = composeRule.onNodeWithTag("cook.ingredient.番茄")
        tomato.performClick().assertIsSelected()
        results.performScrollToNode(hasTestTag("cook.recipe.250"))
        composeRule.onNodeWithTag("cook.recipe.250").performClick()
        val detail = composeRule.onNodeWithTag("recipe.detail.250")
        detail.assertIsDisplayed()
        composeRule.onNodeWithText("已有食材：番茄").assertIsDisplayed()
        composeRule.onNodeWithText("还缺：鸡蛋").assertIsDisplayed()

        composeRule.onNodeWithText("食材").performClick()
        val chooser = composeRule.onNodeWithTag("cook.chooser")
        val egg = composeRule.onNodeWithTag("cook.ingredient.鸡蛋")
        chooser.performScrollToNode(hasTestTag("cook.ingredient.鸡蛋"))
        egg.performClick().assertIsSelected()
        detail.assertIsDisplayed()
        composeRule.onNodeWithText("已有食材：番茄、鸡蛋").assertIsDisplayed()
        composeRule.onNodeWithText("还缺：鸡蛋").assertDoesNotExist()

        egg.performClick()
        composeRule.runOnIdle { prefs.setPantry(listOf("鸡蛋")) }
        composeRule.activityRule.scenario.recreate()
        detail.assertIsDisplayed()
        composeRule.onNodeWithText("已有食材：番茄").assertIsDisplayed()
        composeRule.onNodeWithText("常备调料：鸡蛋").assertIsDisplayed()

        chooser.performScrollToNode(hasTestTag("cook.ingredient.番茄"))
        tomato.performClick()
        detail.assertIsDisplayed()
        composeRule.onNodeWithText("食材：番茄、鸡蛋").assertIsDisplayed()
        composeRule.onNodeWithTag("recipe.matchedIngredients").assertDoesNotExist()
    }

    @Test
    fun recipeFeedbackKeepsTheSharedRecipeAndReturnsToTheSameDetail() {
        awaitText("今天做什么菜")
        openSharedRecipe(250)
        composeRule.onNodeWithTag("recipe.detail.250").assertIsDisplayed()
        composeRule.onNodeWithText("反馈", substring = false).performClick()
        composeRule.onNodeWithText("反馈这道菜").assertIsDisplayed()
        composeRule.onNodeWithText("菜谱 #250", substring = true).assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("反馈这道菜").assertIsDisplayed()
        composeRule.onNodeWithText("菜谱 #250", substring = true).assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("recipe.detail.250").assertIsDisplayed()
    }

    @Test
    fun sharingARecipeProvidesACopyableAddressAndASeparatePreviewTitle() {
        awaitText("今天做什么菜")
        openSharedRecipe(250)
        val detailList = composeRule.onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag("recipe.detail.250")))
        detailList.performScrollToNode(hasText("分享菜谱"))
        composeRule.onNodeWithText("分享菜谱").performClick()

        val chooser = requireNotNull(shadowOf(composeRule.activity).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val share = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("text/plain", share.type)
        assertEquals("https://cook.yunle.fun/recipes/share/?id=250", share.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("番茄炒蛋", share.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("番茄炒蛋", share.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun sharedRecipeHistorySurvivesRecreationButASeparateOpenAddsAVisit() {
        awaitText("今天做什么菜")
        val container = (composeRule.activity.application as CookApplication).container
        val histories = container.userLibraryRepository.observeHistories().filterNotNull()

        openSharedRecipe(250)
        composeRule.waitUntil(timeoutMillis = 5_000L) { runBlocking { histories.first().entries.size == 1 } }
        val original = runBlocking { histories.first().entries.single() }

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("recipe.detail.250").assertIsDisplayed()
        assertNull(runBlocking { withTimeoutOrNull(750) { histories.first { it.entries != listOf(original) } } })

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("recipe.detail.250").assertDoesNotExist()
        openSharedRecipe(250)
        composeRule.waitUntil(timeoutMillis = 5_000L) { runBlocking { histories.first().entries.size == 2 } }
        val reopened = runBlocking { histories.first().entries }
        assertEquals(listOf("番茄炒蛋", "番茄炒蛋"), reopened.map { it.snapshot.name })
        assertEquals(original, reopened.last())
    }

    @Test
    @Config(qualifiers = "w420dp-h900dp")
    fun cloudKitchenResultsReturnToTheSelectedIngredientsAfterRecipeAndRecreation() {
        awaitText("今天做什么菜")
        composeRule.onNodeWithText("云厨房").performClick()
        awaitText("食材篮 · 已选 0 样")
        awaitText("番茄")
        val list = composeRule.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("番茄"))
        composeRule.onNodeWithText("番茄").performClick().assertIsSelected()
        val openResults = hasText("查看 44 道菜")
        list.performScrollToNode(openResults)
        composeRule.onNode(openResults).performClick()
        awaitText("为你匹配到 44 道菜")
        list.performScrollToNode(hasTestTag("cook.recipe.250"))
        composeRule.onNodeWithTag("cook.recipe.250").performClick()

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("recipe.detail.250").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("cook.recipe.250").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("云厨房").assertIsDisplayed()
        list.performScrollToNode(hasText("番茄"))
        composeRule.onNodeWithText("番茄").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w1000dp-h850dp")
    fun cloudKitchenOpensAllSceneMatchesInTheWideResultsPane() {
        awaitText("随机一道")
        val chooserTag = hasTestTag("cook.chooser")
        val resultsTag = hasTestTag("cook.results")
        val chooser = composeRule.onNode(chooserTag)
        val results = composeRule.onNode(resultsTag)
        val easyScene = hasText("省点心", substring = true)
        chooser.performScrollToNode(easyScene)
        composeRule.onNode(easyScene and hasAnyAncestor(chooserTag)).performClick()

        val container = (composeRule.activity.application as CookApplication).container
        val snapshot = (container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        val matches = CookSessionController(CookSessionConditions(discoveryScene = CookDiscoveryScene.EASY))
            .evaluateCandidates(snapshot, container.preferencesStore.current).recipes
        val sampleRows = SemanticsMatcher("灵感菜谱") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("cook.recipe.") == true
        } and hasAnyAncestor(resultsTag)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(sampleRows).fetchSemanticsNodes().size == 3
        }
        val sampleTags = composeRule.onAllNodes(sampleRows).fetchSemanticsNodes().map {
            it.config[SemanticsProperties.TestTag]
        }
        val extra = matches.first { "cook.recipe.${it.id}" !in sampleTags }
        val showAll = hasText("查看全部 ${matches.size} 道菜") and hasAnyAncestor(resultsTag)
        composeRule.onNode(showAll).assertIsDisplayed()

        chooser.performScrollToIndex(0)
        composeRule.onNode(hasText("云厨房") and hasAnyAncestor(chooserTag)).performClick()
        awaitText("食材篮 · 已选 0 样")
        awaitText("番茄")
        val openMenu = hasText("查看 ${matches.size} 道菜")
        chooser.performScrollToNode(openMenu)
        composeRule.onNode(openMenu and hasAnyAncestor(chooserTag)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("正在准备菜单…").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNode(showAll).assertDoesNotExist()
        val extraRecipe = hasTestTag("cook.recipe.${extra.id}")
        results.performScrollToNode(extraRecipe)
        composeRule.onNode(extraRecipe and hasAnyAncestor(resultsTag)).performClick()
        composeRule.onNodeWithTag("recipe.detail.${extra.id}").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("recipe.detail.${extra.id}").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNode(showAll).assertDoesNotExist()
        results.performScrollToNode(extraRecipe)
        composeRule.onNode(extraRecipe and hasAnyAncestor(resultsTag)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w420dp-h900dp")
    fun warmSharedLinksPreserveTheWorkbenchAndReuseAnAlreadyOpenWorkbench() {
        awaitText("今天做什么菜")
        val workbench = composeRule.onNodeWithText("工作台 · 番茄炒蛋")
        val detailList = composeRule.onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag("recipe.detail.250")))
        fun enterWorkbench() {
            detailList.performScrollToNode(hasText("进入厨房工作台"))
            composeRule.onNodeWithText("进入厨房工作台").performClick()
            workbench.assertIsDisplayed()
        }
        fun back() {
            composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }

        openSharedRecipe(250)
        enterWorkbench()
        val instructions = composeRule.onNodeWithText("做法")
        instructions.performClick().assertIsSelected()

        openSharedRecipe(588)
        composeRule.onNodeWithText("食材：鸡蛋").assertIsDisplayed()
        openSharedRecipe(497)
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("recipe.detail.497").assertIsDisplayed()
        back()
        workbench.assertIsDisplayed()
        instructions.assertIsSelected()

        openSharedRecipe(250)
        enterWorkbench()
        instructions.assertIsSelected()
        back()
        composeRule.onNodeWithTag("recipe.detail.250").assertIsDisplayed()
        back()
        composeRule.onNodeWithText("今天做什么菜").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w450dp-h900dp")
    fun fridgePhotoIntakeShowsTheAvailableProduceModelOnFirstUse() {
        val app = composeRule.activity.application as CookApplication
        val installed = app.noBackupFilesDir.resolve("produce-model/classifier.onnx")

        awaitText("今天做什么菜")
        composeRule.onNodeWithText("冰箱").performClick()
        composeRule.onNodeWithText("拍照识别").performClick()
        val expected = if (BuildConfig.COOK_BUNDLED_PRODUCE_MODEL) "可在本机离线识别蔬果。" else "下载蔬果模型"
        awaitText(expected)
        if (BuildConfig.COOK_BUNDLED_PRODUCE_MODEL) {
            assertEquals(app.container.fridgeProduceModelStore.model.bytes, installed.length())
            composeRule.onNodeWithText("下载蔬果模型").assertDoesNotExist()
        } else {
            assertFalse(installed.exists())
        }
        composeRule.activityRule.scenario.recreate()
        awaitText(expected)
    }

    @Test
    fun warmSharedLinkAndBackKeepTheCurrentFridgePhotoDraft() {
        awaitText("今天做什么菜")
        composeRule.onNodeWithText("冰箱").performClick()
        composeRule.onNodeWithText("拍照识别").performClick()
        composeRule.onNodeWithText("选相册图片").assertIsDisplayed()
        val intake = ViewModelProvider(composeRule.activity)[FridgePhotoIntake::class.java]
        val original = requireNotNull(intake.draft)
        composeRule.runOnIdle {
            original.reviewing = true
            original.labelText = "西红柿\n保质期至 ${LocalDate.now().plusDays(7)}"
        }
        val nameField = hasText("核对食材名称") and hasSetTextAction()
        composeRule.onNode(nameField and hasText("番茄")).assertExists()
        composeRule.onNodeWithText("确认入库").assertIsEnabled()
        composeRule.onNode(nameField).performScrollTo().performTextReplacement("")
        composeRule.onNodeWithText("确认入库").assertIsNotEnabled()

        openSharedRecipe(250)
        composeRule.onNodeWithText("选相册图片").assertDoesNotExist()
        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("选相册图片").performScrollTo().assertIsDisplayed()
        composeRule.onNode(nameField and hasText("番茄")).assertDoesNotExist()
        composeRule.onNodeWithText("确认入库").assertIsNotEnabled()
        assertTrue(intake.draft === original)
    }

    @Test
    fun pendingFridgePhotoPickerReturnsToAFreshActivityWithoutSavingTheOldPhoto() {
        awaitText("今天做什么菜")
        composeRule.onNodeWithText("冰箱").performClick()
        composeRule.onNodeWithText("拍照识别").performClick()
        val activity = composeRule.activity
        val original = ViewModelProvider(activity)[FridgePhotoIntake::class.java]
        composeRule.runOnIdle {
            requireNotNull(original.draft).apply {
                photo = FridgePhotoIntake.Photo(byteArrayOf(1))
                ingredientNameOverride = "旧草稿"
                reviewing = true
            }
        }
        composeRule.onNodeWithText("选相册图片").performClick()
        val request = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
        val state = Bundle()
        composeRule.runOnIdle {
            ReflectionHelpers.callInstanceMethod<Unit>(
                activity, "onSaveInstanceState", ReflectionHelpers.ClassParameter.from(Bundle::class.java, state),
            )
        }
        composeRule.activityRule.scenario.close()

        val photo = File.createTempFile("fridge-photo", ".png", activity.cacheDir)
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        FridgePhotoInspector.textRecognizerOverride = { _, _ -> "西红柿" }
        try {
            photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Robolectric.buildActivity(MainActivity::class.java).setup(state).use { controller ->
                val restoredActivity = controller.get()
                val restored = ViewModelProvider(restoredActivity)[FridgePhotoIntake::class.java]
                val draft = requireNotNull(restored.draft)
                assertTrue(restored !== original)
                assertTrue(draft.choosingGalleryPhoto)
                assertNull(draft.photo)
                assertNull(draft.ingredientNameOverride)

                restoredActivity.activityResultRegistry.dispatchResult(
                    request.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(photo)),
                )
                awaitText("已识别包装文字，请核对食材和日期。")
                composeRule.onNode(hasSetTextAction() and hasText("核对食材名称") and hasText("番茄")).assertExists()
                composeRule.onNodeWithText("确认入库").assertIsEnabled()
                assertFalse(draft.choosingGalleryPhoto)
            }
        } finally {
            FridgePhotoInspector.textRecognizerOverride = null
            bitmap.recycle()
            photo.delete()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun photoReviewKeepsEditsWhileRefocusingAndResetsAfterReplacingThePhoto() {
        awaitText("今天做什么菜")
        composeRule.onNodeWithText("冰箱").performClick()
        composeRule.onNodeWithText("拍照识别").performClick()
        fun deliverSelection(resultCode: Int, data: Intent?) {
            composeRule.onNodeWithText("选相册图片").performScrollTo().performClick()
            val activity = composeRule.activity
            val request = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
            awaitText("正在等待所选照片…")
            composeRule.onNodeWithText("确认入库").assertDoesNotExist()
            composeRule.runOnIdle { activity.activityResultRegistry.dispatchResult(request.requestCode, resultCode, data) }
        }

        val photo = File.createTempFile("fridge-label", ".png", composeRule.activity.cacheDir)
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val originalDate = LocalDate.now().plusDays(7)
        val correctedDate = originalDate.plusDays(1)
        val manualDate = correctedDate.plusDays(2)
        val original = "西红柿\n保质期至 $originalDate"
        val corrected = "西红柿\n保质期至 $correctedDate"
        val cropped = "番茄\n保质期至 $originalDate"
        val replacement = "牛奶\n保质期至 ${correctedDate.plusDays(1)}"
        val labelEditor = hasSetTextAction() and hasText("包装标签文字（支持生产日期、保质期、到期日）")
        val nameEditor = hasSetTextAction() and hasText("核对食材名称")
        val showOriginal = composeRule.onNodeWithText("核对识别原文")
        val finishReplacement = CompletableDeferred<Unit>()
        FridgePhotoInspector.textRecognizerOverride = { _, _ -> original }
        try {
            photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            deliverSelection(Activity.RESULT_OK, Intent().setData(Uri.fromFile(photo)))
            awaitText("已识别包装文字，请核对食材和日期。")
            val intake = ViewModelProvider(composeRule.activity)[FridgePhotoIntake::class.java]
            val firstPhoto = requireNotNull(intake.draft?.photo)
            composeRule.onNode(labelEditor).performScrollTo().performTextReplacement(corrected)
            composeRule.onNode(nameEditor).performScrollTo().performTextReplacement("小番茄")
            composeRule.onNodeWithText("到期日 $correctedDate（包装保质期）").assertExists()
            showOriginal.performScrollTo().performClick()
            composeRule.onNodeWithText(original).assertExists()

            composeRule.activityRule.scenario.recreate()
            composeRule.onNodeWithText(original).assertExists()
            composeRule.onNode(labelEditor and hasText(corrected)).assertExists()
            composeRule.runOnIdle {
                val draft = requireNotNull(intake.draft)
                draft.dates = draft.dates.copy(basis = FridgeExpiryBasis.MANUAL, customExpiry = manualDate)
            }
            FridgePhotoInspector.textRecognizerOverride = { _, _ -> cropped }
            composeRule.onNodeWithText("圈选照片区域").performScrollTo().performClick()
            composeRule.onNodeWithText("选择识别范围").assertIsDisplayed()
            composeRule.waitUntil(timeoutMillis = 10_000L) {
                composeRule.onAllNodes(hasContentDescription("所选照片")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("识别框内食材").assertIsEnabled().performClick()
            awaitText(cropped)
            composeRule.onNode(labelEditor and hasText(corrected)).assertExists()
            composeRule.onNode(nameEditor and hasText("小番茄")).assertExists()
            composeRule.runOnIdle { assertEquals(manualDate, intake.draft?.dates?.customExpiry) }

            deliverSelection(Activity.RESULT_CANCELED, null)
            val unreadable = File(photo.parentFile, "${photo.name}.missing")
            deliverSelection(Activity.RESULT_OK, Intent().setData(Uri.fromFile(unreadable)))
            awaitText("本机无法识别这张照片，可以换图或手动录入。")
            composeRule.onNode(nameEditor and hasText("小番茄")).assertExists()
            composeRule.onNodeWithText(cropped).assertExists()
            composeRule.onNodeWithText("确认入库").assertIsEnabled()
            assertTrue(intake.draft?.photo === firstPhoto)

            FridgePhotoInspector.textRecognizerOverride = { _, _ ->
                finishReplacement.await()
                replacement
            }
            deliverSelection(Activity.RESULT_OK, Intent().setData(Uri.fromFile(photo)))
            awaitText("正在读取并识别照片…")
            composeRule.onNode(nameEditor).assertDoesNotExist()
            composeRule.onNode(labelEditor).assertDoesNotExist()
            composeRule.onNodeWithText("确认入库").assertDoesNotExist()
            composeRule.activityRule.scenario.recreate()
            composeRule.onNode(nameEditor).assertDoesNotExist()
            assertTrue(intake.draft?.photo === firstPhoto)
            finishReplacement.complete(Unit)
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodes(nameEditor and hasText("牛奶")).fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithText(original).assertDoesNotExist()
            composeRule.onNodeWithText(cropped).assertDoesNotExist()
            composeRule.onNode(labelEditor and hasText(replacement)).assertExists()
            composeRule.runOnIdle { assertNull(intake.draft?.dates?.customExpiry) }
            val replacementOriginal = composeRule.onNode(hasText(replacement) and !hasSetTextAction())
            replacementOriginal.assertDoesNotExist()
            showOriginal.performScrollTo().performClick()
            replacementOriginal.assertExists()
        } finally {
            finishReplacement.complete(Unit)
            FridgePhotoInspector.textRecognizerOverride = null
            bitmap.recycle()
            photo.delete()
        }
    }

    @Test
    fun singleFridgePhotoSaveRetriesAfterWriteFailureAndFinishesOnceAcrossRecreation() {
        awaitText("今天做什么菜")
        composeRule.onNodeWithText("冰箱").performClick()
        composeRule.onNodeWithText("拍照识别").performClick()
        val app = composeRule.activity.application as CookApplication
        val fridge = app.container.fridgeStore
        val existing = FridgeBatchItem(ingredientName = "牛奶")
        fridge.addItems(listOf(existing))
        val intake = ViewModelProvider(composeRule.activity)[FridgePhotoIntake::class.java]
        val expires = LocalDate.now().plusDays(7)
        val source = FridgePhotoIntake.Photo(byteArrayOf(1))
        composeRule.runOnIdle {
            requireNotNull(intake.draft).apply {
                photo = source
                reviewing = true
                labelText = "西红柿\n保质期至 $expires"
                ingredientNameOverride = "小番茄"
                selectedCategoryId = "veg"
            }
        }

        val blockingPath = File(app.filesDir, "fridge-store-v1.json.tmp")
        assertTrue(blockingPath.mkdir())
        try {
            composeRule.onNodeWithText("确认入库").performClick()
            awaitText("食材未保存，请重试。")
            composeRule.onNode(hasSetTextAction() and hasText("核对食材名称") and hasText("小番茄")).assertExists()
            composeRule.runOnIdle { assertTrue(intake.draft?.photo === source) }
            assertEquals(listOf(existing), FridgeStore(app).state.value.stock)
        } finally {
            assertTrue(blockingPath.delete())
        }

        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lockHolder = thread(isDaemon = true) {
            synchronized(fridge) {
                locked.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            composeRule.onNodeWithText("确认入库").performClick()
            awaitText("正在保存食材…")
            composeRule.activityRule.scenario.recreate()
            composeRule.onNodeWithText("正在保存食材…").assertIsDisplayed()
            composeRule.onNodeWithText("确认入库").assertDoesNotExist()
            assertEquals(listOf(existing), FridgeStore(app).state.value.stock)
        } finally {
            release.countDown()
            lockHolder.join(5_000)
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("正在保存食材…").fetchSemanticsNodes().isEmpty()
        }
        composeRule.runOnIdle { assertNull(intake.draft) }
        val saved = FridgeStore(app).state.value.stock
        assertEquals(existing, saved.last())
        val addition = saved.dropLast(1).single()
        assertEquals("小番茄", addition.ingredientName)
        assertEquals("veg", addition.categoryId)
        assertEquals(FridgeDateKind.PACKAGE_EXPIRY, addition.dateKind)
        assertEquals(expires.toString(), addition.expiresOnIsoDate)
    }

    private fun openSharedRecipe(id: Int) {
        deliverViewLink("https://cook.yunle.fun/recipes/share/?id=$id")
        composeRule.onNodeWithTag("recipe.detail.$id").assertIsDisplayed()
    }

    private fun deliverViewLink(url: String) {
        deliverIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun deliverIntent(intent: Intent) {
        composeRule.activityRule.scenario.onActivity { activity ->
            ReflectionHelpers.callInstanceMethod<Unit>(
                activity,
                "onNewIntent",
                ReflectionHelpers.ClassParameter.from(Intent::class.java, intent),
            )
        }
    }

    @Test
    fun libraryRandomCanFilterInPlaceAndOpenTheMatchedRecipe() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("选食材").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("选食材").performClick()
        composeRule.onNodeWithText("土豆").performClick()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.onNodeWithText("菜谱").performClick()
        composeRule.onNodeWithText("收藏").performClick()
        composeRule.onNodeWithText("今天吃什么").performClick()
        composeRule.onNodeWithText("筛选").performClick()
        composeRule.onNodeWithText("简单").performClick()

        val container = (composeRule.activity.application as CookApplication).container
        val snapshot = (container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        val expected = CookSessionController(CookSessionConditions(
            selectedStuff = listOf("土豆"),
            difficulty = listOf("简单"),
        )).evaluateCandidates(snapshot, container.preferencesStore.current, ignoringKeyword = true)
        composeRule.onNodeWithTag("cook.filter.resultCount").assertIsDisplayed()
        composeRule.onNodeWithText("符合条件 ${expected.recipes.size} 道菜").assertIsDisplayed()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.onNodeWithText("看做法").performScrollTo().performClick()
        val title = composeRule.onNode(SemanticsMatcher("菜谱详情") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("recipe.detail.") == true
        }).fetchSemanticsNode().config[SemanticsProperties.TestTag]
        assertTrue(expected.recipes.any { "recipe.detail.${it.id}" == title })
        composeRule.onNodeWithText("已有食材：土豆").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("recipe.matchedIngredients").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("收藏").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w1000dp-h850dp")
    fun inspirationRandomKeepsItsCapturedBatchWhenFiltersChange() {
        val results = "cook.results"
        val recipeRows = SemanticsMatcher("灵感菜谱") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("cook.recipe.") == true
        } and hasAnyAncestor(hasTestTag(results))
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodes(hasTestTag(results)).fetchSemanticsNodes().isNotEmpty()
        }
        val chooser = composeRule.onNodeWithTag("cook.chooser")
        chooser.performScrollToNode(hasText("省点心", substring = true))
        composeRule.onNode(hasText("省点心", substring = true) and hasAnyAncestor(hasTestTag("cook.chooser")))
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(recipeRows).fetchSemanticsNodes().size == 3
        }
        val batchIds = composeRule.onAllNodes(recipeRows).fetchSemanticsNodes().map {
            it.config[SemanticsProperties.TestTag].substringAfterLast('.').toInt()
        }
        val container = (composeRule.activity.application as CookApplication).container
        val snapshot = (container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        val recipes = batchIds.map { requireNotNull(snapshot.recipe(it)) }
        val otherTool = snapshot.data.food.tools.first { tool -> recipes.none { tool.name in it.tools } }.name

        chooser.performScrollToNode(hasText("从这几道里选"))
        composeRule.onNodeWithText("从这几道里选").performClick()
        val sceneSummary = hasTestTag("decide.pager") and hasContentDescription("难度标为「简单」", substring = true)
        composeRule.onNode(sceneSummary).assertExists()
        composeRule.onNodeWithText("筛选").performClick()
        val filters = "cook.filter.options"
        val toolChip = hasText(otherTool) and hasAnyAncestor(hasTestTag(filters))
        composeRule.onNodeWithText("符合条件 3 道菜").assertIsDisplayed()
        composeRule.onNodeWithTag(filters).performScrollToNode(hasText(otherTool))
        composeRule.onNode(toolChip).performClick()
        composeRule.onNodeWithText("符合条件 0 道菜").assertIsDisplayed()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.onNodeWithText("取消这次场景").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("当前候选池为空，请放宽条件后再随机决定。").assertIsDisplayed()
        composeRule.onNodeWithText("看做法").assertDoesNotExist()
        composeRule.onNodeWithText("筛选").performClick()
        composeRule.onNodeWithText("符合条件 0 道菜").assertIsDisplayed()
        composeRule.onNodeWithTag(filters).performScrollToNode(hasText(otherTool))
        composeRule.onNode(toolChip).performClick()
        composeRule.onNodeWithText("符合条件 3 道菜").assertIsDisplayed()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.onNode(sceneSummary).assertDoesNotExist()
        composeRule.onNodeWithText("看做法").performScrollTo().performClick()
        val detail = composeRule.onNode(SemanticsMatcher("菜谱详情") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("recipe.detail.") == true
        })
        assertTrue(batchIds.any { detail.fetchSemanticsNode().config[SemanticsProperties.TestTag] == "recipe.detail.$it" })
    }

    @Test
    @Config(qualifiers = "w1000dp-h850dp")
    fun randomCanRemoveTheSceneAndKeepTheDifficultyFilter() {
        awaitText("随机一道")
        val chooser = composeRule.onNodeWithTag("cook.chooser")
        val easyScene = hasText("省点心", substring = true)
        chooser.performScrollToNode(easyScene)
        composeRule.onNode(easyScene and hasAnyAncestor(hasTestTag("cook.chooser"))).performClick()
        chooser.performScrollToNode(hasText("随机一道"))
        composeRule.onNodeWithText("随机一道").performClick()
        composeRule.onNodeWithText("筛选").performClick()
        val difficulty = hasText("困难") and hasAnyAncestor(hasTestTag("cook.filter.options"))
        composeRule.onNode(difficulty).performClick()
        composeRule.onNodeWithText("符合条件 0 道菜").assertIsDisplayed()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.onNodeWithText("当前场景下没有符合条件的菜。可以取消场景或调整筛选。").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").performClick()
        composeRule.onNodeWithText("随机一道").assertIsEnabled().performClick()
        composeRule.onNodeWithText("取消这次场景").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("看做法").performScrollTo().performClick()

        val detail = composeRule.onNode(SemanticsMatcher("菜谱详情") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("recipe.detail.") == true
        }).fetchSemanticsNode()
        val id = detail.config[SemanticsProperties.TestTag].substringAfterLast('.').toInt()
        val container = (composeRule.activity.application as CookApplication).container
        val snapshot = (container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        assertEquals("困难", snapshot.recipe(id)?.difficulty)
    }

    @Test
    fun fridgeBatchDraftAndCategorySurviveActivityRecreation() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("冰箱").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("冰箱").performClick()
        composeRule.onNodeWithText("入库").performClick()
        composeRule.onNodeWithText("蓝莓").performClick()
        composeRule.onNodeWithText("搜索或输入食材").performTextInput("牛奶")
        composeRule.onNodeWithText("添加").performClick()
        val form = "manual-fridge-intake-form"
        composeRule.onNodeWithTag(form).performScrollToNode(hasText("乳品熟食"))
        composeRule.onNode(hasText("乳品熟食") and hasAnyAncestor(hasTestTag(form))).performClick()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("放入（2 样）").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("放入（2 样）").performClick()
        val store = (composeRule.activity.application as CookApplication).container.fridgeStore
        assertEquals(mapOf("蓝莓" to "dairy", "牛奶" to "dairy"), store.state.value.stock.associate { it.ingredientName to it.categoryId })
    }

    @Test
    fun newFridgeCategoryKeepsTheIntakeAndItsOwnDraftOnRecreation() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("冰箱").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("冰箱").performClick()
        composeRule.onNodeWithText("入库").performClick()
        composeRule.onNodeWithText("蓝莓").performClick()
        composeRule.onNodeWithTag("manual-fridge-intake-form").performScrollToNode(hasText("新建分类"))
        composeRule.onNodeWithText("新建分类").performClick()
        val name = composeRule.onNode(hasText("分类名称") and hasSetTextAction())
        name.performTextInput("蔬菜水果")
        composeRule.onNodeWithText("保存分类").assertIsNotEnabled()
        name.performTextReplacement(" 这周早餐 ")

        composeRule.activityRule.scenario.recreate()
        composeRule.onNode(hasText(" 这周早餐 ") and hasSetTextAction()).assertExists()
        composeRule.onNodeWithText("保存分类").performClick()

        val store = (composeRule.activity.application as CookApplication).container.fridgeStore
        val custom = store.state.value.categories.single { it.name == "这周早餐" }
        assertTrue(store.state.value.stock.isEmpty())
        composeRule.onNodeWithText("放入（1 样）").performClick()
        val batch = store.state.value.stock.single()
        assertEquals("蓝莓", batch.ingredientName)
        assertEquals(custom.id, batch.categoryId)
    }

    @Test
    fun timerNotificationOpensTheReviewedWorkbenchAndRevealsTheTimerAgainWithoutADuplicateRoute() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("今天做什么菜").fetchSemanticsNodes().isNotEmpty()
        }
        val app = composeRule.activity.application as CookApplication
        val snapshot = (app.container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        val recipe = requireNotNull(snapshot.recipe(333))
        val goal = DietaryGoal.LESS_ADDED_SUGAR
        val click = finishedTimerNotification(recipe.id, recipe.name, goal)

        composeRule.onNodeWithText("冰箱").performClick()
        composeRule.onNodeWithText("入库").assertIsDisplayed()
        deliverIntent(click)
        composeRule.onNodeWithText("工作台 · ${recipe.name}").assertIsDisplayed()
        composeRule.onNodeWithText("计时结束").assertIsDisplayed()
        composeRule.onNodeWithText("做法").performClick()
        composeRule.onNodeWithText("已核对的制作步骤").assertExists()
        composeRule.onNodeWithText("计时结束").assertDoesNotExist()

        val store = app.container.workbenchStore
        val reviewed = requireNotNull(snapshot.reviewedRecipe(recipe, goal))
        composeRule.runOnIdle {
            store.toggleIngredient(recipe.id, "catalog:鸡蛋")
            store.toggleStep(recipe.id, RecipePresentation(reviewed.text).steps.first().id)
        }
        val progress = store.entry(recipe.id)
        val journal = app.container.cookingJournalStore.state.value
        composeRule.onNodeWithText("做完了").performClick()
        composeRule.onNodeWithText("料理完成了吗？").assertIsDisplayed()

        deliverIntent(click)
        composeRule.onNodeWithText("料理完成了吗？").assertDoesNotExist()
        composeRule.onNodeWithText("计时结束").assertIsDisplayed()

        composeRule.onNodeWithText("布局").performClick()
        composeRule.onNodeWithText("重置所有勾选").performClick()
        composeRule.onNodeWithText("重置所有勾选？").assertIsDisplayed()
        deliverIntent(click)
        composeRule.onNodeWithText("重置所有勾选？").assertDoesNotExist()
        composeRule.onNodeWithText("计时结束").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("料理完成了吗？").assertDoesNotExist()
        composeRule.onNodeWithText("计时结束").assertIsDisplayed()
        assertEquals(progress, store.entry(recipe.id))
        assertEquals(journal, app.container.cookingJournalStore.state.value)

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("入库").assertIsDisplayed()
        composeRule.onNodeWithText("计时结束").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w420dp-h900dp")
    fun timerNotificationShowsAboveRecipePhotosAndReturnsToTheSelectedPhoto() {
        awaitText("今天做什么菜")
        val container = (composeRule.activity.application as CookApplication).container
        val snapshot = (container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        val recipe = requireNotNull(snapshot.recipe(526))
        val photos = recipePhotos(snapshot.recipeText(recipe.id), snapshot.textLibrary, recipe.name)
        val cache = ReflectionHelpers.getField<LruCache<String, LoadedRecipeImage>>(container.recipeImages, "cache")
        val testImage = LoadedRecipeImage(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), byteArrayOf(), "image/png")
        photos.forEach { cache.put(it.url, testImage) }
        val click = finishedTimerNotification(recipe.id, recipe.name)

        deliverIntent(click)
        composeRule.onNodeWithText("做法").performClick()
        val firstPhoto = hasContentDescription(photos.first().label)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(firstPhoto)
        composeRule.onNode(firstPhoto).performClick()
        composeRule.onNodeWithText("下一张").performClick()
        val page = "2 / ${photos.size}"
        awaitText(page)

        deliverIntent(click)
        composeRule.onNodeWithText("返回配图").assertIsDisplayed()
        composeRule.onNodeWithText("计时结束").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("返回配图").performClick()
        composeRule.onNodeWithText(page).assertIsDisplayed()
        composeRule.onNodeWithText("关闭").performClick()
        composeRule.onNodeWithText("做法").assertIsSelected()
    }

    private fun finishedTimerNotification(recipeId: Int, recipeName: String, goal: DietaryGoal? = null): Intent {
        val app = composeRule.activity.application as CookApplication
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeRule.runOnIdle {
            val past = KitchenClockReading(System.currentTimeMillis() - 70_000)
            val timer = RecipeTimerState(durationSeconds = 60).start(past, recipeName, goal)
            app.container.workbenchStore.updateTimer(recipeId, timer)
            KitchenTimerScheduler.reschedulePersistedTimers(app)
        }
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        return shadowOf(notification.contentIntent).savedIntent
    }

    @Test
    fun coldBrowserOnlyLinksFinishTheTransientActivityAfterForwarding() {
        val urls = listOf(
            "https://cook.yunle.fun/recipes/share/?id=250&id=588",
            "https://cook.yunyoujun.cn/auth/callback?code=fixture&state=fixture",
        )
        composeRule.runOnUiThread {
            for (url in urls) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                Robolectric.buildActivity(MainActivity::class.java, intent).create().use { controller ->
                    val activity = controller.get()
                    val launched = requireNotNull(shadowOf(activity).nextStartedActivity)
                    assertEquals(url, launched.dataString)
                    assertEquals(setOf(Intent.CATEGORY_APP_BROWSER), launched.selector?.categories)
                    assertTrue("A browser-only link should not leave an activity in the app task: $url", activity.isFinishing)
                }
            }
        }
    }

    @Test
    fun latestShareBeforeFirstRenderStillOpensFromTheRecipeLibrary() {
        val first = Intent(Intent.ACTION_VIEW, Uri.parse("https://cook.yunle.fun/recipes/share/?id=250"))
        val secondUrl = "https://cook.yunle.fun/recipes/share/?id=588#ingredients"
        val second = Intent(Intent.ACTION_VIEW, Uri.parse(secondUrl))
        composeRule.runOnUiThread {
            Robolectric.buildActivity(MainActivity::class.java, first).create().use { controller ->
                controller.newIntent(second)

                val state = Bundle()
                controller.saveInstanceState(state)
                assertEquals(588L, state.getLong("sharedRecipe"))
                assertEquals(secondUrl, state.getString("sharedRecipeUrl"))
                assertTrue(state.getBoolean("sharedRecipeColdLaunch"))
            }
        }
    }

    @Test
    fun browserDestinationsCaughtByTheStaticFiltersReturnToTheSameUrlWithoutChangingInAppNavigation() {
        awaitText("今天做什么菜")
        openSharedRecipe(250)
        val browserLinks = listOf(
            "https://cook.yunyoujun.cn/auth/callback?code=fixture&state=fixture",
            "https://cook.yunyoujun.cn/auth/callback#ylf_sso=web-fixture",
            "https://cook.yunle.fun/recipes/share/?id=invalid",
            "https://cook.yunle.fun/recipes/share/?id=250&id=588",
        )
        for (url in browserLinks) {
            deliverViewLink(url)
            val launched = requireNotNull(shadowOf(composeRule.activity).nextStartedActivity)
            assertEquals(Intent.ACTION_VIEW, launched.action)
            assertEquals(url, launched.dataString)
            assertEquals(Intent.ACTION_MAIN, launched.selector?.action)
            assertEquals(setOf(Intent.CATEGORY_APP_BROWSER), launched.selector?.categories)
            assertFalse(composeRule.activity.isFinishing)
            composeRule.onNodeWithTag("recipe.detail.250").assertIsDisplayed()
        }

        for (url in listOf(
            "https://cook.yunyoujun.cn/auth/callback?platform=native",
            "https://cook.yunyoujun.cn/auth/callback?platform=web&platform=%6eative",
            "https://elsewhere.example/auth/callback?code=fixture",
            "https://elsewhere.example@cook.yunyoujun.cn/auth/callback?code=fixture",
            "https://cook.yunyoujun.cn:444/auth/callback?code=fixture",
            "https://cook.yunle.fun/recipes%2Fshare/?id=invalid",
        )) {
            deliverViewLink(url)
            assertNull(shadowOf(composeRule.activity).nextStartedActivity)
        }
        composeRule.onNodeWithTag("recipe.detail.250").assertIsDisplayed()
    }

    @Test
    fun missingSharedRecipeOpensTheSameUrlInABrowserAfterActivityRecreation() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("今天做什么菜").fetchSemanticsNodes().isNotEmpty()
        }
        val url = "https://cook.yunle.fun/recipes/share?id=9007199254740991&from=share%20card#ingredients"
        val shared = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        composeRule.activityRule.scenario.onActivity {
            ReflectionHelpers.callInstanceMethod<Unit>(
                it, "onNewIntent", ReflectionHelpers.ClassParameter.from(Intent::class.java, shared),
            )
        }
        composeRule.onNodeWithText("找不到分享的菜谱").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("在网页查看").performClick()

        val launch = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, launch.action)
        assertEquals(url, launch.dataString)
        assertEquals(Intent.ACTION_MAIN, launch.selector?.action)
        assertEquals(setOf(Intent.CATEGORY_APP_BROWSER), launch.selector?.categories)
        composeRule.onNodeWithText("找不到分享的菜谱").assertDoesNotExist()
    }

    @Test
    fun aSharedRecipeCanOpenInTheBrowserWhenTheCatalogFailsAndStillOpensLocallyAfterRetry() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("今天做什么菜").fetchSemanticsNodes().isNotEmpty()
        }
        val url = "https://cook.yunle.fun/recipes/share/?id=%32%35%30&from=share%20card#ingredients"
        val shared = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val repository = (composeRule.activity.application as CookApplication).container.catalogRepository
        val state = ReflectionHelpers.getField<MutableStateFlow<CatalogLoadState>>(repository, "_state")
        composeRule.runOnIdle {
            state.value = CatalogLoadState.Loading
        }
        composeRule.onNodeWithText("正在加载内置菜谱库…").assertIsDisplayed()
        composeRule.onNodeWithText("在网页查看").assertDoesNotExist()

        composeRule.activityRule.scenario.onActivity {
            ReflectionHelpers.callInstanceMethod<Unit>(
                it, "onNewIntent", ReflectionHelpers.ClassParameter.from(Intent::class.java, shared),
            )
        }
        composeRule.onNodeWithText("在网页查看").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = CatalogLoadState.Failed("测试菜谱库读取失败")
        }
        composeRule.onNodeWithText("无法读取内置菜谱数据包").assertIsDisplayed()
        composeRule.onNodeWithText("在网页查看").performClick()
        val browser = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(url, browser.dataString)
        assertEquals(setOf(Intent.CATEGORY_APP_BROWSER), browser.selector?.categories)

        composeRule.onNodeWithText("重试").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("进入厨房工作台").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("番茄炒蛋").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun cookingCompletionAndDetailUndoKeepOnlyTheIntendedHistoryAcrossActivityRecreation() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("今天做什么菜").fetchSemanticsNodes().isNotEmpty()
        }
        val app = composeRule.activity.application as CookApplication
        val store = app.container.cookingJournalStore
        val recipeId = JournalRecipeId.Catalog(250)
        val shared = Intent(Intent.ACTION_VIEW, Uri.parse("https://cook.yunle.fun/recipes/share/?id=250"))
        composeRule.activityRule.scenario.onActivity {
            ReflectionHelpers.callInstanceMethod<Unit>(
                it, "onNewIntent", ReflectionHelpers.ClassParameter.from(Intent::class.java, shared),
            )
        }
        val list = composeRule.onNode(hasScrollToNodeAction())
        val openWorkbench = composeRule.onNodeWithText("进入厨房工作台")
        val learn = composeRule.onNodeWithText("记为学会")
        list.performScrollToNode(hasText("我做过这道"))
        learn.assertDoesNotExist()
        list.performScrollToNode(hasText("进入厨房工作台"))
        openWorkbench.performClick()
        composeRule.onNodeWithText("做完了").performClick()
        composeRule.onNodeWithText("跳过").performClick()
        assertTrue(store.state.value.cooked.isEmpty())

        composeRule.onNodeWithText("做完了").performClick()
        composeRule.onNodeWithText("今天学会了").performClick()
        composeRule.onNodeWithText("获得菜肴徽章").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("获得菜肴徽章").assertIsDisplayed()
        composeRule.onNodeWithText("撤销本次确认").performClick()
        assertTrue(store.state.value.learned.isEmpty())
        assertTrue(store.state.value.cooked.isEmpty())

        composeRule.onNodeWithText("做完了").performClick()
        composeRule.onNodeWithText("只记为做过").performClick()
        val cooked = store.state.value.cooked.single()
        assertEquals(recipeId, cooked.recipeId)
        assertTrue(store.state.value.learned.isEmpty())

        composeRule.onNodeWithText("返回").performClick()
        list.performScrollToNode(hasText("记为学会"))
        learn.performClick()
        composeRule.onNodeWithText("撤销本次确认").performClick()
        assertEquals(listOf(cooked), store.state.value.cooked)
        assertTrue(store.state.value.learned.isEmpty())
        learn.performClick()
        composeRule.onNodeWithText("完成").performClick()
        val learned = store.state.value.learned.single()
        composeRule.onNodeWithText("已学会 ·", substring = true).performClick()
        composeRule.onNodeWithText("在做菜足迹中查看").performClick()
        composeRule.onNodeWithText("删除做过记录").performClick()
        composeRule.onNodeWithText("移除").performClick()
        assertTrue(store.state.value.cooked.isEmpty())
        assertEquals(listOf(learned), store.state.value.learned)
        composeRule.onNodeWithText("已学会 ·", substring = true).assertIsDisplayed()
    }

    @Test
    fun reviewedRecipeKeepsItsGoalAcrossActivityRecreationAndIntoTheWorkbench() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("今天做什么菜").fetchSemanticsNodes().isNotEmpty()
        }
        val container = (composeRule.activity.application as CookApplication).container
        val snapshot = (container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        val goal = DietaryGoal.LESS_ADDED_SUGAR
        val recipe = requireNotNull(snapshot.recipe(333))
        val reviewed = requireNotNull(snapshot.reviewedRecipe(recipe, goal))
        val summary = "饮食目标核对 · ${goal.title}"
        val list = composeRule.onNode(hasScrollToNodeAction())

        list.performScrollToNode(hasText(goal.title))
        composeRule.onNodeWithText(goal.title).performClick()
        list.performScrollToNode(hasText(recipe.name, substring = true))
        composeRule.onNodeWithText(recipe.name, substring = true).performClick()
        list.performScrollToNode(hasText(summary))

        composeRule.runOnIdle { container.preferencesStore.selectDietaryGoal(DietaryGoal.BALANCED_PAIRING) }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("查看已核对做法").fetchSemanticsNodes().isNotEmpty()
        }
        list.performScrollToNode(hasText("分享已核对原文"))
        composeRule.onNodeWithText("分享已核对原文").performClick()
        val chooser = shadowOf(composeRule.activity).nextStartedActivity
        val share = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, share.action)
        assertTrue(requireNotNull(share.getStringExtra(Intent.EXTRA_TEXT)).endsWith(reviewed.entry.sourceLink))

        composeRule.onNodeWithText("查看已核对做法").performClick()
        composeRule.onNodeWithText("已核对的图文做法").assertIsDisplayed()
        list.performScrollToNode(hasText("西葫芦炒鸡蛋是一道清爽可口的家常快手菜", substring = true))
        composeRule.onNodeWithText("预估卡路里", substring = true).assertDoesNotExist()

        list.performScrollToNode(hasText("进入厨房工作台"))
        composeRule.onNodeWithText("进入厨房工作台").performClick()
        composeRule.onNodeWithText(summary).assertIsDisplayed()
        list.performScrollToNode(hasText("已核对的制作步骤"))
        composeRule.onNodeWithText("已核对的制作步骤").assertIsDisplayed()
        composeRule.onNodeWithText("饮食目标核对 · 均衡搭配").assertDoesNotExist()

        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("返回").performClick()
        list.performScrollToNode(hasText("随机一道"))
        composeRule.onNodeWithText("随机一道").performClick()
        composeRule.onNodeWithText("看做法").performClick()
        list.performScrollToNode(hasText("饮食目标核对 · 均衡搭配"))
        composeRule.onNodeWithText("饮食目标核对 · 均衡搭配").assertIsDisplayed()
    }
}
