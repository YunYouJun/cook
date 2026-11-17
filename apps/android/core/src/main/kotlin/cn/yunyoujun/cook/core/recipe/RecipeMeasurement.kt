package cn.yunyoujun.cook.core.recipe

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * 数值与单位始终独立保存，不从渲染后的展示文案倒推总量。
 */
@ConsistentCopyVisibility
data class RecipeMeasurement private constructor(
    val lower: Double,
    val upper: Double,
    val unit: String,
) {
    private val base: Pair<String, Double>
        get() = when (unit) {
            "kg" -> "g" to 1000.0
            "L" -> "ml" to 1000.0
            else -> unit to 1.0
        }

    fun adding(other: RecipeMeasurement): RecipeMeasurement? {
        val (myUnit, myFactor) = base
        val (otherUnit, otherFactor) = other.base
        if (myUnit != otherUnit) return null
        val factor = otherFactor / myFactor
        return create(lower + other.lower * factor, upper + other.upper * factor, unit)
    }

    fun remaining(after: List<RecipeMeasurement>): RecipeMeasurement? {
        val (myUnit, myFactor) = base
        var used = 0.0
        for (value in after) {
            // 前序范围并未指定实际取了多少，不能替用户选上下限
            if (value.lower != value.upper || value.base.first != myUnit) return null
            used += value.lower * value.base.second / myFactor
        }
        if (!used.isFinite() || used > lower + 1e-9) return null
        return create(maxOf(0.0, lower - used), maxOf(0.0, upper - used), unit)
    }

    val text: String
        get() {
            val range = if (lower == upper) formatNumber(lower) else "${formatNumber(lower)}–${formatNumber(upper)}"
            return "$range $unit"
        }

    companion object {
        fun create(lower: Double, upper: Double, unit: String): RecipeMeasurement? {
            if (!lower.isFinite() || !upper.isFinite() || lower < 0.0 || upper < lower || unit.isEmpty()) {
                return null
            }
            return RecipeMeasurement(lower, upper, unit)
        }

        internal fun formatNumber(value: Double): String {
            val normalized = if (value == -0.0 || value == 0.0) 0.0 else value
            if (normalized > 0.0 && normalized < 0.000001) {
                return String.format(Locale.US, "%.3g", normalized)
            }
            val symbols = DecimalFormatSymbols(Locale.US)
            val formatter = DecimalFormat("0.######", symbols)
            formatter.isGroupingUsed = false
            return formatter.format(normalized)
        }
    }
}
