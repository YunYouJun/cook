package cn.yunyoujun.cook.platform

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.roundToInt

internal class KitchenSceneAccessibility(
    view: View,
    private val radius: Float,
    private val targets: () -> List<CookSceneTarget>,
    private val selected: (String) -> Boolean,
    private val preparingMenu: () -> Boolean,
    private val pick: (String) -> Unit,
) : ExploreByTouchHelper(view) {
    override fun getVirtualViewAt(x: Float, y: Float): Int =
        nearestSceneTarget(targets(), x, y, radius)?.id ?: INVALID_ID

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        for (target in targets()) virtualViewIds.add(target.id)
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, info: AccessibilityNodeInfoCompat) {
        val target = targets().firstOrNull { it.id == virtualViewId }
        if (target == null) {
            info.contentDescription = "台面食材"
            info.setBoundsInParent(Rect())
            info.isEnabled = false
            return
        }
        val pot = target.name == CLOUD_KITCHEN_POT
        info.className = Button::class.java.name
        info.contentDescription = if (pot) "锅，开火推荐菜谱" else "台面上的${target.name}"
        info.setBoundsInParent(Rect(
            (target.x - radius).roundToInt(), (target.y - radius).roundToInt(),
            (target.x + radius).roundToInt(), (target.y + radius).roundToInt(),
        ))
        if (pot && preparingMenu()) {
            info.stateDescription = "正在准备菜单"
            info.isEnabled = false
        } else {
            info.isSelected = !pot && selected(target.name)
            info.isClickable = true
            info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        }
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        val target = targets().firstOrNull { it.id == virtualViewId } ?: return false
        if (target.name == CLOUD_KITCHEN_POT && preparingMenu()) return false
        pick(target.name)
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }
}
