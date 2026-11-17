package cn.yunyoujun.cook.core.recipe

/**
 * 只替换步骤中有明确份数依据的完整用量表达式；来源正文和步骤身份保持不变。
 */
data class RecipeInstructionAmounts(
    val text: String,
    val hasAdjustedAmounts: Boolean,
    val hasUnresolvedFormula: Boolean,
) {
    fun withUsageText(text: String, adjusted: Boolean, unresolved: Boolean): RecipeInstructionAmounts =
        RecipeInstructionAmounts(
            text = text,
            hasAdjustedAmounts = hasAdjustedAmounts || adjusted,
            hasUnresolvedFormula = hasUnresolvedFormula || unresolved,
        )

    private data class Span(val start: Int, val end: Int) {
        val isEmpty: Boolean get() = end <= start
        fun intersects(other: Span): Boolean = maxOf(start, other.start) < minOf(end, other.end)
    }

    private data class Edit(val span: Span, val replacement: String)

    companion object {
        private const val UNITS = "千克|公斤|毫升|汤匙|茶匙|kg|KG|ml|mL|ML|g|G|L|l|克|斤|升|个|颗|只|根|瓣|片|朵|张|支|块|勺|把|杯"
        private const val NUMBER = "(?:[0-9]+(?:\\.[0-9]+)?|[一二三四五六七八九十两半])"
        private const val QUANTITY = "$NUMBER(?:\\s*(?:/|[-–~～至])\\s*$NUMBER)?\\s*(?:$UNITS)?(?![A-Za-z0-9.一二三四五六七八九十两半])"
        private const val ATOM = "(?:份数|$QUANTITY)"
        private val CODE_REGEX = Regex("`([^`\\n]+)`")
        private val SUFFIX_UNIT_REGEX = Regex("^\\s*($UNITS)(?![A-Za-z])")
        private val FORMULA_REGEX = Regex("$ATOM(?:\\s*[*×/+÷^=−-]\\s*$ATOM)+(?:[，,]?\\s*向上取整|\\s*[（(]向上取整[）)])?")
        private val UNRESOLVED_REGEX = Regex("份数\\s*[*×/+÷^=−-]|[*×/+÷^=−-]\\s*份数")
        private val LINK_REGEX = Regex("!?\\[[^\\]\\n]*\\]\\([^)\\n]*\\)|https?://[^\\s<>]+")
        private val ROUNDING_AFTER_REGEX = Regex("^[，,]?\\s*(?:[（(]\\s*)?向[上下]取整")
        private const val OPERATORS = "*×/+÷^=−-()（）"

        fun parse(source: String, evaluate: (String) -> String?): RecipeInstructionAmounts {
            val protectedSpans = linksIn(source).toMutableList()
            val edits = ArrayList<Edit>()

            fun intersects(span: Span): Boolean = protectedSpans.any { !it.isEmpty && !span.isEmpty && it.intersects(span) }
            fun replace(span: Span, expression: String) {
                evaluate(expression)?.let { edits.add(Edit(span, it)) }
            }

            // 保留代码标记和其他 Markdown；无法理解的完整表达式不再拆开尝试
            for (match in CODE_REGEX.findAll(source)) {
                val outerSpan = Span(match.range.first, match.range.last + 1)
                if (intersects(outerSpan)) continue
                val innerGroup = match.groups[1]!!
                val contentSpan = Span(innerGroup.range.first, innerGroup.range.last + 1)
                val expression = innerGroup.value
                if ((('*' in expression) || ('×' in expression) || ('/' in expression)) && !hasAdjacentOperation(outerSpan, source)) {
                    replace(contentSpan, expression)
                }
                protectedSpans.add(outerSpan)
            }

            // 处理「（份数 * 5）g」和「（2g * 份数）」；嵌套运算交给求值器拒绝
            val stack = ArrayList<Int>()
            for (index in source.indices) {
                val ch = source[index]
                if (ch == '(' || ch == '（') stack.add(index)
                if ((ch == ')' || ch == '）') && stack.isNotEmpty()) {
                    val start = stack.removeAt(stack.lastIndex)
                    if (stack.isNotEmpty()) continue
                    val parenSpan = Span(start, index + 1)
                    if (intersects(parenSpan)) continue
                    val inner = source.substring(start + 1, index)
                    if ("份数" !in inner && '*' !in inner && '×' !in inner) continue
                    val suffix = source.substring(index + 1)
                    val unitMatch = SUFFIX_UNIT_REGEX.find(suffix)
                    val unitLength = unitMatch?.value?.length ?: 0
                    val complete = Span(start, index + 1 + unitLength)
                    val expression = if (unitMatch != null) "$inner * 1 ${unitMatch.groupValues[1]}" else inner
                    if (!hasAdjacentOperation(complete, source)) {
                        replace(complete, expression)
                    }
                    protectedSpans.add(complete)
                }
            }

            for (match in FORMULA_REGEX.findAll(source)) {
                val span = Span(match.range.first, match.range.last + 1)
                if (intersects(span)) continue
                val expression = match.value
                if ("份数" in expression && !hasAdjacentOperation(span, source)) {
                    replace(span, expression)
                }
            }

            val builder = StringBuilder(source)
            for (edit in edits.sortedByDescending { it.span.start }) {
                builder.replace(edit.span.start, edit.span.end, edit.replacement)
            }
            val resultText = builder.toString()
            val links = linksIn(resultText)
            val unresolved = UNRESOLVED_REGEX.findAll(resultText).any { match ->
                val span = Span(match.range.first, match.range.last + 1)
                links.none { it.intersects(span) }
            }
            return RecipeInstructionAmounts(
                text = resultText,
                hasAdjustedAmounts = edits.isNotEmpty(),
                hasUnresolvedFormula = unresolved,
            )
        }

        private fun linksIn(text: String): List<Span> =
            LINK_REGEX.findAll(text).map { Span(it.range.first, it.range.last + 1) }.toList()

        private fun hasAdjacentOperation(span: Span, source: String): Boolean {
            val before = source.substring(0, span.start).trim()
            val after = source.substring(span.end).trim()
            if (ROUNDING_AFTER_REGEX.containsMatchIn(after)) return true
            if (before.lastOrNull()?.let { it in OPERATORS } == true) return true
            if (after.firstOrNull()?.let { it in OPERATORS } == true) return true
            return false
        }
    }
}
