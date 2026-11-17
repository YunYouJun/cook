package cn.yunyoujun.cook.core.recipe

import kotlin.math.ceil
import kotlin.math.floor

/**
 * 按原文明确写出的份数公式计算，不把固定用量擅自视为「每份」。
 * 支持乘除、范围、分数、引用其他原料数量和向上取整；未知表达式保留原文。
 */
class RecipeAmounts(
    private val rows: List<RecipePresentation.Quantity>,
    requestedServings: Int,
) {
    data class Amount(
        val text: String,
        val note: String?,
        val adjustsWithServings: Boolean,
        val isResolved: Boolean,
        val measurement: RecipeMeasurement? = null,
        val isConditional: Boolean = false,
    )

    val servings: Int = requestedServings.coerceIn(SERVING_RANGE.first, SERVING_RANGE.last)
    private val values = LinkedHashMap<RecipePresentation.Quantity, Amount>()

    init {
        val resolver = Resolver(rows = rows, servings = servings)
        for (row in rows) {
            val result = resolver.resolve(row)
            values[row] = if (result != null) {
                Amount(
                    text = result.display,
                    note = if (result.conditional) "依赖可选或条件原料，需确认分支" else result.note,
                    adjustsWithServings = result.dependsOnServings,
                    isResolved = true,
                    measurement = if (result.conditional) null else RecipeMeasurement.create(result.lower, result.upper, result.unit),
                    isConditional = result.conditional,
                )
            } else {
                Amount(
                    text = row.amount,
                    note = "需按原文确认",
                    adjustsWithServings = false,
                    isResolved = false,
                )
            }
        }
    }

    val supportsServings: Boolean
        get() = values.values.any { it.adjustsWithServings }

    /**
     * 步骤按自身公式换算，不用整道菜的总量覆盖分次投料。
     */
    fun instruction(text: String): RecipeInstructionAmounts {
        val resolver = Resolver(rows = rows, servings = servings)
        return RecipeInstructionAmounts.parse(text) { expression ->
            val row = RecipePresentation.Quantity.parse("步骤用量 = $expression") ?: return@parse null
            val result = resolver.resolve(row) ?: return@parse null
            if (!result.dependsOnServings || result.unit.isEmpty()) return@parse null
            val rounding = if (result.note == "已向上取整") "（已向上取整）" else ""
            result.display + rounding
        }
    }

    fun amount(row: RecipePresentation.Quantity): Amount =
        values[row] ?: amount(row.name) ?: Amount(
            text = row.amount,
            note = "需按原文确认",
            adjustsWithServings = false,
            isResolved = false,
        )

    fun amount(ingredient: String): Amount? {
        val targetKey = key(ingredient)
        val matches = rows.filter {
            key(it.name) == targetKey || (it.annotation?.aliases?.contains(ingredient) == true)
        }
        if (matches.size != 1) return null
        return values[matches.first()]
    }

    val ingredientNames: List<String>
        get() = rows.map { key(it.name) }.distinct().sorted()

    fun measurement(formula: RecipeAmountAnnotations.Formula): RecipeMeasurement? {
        val value = Resolver(rows = rows, servings = servings).formula(formula) ?: return null
        return RecipeMeasurement.create(value.lower, value.upper, value.unit)
    }

    fun isConditional(formula: RecipeAmountAnnotations.Formula): Boolean =
        Resolver(rows = rows, servings = servings).formula(formula)?.conditional ?: true

    private data class Value(
        var lower: Double,
        var upper: Double,
        var unit: String = "",
        var dependsOnServings: Boolean = false,
        var note: String? = null,
        val annotations: MutableList<String> = ArrayList(),
        var conditional: Boolean = false,
    ) {
        val isValid: Boolean
            get() = lower.isFinite() && upper.isFinite() && lower >= 0.0 && upper >= lower

        val display: String
            get() {
                val number = if (lower == upper) {
                    RecipeMeasurement.formatNumber(lower)
                } else {
                    "${RecipeMeasurement.formatNumber(lower)}–${RecipeMeasurement.formatNumber(upper)}"
                }
                val quantity = if (unit.isEmpty()) number else "$number $unit"
                val suffix = annotations.joinToString("") { "（$it）" }
                return quantity + suffix
            }

        fun multipliedBy(other: Value): Value? {
            if (unit.isNotEmpty() && other.unit.isNotEmpty()) return null
            val result = Value(
                lower = lower * other.lower,
                upper = upper * other.upper,
                unit = unit.ifEmpty { other.unit },
                dependsOnServings = dependsOnServings || other.dependsOnServings,
                conditional = conditional || other.conditional,
            )
            return if (result.isValid) result else null
        }

        fun dividedBy(lowerDivisor: Double, upperDivisor: Double): Value? {
            if (lowerDivisor <= 0.0 || upperDivisor < lowerDivisor) return null
            val result = copy(
                lower = lower / upperDivisor,
                upper = upper / lowerDivisor,
                annotations = annotations.toMutableList(),
            )
            return if (result.isValid) result else null
        }
    }

    private class Resolver(
        private val rows: List<RecipePresentation.Quantity>,
        private val servings: Int,
    ) {
        private val cache = HashMap<RecipePresentation.Quantity, Value>()
        private val visiting = HashSet<RecipePresentation.Quantity>()

        fun resolve(row: RecipePresentation.Quantity): Value? {
            cache[row]?.let { return it }
            if (visiting.size >= 32 || !visiting.add(row)) return null
            try {
                val annotation = row.annotation
                if (annotation != null) {
                    val result = formula(annotation.formula) ?: return null
                    if (!result.isValid || result.unit.isEmpty()) return null
                    result.conditional = result.conditional || annotation.conditional
                    if (annotation.note != null) {
                        val prefix = if (result.dependsOnServings) "原文基准说明：" else "原文说明："
                        result.annotations.add("$prefix${annotation.note}")
                    }
                    if (result.conditional) {
                        result.annotations.add("可选或条件用量，采用该分支时适用")
                    }
                    if (result.note == null) {
                        result.note = if (result.dependsOnServings) "按原文份数依据换算" else "原文固定用量"
                    }
                    cache[row] = result
                    return result
                }

                var expression = row.amount.replace("`", "")
                val notes = NOTE_REGEX.findAll(expression).map { it.groupValues[1] }.toList()
                // 只剥离说明性括号；含运算的括号不能被当作注释丢掉
                if (notes.any { "份数" in it || it.any { ch -> ch in "+*×/=^" } }) {
                    return null
                }
                expression = expression.replace(NOTE_REGEX, "")
                val roundUp = "向上取整" in expression || "向上取整" in notes
                expression = expression.replace("向上取整", "")
                    .trim { it.isWhitespace() || it == '，' || it == ',' || it == '。' }
                val terms = expression.split('*', '×')
                if (terms.isEmpty() || terms.size > 12) return null

                var result = Value(lower = 1.0, upper = 1.0)
                var literalBase: Value? = null
                val hasLiteralUnit = terms.any { literal(it.trim())?.unit?.isNotEmpty() == true }
                for (term in terms) {
                    val token = term.trim()
                    literal(token)?.takeIf { it.unit.isNotEmpty() }?.let { literalBase = it }
                    val factor = factor(token, scalarReference = hasLiteralUnit) ?: return null
                    result = result.multipliedBy(factor) ?: return null
                }

                if (roundUp) {
                    result.lower = maxOf(0.0, ceil(result.lower - 1e-10))
                    result.upper = maxOf(0.0, ceil(result.upper - 1e-10))
                    result.note = "已向上取整"
                }

                for (note in notes) {
                    if (note == "向上取整") continue
                    val amountText = if (note.startsWith("约")) note.drop(1) else note
                    val weight = literal(amountText)?.takeIf { it.unit.isNotEmpty() }
                    val base = literalBase
                    if (weight != null && base != null && base.lower > 0.0 && base.lower == base.upper && base.unit == result.unit) {
                        val scaled = weight.copy(
                            lower = weight.lower * (result.lower / base.lower),
                            upper = weight.upper * (result.upper / base.upper),
                            annotations = ArrayList(),
                        )
                        if (!scaled.isValid) return null
                        result.annotations.add((if (note.startsWith("约")) "约 " else "") + scaled.display)
                    } else {
                        result.annotations.add(note)
                    }
                }

                if (!result.dependsOnServings) {
                    result.note = "原文固定用量"
                }
                cache[row] = result
                return result
            } finally {
                visiting.remove(row)
            }
        }

        fun formula(expression: RecipeAmountAnnotations.Formula): Value? {
            if (!expression.isValid) return null
            return when (expression.op) {
                "literal", "scalar" -> {
                    val lower = expression.lower!!
                    Value(lower = lower, upper = expression.upper ?: lower, unit = expression.unit ?: "")
                }
                "servings" -> Value(lower = servings.toDouble(), upper = servings.toDouble(), dependsOnServings = true)
                "reference", "count" -> {
                    val targetKey = key(expression.name!!)
                    val matches = rows.filter { key(it.name) == targetKey }
                    if (matches.size != 1) return null
                    val resolved = resolve(matches[0])?.copy(
                        annotations = ArrayList(),
                        note = null,
                    ) ?: return null
                    if (expression.op == "count") {
                        if (resolved.unit != expression.unit) return null
                        resolved.unit = ""
                    }
                    resolved
                }
                "ceil", "floor" -> {
                    val inner = formula(expression.args!![0]) ?: return null
                    val updated = if (expression.op == "ceil") {
                        inner.copy(
                            lower = maxOf(0.0, ceil(inner.lower - 1e-10)),
                            upper = maxOf(0.0, ceil(inner.upper - 1e-10)),
                            note = "已向上取整",
                            annotations = inner.annotations.toMutableList(),
                        )
                    } else {
                        inner.copy(
                            lower = floor(inner.lower + 1e-10),
                            upper = floor(inner.upper + 1e-10),
                            note = "已向下取整",
                            annotations = inner.annotations.toMutableList(),
                        )
                    }
                    if (updated.isValid) updated else null
                }
                else -> {
                    val args = expression.args ?: return null
                    if (args.size != 2) return null
                    val left = formula(args[0]) ?: return null
                    val right = formula(args[1]) ?: return null
                    when (expression.op) {
                        "multiply" -> left.multipliedBy(right)
                        "divide" -> {
                            if (right.unit.isNotEmpty()) return null
                            val divided = left.dividedBy(right.lower, right.upper) ?: return null
                            divided.dependsOnServings = left.dependsOnServings || right.dependsOnServings
                            divided.conditional = left.conditional || right.conditional
                            divided
                        }
                        "add" -> {
                            val leftMeasure = RecipeMeasurement.create(left.lower, left.upper, left.unit) ?: return null
                            val rightMeasure = RecipeMeasurement.create(right.lower, right.upper, right.unit) ?: return null
                            val sum = leftMeasure.adding(rightMeasure) ?: return null
                            Value(
                                lower = sum.lower,
                                upper = sum.upper,
                                unit = sum.unit,
                                dependsOnServings = left.dependsOnServings || right.dependsOnServings,
                                conditional = left.conditional || right.conditional,
                            )
                        }
                        else -> null
                    }
                }
            }
        }

        private fun factor(token: String, scalarReference: Boolean): Value? {
            if (token == "份数") {
                return Value(lower = servings.toDouble(), upper = servings.toDouble(), dependsOnServings = true)
            }
            PER_SERVING_REGEX.matchEntire(token)?.let { match ->
                val value = literal(match.groupValues[1]) ?: return@let
                val people = number(match.groupValues[2]) ?: return@let
                if (people <= 0.0) return@let
                val divided = value.dividedBy(people, people) ?: return@let
                divided.lower *= servings.toDouble()
                divided.upper *= servings.toDouble()
                divided.dependsOnServings = true
                if (divided.isValid) return divided
            }
            literal(token)?.let { return it }

            val parts = token.split('/').map { it.trim() }
            if (parts.size > 2) return null
            val targetKey = key(parts[0])
            val matches = rows.filter { key(it.name) == targetKey }
            if (matches.size != 1) return null
            var value = resolve(matches.first())?.copy(
                annotations = ArrayList(),
                note = null,
            ) ?: return null

            if (parts.size == 2) {
                val divisor = literal(parts[1])
                if (divisor != null && (divisor.unit.isEmpty() || divisor.unit == value.unit)) {
                    value = value.dividedBy(divisor.lower, divisor.upper) ?: return null
                } else {
                    if (unit(parts[1]) != value.unit || value.unit.isEmpty()) return null
                    value.unit = ""
                }
            }
            if (scalarReference) value.unit = ""
            return value
        }

        companion object {
            private val NOTE_REGEX = Regex("[（(]([^（）()]*)[）)]")
            private const val NUMBER_PATTERN = "(?:[0-9]+(?:\\.[0-9]+)?|[一二三四五六七八九十两半])"
            private const val UNIT_PATTERN = "(kg|KG|g|G|ml|mL|ML|L|l|克|千克|公斤|斤|毫升|升|个|颗|只|根|瓣|片|朵|张|支|块|勺|汤匙|茶匙|把|杯)?"
            private val PER_SERVING_REGEX = Regex("^(.+)/([0-9]+|[一二三四五六七八九十两])(人|份)$")
            private val RANGE_LITERAL_REGEX = Regex("^($NUMBER_PATTERN)(?:\\s*[-–~～至]\\s*($NUMBER_PATTERN))?\\s*$UNIT_PATTERN$")
            private val FRACTION_LITERAL_REGEX = Regex("^($NUMBER_PATTERN)\\s*/\\s*($NUMBER_PATTERN)\\s*$UNIT_PATTERN$")
            private val CHINESE_NUMBERS = mapOf(
                "一" to 1.0,
                "二" to 2.0,
                "两" to 2.0,
                "三" to 3.0,
                "四" to 4.0,
                "五" to 5.0,
                "六" to 6.0,
                "七" to 7.0,
                "八" to 8.0,
                "九" to 9.0,
                "十" to 10.0,
                "半" to 0.5,
            )

            fun literal(text: String): Value? {
                val trimmed = text.trim()
                RANGE_LITERAL_REGEX.matchEntire(trimmed)?.let { match ->
                    val lower = number(match.groupValues[1]) ?: return null
                    val upperText = match.groupValues[2]
                    val upper = if (upperText.isEmpty()) lower else (number(upperText) ?: return null)
                    val value = Value(lower = lower, upper = upper, unit = unit(match.groupValues[3]))
                    return if (value.isValid) value else null
                }
                FRACTION_LITERAL_REGEX.matchEntire(trimmed)?.let { match ->
                    val numerator = number(match.groupValues[1]) ?: return null
                    val denominator = number(match.groupValues[2]) ?: return null
                    if (denominator <= 0.0) return null
                    val value = Value(
                        lower = numerator / denominator,
                        upper = numerator / denominator,
                        unit = unit(match.groupValues[3]),
                    )
                    return if (value.isValid) value else null
                }
                return null
            }

            private fun number(value: String): Double? = CHINESE_NUMBERS[value] ?: value.toDoubleOrNull()

            private fun unit(value: String): String = when (value) {
                "g", "G", "克" -> "g"
                "kg", "KG", "千克", "公斤" -> "kg"
                "ml", "mL", "ML", "毫升" -> "ml"
                "L", "l", "升" -> "L"
                else -> value
            }
        }
    }

    companion object {
        val SERVING_RANGE = 1..20
        private val NAME_SUFFIXES = listOf("的数量", "的用量", "数量", "用量", "量")

        fun literalMeasurement(text: String): RecipeMeasurement? {
            val plain = text.replace("`", "")
                .replace("**", "")
                .replace("（已向上取整）", "")
                .trim()
            val value = Resolver.literal(plain) ?: return null
            if (value.unit.isEmpty()) return null
            return RecipeMeasurement.create(value.lower, value.upper, value.unit)
        }

        fun key(name: String): String {
            var cleaned = RecipePresentation.ingredientName(name).replace("`", "")
            for (suffix in NAME_SUFFIXES) {
                if (cleaned.endsWith(suffix)) {
                    cleaned = cleaned.dropLast(suffix.length)
                    break
                }
            }
            return cleaned.trim()
        }
    }
}
