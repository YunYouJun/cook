package cn.yunyoujun.cook.ui.state

import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.data.local.DevicePreferencesSnapshot
import cn.yunyoujun.cook.data.repository.CookDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAndDiscoveryTest {
    @Test
    fun clearFiltersPreservesIngredientsWhileClearSessionClearsBoth() {
        val controller = CookSessionController()
        controller.toggleStuff("番茄")
        controller.toggleStuff("鸡蛋")
        controller.toggleTool("炒锅")
        controller.toggleDifficulty("简单")
        controller.setKeyword("番茄")

        val prefs = DevicePreferencesSnapshot(rememberedDietaryGoal = DietaryGoal.WEIGHT_AWARE, noSpicy = true)
        assertTrue(controller.conditions.value.isFilterActive(prefs))

        controller.clearFiltersOnly()
        assertEquals(listOf("番茄", "鸡蛋"), controller.conditions.value.selectedStuff)
        assertEquals("番茄", controller.conditions.value.keyword)
        assertEquals(null, controller.conditions.value.tool)
        assertTrue(controller.conditions.value.difficulty.isEmpty())
        assertNull(controller.conditions.value.dietaryGoal(prefs))
        assertEquals(DietaryGoal.WEIGHT_AWARE, CookSessionConditions().dietaryGoal(prefs))
        assertFalse(controller.conditions.value.isFilterActive(prefs.copy(noSpicy = false)))

        controller.clearSessionConditions()
        assertTrue(controller.conditions.value.selectedStuff.isEmpty())
        assertEquals("", controller.conditions.value.keyword)
    }

    @Test
    fun discoverySamplePrioritizesUnseenRecipes() {
        val pool = (1..6).map { idx ->
            Recipe(id = idx, name = "测试菜$idx", stuff = listOf("番茄"))
        }
        val first = CookDiscovery.sample(pool, count = 3, avoiding = emptyList())
        assertEquals(3, first.size)
        val second = CookDiscovery.sample(pool, count = 3, avoiding = first)
        assertEquals(3, second.size)
        val firstIds = first.map { it.id }.toSet()
        val secondIds = second.map { it.id }.toSet()
        assertTrue(firstIds.intersect(secondIds).isEmpty())
    }
}
