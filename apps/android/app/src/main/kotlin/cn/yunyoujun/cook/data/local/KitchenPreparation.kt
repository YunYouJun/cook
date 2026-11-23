package cn.yunyoujun.cook.data.local

import cn.yunyoujun.cook.core.recipe.RecipeAmounts
import cn.yunyoujun.cook.core.recipe.RecipePresentation

internal val RecipePresentation.Quantity.preparationKey: String get() = "$name=$amount"

internal fun List<RecipePresentation.Quantity>.preparationQuantity(name: String): RecipePresentation.Quantity? {
    val key = RecipeAmounts.key(name)
    return singleOrNull { RecipeAmounts.key(it.name) == key || it.annotation?.aliases?.contains(name) == true }
}

internal data class KitchenPreparationItem(
    val key: String,
    val labels: List<String>,
    val quantity: RecipePresentation.Quantity?,
    val isTool: Boolean,
    val previousKeys: Set<String> = emptySet(),
) {
    fun isChecked(checked: Set<String>): Boolean = key in checked || previousKeys.any { it in checked }
}

private val kitchenToolNames = setOf(
    "锅", "炒锅", "平底锅", "不粘锅", "汤锅", "砂锅", "蒸锅", "锅铲", "菜刀", "刀", "砧板", "案板",
    "碗", "盆", "盘子", "筷子", "勺子", "烤箱", "空气炸锅", "微波炉", "电饭煲", "高压锅", "打蛋器", "烤盘",
    "深锅", "炖煮锅", "厨房温度计", "漏勺", "笊篱", "蒸笼垫", "洗菜盆",
)
private val toolLabelSeparator = Regex("[\\s（(、，,/／]+")

private fun toolLabel(name: String): String = if (name == "一口大锅") "炒锅" else name

private fun toolKey(name: String): String = toolLabel(
    name.removePrefix("工具：").removePrefix("工具:").trim().trimStart('`')
        .split(toolLabelSeparator, limit = 2).first().trimEnd('`'),
)

private fun legacyIngredientName(label: String): String {
    val amount = label.substringAfter(' ', "")
    val name = if (RecipeAmounts.literalMeasurement(amount) != null) label.substringBefore(' ') else label
    return RecipeAmounts.key(name)
}

internal fun kitchenPreparationItems(
    presentation: RecipePresentation?,
    fallbackIngredients: List<String>,
    tools: List<String>,
    legacyCatalogIngredients: List<String> = emptyList(),
): List<KitchenPreparationItem> {
    val quantities = presentation?.allQuantities.orEmpty().distinctBy { it.preparationKey }
    val explicit = presentation?.ingredients.orEmpty().ifEmpty { fallbackIngredients }
        .map(String::trim).filter(String::isNotEmpty).distinct()
    // Older ordinary recipes without quantities used the catalog check keys.
    val legacy = if (quantities.isEmpty()) legacyCatalogIngredients.filter { it.isNotBlank() }.distinct() else emptyList()
    val sourceByName = explicit.groupBy(::legacyIngredientName)
    val aliases = legacy.groupBy { sourceByName[RecipeAmounts.key(it)]?.singleOrNull() }
    val source = explicit + aliases[null].orEmpty().filterNot { it in explicit }
    val metadataTools = tools.map(String::trim).filter(String::isNotEmpty).distinct()
    val knownTools = kitchenToolNames + metadataTools.map(::toolLabel)
    val matches = source.associateWith { quantities.preparationQuantity(it) }
    val byMeasure = source.groupBy(matches::get)
    val rows = matches.map { (label, quantity) ->
        val labels = if (quantity == null) listOf(label) else byMeasure.getValue(quantity)
        KitchenPreparationItem(
            key = quantity?.preparationKey ?: "catalog:$label",
            labels = labels,
            quantity = quantity,
            isTool = toolKey(label) in knownTools,
            previousKeys = (if (quantity != null) labels else aliases[label].orEmpty())
                .filterNot { quantity == null && it == label }.mapTo(mutableSetOf()) { "catalog:$it" },
        )
    }.distinctBy { it.key }
    val extraQuantities = quantities.filterNot(byMeasure::containsKey).map { quantity ->
        KitchenPreparationItem(quantity.preparationKey, listOf(quantity.name), quantity, toolKey(quantity.name) in knownTools)
    }
    val listedTools = (rows + extraQuantities).filter { it.isTool }.flatMap { it.labels }.map(::toolKey).toSet()
    val extraTools = metadataTools
        // An explicit pot in the source is more specific than the generic catalog category.
        .filterNot { it == "一口大锅" && listedTools.any { tool -> tool.endsWith("锅") } }
        .map(::toolLabel).distinct().filterNot { it in listedTools }.map { name ->
            KitchenPreparationItem("catalog:$name", listOf(name), null, true)
        }
    return rows + extraQuantities + extraTools
}

internal fun RecipeAmounts.canAdjustWorkbenchServings(presentation: RecipePresentation?): Boolean =
    supportsServings || presentation?.steps?.any { instruction(it.text).hasAdjustedAmounts } == true
