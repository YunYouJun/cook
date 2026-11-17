package cn.yunyoujun.cook.core.recommend

import cn.yunyoujun.cook.core.model.IncompatibleRule

fun findIncompatibleRules(
    ingredients: List<String>,
    rules: List<IncompatibleRule>,
): List<IncompatibleRule> {
    if (ingredients.size < 2) return emptyList()
    val selected = ingredients.toSet()
    return rules.filter { it.foodA in selected && it.foodB in selected }
}
