package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.ui.components.WindowFold
import cn.yunyoujun.cook.ui.components.rememberWindowFold
import kotlin.math.roundToInt

internal data class CookDiscoveryFrames(val chooser: IntRect, val results: IntRect? = null)

internal fun Density.cookDiscoveryFrames(
    size: IntSize,
    originInWindow: IntOffset,
    fold: WindowFold?,
): CookDiscoveryFrames {
    val (width, height) = size
    val whole = IntRect(0, 0, width, height)
    val hinge = fold?.bounds?.translate(-originInWindow)
    val canShowTwoPanes = height >= 260.dp.roundToPx() && fontScale < 1.5f
    if (hinge != null && fold.horizontal && hinge.left <= 1 && hinge.right >= width - 1 &&
        hinge.top > 0 && hinge.bottom < height
    ) {
        val top = IntRect(0, 0, width, hinge.top)
        val bottom = IntRect(0, hinge.bottom, width, height)
        return CookDiscoveryFrames(if (top.height >= bottom.height) top else bottom)
    }
    if (hinge != null && !fold.horizontal && hinge.top <= 1 && hinge.bottom >= height - 1 &&
        hinge.left > 0 && hinge.right < width
    ) {
        val left = IntRect(0, 0, hinge.left, height)
        val right = IntRect(hinge.right, 0, width, height)
        return if (canShowTwoPanes && minOf(left.width, right.width) >= 300.dp.roundToPx()) {
            CookDiscoveryFrames(left, right)
        } else {
            CookDiscoveryFrames(if (left.width >= right.width) left else right)
        }
    }
    if (!canShowTwoPanes || width < 720.dp.roundToPx()) return CookDiscoveryFrames(whole)

    val sidebar = (width * 0.42f).roundToInt().coerceIn(300.dp.roundToPx(), 440.dp.roundToPx())
    val divider = 1.dp.roundToPx().coerceAtLeast(1)
    return CookDiscoveryFrames(IntRect(0, 0, sidebar, height), IntRect(sidebar + divider, 0, width, height))
}

@Composable
internal fun CookDiscoveryLayout(
    overlay: @Composable (expanded: Boolean) -> Unit,
    content: @Composable (sidebarWidth: Dp?, separation: Dp) -> Unit,
) {
    val fold = rememberWindowFold()
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
        },
    ) {
        val frames = density.cookDiscoveryFrames(IntSize(constraints.maxWidth, constraints.maxHeight), origin, fold)
        val chooser = frames.chooser
        val results = frames.results
        with(density) {
            if (results == null) {
                Box(
                    Modifier.absoluteOffset { IntOffset(chooser.left, chooser.top) }
                        .size(chooser.width.toDp(), chooser.height.toDp())
                        .clipToBounds(),
                ) {
                    content(null, 0.dp)
                }
            } else {
                content(chooser.width.toDp(), (results.left - chooser.right).toDp())
            }
        }
        // Keep dialogs at the same composition site across compact and expanded layouts.
        overlay(results != null)
    }
}
