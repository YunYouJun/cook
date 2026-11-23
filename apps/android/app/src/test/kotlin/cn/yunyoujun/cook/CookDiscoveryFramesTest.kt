package cn.yunyoujun.cook

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import cn.yunyoujun.cook.ui.components.WindowFold
import cn.yunyoujun.cook.ui.screens.cookDiscoveryFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CookDiscoveryFramesTest {
    @Test
    fun usesTwoPanesWhenBothFitAndKeepsACompactLayoutForLargeText() {
        val tablet = IntSize(820, 960)
        assertNotNull(Density(1f).cookDiscoveryFrames(tablet, IntOffset.Zero, null).results)
        assertNull(Density(1f, 2f).cookDiscoveryFrames(tablet, IntOffset.Zero, null).results)
        assertNull(Density(1f).cookDiscoveryFrames(IntSize(400, 850), IntOffset.Zero, null).results)
    }

    @Test
    fun accountsForTheNavigationInsetsAndKeepsBothPanesClearOfTheHinge() {
        val size = IntSize(820, 640)
        val origin = IntOffset(20, 32)
        val verticalFold = WindowFold(IntRect(410, 0, 430, 720), horizontal = false)
        val horizontalFold = WindowFold(IntRect(0, 280, 870, 310), horizontal = true)
        val density = Density(1f)
        val split = density.cookDiscoveryFrames(size, origin, verticalFold)
        assertEquals(IntRect(0, 0, 390, 640), split.chooser)
        assertEquals(IntRect(410, 0, 820, 640), split.results)

        val tabletop = density.cookDiscoveryFrames(size, origin, horizontalFold)
        assertEquals(IntRect(0, 278, 820, 640), tabletop.chooser)
        assertNull(tabletop.results)
    }
}
