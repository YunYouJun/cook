package cn.yunyoujun.cook.data.repository

import cn.yunyoujun.cook.core.dietary.InstitutionalLowGICatalog
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.recommend.Recommender

data class CookDiscoveryFilters(
    val easyOnly: Boolean = false,
    val methodGroup: String? = null,
    val tag: String? = null,
) {
    val isEmpty: Boolean
        get() = !easyOnly && methodGroup.isNullOrEmpty() && tag.isNullOrEmpty()
}

enum class CookDiscoveryScene(
    val rawValue: String,
    val label: String,
    val emoji: String,
    val title: String,
    val story: String,
    val explanation: String,
    val filters: CookDiscoveryFilters,
) {
    EASY(
        rawValue = "easy",
        label = "省点心",
        emoji = "🍲",
        title = "下班了，想对自己好一点",
        story = "包放下，饭好好吃。今天更想……",
        explanation = "先看看已标为「简单」的做法",
        filters = CookDiscoveryFilters(easyOnly = true),
    ),
    SOUP(
        rawValue = "soup",
        label = "来点热乎的",
        emoji = "🍲",
        title = "想端起一碗热乎的。",
        story = "锅里咕嘟咕嘟，桌上多了一只汤碗。今天，想从一碗汤开始。",
        explanation = "先看看标有「汤」的汤羹做法",
        filters = CookDiscoveryFilters(methodGroup = "汤羹"),
    ),
    HOME(
        rawValue = "home",
        label = "换个家常口味",
        emoji = "🍚",
        title = "熟悉的饭桌，也想换道菜。",
        story = "米饭已经盛好，想找道有家常味、又不是昨天那一道的菜。",
        explanation = "先看看标有「家常菜」的做法",
        filters = CookDiscoveryFilters(tag = "家常菜"),
    ),
    NEXT_DAY_LUNCH(
        rawValue = "next-day-lunch",
        label = "次日便当",
        emoji = "🍱",
        title = "提前备好，明天带饭。",
        story = "先挑带饭的菜，再搭配主食和蔬菜。",
        explanation = "份量和用油参考见图文做法。须快速冷却、冷藏运输和存放，次日彻底复热。",
        filters = CookDiscoveryFilters(tag = "次日便当"),
    ),
    INSTITUTIONAL_LOW_GI(
        rawValue = "institutional-low-gi",
        label = "机构低 GI 食谱",
        emoji = "📖",
        title = "按机构原方，找一道低 GI 食谱。",
        story = "先查看配方要求，再打开机构原文。",
        explanation = InstitutionalLowGICatalog.EXPLANATION,
        filters = CookDiscoveryFilters(tag = InstitutionalLowGICatalog.TAG),
    ),
}

object CookDiscovery {
    fun filter(recipes: List<Recipe>, filters: CookDiscoveryFilters): List<Recipe> =
        recipes.filter { matches(it, filters) }

    fun matches(recipe: Recipe, filters: CookDiscoveryFilters): Boolean {
        val group = filters.methodGroup?.takeIf { it.isNotEmpty() }
        if (group != null && Recommender.METHOD_GROUPS.none { it.label == group }) {
            return false
        }
        val tag = filters.tag?.takeIf { it.isNotEmpty() }
        return (!filters.easyOnly || recipe.difficulty == "简单") &&
            Recommender.matchesTaste(
                recipe = recipe,
                selectedGroups = group?.let { listOf(it) },
                noSpicy = false,
                difficulty = null,
            ) &&
            (tag == null || tag in recipe.tags)
    }

    fun reason(recipe: Recipe, filters: CookDiscoveryFilters): String {
        val reasons = ArrayList<String>()
        if (filters.easyOnly && recipe.difficulty == "简单") {
            reasons.add("难度标为「简单」")
        }
        val group = Recommender.METHOD_GROUPS.firstOrNull { it.label == filters.methodGroup }
        if (group != null) {
            val methods = recipe.methods.flatMap { it.split('、', '/') }.filter { it.isNotEmpty() }
            val matchedTag = group.tags.firstOrNull { it in recipe.tags }
            val matchedMethod = group.methods.firstOrNull { it in methods }
            if (matchedTag != null) {
                reasons.add("标有「$matchedTag」标签")
            } else if (matchedMethod != null) {
                reasons.add("做法记录为「$matchedMethod」")
            }
        }
        val tag = filters.tag?.takeIf { it.isNotEmpty() }
        if (tag != null && tag in recipe.tags) {
            reasons.add("标有「$tag」标签")
        }
        if (reasons.isNotEmpty()) {
            return reasons.distinct().joinToString(" · ")
        }
        val difficulty = recipe.difficulty?.takeIf { it.isNotEmpty() }
        if (difficulty != null) {
            return "难度标为「$difficulty」"
        }
        if (recipe.stuff.isNotEmpty()) {
            return "记录食材：${recipe.stuff.joinToString("、")}"
        }
        return "完整用料请查看做法"
    }

    fun sample(recipes: List<Recipe>, count: Int = 3, avoiding: List<Recipe> = emptyList()): List<Recipe> {
        if (count <= 0) return emptyList()
        val seenNames = HashSet<String>()
        val unique = recipes.filter { seenNames.add(it.name.trim()) }
        val prevNames = avoiding.map { it.name.trim() }.toSet()
        val fresh = unique.filter { it.name.trim() !in prevNames }.shuffled()
        val repeated = unique.filter { it.name.trim() in prevNames }.shuffled()
        return (fresh + repeated).take(count)
    }
}
