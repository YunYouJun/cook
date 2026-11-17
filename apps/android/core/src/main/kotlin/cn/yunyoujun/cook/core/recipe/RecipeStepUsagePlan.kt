package cn.yunyoujun.cook.core.recipe

/**
 * 依菜谱原始顺序建立投料关系。勾选步骤只是进度，不会再次扣料或改变后续提示。
 */
class RecipeStepUsagePlan(
    steps: List<RecipePresentation.Step>,
    amounts: RecipeAmounts,
    annotations: RecipeAmountAnnotations.Entry? = null,
) {
    private val instructions = LinkedHashMap<String, RecipeInstructionAmounts>()

    private data class Span(val start: Int, val end: Int) {
        val length: Int get() = end - start
        fun intersects(other: Span): Boolean = maxOf(start, other.start) < minOf(end, other.end)
    }

    private enum class KindType { EXPLICIT, WHOLE, REMAINING, UNKNOWN }

    private data class Use(
        val ingredient: String,
        val step: Int,
        val range: Span,
        val insertion: Int,
        val kind: KindType,
        val measurement: RecipeMeasurement? = null,
        val conditional: Boolean,
    )

    private data class Insertion(val offset: Int, val text: String)

    init {
        val rendered = steps.map { amounts.instruction(it.text) }.toMutableList()
        val annotatedEvents = ArrayList<Use>()
        val annotatedNames = annotations?.usages?.map { it.ingredient }?.toSet().orEmpty()
        val blocked = annotations?.blockedIngredients?.toSet().orEmpty()

        steps.forEachIndexed { index, step ->
            val entries = annotations?.usages.orEmpty()
                .filter { it.anchor.block == step.sourceBlock && it.anchor.item == step.sourceItem }
            var source = step.text
            var adjusted = false
            var unresolved = false

            for (entry in entries) {
                if (entry.kind != "amount") continue
                val formula = entry.formula
                val value = formula?.let { amounts.measurement(it) }
                val target = entry.target
                if (formula != null && value != null && target != null && source.split(target).size == 2) {
                    if (entry.display != false && RecipeAmounts.literalMeasurement(amounts.instruction(target).text) != value) {
                        source = source.replace(target, "$target（本步 ${entry.ingredient} ${value.text}）")
                        adjusted = true
                    }
                    val targetPos = step.text.indexOf(target)
                    annotatedEvents.add(
                        Use(
                            ingredient = entry.ingredient,
                            step = index,
                            range = Span(targetPos, targetPos + target.length),
                            insertion = 0,
                            kind = KindType.EXPLICIT,
                            measurement = value,
                            conditional = entry.conditional || amounts.isConditional(formula),
                        ),
                    )
                } else {
                    unresolved = true
                }
            }

            val updatedStep = amounts.instruction(source)
            rendered[index] = updatedStep.withUsageText(updatedStep.text, adjusted = adjusted, unresolved = unresolved)

            for (entry in entries) {
                if (entry.kind == "amount" || entry.kind == "reference") continue
                val span = entry.target?.let { target ->
                    val idx = rendered[index].text.indexOf(target)
                    if (idx >= 0) Span(idx, idx + target.length) else null
                } ?: Span(0, 0)
                val kindType = when (entry.kind) {
                    "total" -> KindType.WHOLE
                    "remaining" -> KindType.REMAINING
                    else -> KindType.UNKNOWN
                }
                if (entry.target == null || rendered[index].text.contains(entry.target)) {
                    annotatedEvents.add(
                        Use(
                            ingredient = entry.ingredient,
                            step = index,
                            range = span,
                            insertion = span.end,
                            kind = kindType,
                            conditional = entry.conditional,
                        ),
                    )
                }
            }

            // 标注只覆盖它指向的使用点，不能隐藏同一食材在别处的未标注投料
            for (event in usesIn(step.text, step = index, names = amounts.ingredientNames)) {
                if (event.ingredient !in annotatedNames) continue
                val covered = entries.any { entry ->
                    if (entry.ingredient != event.ingredient) return@any false
                    if (entry.kind == "unknown") return@any true
                    val target = entry.target ?: return@any false
                    val idx = step.text.indexOf(target)
                    val targetSpan = if (idx >= 0) Span(idx, idx + target.length) else Span(0, 0)
                    (targetSpan.length > 0 && targetSpan.intersects(event.range)) || step.text.split(event.ingredient).size == 2
                }
                if (!covered) {
                    annotatedEvents.add(
                        Use(
                            ingredient = event.ingredient,
                            step = index,
                            range = event.range,
                            insertion = 0,
                            kind = KindType.UNKNOWN,
                            conditional = true,
                        ),
                    )
                }
            }
        }

        val uses = (rendered.flatMapIndexed { stepIndex, instructionAmounts ->
            usesIn(instructionAmounts.text, step = stepIndex, names = amounts.ingredientNames)
                .map { use ->
                    if (use.ingredient in blocked) {
                        use.copy(kind = KindType.UNKNOWN, measurement = null, conditional = true)
                    } else {
                        use
                    }
                }
                .filter { it.ingredient !in annotatedNames }
        } + annotatedEvents).sortedWith(compareBy<Use>({ it.step }, { it.range.start }))

        val insertions = HashMap<Int, MutableList<Insertion>>()
        val unresolvedSteps = HashSet<Int>()

        for ((name, events) in uses.groupBy { it.ingredient }) {
            val total = amounts.amount(name)?.measurement
            val previous = ArrayList<RecipeMeasurement>()
            var uncertain = false
            events.forEachIndexed { index, event ->
                when (event.kind) {
                    KindType.EXPLICIT -> {
                        event.measurement?.let { previous.add(it) }
                    }
                    KindType.WHOLE -> {
                        val value = if (events.size == 1) total else null
                        if (value != null) {
                            insertions.getOrPut(event.step) { ArrayList() }.add(Insertion(event.insertion, "（${value.text}）"))
                        } else {
                            unresolvedSteps.add(event.step)
                        }
                        uncertain = true
                    }
                    KindType.REMAINING -> {
                        val value = if (!uncertain && previous.isNotEmpty() && index == events.lastIndex) {
                            total?.remaining(previous)
                        } else {
                            null
                        }
                        if (value != null) {
                            insertions.getOrPut(event.step) { ArrayList() }.add(Insertion(event.insertion, "（${value.text}）"))
                        } else {
                            unresolvedSteps.add(event.step)
                        }
                        uncertain = true
                    }
                    KindType.UNKNOWN -> {
                        uncertain = true
                        unresolvedSteps.add(event.step)
                    }
                }
                if (event.conditional) uncertain = true
            }
        }

        steps.forEachIndexed { index, step ->
            val stepInsertions = insertions[index]
            val result = StringBuilder(rendered[index].text)
            stepInsertions?.sortedByDescending { it.offset }?.forEach { ins ->
                result.insert(ins.offset, ins.text)
            }
            instructions[step.id] = rendered[index].withUsageText(
                text = result.toString(),
                adjusted = stepInsertions != null,
                unresolved = index in unresolvedSteps,
            )
        }
    }

    fun instruction(step: RecipePresentation.Step): RecipeInstructionAmounts? = instructions[step.id]

    companion object {
        private val LINK_REGEX = Regex("!?\\[[^\\]\\n]*\\]\\([^)\\n]*\\)|https?://[^\\s<>]+")
        private val VERB_REGEX = Regex("加入|放入|倒入|撒入|撒上|淋入|下入|加进|加上")
        private val OTHER_VERB_REGEX = Regex("加|放|倒|撒|淋|腌|拌|用|分|取|打入")
        private val PREFIX_REMAINING_REGEX = Regex("^(剩余|剩下|全部)的?\\s*")
        private val QUANTITY_PREFIX_TAIL_REGEX = Regex("\\s*的$")
        private val PAREN_NOTE_REGEX = Regex("[（(][^（）()]*[）)]")
        private val CONDITIONAL_WORDS = listOf("如果", "若", "可以", "可选", "按需", "否则", "或者")
        private val FORBIDDEN_WORDS = listOf("不要", "不能", "无需", "不必", "勿")
        private val SHORT_ALIASES = listOf("食用油" to "油", "食盐" to "盐", "食用盐" to "盐")

        private fun usesIn(text: String, step: Int, names: List<String>): List<Use> {
            val links = LINK_REGEX.findAll(text).map { Span(it.range.first, it.range.last + 1) }.toList()
            val result = ArrayList<Use>()
            for (clauseSpan in splitTopLevel(text, "，,。；;\n")) {
                val clause = text.substring(clauseSpan.start, clauseSpan.end)
                val verbs = VERB_REGEX.findAll(clause).toList()
                val mentions = names.flatMap { name ->
                    Regex.fromLiteral(name).findAll(clause).map { name to Span(it.range.first, it.range.last + 1) }
                }.filter { (_, localSpan) ->
                    val global = Span(clauseSpan.start + localSpan.start, clauseSpan.start + localSpan.end)
                    links.none { it.intersects(global) }
                }
                val recognized = HashSet<Int>()

                verbs.forEachIndexed { verbIndex, verb ->
                    val start = verb.range.last + 1
                    val end = if (verbIndex + 1 < verbs.size) verbs[verbIndex + 1].range.first else clause.length
                    val objects = clause.substring(start, end)
                    for (partSpan in splitTopLevel(objects, "、和及")) {
                        val objectText = objects.substring(partSpan.start, partSpan.end)
                        val objectStart = start + partSpan.start
                        val objectEnd = objectStart + partSpan.length
                        val localMentions = mentions.filter { it.second.start >= objectStart && it.second.end <= objectEnd }
                        val candidates = localMentions.filter { item ->
                            localMentions.none { other ->
                                other.second.length > item.second.length &&
                                    maxOf(item.second.start, other.second.start) == item.second.start &&
                                    minOf(item.second.end, other.second.end) == item.second.end
                            }
                        }
                        if (candidates.size != 1) continue
                        val (name, localRange) = candidates.first()
                        val nameStartInObject = localRange.start - objectStart
                        val nameEndInObject = localRange.end - objectStart
                        val prefix = plain(objectText.substring(0, nameStartInObject))
                        val suffix = plain(objectText.substring(nameEndInObject))
                        val prefixWithoutRemaining = prefix.replaceFirst(PREFIX_REMAINING_REGEX, "")
                        val quantityPrefix = prefixWithoutRemaining.replaceFirst(QUANTITY_PREFIX_TAIL_REGEX, "")
                        val quantitySuffix = suffix.replace(PAREN_NOTE_REGEX, "").trim()
                        val beforeVerb = clause.substring(0, verb.range.first)
                        val context = text.substring(0, clauseSpan.start + objectStart) + objectText
                        val conditional = CONDITIONAL_WORDS.any { it in context }
                        val forbidden = FORBIDDEN_WORDS.any { it in beforeVerb }

                        val prefixMeasured = if (quantitySuffix.isEmpty()) RecipeAmounts.literalMeasurement(quantityPrefix) else null
                        val suffixMeasured = if (quantityPrefix.isEmpty()) RecipeAmounts.literalMeasurement(quantitySuffix) else null

                        val kindType: KindType
                        val measured: RecipeMeasurement?
                        when {
                            forbidden -> {
                                kindType = KindType.UNKNOWN
                                measured = null
                            }
                            prefixMeasured != null -> {
                                kindType = KindType.EXPLICIT
                                measured = prefixMeasured
                            }
                            suffixMeasured != null -> {
                                kindType = KindType.EXPLICIT
                                measured = suffixMeasured
                            }
                            quantityPrefix.isEmpty() && quantitySuffix.isEmpty() -> {
                                kindType = if (prefix.startsWith("剩余") || prefix.startsWith("剩下")) {
                                    KindType.REMAINING
                                } else {
                                    KindType.WHOLE
                                }
                                measured = null
                            }
                            else -> {
                                kindType = KindType.UNKNOWN
                                measured = null
                            }
                        }

                        val global = Span(clauseSpan.start + localRange.start, clauseSpan.start + localRange.end)
                        var insertion = global.end
                        while (insertion < text.length && (text[insertion] == '`' || text[insertion] == '*')) {
                            insertion += 1
                        }
                        result.add(
                            Use(
                                ingredient = name,
                                step = step,
                                range = global,
                                insertion = insertion,
                                kind = kindType,
                                measurement = measured,
                                conditional = conditional,
                            ),
                        )
                        recognized.add(localRange.start)
                    }
                }

                if (OTHER_VERB_REGEX.containsMatchIn(clause)) {
                    for ((name, range) in mentions) {
                        if (range.start in recognized) continue
                        val global = Span(clauseSpan.start + range.start, clauseSpan.start + range.end)
                        result.add(
                            Use(
                                ingredient = name,
                                step = step,
                                range = global,
                                insertion = global.end,
                                kind = KindType.UNKNOWN,
                                conditional = true,
                            ),
                        )
                    }
                    for ((name, alias) in SHORT_ALIASES) {
                        if (name !in names) continue
                        for (match in Regex.fromLiteral(alias).findAll(clause)) {
                            val matchSpan = Span(match.range.first, match.range.last + 1)
                            if (mentions.any { it.second.intersects(matchSpan) }) continue
                            val global = Span(clauseSpan.start + matchSpan.start, clauseSpan.start + matchSpan.end)
                            if (links.any { it.intersects(global) }) continue
                            result.add(
                                Use(
                                    ingredient = name,
                                    step = step,
                                    range = global,
                                    insertion = global.end,
                                    kind = KindType.UNKNOWN,
                                    conditional = true,
                                ),
                            )
                        }
                    }
                }
            }
            return result.sortedBy { it.range.start }
        }

        private fun plain(text: String): String =
            text.replace("`", "").replace("**", "").trim()

        private fun splitTopLevel(source: String, separators: String): List<Span> {
            var depth = 0
            var start = 0
            val ranges = ArrayList<Span>()
            source.forEachIndexed { index, ch ->
                if (ch == '(' || ch == '（') depth += 1
                if (ch == ')' || ch == '）') depth = maxOf(0, depth - 1)
                if (depth == 0 && ch in separators) {
                    ranges.add(Span(start, index))
                    start = index + 1
                }
            }
            ranges.add(Span(start, source.length))
            return ranges
        }
    }
}
