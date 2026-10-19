import Foundation

/// 整句识别的词表（packages/data/query.json），Web 和 iOS 共用
public struct QueryRules: Codable, Hashable, Sendable {
    /// 补充的食材写法 → 标准名
    public var stuff: [String: String]
    /// 含食材字样但不是食材的词，如「玉米」里的「米」
    public var ignore: [String]
    /// 厨具写法 → food.json 里的厨具名
    public var tools: [String: String]
    public var have: [String]
    public var avoid: [String]
    public var lack: [String]
    public var noSpicy: [String]
    /// 难度 → 说法
    public var difficulty: [String: [String]]
    /// 做法分组 label → 说法
    public var methodGroups: [String: [String]]
}

/// 一句话识别出的筛选条件，字段和 TS 的 CookQuery 一致
public struct CookQuery: Codable, Hashable, Sendable {
    /// 识别到的食材（标准名）
    public var stuff: [String] = []
    /// 词典里没有、但看起来像食材的词，原样保留
    public var unknown: [String] = []
    /// 忌口
    public var avoid: [String] = []
    /// 厨具，只取第一个
    public var tool: String?
    public var noSpicy = false
    public var difficulty: [String] = []
    /// 对应 Recommender.methodGroups 的 label
    public var methodGroups: [String] = []

    public init() {}

    /// 一句话里有没有认出任何东西
    public var isEmpty: Bool {
        stuff.isEmpty && unknown.isEmpty && avoid.isEmpty && tool == nil
            && !noSpicy && difficulty.isEmpty && methodGroups.isEmpty
    }
}

/// 整句识别：把「冰箱里有土豆和鸡蛋，不要辣」这样的一句话拆成推荐用的筛选条件。
/// 移植自 packages/cook/src/utils/query.ts，只靠词表做最长匹配，不调用模型；
/// 两端都跑 packages/data/fixtures/query.json，改规则时两边一起改。
public struct CookQueryParser: Sendable {
    enum Term: Hashable, Sendable {
        case stuff(String)
        case tool(String)
        case method(String)
        case difficulty(String)
        case have
        case avoid
        case lack
        case noSpicy
        case ignore
    }

    private enum Scope {
        case have
        case avoid
        case lack
    }

    private enum ListKind {
        case stuff
        case unknown
    }

    static let clauseSeparators: Set<Character> = ["，", ",", "。", ".", ";", "；", "！", "!", "？", "?", "\n"]
    /// 多字的分句词，长的在前
    static let clauseWords = ["但是", "但", "不过", "可是"]
    static let listSeparators: Set<Character> = ["、", "和", "跟", "与", "及"]
    /// 否定词后面只剩这些字时，它说的是前面的食材，如「香菜不要」「虾过敏」
    static let tailCharacters: Set<Character> = ["了", "的", "哦", "吧", "啊", "呀", "呢", "嘛", "哈", "~", "～"]
    /// 含这些字的片段多半是句子成分而不是食材名，如「冰箱里」「两个」「能做的」
    static let notStuffCharacters = Set("的了吗呢吧啊呀哦嘛做吃要想能么什怎啥咋我你他她有没还都就很太些里剩用给帮看找来去在是个只点下可以会把对也再又一二两三四五六七八九十半几颗根块斤克条盒包袋瓶")

    let terms: [String: Term]
    let maxLength: Int
    let rules: QueryRules
    let food: FoodCatalog
    let aliases: [String: String]

    public init(rules: QueryRules, food: FoodCatalog, aliases: [String: String], extraStuff: [String] = []) {
        self.rules = rules
        self.food = food
        self.aliases = aliases
        var terms: [String: Term] = [:]
        // 先放优先级低的，后放的同名词覆盖前面的
        for name in extraStuff {
            terms[name] = .stuff(aliases[name] ?? name)
        }
        for item in food.vegetable + food.meat + food.staple {
            terms[item.name] = .stuff(item.name)
            if let alias = item.alias, !alias.isEmpty {
                terms[alias] = .stuff(item.name)
            }
        }
        for (alias, name) in aliases.merging(rules.stuff, uniquingKeysWith: { _, rule in rule }) {
            terms[alias] = .stuff(name)
        }
        for (label, words) in rules.methodGroups {
            for word in words {
                terms[word] = .method(label)
            }
        }
        for item in food.tools {
            terms[item.name] = .tool(item.name)
        }
        for (alias, name) in rules.tools {
            terms[alias] = .tool(name)
        }
        let kinds: [([String], Term)] = [
            (rules.have, .have), (rules.lack, .lack), (rules.avoid, .avoid), (rules.noSpicy, .noSpicy), (rules.ignore, .ignore),
        ]
        for (words, term) in kinds {
            for word in words {
                terms[word] = term
            }
        }
        for (level, words) in rules.difficulty {
            for word in words {
                terms[word] = .difficulty(level)
            }
        }
        self.terms = terms
        maxLength = terms.keys.map(\.count).max() ?? 0
    }

    /// 词典以外也要认的食材，比如自己添加的菜谱里用到的
    public func adding(extraStuff: [String]) -> CookQueryParser {
        extraStuff.isEmpty ? self : CookQueryParser(rules: rules, food: food, aliases: aliases, extraStuff: extraStuff)
    }

    private struct Token {
        var term: Term
        var start: Int
        var end: Int
    }

    /// 从左到右，每个位置取最长的词
    private func scan(_ text: [Character]) -> [Token] {
        var tokens: [Token] = []
        var start = 0
        while start < text.count {
            var found: Token?
            var length = min(maxLength, text.count - start)
            while length > 0 {
                if let term = terms[String(text[start ..< start + length])] {
                    found = Token(term: term, start: start, end: start + length)
                    break
                }
                length -= 1
            }
            if let found {
                tokens.append(found)
            }
            start = found?.end ?? start + 1
        }
        return tokens
    }

    static func splitClauses(_ text: String) -> [[Character]] {
        let characters = Array(text)
        var clauses: [[Character]] = []
        var current: [Character] = []
        var index = 0
        outer: while index < characters.count {
            if clauseSeparators.contains(characters[index]) {
                clauses.append(current)
                current = []
                index += 1
                continue
            }
            for word in clauseWords {
                let length = word.count
                if index + length <= characters.count, String(characters[index ..< index + length]) == word {
                    clauses.append(current)
                    current = []
                    index += length
                    continue outer
                }
            }
            current.append(characters[index])
            index += 1
        }
        clauses.append(current)
        return clauses
    }

    static func isTail(_ text: ArraySlice<Character>) -> Bool {
        text.allSatisfy { tailCharacters.contains($0) || $0.isWhitespace }
    }

    static func isUnknownStuff(_ piece: [Character]) -> Bool {
        guard (1 ... 6).contains(piece.count), !piece.contains(where: notStuffCharacters.contains) else {
            return false
        }
        return piece.allSatisfy { character in
            character.unicodeScalars.count == 1 && (0x4E00 ... 0x9FFF).contains(character.unicodeScalars.first!.value)
        }
    }

    static func unknownStuff(_ gap: ArraySlice<Character>) -> [String] {
        var pieces: [[Character]] = [[]]
        for character in gap {
            if listSeparators.contains(character) || character.isWhitespace {
                if !(pieces.last?.isEmpty ?? true) {
                    pieces.append([])
                }
            } else {
                pieces[pieces.count - 1].append(character)
            }
        }
        return pieces.filter(isUnknownStuff).map { String($0) }
    }

    /// 把一句话拆成筛选条件
    public func parse(_ text: String) -> CookQuery {
        var stuff: [String] = []
        var unknown: [String] = []
        var avoid: [String] = []
        var tools: [String] = []
        var difficulty: [String] = []
        var methodGroups: [String] = []
        var noSpicy = false

        for clause in Self.splitClauses(text) {
            var scope = Scope.have
            // 当前作用范围里已经算作「有」的食材，遇到「香菜不要」这种后置否定时要改判
            var pending: [(list: ListKind, name: String)] = []

            func addStuff(_ name: String, isUnknown: Bool = false) {
                switch scope {
                case .have:
                    if isUnknown {
                        unknown.append(name)
                    } else {
                        stuff.append(name)
                    }
                    pending.append((isUnknown ? .unknown : .stuff, name))
                case .avoid:
                    if !isUnknown {
                        avoid.append(name)
                    }
                case .lack:
                    break
                }
            }

            func removeLast(_ name: String, from list: inout [String]) {
                if let index = list.lastIndex(of: name) {
                    list.remove(at: index)
                }
            }

            var cursor = 0
            for token in scan(clause) {
                for name in Self.unknownStuff(clause[cursor ..< token.start]) {
                    addStuff(name, isUnknown: true)
                }
                cursor = token.end

                switch token.term {
                case let .stuff(name):
                    addStuff(name)
                case .have:
                    scope = .have
                    pending = []
                case .avoid, .lack:
                    let isAvoid = token.term == .avoid
                    if Self.isTail(clause[token.end...]) {
                        for item in pending {
                            switch item.list {
                            case .stuff:
                                removeLast(item.name, from: &stuff)
                                if isAvoid {
                                    avoid.append(item.name)
                                }
                            case .unknown:
                                removeLast(item.name, from: &unknown)
                            }
                        }
                    }
                    scope = isAvoid ? .avoid : .lack
                    pending = []
                case .noSpicy:
                    noSpicy = true
                case let .tool(name):
                    if scope == .have {
                        tools.append(name)
                    }
                case let .method(label):
                    if scope == .have {
                        methodGroups.append(label)
                    }
                case let .difficulty(level):
                    difficulty.append(level)
                case .ignore:
                    break
                }
            }
            for name in Self.unknownStuff(clause[cursor...]) {
                addStuff(name, isUnknown: true)
            }
        }

        let avoided = Set(avoid)
        var query = CookQuery()
        query.stuff = Self.unique(stuff).filter { !avoided.contains($0) }
        query.unknown = Self.unique(unknown)
        query.avoid = Self.unique(avoid)
        query.tool = tools.first
        query.noSpicy = noSpicy
        query.difficulty = Self.unique(difficulty)
        query.methodGroups = Self.unique(methodGroups)
        return query
    }

    static func unique(_ items: [String]) -> [String] {
        var seen = Set<String>()
        return items.filter { seen.insert($0).inserted }
    }
}
