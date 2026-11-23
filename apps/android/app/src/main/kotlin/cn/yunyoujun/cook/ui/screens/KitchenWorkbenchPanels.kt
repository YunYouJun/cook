package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.ui.components.WindowFold
import cn.yunyoujun.cook.ui.components.rememberWindowFold
import kotlin.math.roundToInt

internal enum class WorkbenchLayout(val label: String) { AUTOMATIC("自动"), TABLETOP("台面") }

private enum class WorkbenchPane(val label: String) {
    PREPARATION("备料与计时"), INSTRUCTIONS("做法"),
}

internal data class WorkbenchFrames(val preparation: IntRect, val instructions: IntRect? = null)

internal fun Density.workbenchFrames(
    size: IntSize,
    originInWindow: IntOffset,
    fold: WindowFold?,
    layout: WorkbenchLayout,
): WorkbenchFrames {
    val width = size.width
    val height = size.height
    val bounds = IntRect(0, 0, width, height)
    if (fold != null) {
        val hinge = fold.bounds.translate(-originInWindow)
        if (fold.horizontal && hinge.left <= 1 && hinge.right >= width - 1 && hinge.top > 0 && hinge.bottom < height) {
            val instructions = IntRect(0, 0, width, hinge.top)
            val preparation = IntRect(0, hinge.bottom, width, height)
            if (minOf(instructions.height, preparation.height) >= 144.dp.roundToPx()) {
                return WorkbenchFrames(preparation, instructions)
            }
            return WorkbenchFrames(if (preparation.height >= instructions.height) preparation else instructions)
        }
        if (!fold.horizontal && hinge.top <= 1 && hinge.bottom >= height - 1 && hinge.left > 0 && hinge.right < width) {
            val instructions = IntRect(0, 0, hinge.left, height)
            val preparation = IntRect(hinge.right, 0, width, height)
            if (minOf(instructions.width, preparation.width) >= 210.dp.roundToPx()) {
                return WorkbenchFrames(preparation, instructions)
            }
            return WorkbenchFrames(if (preparation.width >= instructions.width) preparation else instructions)
        }
    }
    val divider = 1.dp.roundToPx().coerceAtLeast(1)
    if (layout == WorkbenchLayout.TABLETOP && height >= 400.dp.roundToPx()) {
        val split = (height - divider) / 2
        return WorkbenchFrames(IntRect(0, split + divider, width, height), IntRect(0, 0, width, split))
    }
    if (width >= 640.dp.roundToPx() && height >= 320.dp.roundToPx() && fontScale < 1.5f) {
        val sidebar = (width * 0.38f).roundToInt().coerceIn(300.dp.roundToPx(), 420.dp.roundToPx())
        val split = width - sidebar
        return WorkbenchFrames(IntRect(split, 0, width, height), IntRect(0, 0, split - divider, height))
    }
    return WorkbenchFrames(bounds)
}

@Composable
internal fun KitchenWorkbenchPanels(
    recipeId: Int,
    layout: WorkbenchLayout,
    instructionsScroll: LazyListState,
    startsWithInstructions: Boolean,
    modifier: Modifier = Modifier,
    revealTimer: Boolean = false,
    onTimerRevealed: () -> Unit = {},
    instructionHeader: @Composable () -> Unit,
    preparation: LazyListScope.() -> Unit,
    instructions: LazyListScope.() -> Unit,
) {
    val fold = rememberWindowFold()
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    var pane by rememberSaveable(recipeId, startsWithInstructions) {
        mutableStateOf(if (startsWithInstructions && !revealTimer) WorkbenchPane.INSTRUCTIONS else WorkbenchPane.PREPARATION)
    }
    val preparationScroll = rememberLazyListState()
    LaunchedEffect(revealTimer) {
        if (revealTimer) {
            pane = WorkbenchPane.PREPARATION
            preparationScroll.requestScrollToItem(0)
            onTimerRevealed()
        }
    }

    SubcomposeLayout(
        modifier = modifier
            .background(MaterialTheme.colorScheme.outlineVariant)
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
            },
    ) { constraints ->
        val size = IntSize(constraints.maxWidth, constraints.maxHeight)
        val frames = workbenchFrames(size, origin, fold, layout)
        val leftOrTop = frames.instructions
        val prep = frames.preparation
        if (leftOrTop == null) {
            val content = subcompose("single") {
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        WorkbenchPane.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = pane == option,
                                onClick = { pane = option },
                                shape = SegmentedButtonDefaults.itemShape(index, WorkbenchPane.entries.size),
                                label = { Text(option.label) },
                            )
                        }
                    }
                    if (pane == WorkbenchPane.PREPARATION) WorkbenchPaneList(preparationScroll, preparation)
                    else {
                        instructionHeader()
                        WorkbenchPaneList(instructionsScroll, instructions)
                    }
                }
            }.single().measure(Constraints.fixed(prep.width, prep.height))
            layout(size.width, size.height) { content.place(prep.left, prep.top) }
        } else {
            val method = subcompose(WorkbenchPane.INSTRUCTIONS) {
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    instructionHeader()
                    WorkbenchPaneList(instructionsScroll, instructions)
                }
            }.single().measure(Constraints.fixed(leftOrTop.width, leftOrTop.height))
            val controls = subcompose(WorkbenchPane.PREPARATION) {
                WorkbenchPaneList(preparationScroll, preparation)
            }.single().measure(Constraints.fixed(prep.width, prep.height))
            layout(size.width, size.height) {
                method.place(leftOrTop.left, leftOrTop.top)
                controls.place(prep.left, prep.top)
            }
        }
    }
}

@Composable
private fun WorkbenchPaneList(state: LazyListState, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        state = state,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
