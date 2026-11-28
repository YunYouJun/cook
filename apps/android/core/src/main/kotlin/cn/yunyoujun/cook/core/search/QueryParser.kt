package cn.yunyoujun.cook.core.search

import cn.yunyoujun.cook.core.model.FoodCatalog
import cn.yunyoujun.cook.core.model.QueryRules
import kotlinx.serialization.Serializable

@Serializable
data class CookQuery(
    val stuff: List<String> = emptyList(),
    val unknown: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    val tool: String? = null,
    val noSpicy: Boolean = false,
    val difficulty: List<String> = emptyList(),
    val methodGroups: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = stuff.isEmpty() &&
            unknown.isEmpty() &&
            avoid.isEmpty() &&
            tool == null &&
            !noSpicy &&
            difficulty.isEmpty() &&
            methodGroups.isEmpty()
}

class CookQueryParser(
    private val rules: QueryRules,
    private val food: FoodCatalog,
    private val aliases: Map<String, String>,
    extraStuff: List<String> = emptyList(),
) {
    internal sealed interface Term {
        data class Stuff(val name: String) : Term
        data class Tool(val name: String) : Term
        data class Method(val label: String) : Term
        data class Difficulty(val level: String) : Term
        data object Have : Term
        data object Avoid : Term
        data object Lack : Term
        data object NoSpicy : Term
        data object Ignore : Term
    }

    private enum class Scope {
        HAVE,
        AVOID,
        LACK,
    }

    private enum class ListKind {
        STUFF,
        UNKNOWN,
    }

    private data class Token(
        val term: Term,
        val start: Int,
        val end: Int,
    )

    internal val terms: Map<String, Term>
    internal val maxLength: Int

    init {
        val map = HashMap<String, Term>()
        for (name in extraStuff) {
            map[name] = Term.Stuff(aliases[name] ?: name)
        }
        for (item in food.vegetable + food.meat + food.staple) {
            map[item.name] = Term.Stuff(item.name)
            val alias = item.alias
            if (!alias.isNullOrEmpty()) {
                map[alias] = Term.Stuff(item.name)
            }
        }
        val mergedAliases = LinkedHashMap(aliases)
        mergedAliases.putAll(rules.stuff)
        for ((alias, name) in mergedAliases) {
            map[alias] = Term.Stuff(name)
        }
        for ((label, words) in rules.methodGroups) {
            for (word in words) {
                map[word] = Term.Method(label)
            }
        }
        for (item in food.tools) {
            map[item.name] = Term.Tool(item.name)
        }
        for ((alias, name) in rules.tools) {
            map[alias] = Term.Tool(name)
        }
        for (word in rules.have) map[word] = Term.Have
        for (word in rules.lack) map[word] = Term.Lack
        for (word in rules.avoid) map[word] = Term.Avoid
        for (word in rules.noSpicy) map[word] = Term.NoSpicy
        for (word in rules.ignore) {
            if (map[word] !is Term.Stuff) map[word] = Term.Ignore
        }
        for ((level, words) in rules.difficulty) {
            for (word in words) {
                map[word] = Term.Difficulty(level)
            }
        }
        terms = map
        maxLength = map.keys.maxOfOrNull { it.length } ?: 0
    }

    fun adding(extraStuff: List<String>): CookQueryParser =
        if (extraStuff.isEmpty()) this else CookQueryParser(rules, food, aliases, extraStuff)

    private fun scan(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var start = 0
        while (start < text.length) {
            var found: Token? = null
            var length = minOf(maxLength, text.length - start)
            while (length > 0) {
                val sub = text.substring(start, start + length)
                val term = terms[sub]
                if (term != null) {
                    found = Token(term = term, start = start, end = start + length)
                    break
                }
                length -= 1
            }
            if (found != null) {
                tokens.add(found)
                start = found.end
            } else {
                start += 1
            }
        }
        return tokens
    }

    fun parse(text: String): CookQuery {
        val stuff = ArrayList<String>()
        val unknown = ArrayList<String>()
        val avoid = ArrayList<String>()
        val tools = ArrayList<String>()
        val difficulty = ArrayList<String>()
        val methodGroups = ArrayList<String>()
        var noSpicy = false

        for (clause in splitClauses(text)) {
            var scope = Scope.HAVE
            val pending = ArrayList<Pair<ListKind, String>>()

            fun addStuff(name: String, isUnknown: Boolean = false) {
                when (scope) {
                    Scope.HAVE -> {
                        if (isUnknown) unknown.add(name) else stuff.add(name)
                        pending.add((if (isUnknown) ListKind.UNKNOWN else ListKind.STUFF) to name)
                    }

                    Scope.AVOID -> {
                        if (!isUnknown) avoid.add(name)
                    }

                    Scope.LACK -> Unit
                }
            }

            fun removeLast(name: String, list: MutableList<String>) {
                val idx = list.lastIndexOf(name)
                if (idx >= 0) list.removeAt(idx)
            }

            var cursor = 0
            for (token in scan(clause)) {
                for (name in unknownStuff(clause.substring(cursor, token.start))) {
                    addStuff(name, isUnknown = true)
                }
                cursor = token.end

                when (val term = token.term) {
                    is Term.Stuff -> addStuff(term.name)
                    Term.Have -> {
                        scope = Scope.HAVE
                        pending.clear()
                    }

                    Term.Avoid, Term.Lack -> {
                        val isAvoid = term == Term.Avoid
                        if (isTail(clause.substring(token.end))) {
                            for ((kind, name) in pending) {
                                when (kind) {
                                    ListKind.STUFF -> {
                                        removeLast(name, stuff)
                                        if (isAvoid) avoid.add(name)
                                    }

                                    ListKind.UNKNOWN -> removeLast(name, unknown)
                                }
                            }
                        }
                        scope = if (isAvoid) Scope.AVOID else Scope.LACK
                        pending.clear()
                    }

                    Term.NoSpicy -> noSpicy = true
                    is Term.Tool -> if (scope == Scope.HAVE) tools.add(term.name)
                    is Term.Method -> if (scope == Scope.HAVE) methodGroups.add(term.label)
                    is Term.Difficulty -> difficulty.add(term.level)
                    Term.Ignore -> Unit
                }
            }

            for (name in unknownStuff(clause.substring(cursor))) {
                addStuff(name, isUnknown = true)
            }
        }

        val avoided = avoid.toSet()
        return CookQuery(
            stuff = unique(stuff).filter { it !in avoided },
            unknown = unique(unknown),
            avoid = unique(avoid),
            tool = tools.firstOrNull(),
            noSpicy = noSpicy,
            difficulty = unique(difficulty),
            methodGroups = unique(methodGroups),
        )
    }

    companion object {
        private val CLAUSE_SEPARATORS = setOf('，', ',', '。', '.', ';', '；', '！', '!', '？', '?', '\n')
        private val CLAUSE_WORDS = listOf("但是", "但", "不过", "可是")
        private val LIST_SEPARATORS = setOf('、', '和', '跟', '与', '及')
        private val TAIL_CHARACTERS = setOf('了', '的', '哦', '吧', '啊', '呀', '呢', '嘛', '哈', '~', '～')
        private val NOT_STUFF_CHARACTERS =
            "的了吗呢吧啊呀哦嘛做吃要想能么什怎啥咋我你他她有没还都就很太些里剩用给帮看找来去在是个只点下可以会把对也再又一二两三四五六七八九十半几颗根块斤克条盒包袋瓶".toSet()

        internal fun splitClauses(text: String): List<String> {
            val clauses = ArrayList<String>()
            val current = StringBuilder()
            var index = 0
            outer@ while (index < text.length) {
                val ch = text[index]
                if (ch in CLAUSE_SEPARATORS) {
                    clauses.add(current.toString())
                    current.setLength(0)
                    index += 1
                    continue
                }
                for (word in CLAUSE_WORDS) {
                    if (text.startsWith(word, startIndex = index)) {
                        clauses.add(current.toString())
                        current.setLength(0)
                        index += word.length
                        continue@outer
                    }
                }
                current.append(ch)
                index += 1
            }
            clauses.add(current.toString())
            return clauses
        }

        private fun isTail(text: String): Boolean =
            text.all { it in TAIL_CHARACTERS || it.isWhitespace() }

        private fun isUnknownStuff(piece: String): Boolean {
            if (piece.length !in 1..6) return false
            if (piece.any { it in NOT_STUFF_CHARACTERS }) return false
            return piece.all { it.code in 0x4E00..0x9FFF }
        }

        private fun unknownStuff(gap: String): List<String> {
            val pieces = ArrayList<StringBuilder>()
            pieces.add(StringBuilder())
            for (ch in gap) {
                if (ch in LIST_SEPARATORS || ch.isWhitespace()) {
                    if (pieces.last().isNotEmpty()) {
                        pieces.add(StringBuilder())
                    }
                } else {
                    pieces.last().append(ch)
                }
            }
            return pieces.map { it.toString() }.filter(::isUnknownStuff)
        }

        internal fun unique(items: List<String>): List<String> {
            val seen = LinkedHashSet<String>()
            for (item in items) seen.add(item)
            return seen.toList()
        }
    }
}
