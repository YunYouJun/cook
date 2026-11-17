package cn.yunyoujun.cook.core.ai

import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.recommend.Recommender
import cn.yunyoujun.cook.core.recommend.StuffNormalizer
import cn.yunyoujun.cook.core.search.CookQuery
import cn.yunyoujun.cook.core.search.CookQueryParser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
enum class SearchIntentSource(val rawValue: String) {
    @SerialName("local")
    LOCAL("local"),

    @SerialName("mock")
    MOCK("mock"),

    @SerialName("deepseek")
    DEEPSEEK("deepseek"),
}

@Serializable
data class SearchIntent(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val dataVersion: String,
    val policyVersion: Int = CURRENT_POLICY_VERSION,
    val source: SearchIntentSource,
    val query: CookQuery,
    val goal: DietaryGoal? = null,
    val unresolved: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = query.isEmpty && goal == null

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val CURRENT_POLICY_VERSION = 1
    }
}

data class SearchIntentVocabulary(
    val stuff: List<String>,
    val tools: List<String>,
    val difficulty: List<String>,
    val methodGroups: List<String>,
) {
    constructor(data: CookData) : this(
        stuff = data.normalizer.unique(
            (data.food.vegetable + data.food.meat + data.food.staple).map { it.name } +
                data.recipes.flatMap { it.stuff } +
                data.pantry.options,
        ).sorted(),
        tools = data.food.tools.map { it.name }.toSet().sorted(),
        difficulty = Recommender.DIFFICULTY_LEVELS,
        methodGroups = Recommender.METHOD_GROUPS.map { it.label },
    )
}

sealed class SearchIntentException(message: String) : Exception(message) {
    class UnknownField(val field: String) :
        SearchIntentException("识别结果包含无法使用的条件，请重试或使用本地识别。")

    class InvalidResponse :
        SearchIntentException("识别结果包含无法使用的条件，请重试或使用本地识别。")

    class UnsupportedSchemaVersion :
        SearchIntentException("识别结果与当前菜谱数据不一致，请重新识别。")

    class StaleDataVersion :
        SearchIntentException("识别结果与当前菜谱数据不一致，请重新识别。")

    class UnsupportedPolicyVersion :
        SearchIntentException("识别结果与当前菜谱数据不一致，请重新识别。")

    class InvalidSource :
        SearchIntentException("识别结果包含无法使用的条件，请重试或使用本地识别。")

    class IllegalValue(val field: String) :
        SearchIntentException("识别结果包含无法使用的条件，请重试或使用本地识别。")

    class UnavailableGoal :
        SearchIntentException("这个饮食目标目前没有足够的已核对菜谱，请修改条件。")

    class ConflictingConditions :
        SearchIntentException("识别结果中的食材和忌口相互冲突，请修改条件。")
}

class SearchIntentValidator(
    data: CookData,
    val availableGoals: List<DietaryGoal>,
) {
    val vocabulary = SearchIntentVocabulary(data)
    val dataVersion: String = data.meta.dataVersion

    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun decode(rawJson: String, expectedSource: SearchIntentSource? = null): SearchIntent {
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_WIRE_BYTES) {
            throw SearchIntentException.InvalidResponse()
        }
        val element = runCatching { Json.parseToJsonElement(rawJson) }
            .getOrElse { throw SearchIntentException.InvalidResponse() }
        val rootObj = (element as? JsonObject) ?: throw SearchIntentException.InvalidResponse()
        rootObj.keys.firstOrNull { it !in ALLOWED_OUTER_KEYS }?.let { unknown ->
            throw SearchIntentException.UnknownField(unknown)
        }
        val queryObj = (rootObj["query"] as? JsonObject) ?: throw SearchIntentException.InvalidResponse()
        queryObj.keys.firstOrNull { it !in ALLOWED_QUERY_KEYS }?.let { unknown ->
            throw SearchIntentException.UnknownField(unknown)
        }
        val intent = runCatching {
            strictJson.decodeFromJsonElement(SearchIntent.serializer(), rootObj)
        }.getOrElse { throw SearchIntentException.InvalidResponse() }
        return validate(intent, expectedSource = expectedSource)
    }

    fun validate(intent: SearchIntent, expectedSource: SearchIntentSource? = null): SearchIntent {
        if (intent.schemaVersion != SearchIntent.CURRENT_SCHEMA_VERSION) {
            throw SearchIntentException.UnsupportedSchemaVersion()
        }
        if (intent.dataVersion != dataVersion) {
            throw SearchIntentException.StaleDataVersion()
        }
        if (intent.policyVersion != SearchIntent.CURRENT_POLICY_VERSION) {
            throw SearchIntentException.UnsupportedPolicyVersion()
        }
        if (expectedSource != null && intent.source != expectedSource) {
            throw SearchIntentException.InvalidSource()
        }

        val query = intent.query
        checkList(query.stuff, vocabulary.stuff.toSet(), "stuff")
        checkList(query.avoid, vocabulary.stuff.toSet(), "avoid")
        if (query.unknown.isNotEmpty()) {
            throw SearchIntentException.IllegalValue("unknown")
        }
        val tool = query.tool
        if (tool != null && tool !in vocabulary.tools) {
            throw SearchIntentException.IllegalValue("tool")
        }
        checkList(query.difficulty, vocabulary.difficulty.toSet(), "difficulty")
        checkList(query.methodGroups, vocabulary.methodGroups.toSet(), "methodGroups")
        if (query.stuff.toSet().intersect(query.avoid.toSet()).isNotEmpty()) {
            throw SearchIntentException.ConflictingConditions()
        }
        val goal = intent.goal
        if (goal != null && goal !in availableGoals) {
            throw SearchIntentException.UnavailableGoal()
        }
        val unresolvedValid = intent.unresolved.size <= 20 &&
            intent.unresolved.all { it.trim().isNotEmpty() && it.length <= 500 } &&
            intent.unresolved.toSet().size == intent.unresolved.size
        if (!unresolvedValid) {
            throw SearchIntentException.IllegalValue("unresolved")
        }
        return intent
    }

    private fun checkList(values: List<String>, allowed: Set<String>, field: String) {
        if (values.size > 100 || values.toSet().size != values.size || !values.all { it in allowed }) {
            throw SearchIntentException.IllegalValue(field)
        }
    }

    companion object {
        private const val MAX_WIRE_BYTES = 32_768
        private val ALLOWED_OUTER_KEYS = setOf(
            "schemaVersion",
            "dataVersion",
            "policyVersion",
            "source",
            "query",
            "goal",
            "unresolved",
        )
        private val ALLOWED_QUERY_KEYS = setOf(
            "stuff",
            "unknown",
            "avoid",
            "tool",
            "noSpicy",
            "difficulty",
            "methodGroups",
        )
    }
}

class LocalSearchIntentParser(
    data: CookData,
    availableGoals: List<DietaryGoal>,
) {
    private val parser: CookQueryParser = data.makeQueryParser()
    private val validator = SearchIntentValidator(data, availableGoals)
    private val normalizer: StuffNormalizer = data.normalizer

    fun parse(text: String): SearchIntent {
        if (text.length > 1_000) {
            return SearchIntent(
                dataVersion = validator.dataVersion,
                source = SearchIntentSource.LOCAL,
                query = CookQuery(),
                unresolved = listOf("这段话太长，请缩短到 1000 字以内。"),
            )
        }

        val stuffList = ArrayList<String>()
        val avoidList = ArrayList<String>()
        var noSpicy = false
        val difficultyList = ArrayList<String>()
        val methodGroupList = ArrayList<String>()
        var chosenTool: String? = null
        val goals = ArrayList<DietaryGoal>()
        val negatedGoals = HashSet<DietaryGoal>()
        val unresolved = ArrayList<String>()
        var hasConflictingTools = false

        for (rawClause in CookQueryParser.splitClauses(text)) {
            val clause = rawClause.trim()
            if (clause.isEmpty()) continue
            var remaining = clause
            val clauseGoals = DietaryGoal.entries.filter { it.title in remaining }
            val ambiguousGoalCombination = clauseGoals.size > 1
            var clauseUnresolved = ambiguousGoalCombination

            for (goal in clauseGoals) {
                val negated = isNegated(goal.title, remaining)
                if (ambiguousGoalCombination || negated || goal !in validator.availableGoals) {
                    if (negated) negatedGoals.add(goal)
                    clauseUnresolved = true
                } else {
                    goals.add(goal)
                }
                remaining = remaining.replace(goal.title, " ")
            }

            for (term in UNSUPPORTED_REQUESTS) {
                if (term in remaining) {
                    clauseUnresolved = true
                    remaining = remaining.replace(term, " ")
                }
            }

            var part = parser.parse(remaining)
            val hasNegatedRestriction = parser.terms.any { (word, term) ->
                when (term) {
                    is CookQueryParser.Term.Tool,
                    is CookQueryParser.Term.Method,
                    is CookQueryParser.Term.Difficulty,
                    CookQueryParser.Term.NoSpicy,
                    -> word in remaining && isNegated(word, remaining)
                    else -> false
                }
            }
            if (hasNegatedRestriction) {
                clauseUnresolved = true
                part = part.copy(
                    tool = null,
                    methodGroups = emptyList(),
                    difficulty = emptyList(),
                    noSpicy = false,
                )
            }

            val mentionedTools = parser.terms.mapNotNull { (word, term) ->
                if (term is CookQueryParser.Term.Tool && word in remaining) term.name else null
            }.toSet()
            if (mentionedTools.size > 1) {
                hasConflictingTools = true
                clauseUnresolved = true
            }

            if (part.unknown.isNotEmpty() || hasUnrecognizedFragment(remaining)) {
                clauseUnresolved = true
            }
            if (clauseUnresolved) {
                unresolved.add(clause.take(500))
            }

            stuffList.addAll(part.stuff)
            avoidList.addAll(part.avoid)
            noSpicy = noSpicy || part.noSpicy
            difficultyList.addAll(part.difficulty)
            methodGroupList.addAll(part.methodGroups)
            val partTool = part.tool
            if (partTool != null) {
                if (chosenTool != null && chosenTool != partTool) {
                    hasConflictingTools = true
                } else {
                    chosenTool = partTool
                }
            }
        }

        if (hasConflictingTools) {
            chosenTool = null
            unresolved.add("提到了多种厨具，请选择本次使用的一种。")
        }

        val uniqueGoals = goals.toSet() - negatedGoals
        if (uniqueGoals.size > 1) {
            unresolved.add("提到了多个饮食目标，请选择本次使用的一个目标。")
        }

        val knownStuff = validator.vocabulary.stuff.toSet()
        val finalAvoid = normalizer.unique(avoidList).filter { it in knownStuff }
        val avoidedSet = finalAvoid.toSet()
        val finalStuff = normalizer.unique(stuffList).filter { it in knownStuff && it !in avoidedSet }
        val finalQuery = CookQuery(
            stuff = finalStuff,
            unknown = emptyList(),
            avoid = finalAvoid,
            tool = chosenTool,
            noSpicy = noSpicy,
            difficulty = CookQueryParser.unique(difficultyList),
            methodGroups = CookQueryParser.unique(methodGroupList),
        )
        val uniqueUnresolved = CookQueryParser.unique(unresolved)
        val pending = if (uniqueUnresolved.size > 20) {
            uniqueUnresolved.take(19) + "还有未识别的条件，请缩短输入并重新识别。"
        } else {
            uniqueUnresolved
        }

        return SearchIntent(
            dataVersion = validator.dataVersion,
            source = SearchIntentSource.LOCAL,
            query = finalQuery,
            goal = if (uniqueGoals.size == 1) uniqueGoals.first() else null,
            unresolved = pending,
        )
    }

    private fun isNegated(word: String, text: String): Boolean {
        var searchStart = 0
        while (true) {
            val matchIndex = text.indexOf(word, startIndex = searchStart)
            if (matchIndex < 0) return false
            var prefix = text.substring(0, matchIndex).filterNot { it.isWhitespace() }
            var suffix = text.substring(matchIndex + word.length).filterNot { it.isWhitespace() }

            if (NEGATIONS.any { prefix.endsWith(it) }) return true
            while (true) {
                val filler = PREFIX_FILLERS.firstOrNull { prefix.endsWith(it) } ?: break
                prefix = prefix.dropLast(filler.length)
                if (NEGATIONS.any { prefix.endsWith(it) }) return true
            }

            if (POSTPOSITIONS.any { suffix.startsWith(it) }) return true
            while (true) {
                val filler = SUFFIX_FILLERS.firstOrNull { suffix.startsWith(it) } ?: break
                suffix = suffix.drop(filler.length)
                if (POSTPOSITIONS.any { suffix.startsWith(it) }) return true
            }

            searchStart = matchIndex + word.length
        }
    }

    private fun hasUnrecognizedFragment(text: String): Boolean {
        val sb = StringBuilder()
        var index = 0
        while (index < text.length) {
            val maxLen = minOf(parser.maxLength, text.length - index)
            var matchedLength: Int? = null
            for (len in maxOf(1, maxLen) downTo 1) {
                if (index + len <= text.length && text.substring(index, index + len) in parser.terms) {
                    matchedLength = len
                    break
                }
            }
            if (matchedLength != null) {
                index += matchedLength
            } else {
                sb.append(text[index])
                index += 1
            }
        }
        var residual = sb.toString()
        for (filler in FILLERS) {
            residual = residual.replace(filler, "")
        }
        return residual.any { ch ->
            !ch.isWhitespace() && !ch.isDigit() && ch !in FILLER_CHARACTERS
        }
    }

    companion object {
        private val NEGATIONS = listOf(
            "不", "不要", "不想", "不想要", "不用", "别", "没有", "没", "不需要", "不考虑", "不打算",
            "无需", "无须", "不必", "不希望", "不要求", "不准备", "不追求", "不选择", "不采用", "暂不", "先不",
        )
        private val POSTPOSITIONS = NEGATIONS.filterNot { it in setOf("不", "没", "别") } + "没了"
        private val PREFIX_FILLERS = listOf(
            "刻意", "特意", "专门", "进行", "选择", "考虑", "以及", "或者", "再", "去", "做", "要", "和", "跟", "与", "及", "或",
        )
        private val SUFFIX_FILLERS = listOf(
            "这个目标", "这一目标", "目标", "这次", "本次", "目前", "暂时", "现在", "就", "先",
        )
        private val FILLERS = listOf(
            "冰箱里面", "冰箱里", "家里面", "家里", "今天晚上", "今天", "今晚", "现在", "帮我", "做个", "想要", "想吃", "想做", "一点", "一些", "简单点",
        )
        private val FILLER_CHARACTERS = "和跟与及的了呢吧啊呀哦嘛我想吃做用菜有还再来个道种颗根块斤克条盒包袋瓶一二两三四五六七八九十半些点、 ".toSet()
        private val UNSUPPORTED_REQUESTS = listOf(
            "控制体重", "减肥", "减脂", "低热量", "热量", "卡路里", "生酮", "痘痘", "祛痘", "孕期", "孕妇",
            "糖尿病", "控糖", "血糖", "高血压", "低盐", "低糖", "无糖", "不含糖", "降糖", "高蛋白", "低脂",
            "脂肪", "分钟", "健康", "营养", "清爽", "便宜",
        )
    }
}
