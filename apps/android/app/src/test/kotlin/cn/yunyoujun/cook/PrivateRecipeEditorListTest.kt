package cn.yunyoujun.cook

import androidx.compose.runtime.saveable.SaverScope
import cn.yunyoujun.cook.core.recommend.StuffNormalizer
import cn.yunyoujun.cook.ui.screens.PrivateRecipeEditorList
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivateRecipeEditorListTest {
    @Test
    fun catalogAppendPreservesImportedEntriesAndRejectsAliasDuplicatesAfterRestore() {
        val original = listOf("西红柿", "葱、姜（同一份）", "铁锅\n或铸铁锅")
        val normalizer = StuffNormalizer(mapOf("西红柿" to "番茄"))
        val draft = restore(PrivateRecipeEditorList(original))

        draft.append("番茄", normalizer::normalize)
        assertEquals(original, draft.values)
        draft.append("鸡蛋", normalizer::normalize)
        assertEquals(original + "鸡蛋", restore(draft).values)
    }

    @Test
    fun manualEntrySeparatesLinesAndCommasAndKeepsTheDraftAfterRestore() {
        val draft = PrivateRecipeEditorList(listOf("旧名称"))
        draft.text = " 鸡蛋\r\n北豆腐，鸡蛋、香菇, 油 "

        val restored = restore(draft)
        assertEquals(draft.text, restored.text)
        assertEquals(listOf("鸡蛋", "北豆腐", "香菇", "油"), restored.values)
    }

    private fun restore(draft: PrivateRecipeEditorList): PrivateRecipeEditorList {
        val saver = PrivateRecipeEditorList.Saver
        val saved = with(saver) { SaverScope { it is String }.save(draft) }
        return requireNotNull(saver.restore(requireNotNull(saved)))
    }
}
