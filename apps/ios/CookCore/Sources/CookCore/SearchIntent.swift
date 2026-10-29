import Foundation

/// Identifies the adapter that produced a condition draft, never a recipe recommendation.
public enum SearchIntentSource: String, Codable, Hashable, Sendable {
    case local, mock, deepseek
}

/// A versioned, editable proposal. Applying it remains an explicit client action.
public struct SearchIntent: Codable, Hashable, Sendable {
    public static let currentSchemaVersion = 1
    public static let currentPolicyVersion = 1

    public var schemaVersion: Int
    public var dataVersion: String
    public var policyVersion: Int
    public var source: SearchIntentSource
    public var query: CookQuery
    public var goal: DietaryGoal?
    public var unresolved: [String]

    public init(schemaVersion: Int = currentSchemaVersion, dataVersion: String, policyVersion: Int = currentPolicyVersion,
                source: SearchIntentSource, query: CookQuery, goal: DietaryGoal? = nil, unresolved: [String] = []) {
        self.schemaVersion = schemaVersion
        self.dataVersion = dataVersion
        self.policyVersion = policyVersion
        self.source = source
        self.query = query
        self.goal = goal
        self.unresolved = unresolved
    }

    public var isEmpty: Bool { query.isEmpty && goal == nil }

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case schemaVersion, dataVersion, policyVersion, source, query, goal, unresolved
    }

    public init(from decoder: Decoder) throws {
        try rejectUnknownKeys(decoder, allowed: Set(CodingKeys.allCases.map(\.rawValue)))
        let values = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try values.decode(Int.self, forKey: .schemaVersion)
        dataVersion = try values.decode(String.self, forKey: .dataVersion)
        policyVersion = try values.decode(Int.self, forKey: .policyVersion)
        source = try values.decode(SearchIntentSource.self, forKey: .source)
        let queryDecoder = try values.superDecoder(forKey: .query)
        try rejectUnknownKeys(queryDecoder, allowed: ["stuff", "unknown", "avoid", "tool", "noSpicy", "difficulty", "methodGroups"])
        query = try CookQuery(from: queryDecoder)
        goal = try values.decodeIfPresent(DietaryGoal.self, forKey: .goal)
        unresolved = try values.decode([String].self, forKey: .unresolved)
    }
}

private struct IntentCodingKey: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { return nil }
}

private func rejectUnknownKeys(_ decoder: Decoder, allowed: Set<String>) throws {
    let values = try decoder.container(keyedBy: IntentCodingKey.self)
    if let unknown = values.allKeys.first(where: { !allowed.contains($0.stringValue) }) {
        throw SearchIntentError.unknownField(unknown.stringValue)
    }
}

/// Public canonical names that an adapter may return for the current bundled data.
public struct SearchIntentVocabulary: Sendable {
    public let stuff: [String]
    public let tools: [String]
    public let difficulty: [String]
    public let methodGroups: [String]

    public init(data: CookData) {
        let ingredients = (data.food.vegetable + data.food.meat + data.food.staple).map(\.name)
        stuff = data.normalizer.unique(ingredients + data.recipes.flatMap(\.stuff) + data.pantry.options).sorted()
        tools = Array(Set(data.food.tools.map(\.name))).sorted()
        difficulty = Recommender.difficultyLevels
        methodGroups = Recommender.methodGroups.map(\.label)
    }
}

/// Recoverable adapter failures; callers can keep the existing filters and offer local parsing.
public enum SearchIntentError: Error, Equatable, LocalizedError {
    case unknownField(String)
    case invalidResponse
    case unsupportedSchemaVersion
    case staleDataVersion
    case unsupportedPolicyVersion
    case invalidSource
    case illegalValue(String)
    case unavailableGoal
    case conflictingConditions

    public var errorDescription: String? {
        switch self {
        case .unknownField, .invalidResponse, .illegalValue, .invalidSource: return "识别结果包含无法使用的条件，请重试或使用本地识别。"
        case .unsupportedSchemaVersion, .staleDataVersion, .unsupportedPolicyVersion: return "识别结果与当前菜谱数据不一致，请重新识别。"
        case .unavailableGoal: return "这个饮食目标目前没有足够的已核对菜谱，请修改条件。"
        case .conflictingConditions: return "识别结果中的食材和忌口相互冲突，请修改条件。"
        }
    }
}

/// Checks every condition against the current dictionary, reviewed goal availability and versions.
public struct SearchIntentValidator: Sendable {
    public let vocabulary: SearchIntentVocabulary
    public let dataVersion: String
    public let availableGoals: [DietaryGoal]

    public init(data: CookData, availableGoals: [DietaryGoal]) {
        vocabulary = SearchIntentVocabulary(data: data)
        dataVersion = data.meta.dataVersion
        self.availableGoals = availableGoals
    }

    /// Decodes the strict wire contract, including rejection of unknown nested query fields.
    public func decode(_ data: Data, expectedSource: SearchIntentSource? = nil) throws -> SearchIntent {
        guard data.count <= 32_768 else { throw SearchIntentError.invalidResponse }
        let intent: SearchIntent
        do { intent = try JSONDecoder().decode(SearchIntent.self, from: data) }
        catch let error as SearchIntentError { throw error }
        catch { throw SearchIntentError.invalidResponse }
        return try validate(intent, expectedSource: expectedSource)
    }

    /// Validates edited drafts as well as remote responses before they reach a cooking session.
    @discardableResult
    public func validate(_ intent: SearchIntent, expectedSource: SearchIntentSource? = nil) throws -> SearchIntent {
        guard intent.schemaVersion == SearchIntent.currentSchemaVersion else { throw SearchIntentError.unsupportedSchemaVersion }
        guard intent.dataVersion == dataVersion else { throw SearchIntentError.staleDataVersion }
        guard intent.policyVersion == SearchIntent.currentPolicyVersion else { throw SearchIntentError.unsupportedPolicyVersion }
        if let expectedSource, intent.source != expectedSource { throw SearchIntentError.invalidSource }
        let query = intent.query
        try check(query.stuff, allowed: vocabulary.stuff, field: "stuff")
        try check(query.avoid, allowed: vocabulary.stuff, field: "avoid")
        guard query.unknown.isEmpty else { throw SearchIntentError.illegalValue("unknown") }
        if let tool = query.tool, !vocabulary.tools.contains(tool) { throw SearchIntentError.illegalValue("tool") }
        try check(query.difficulty, allowed: vocabulary.difficulty, field: "difficulty")
        try check(query.methodGroups, allowed: vocabulary.methodGroups, field: "methodGroups")
        guard Set(query.stuff).isDisjoint(with: query.avoid) else { throw SearchIntentError.conflictingConditions }
        if let goal = intent.goal, !availableGoals.contains(goal) { throw SearchIntentError.unavailableGoal }
        guard intent.unresolved.count <= 20,
              intent.unresolved.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.count <= 500 }),
              Set(intent.unresolved).count == intent.unresolved.count else { throw SearchIntentError.illegalValue("unresolved") }
        return intent
    }

    private func check(_ values: [String], allowed: [String], field: String) throws {
        guard values.count <= 100, Set(values).count == values.count, values.allSatisfy(allowed.contains) else {
            throw SearchIntentError.illegalValue(field)
        }
    }
}

/// A conservative local adapter. Unsupported requests stay visible instead of becoming food names.
public struct LocalSearchIntentParser: Sendable {
    private let parser: CookQueryParser
    private let validator: SearchIntentValidator
    private let normalizer: StuffNormalizer

    public init(data: CookData, availableGoals: [DietaryGoal]) {
        parser = data.makeQueryParser()
        validator = SearchIntentValidator(data: data, availableGoals: availableGoals)
        normalizer = data.normalizer
    }

    /// Creates a draft without touching preferences, persistence, or the recommendation engine.
    public func parse(_ text: String) -> SearchIntent {
        guard text.count <= 1_000 else {
            return SearchIntent(dataVersion: validator.dataVersion, source: .local, query: CookQuery(), unresolved: ["这段话太长，请缩短到 1000 字以内。"])
        }
        var query = CookQuery()
        var goals: [DietaryGoal] = []
        var negatedGoals = Set<DietaryGoal>()
        var unresolved: [String] = []
        var hasConflictingTools = false
        for characters in CookQueryParser.splitClauses(text) {
            let clause = String(characters).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !clause.isEmpty else { continue }
            var remaining = clause
            let clauseGoals = DietaryGoal.allCases.filter { remaining.contains($0.title) }
            let ambiguousGoalCombination = clauseGoals.count > 1
            var clauseUnresolved = ambiguousGoalCombination
            for goal in clauseGoals {
                if ambiguousGoalCombination || isNegated(goal.title, in: remaining) || !validator.availableGoals.contains(goal) {
                    if isNegated(goal.title, in: remaining) { negatedGoals.insert(goal) }
                    clauseUnresolved = true
                } else {
                    goals.append(goal)
                }
                remaining = remaining.replacingOccurrences(of: goal.title, with: " ")
            }
            for term in Self.unsupportedRequests where remaining.contains(term) {
                clauseUnresolved = true
                remaining = remaining.replacingOccurrences(of: term, with: " ")
            }
            var part = parser.parse(remaining)
            let restrictedTerms = parser.terms.filter { word, term in
                switch term {
                case .tool, .method, .difficulty, .noSpicy: return remaining.contains(word) && isNegated(word, in: remaining)
                default: return false
                }
            }
            if !restrictedTerms.isEmpty {
                clauseUnresolved = true
                part.tool = nil
                part.methodGroups = []
                part.difficulty = []
                part.noSpicy = false
            }
            let mentionedTools = Set(parser.terms.compactMap { word, term -> String? in
                if case let .tool(name) = term, remaining.contains(word) { return name }
                return nil
            })
            if mentionedTools.count > 1 {
                hasConflictingTools = true
                clauseUnresolved = true
            }
            if !part.unknown.isEmpty || hasUnrecognizedFragment(remaining) { clauseUnresolved = true }
            if clauseUnresolved { unresolved.append(String(clause.prefix(500))) }
            query.stuff += part.stuff
            query.avoid += part.avoid
            query.noSpicy = query.noSpicy || part.noSpicy
            query.difficulty += part.difficulty
            query.methodGroups += part.methodGroups
            if let tool = part.tool {
                if let current = query.tool, current != tool {
                    hasConflictingTools = true
                } else { query.tool = tool }
            }
        }
        if hasConflictingTools {
            query.tool = nil
            unresolved.append("提到了多种厨具，请选择本次使用的一种。")
        }
        let uniqueGoals = Array(Set(goals).subtracting(negatedGoals))
        if uniqueGoals.count > 1 { unresolved.append("提到了多个饮食目标，请选择本次使用的一个目标。") }
        let known = Set(validator.vocabulary.stuff)
        query.avoid = normalizer.unique(query.avoid).filter(known.contains)
        let avoided = Set(query.avoid)
        query.stuff = normalizer.unique(query.stuff).filter { known.contains($0) && !avoided.contains($0) }
        query.difficulty = CookQueryParser.unique(query.difficulty)
        query.methodGroups = CookQueryParser.unique(query.methodGroups)
        let uniqueUnresolved = CookQueryParser.unique(unresolved)
        let pending = uniqueUnresolved.count > 20
            ? Array(uniqueUnresolved.prefix(19)) + ["还有未识别的条件，请缩短输入并重新识别。"]
            : uniqueUnresolved
        return SearchIntent(dataVersion: validator.dataVersion, source: .local, query: query,
                            goal: uniqueGoals.count == 1 ? uniqueGoals.first : nil, unresolved: pending)
    }

    private func isNegated(_ word: String, in text: String) -> Bool {
        let negations = ["不", "不要", "不想", "不想要", "不用", "别", "没有", "没", "不需要", "不考虑", "不打算", "无需", "无须", "不必", "不希望", "不要求", "不准备", "不追求", "不选择", "不采用", "暂不", "先不"]
        let postpositions = negations.filter { !["不", "没", "别"].contains($0) } + ["没了"]
        let prefixFillers = ["刻意", "特意", "专门", "进行", "选择", "考虑", "以及", "或者", "再", "去", "做", "要", "和", "跟", "与", "及", "或"]
        let suffixFillers = ["这个目标", "这一目标", "目标", "这次", "本次", "目前", "暂时", "现在", "就", "先"]
        var searchStart = text.startIndex
        while let range = text.range(of: word, range: searchStart..<text.endIndex) {
            var prefix = String(text[..<range.lowerBound].filter { !$0.isWhitespace })
            var suffix = String(text[range.upperBound...].filter { !$0.isWhitespace })
            if negations.contains(where: { prefix.hasSuffix($0) }) { return true }
            while let filler = prefixFillers.first(where: { prefix.hasSuffix($0) }) {
                prefix.removeLast(filler.count)
                if negations.contains(where: { prefix.hasSuffix($0) }) { return true }
            }
            if postpositions.contains(where: { suffix.hasPrefix($0) }) { return true }
            while let filler = suffixFillers.first(where: { suffix.hasPrefix($0) }) {
                suffix.removeFirst(filler.count)
                if postpositions.contains(where: { suffix.hasPrefix($0) }) { return true }
            }
            searchStart = range.upperBound
        }
        return false
    }

    private func hasUnrecognizedFragment(_ text: String) -> Bool {
        let characters = Array(text)
        var residual = ""
        var index = 0
        while index < characters.count {
            let maxLength = min(parser.maxLength, characters.count - index)
            let match = (1...max(1, maxLength)).reversed().first { length in
                index + length <= characters.count && parser.terms[String(characters[index..<index + length])] != nil
            }
            if let match { index += match } else { residual.append(characters[index]); index += 1 }
        }
        for filler in Self.fillers { residual = residual.replacingOccurrences(of: filler, with: "") }
        return residual.contains { !$0.isWhitespace && !$0.isNumber && !Self.fillerCharacters.contains($0) }
    }

    private static let fillers = ["冰箱里面", "冰箱里", "家里面", "家里", "今天晚上", "今天", "今晚", "现在", "帮我", "做个", "想要", "想吃", "想做", "一点", "一些", "简单点"]
    private static let fillerCharacters = Set("和跟与及的了呢吧啊呀哦嘛我想吃做用菜有还再来个道种颗根块斤克条盒包袋瓶一二两三四五六七八九十半些点、 ")
    private static let unsupportedRequests = ["控制体重", "减肥", "减脂", "低热量", "热量", "卡路里", "生酮", "痘痘", "祛痘", "孕期", "孕妇", "糖尿病", "控糖", "血糖", "高血压", "低盐", "低糖", "无糖", "不含糖", "降糖", "高蛋白", "低脂", "脂肪", "分钟", "健康", "营养", "清爽", "便宜"]
}
