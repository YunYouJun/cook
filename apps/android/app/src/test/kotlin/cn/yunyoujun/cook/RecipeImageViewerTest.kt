package cn.yunyoujun.cook

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import cn.yunyoujun.cook.data.remote.RecipeImageLoader
import cn.yunyoujun.cook.ui.screens.RecipeImageViewer
import cn.yunyoujun.cook.ui.screens.RecipePhoto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipeImageViewerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loadsSelectedPhotoRetriesAnotherAndSharesItsOriginalBytes() {
        val photos = listOf(
            RecipePhoto(4, "https://cook.example/first.png", "第一张图", 1f),
            RecipePhoto(7, "https://cook.example/second.png", "第二张图", 1f),
        )
        val first = png(0xFFCC7700.toInt())
        val second = png(0xFF117755.toInt())
        val secondAttempts = AtomicInteger()
        val loader = RecipeImageLoader { url ->
            if (url == photos[0].url) first
            else if (secondAttempts.incrementAndGet() == 1) throw IOException("Temporarily unavailable")
            else second
        }

        compose.setContent {
            MaterialTheme { RecipeImageViewer(264, photos, initialBlock = 4, loader, onClose = {}) }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("第一张图").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("第一张图").assertIsDisplayed()
        compose.onNodeWithText("缩小").assertIsNotEnabled()
        compose.onNodeWithContentDescription("第一张图").performTouchInput { doubleClick() }
        compose.onNodeWithText("缩小").assertIsEnabled()
        compose.onNodeWithContentDescription("第一张图").performTouchInput { doubleClick() }
        compose.onNodeWithText("缩小").assertIsNotEnabled()
        compose.onNodeWithText("放大").performClick()
        compose.onNodeWithText("缩小").assertIsEnabled()
        compose.onNodeWithText("下一张").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { secondAttempts.get() == 1 }
        compose.onNodeWithText("配图加载失败").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsNotEnabled()
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("第二张图").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("第二张图").assertIsDisplayed()
        compose.onNodeWithText("缩小").assertIsNotEnabled()
        compose.onNodeWithContentDescription("第二张图").performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f), end0 = center - Offset(90f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(90f, 0f),
            )
        }
        compose.onNodeWithText("缩小").assertIsEnabled()
        assertEquals(2, secondAttempts.get())

        compose.onNodeWithText("分享").performClick()
        var chooser: Intent? = null
        compose.waitUntil(timeoutMillis = 5_000) {
            chooser = shadowOf(compose.activity).nextStartedActivity
            chooser != null
        }
        val share = requireNotNull(chooser?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        val uri = requireNotNull(share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("image/png", share.type)
        assertEquals("content", uri.scheme)
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val resolver = compose.activity.contentResolver
        val file = resolver.openInputStream(uri)
        assertNotNull(file)
        assertArrayEquals(second, file?.use { it.readBytes() })
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
    }
}
