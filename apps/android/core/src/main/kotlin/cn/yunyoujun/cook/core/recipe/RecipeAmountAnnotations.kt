package cn.yunyoujun.cook.core.recipe

import cn.yunyoujun.cook.core.data.CookData
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 独立于正文的用量证据。整篇指纹、逐条锚点和语义校验全部通过才采用；否则整菜回退到原文规则。
 */
@Serializable
data class RecipeAmountAnnotations(
    val schemaVersion: Int,
    private val recipes: Map<String, Entry> = emptyMap(),
) {
    @Serializable
    data class Anchor(
        val block: Int,
        val item: Int? = null,
        val text: String,
    ) {
        fun matches(source: RecipeText): Boolean {
            if (block !in source.blocks.indices) return false
            return when (val b = source.blocks[block]) {
                is RecipeTextBlock.Heading -> item == null && b.text == text
                is RecipeTextBlock.Paragraph -> item == null && b.text == text
                is RecipeTextBlock.ListBlock -> item != null && item in b.items.indices && b.items[item] == text
                is RecipeTextBlock.ImageBlock -> false
            }
        }
    }

    @Serializable
    data class Formula(
        val op: String,
        val lower: Double? = null,
        val upper: Double? = null,
        val unit: String? = null,
        val name: String? = null,
        val args: List<Formula>? = null,
    ) {
        val isValid: Boolean
            get() = valid(depth = 0)

        private fun valid(depth: Int): Boolean {
            if (depth >= 16) return false
            return when (op) {
                "literal" -> {
                    val l = lower ?: return false
                    val u = unit ?: return false
                    if (u !in UNITS || !l.isFinite() || l < 0.0) return false
                    val measurement = RecipeMeasurement.create(l, upper ?: l, u) ?: return false
                    measurement.lower == l && name == null && args == null
                }
                "servings" -> lower == null && upper == null && unit == null && name == null && args == null
                "scalar" -> lower != null && lower.isFinite() && lower >= 0.0 && upper == null && unit == null && name == null && args == null
                "reference" -> !name.isNullOrEmpty() && lower == null && upper == null && args == null && unit == null
                "count" -> !name.isNullOrEmpty() && unit in UNITS && lower == null && upper == null && args == null
                "multiply", "divide", "add" ->
                    lower == null && upper == null && unit == null && name == null &&
                        args?.size == 2 && args.all { it.valid(depth + 1) }
                "ceil", "floor" ->
                    lower == null && upper == null && unit == null && name == null &&
                        args?.size == 1 && args[0].valid(depth + 1)
                else -> false
            }
        }

        companion object {
            val UNITS = setOf(
                "g", "kg", "ml", "L", "斤", "cm", "个", "颗", "只", "根", "瓣", "片", "朵",
                "张", "支", "块", "勺", "汤匙", "茶匙", "把", "杯", "袋", "包", "盒", "瓶",
                "罐", "枚", "粒", "棵", "条", "碗", "滴", "扎", "圈", "节", "段", "株", "叶",
                "卷", "头", "份", "小块", "瓶盖",
            )
        }
    }

    @Serializable
    data class Quantity(
        val name: String,
        val formula: Formula,
        val evidence: List<Anchor>,
        val aliases: List<String> = emptyList(),
        val note: String? = null,
        val conditional: Boolean = false,
    )

    @Serializable
    data class Usage(
        val anchor: Anchor,
        val ingredient: String,
        val kind: String,
        val formula: Formula? = null,
        val target: String? = null,
        val display: Boolean? = null,
        val conditional: Boolean = false,
        val evidence: List<Anchor> = emptyList(),
    )

    @Serializable
    data class Entry(
        val sourceFingerprint: String,
        val servingEvidence: List<Anchor> = emptyList(),
        val servingDescription: String? = null,
        val referenceServings: Int? = null,
        val quantities: List<Quantity> = emptyList(),
        val stepSections: List<Anchor> = emptyList(),
        val usages: List<Usage> = emptyList(),
        val blockedIngredients: List<String> = emptyList(),
    ) {
        fun validated(source: RecipeText): Entry? {
            if (referenceServings != null && referenceServings !in RecipeAmounts.SERVING_RANGE) return null
            if (sourceFingerprint != fingerprint(source)) return null
            if (!servingEvidence.all { it.matches(source) }) return null
            if (!stepSections.all { anchor -> anchor.matches(source) && source.blocks[anchor.block] is RecipeTextBlock.Heading }) {
                return null
            }
            if (quantities.map { RecipeAmounts.key(it.name) }.distinct().size != quantities.size) return null
            val usageKeys = usages.map { "${it.anchor.block}:${it.anchor.item ?: -1}:${it.ingredient}:${it.kind}:${it.target ?: ""}" }
            if (usageKeys.distinct().size != usages.size) return null

            val validQuantities = quantities.all { q ->
                q.name.isNotEmpty() && q.formula.isValid && q.evidence.isNotEmpty() && q.evidence.all { it.matches(source) }
            }
            if (!validQuantities) return null

            val validUsages = usages.all { use ->
                use.anchor.matches(source) &&
                    use.ingredient.isNotEmpty() &&
                    use.kind in ALLOWED_USAGE_KINDS &&
                    (use.kind != "amount" || use.formula?.isValid == true) &&
                    use.evidence.isNotEmpty() &&
                    use.evidence.all { it.matches(source) } &&
                    (use.kind == "unknown" || (!use.target.isNullOrEmpty() && use.anchor.text.split(use.target).size == 2))
            }
            if (!validUsages) return null

            // 别名必须唯一，不能把同一名称映射给不同材料
            val owners = HashMap<String, String>()
            for (quantity in quantities) {
                for (candidateName in listOf(quantity.name) + quantity.aliases) {
                    if (candidateName.isEmpty()) return null
                    val existing = owners[candidateName]
                    if (existing != null && existing != quantity.name) return null
                    owners[candidateName] = quantity.name
                }
            }

            // 公式必须在支持的份数上完整可求值；循环、未知引用、单位相乘/相加不兼容均整菜回退
            val original = source.copy(amountAnnotations = null)
            val baseRows = RecipePresentation(original).allQuantities
            val replacementNames = quantities.map { RecipeAmounts.key(it.name) }.toSet()
            val rows = baseRows.filter { RecipeAmounts.key(it.name) !in replacementNames } +
                quantities.map { RecipePresentation.Quantity.fromAnnotation(it) }

            for (servings in RecipeAmounts.SERVING_RANGE) {
                val amounts = RecipeAmounts(rows = rows, requestedServings = servings)
                val allQuantitiesResolved = rows.filter { it.annotation != null }.all { amounts.amount(it).isResolved }
                if (!allQuantitiesResolved) return null
                val allAmountUsagesResolved = usages.filter { it.kind == "amount" }
                    .all { use -> use.formula?.let { amounts.measurement(it) } != null }
                if (!allAmountUsagesResolved) return null
            }

            return this
        }

        companion object {
            private val ALLOWED_USAGE_KINDS = setOf("amount", "total", "remaining", "unknown", "reference")
        }
    }

    fun entry(id: Int, source: RecipeText): Entry? = recipes[id.toString()]?.validated(source)

    companion object {
        const val FILE_NAME = "recipe-amounts.json"

        fun decode(jsonText: String): RecipeAmountAnnotations? =
            runCatching {
                val library = CookData.coreJson.decodeFromString<RecipeAmountAnnotations>(jsonText)
                if (library.schemaVersion == 1) library else null
            }.getOrNull()

        fun loadFromDirectory(directory: File): RecipeAmountAnnotations? {
            val file = File(directory, FILE_NAME)
            if (!file.isFile) return null
            return decode(file.readText(Charsets.UTF_8))
        }

        fun fingerprint(source: RecipeText): String {
            val tokens = ArrayList<String>(source.blocks.size * 3)
            for (block in source.blocks) {
                when (block) {
                    is RecipeTextBlock.Heading -> {
                        tokens.add("heading")
                        tokens.add(block.text)
                    }
                    is RecipeTextBlock.Paragraph -> {
                        tokens.add("paragraph")
                        tokens.add(block.text)
                    }
                    is RecipeTextBlock.ListBlock -> {
                        tokens.add("list")
                        tokens.add(if (block.ordered) "true" else "false")
                        tokens.add(block.start.toString())
                        tokens.add(block.items.size.toString())
                        tokens.addAll(block.items)
                    }
                    is RecipeTextBlock.ImageBlock -> {
                        tokens.add("image")
                        tokens.add(block.src)
                        tokens.add(block.width.toString())
                        tokens.add(block.height.toString())
                        tokens.add(block.alt ?: "")
                    }
                }
            }
            return CookData.fnv1a64Hex(tokens)
        }
    }
}
