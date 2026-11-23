package cn.yunyoujun.cook.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.flowOf

internal data class WindowFold(val bounds: IntRect, val horizontal: Boolean)

@Composable
internal fun rememberWindowFold(): WindowFold? {
    val activity = LocalActivity.current
    val layoutChanges = remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) } ?: flowOf(null)
    }
    val windowInfo by layoutChanges.collectAsStateWithLifecycle(initialValue = null)
    return remember(windowInfo) {
        windowInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull {
            it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL
        }?.let {
            val bounds = it.bounds
            WindowFold(
                IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                horizontal = it.orientation == FoldingFeature.Orientation.HORIZONTAL,
            )
        }
    }
}
