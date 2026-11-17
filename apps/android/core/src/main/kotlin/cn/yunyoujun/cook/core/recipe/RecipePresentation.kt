package cn.yunyoujun.cook.core.recipe

/**
 * 从明确的章节提取展示信息；无法识别的内容始终交回原文渲染，不推算用量或热量。
 */
class RecipePresentation(text: RecipeText) {
    data class Quantity(
        val name: String,
        val amount: String,
        val annotation: RecipeAmountAnnotations.Quantity? = null,
    ) {
        companion object {
            fun fromAnnotation(annotation: RecipeAmountAnnotations.Quantity): Quantity =
                Quantity(
                    name = annotation.name,
                    amount = annotation.evidence.joinToString("；") { it.text },
                    annotation = annotation,
                )

            fun parse(text: String): Quantity? {
                val sepIdx = text.indexOfFirst { it == '=' || it == '＝' }
                if (sepIdx < 0) return null
                val name = text.substring(0, sepIdx).trim()
                val amount = text.substring(sepIdx + 1).trim()
                if (name.isEmpty() || amount.isEmpty() || '\n' in name) return null
                return Quantity(name = name, amount = amount, annotation = null)
            }
        }
    }

    data class Step(
        val id: String,
        val number: Int,
        val text: String,
        val sourceBlock: Int,
        val sourceItem: Int?,
    )

    private enum class Section {
        OTHER,
        INGREDIENTS,
        QUANTITIES,
        STEPS,
        ANNOTATED_STEPS,
    }

    val amountAnnotations: RecipeAmountAnnotations.Entry? = text.amountAnnotations?.validated(text)
    val servingDescription: String?
        get() = amountAnnotations?.servingDescription

    var difficulty: Int? = null
        private set
    var calories: String? = null
        private set
    val metadataBlocks: Set<Int>
    val ingredientBlocks: Set<Int>
    val ingredients: List<String>
    val quantities: Map<Int, List<Quantity>>
    val stepsByBlock: Map<Int, List<Step>>
    val steps: List<Step>

    init {
        val annotatedStepHeadings = amountAnnotations?.stepSections?.map { it.text }?.toSet().orEmpty()
        var section = Section.OTHER
        var ingredientHeading: Int? = null
        var ingredientHasNotes = false
        val occurrences = HashMap<String, Int>()
        val annotatedOccurrences = HashMap<String, Int>()

        val metaSet = LinkedHashSet<Int>()
        val ingredientBlockSet = LinkedHashSet<Int>()
        val rawIngredients = ArrayList<String>()
        val quantityMap = LinkedHashMap<Int, List<Quantity>>()
        val stepBlockMap = LinkedHashMap<Int, MutableList<Step>>()
        val stepList = ArrayList<Step>()

        fun appendSteps(
            items: List<String>,
            offset: Int,
            startingAt: Int? = null,
            occMap: MutableMap<String, Int>,
            identityPrefix: String = "",
            sourceItems: Boolean = true,
        ) {
            items.forEachIndexed { index, item ->
                val count = (occMap[item] ?: 0) + 1
                occMap[item] = count
                val step = Step(
                    id = "$identityPrefix$count|$item",
                    number = startingAt?.let { it + index } ?: (stepList.size + 1),
                    text = item,
                    sourceBlock = offset,
                    sourceItem = if (sourceItems) index else null,
                )
                stepList.add(step)
                stepBlockMap.getOrPut(offset) { ArrayList() }.add(step)
            }
        }

        text.blocks.forEachIndexed { offset, block ->
            when (block) {
                is RecipeTextBlock.Heading -> {
                    when (block.text.trim()) {
                        "必备原料和工具" -> {
                            section = Section.INGREDIENTS
                            ingredientHeading = offset
                        }
                        "计算" -> section = Section.QUANTITIES
                        "操作", "操作步骤", "制作步骤", "步骤", "做法" -> section = Section.STEPS
                        else -> section = if (block.text in annotatedStepHeadings) Section.ANNOTATED_STEPS else Section.OTHER
                    }
                }
                is RecipeTextBlock.Paragraph -> {
                    val value = block.text.trim()
                    if (section == Section.INGREDIENTS && value.isNotEmpty()) {
                        ingredientHasNotes = true
                    }
                    val stars = if (section == Section.OTHER && difficulty == null) valueAfter(value, "预估烹饪难度") else null
                    val energy = if (section == Section.OTHER && calories == null) valueAfter(value, "预估卡路里") else null
                    if (stars != null && stars.isNotEmpty() && stars.all { it == '★' } && stars.length in 1..5) {
                        difficulty = stars.length
                        metaSet.add(offset)
                    } else if (energy != null && energy.isNotEmpty() && '\n' !in energy) {
                        calories = energy
                        metaSet.add(offset)
                    } else if (value.isNotEmpty()) {
                        if (section == Section.STEPS) {
                            appendSteps(listOf(value), offset = offset, occMap = occurrences, sourceItems = false)
                        } else if (section == Section.ANNOTATED_STEPS) {
                            appendSteps(
                                listOf(value),
                                offset = offset,
                                occMap = annotatedOccurrences,
                                identityPrefix = "annotated|",
                                sourceItems = false,
                            )
                        }
                    }
                }
                is RecipeTextBlock.ListBlock -> {
                    when (section) {
                        Section.INGREDIENTS -> {
                            rawIngredients.addAll(block.items)
                            ingredientBlockSet.add(offset)
                        }
                        Section.QUANTITIES -> {
                            val rows = block.items.mapNotNull(Quantity::parse)
                            if (rows.isNotEmpty() && rows.size == block.items.size) {
                                quantityMap[offset] = rows
                            }
                        }
                        Section.STEPS -> {
                            appendSteps(
                                block.items,
                                offset = offset,
                                startingAt = if (block.ordered) block.start else null,
                                occMap = occurrences,
                            )
                        }
                        Section.ANNOTATED_STEPS -> {
                            appendSteps(
                                block.items,
                                offset = offset,
                                startingAt = if (block.ordered) block.start else null,
                                occMap = annotatedOccurrences,
                                identityPrefix = "annotated|",
                            )
                        }
                        Section.OTHER -> Unit
                    }
                }
                is RecipeTextBlock.ImageBlock -> {
                    if (section == Section.INGREDIENTS) ingredientHasNotes = true
                }
            }
        }

        val seen = LinkedHashSet<String>()
        ingredients = rawIngredients.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it) }
        if (ingredientHeading != null && ingredients.isNotEmpty() && !ingredientHasNotes) {
            ingredientBlockSet.add(ingredientHeading)
        }

        metadataBlocks = metaSet
        ingredientBlocks = ingredientBlockSet
        quantities = quantityMap
        stepsByBlock = stepBlockMap
        steps = stepList
    }

    val allQuantities: List<Quantity>
        get() {
            val original = quantities.keys.sorted().flatMap { quantities[it].orEmpty() }
            val annotations = amountAnnotations ?: return original
            val annotated = annotations.quantities.map { Quantity.fromAnnotation(it) }
            val names = annotated.map { RecipeAmounts.key(it.name) }.toSet()
            return original.filter { RecipeAmounts.key(it.name) !in names } + annotated
        }

    val supplementalQuantities: List<Quantity>
        get() {
            val originalNames = quantities.values.flatten().map { RecipeAmounts.key(it.name) }.toSet()
            return allQuantities.filter { it.annotation != null && RecipeAmounts.key(it.name) !in originalNames }
        }

    fun amount(ingredient: String): String? {
        val target = ingredientName(ingredient)
        return allQuantities.firstOrNull { ingredientName(it.name) == target }?.amount
    }

    companion object {
        fun ingredientName(value: String): String =
            value.replace("（可选）", "")
                .replace("(可选)", "")
                .trim()

        private fun valueAfter(text: String, prefix: String): String? {
            for (separator in listOf("：", ":")) {
                val marker = "$prefix$separator"
                if (text.startsWith(marker)) {
                    return text.drop(marker.length).trim()
                }
            }
            return null
        }
    }
}
