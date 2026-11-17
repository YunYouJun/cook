package cn.yunyoujun.cook

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityRecreationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("筛选条件与推荐模式").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("简单").performClick()
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
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
}
