package cn.yunyoujun.cook

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import cn.yunyoujun.cook.ui.components.WindowFold
import cn.yunyoujun.cook.ui.screens.WorkbenchLayout
import cn.yunyoujun.cook.ui.screens.workbenchFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KitchenWorkbenchFramesTest {
    private val density = Density(1f)

    @Test
    fun wideWindowsUseColumnsAndLargeTextFallsBackToOnePane() {
        val size = IntSize(900, 640)
        val columns = density.workbenchFrames(size, IntOffset.Zero, null, WorkbenchLayout.AUTOMATIC)
        assertEquals(IntRect(0, 0, 557, 640), columns.instructions)
        assertEquals(IntRect(558, 0, 900, 640), columns.preparation)

        val largeText = Density(1f, fontScale = 1.7f)
            .workbenchFrames(size, IntOffset.Zero, null, WorkbenchLayout.AUTOMATIC)
        assertNull(largeText.instructions)
        assertEquals(IntRect(0, 0, 900, 640), largeText.preparation)
    }

    @Test
    fun foldGeometryUsesActualWindowCoordinatesAndLeavesTheHingeEmpty() {
        val horizontal = density.workbenchFrames(
            size = IntSize(800, 740),
            originInWindow = IntOffset(16, 100),
            fold = WindowFold(IntRect(0, 365, 832, 385), horizontal = true),
            layout = WorkbenchLayout.AUTOMATIC,
        )
        assertEquals(IntRect(0, 0, 800, 265), horizontal.instructions)
        assertEquals(IntRect(0, 285, 800, 740), horizontal.preparation)

        val vertical = density.workbenchFrames(
            size = IntSize(900, 640),
            originInWindow = IntOffset(30, 80),
            fold = WindowFold(IntRect(540, 0, 560, 780), horizontal = false),
            layout = WorkbenchLayout.TABLETOP,
        )
        assertEquals(IntRect(0, 0, 510, 640), vertical.instructions)
        assertEquals(IntRect(530, 0, 900, 640), vertical.preparation)
    }

    @Test
    fun narrowHingeRegionKeepsCompactControlsInTheUsablePane() {
        val frames = density.workbenchFrames(
            size = IntSize(600, 640),
            originInWindow = IntOffset(0, 80),
            fold = WindowFold(IntRect(125, 0, 141, 720), horizontal = false),
            layout = WorkbenchLayout.AUTOMATIC,
        )
        assertNull(frames.instructions)
        assertEquals(IntRect(141, 0, 600, 640), frames.preparation)
    }
}
