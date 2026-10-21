import Foundation

/// 推荐模式：宽松（缺两样以内）、严格（选的食材都要用上）、生存（一样都不缺）
public enum RecommendationMode: String, Codable, Hashable, Sendable {
    case loose
    case strict
    case survival
}

/// 推荐的筛选条件，字段和 TS 的 RecommendationOptions 一致，可直接解码共享用例
public struct RecommendationOptions: Codable, Hashable, Sendable {
    public var stuff: [String]
    public var pantry: [String]?
    public var tool: String?
    public var keyword: String?
    public var mode: RecommendationMode?
    /// 忌口：普通菜只要用到就排除，大杂烩只是不再建议加它
    public var avoid: [String]?
    /// 做法分组（见 Recommender.methodGroups 的 label），选了多个时满足任意一个即可
    public var methodGroups: [String]?
    /// 不吃辣：排除打了「辣」标签的菜
    public var noSpicy: Bool?
    /// 难度，选了多个时满足任意一个即可；没有记录难度的菜会被排除
    public var difficulty: [String]?

    public init(
        stuff: [String],
        pantry: [String]? = nil,
        tool: String? = nil,
        keyword: String? = nil,
        mode: RecommendationMode? = nil,
        avoid: [String]? = nil,
        methodGroups: [String]? = nil,
        noSpicy: Bool? = nil,
        difficulty: [String]? = nil
    ) {
        self.stuff = stuff
        self.pantry = pantry
        self.tool = tool
        self.keyword = keyword
        self.mode = mode
        self.avoid = avoid
        self.methodGroups = methodGroups
        self.noSpicy = noSpicy
        self.difficulty = difficulty
    }
}

/// 一条推荐结果
public struct RecipeMatch: Hashable, Sendable {
    public var recipe: Recipe
    /// 在数据里的位置，排序相同时按它
    public var sourceIndex: Int
    public var requiredStuff: [String]
    public var matchedStuff: [String]
    public var pantryStuff: [String]
    public var missingStuff: [String]
    /// 大杂烩里没选、但可以随意加的食材；普通菜为空
    public var optionalStuff: [String]
}

/// 「再补一样」的一条建议：补上这样食材，能多做 count 道差一种的菜
public struct UnlockSuggestion: Hashable, Sendable {
    public var name: String
    public var count: Int
}

/// 做法分组：数据里的做法写法很散，按吃起来的区别归成几类；汤羹靠「汤」标签识别
public struct MethodGroup: Hashable, Sendable {
    public var label: String
    public var methods: [String]
    public var tags: [String]
}

/// 按已有食材推荐菜谱，移植自 packages/cook/src/utils/recommend.ts。
/// 改动规则时两边一起改，并用 `pnpm fixtures` 重新生成共享用例。
public struct Recommender: Sendable {
    /// 没有质量分的菜按中间分排，和 TS 的 DEFAULT_RECIPE_SCORE 一致
    public static let defaultScore = 50
    /// 兜底大杂烩（火锅、炸万物等）列的是「能放什么」而不是「必须有什么」，数据里都在 10 种食材以上
    public static let catchAllMinStuff = 10
    /// 大杂烩至少要用上这么多种已选食材才推荐
    public static let catchAllMinMatch = 2
    public static let spicyTag = "辣"
    public static let difficultyLevels = ["简单", "普通", "困难"]
    public static let methodGroups: [MethodGroup] = [
        MethodGroup(label: "炒菜", methods: ["炒"], tags: []),
        MethodGroup(label: "汤羹", methods: [], tags: ["汤"]),
        MethodGroup(label: "炖煮", methods: ["炖", "煮", "煲", "卤", "焖", "烧", "灼"], tags: []),
        MethodGroup(label: "蒸", methods: ["蒸"], tags: []),
        MethodGroup(label: "炸煎", methods: ["炸", "煎", "烙"], tags: []),
        MethodGroup(label: "烤", methods: ["烤", "烘", "焗"], tags: []),
        MethodGroup(label: "凉拌", methods: ["拌", "凉拌", "腌", "腌制", "泡菜", "油泼"], tags: []),
        MethodGroup(label: "微波", methods: ["微波加热"], tags: []),
    ]

    struct Entry: Sendable {
        var recipe: Recipe
        var sourceIndex: Int
        var requiredStuff: [String]
    }

    public let normalizer: StuffNormalizer
    let catalog: [Entry]

    /// 菜谱的食材只在这里标准化一次
    public init(recipes: [Recipe], normalizer: StuffNormalizer) {
        self.normalizer = normalizer
        catalog = recipes.enumerated().map { index, recipe in
            Entry(recipe: recipe, sourceIndex: index, requiredStuff: normalizer.unique(recipe.stuff))
        }
    }

    static func isCatchAll(_ requiredStuff: [String]) -> Bool {
        requiredStuff.count >= catchAllMinStuff
    }

    /// 菜里有没有忌口的食材；大杂烩里的食材都是可选的，不算
    public func hasAvoidedStuff(_ recipe: Recipe, avoid: [String]) -> Bool {
        if avoid.isEmpty {
            return false
        }
        let requiredStuff = normalizer.unique(recipe.stuff)
        if Self.isCatchAll(requiredStuff) {
            return false
        }
        let avoided = Set(normalizer.unique(avoid))
        return requiredStuff.contains { avoided.contains($0) }
    }

    /// 做法、口味和难度筛选；没有记录做法或难度的菜在选了对应条件时不会出现
    public static func matchesTaste(_ recipe: Recipe, methodGroups selectedGroups: [String]?, noSpicy: Bool, difficulty: [String]?) -> Bool {
        if noSpicy && recipe.tags.contains(spicyTag) {
            return false
        }
        if let difficulty, !difficulty.isEmpty {
            guard let recipeDifficulty = recipe.difficulty, !recipeDifficulty.isEmpty, difficulty.contains(recipeDifficulty) else {
                return false
            }
        }
        let groups = methodGroups.filter { selectedGroups?.contains($0.label) ?? false }
        if groups.isEmpty {
            return true
        }
        let methods = recipe.methods.flatMap { method in
            method.split(whereSeparator: { $0 == "、" || $0 == "/" }).map(String.init)
        }
        return groups.contains { group in
            group.methods.contains { methods.contains($0) } || group.tags.contains { recipe.tags.contains($0) }
        }
    }

    /// 只选了厨具、还没选食材时，列出用这个厨具能做的菜；忌口、做法、口味和难度照样生效。
    /// 对应 Web 做菜页的 toolRecipes
    public func recipes(using tool: String, options: RecommendationOptions) -> [Recipe] {
        let keyword = (options.keyword ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let avoided = Set(normalizer.unique(options.avoid ?? []))
        return catalog.compactMap { entry in
            let recipe = entry.recipe
            guard recipe.tools.contains(tool),
                  keyword.isEmpty || recipe.name.contains(keyword),
                  Self.isCatchAll(entry.requiredStuff) || !entry.requiredStuff.contains(where: { avoided.contains($0) }),
                  Self.matchesTaste(recipe, methodGroups: options.methodGroups, noSpicy: options.noSpicy ?? false, difficulty: options.difficulty)
            else {
                return nil
            }
            return recipe
        }
    }

    /// 「今天吃什么」的候选：不看食材，只按厨具、忌口、做法、口味和难度从全部菜里挑
    public func candidates(_ options: RecommendationOptions) -> [Recipe] {
        if let tool = options.tool, !tool.isEmpty {
            return recipes(using: tool, options: options)
        }
        let avoided = Set(normalizer.unique(options.avoid ?? []))
        return catalog.compactMap { entry in
            let recipe = entry.recipe
            guard Self.isCatchAll(entry.requiredStuff) || !entry.requiredStuff.contains(where: { avoided.contains($0) }),
                  Self.matchesTaste(recipe, methodGroups: options.methodGroups, noSpicy: options.noSpicy ?? false, difficulty: options.difficulty)
            else {
                return nil
            }
            return recipe
        }
    }

    /// 「再补一样」：差一种的菜里最常缺的食材，补上后能直接多做几道。至少多 2 道才建议，最多 limit 个。
    /// 对应 Web 做菜页的 unlockSuggestions
    public static func unlockSuggestions(_ matches: [RecipeMatch], limit: Int = 4) -> [UnlockSuggestion] {
        var counts: [String: Int] = [:]
        var order: [String] = []
        for match in matches where match.missingStuff.count == 1 {
            let name = match.missingStuff[0]
            if counts[name] == nil {
                order.append(name)
            }
            counts[name, default: 0] += 1
        }
        // 次数相同时按第一次出现的顺序，和 JS 的稳定排序一致
        let ranked = order.map { UnlockSuggestion(name: $0, count: counts[$0] ?? 0) }
        let indexed = ranked.enumerated().filter { $0.element.count >= 2 }
        let sorted = indexed.sorted { a, b in
            a.element.count != b.element.count ? a.element.count > b.element.count : a.offset < b.offset
        }
        return sorted.prefix(limit).map(\.element)
    }

    /// 只有菜谱里明确记录的食材才算要求；没选食材时不推荐
    public func recommend(_ options: RecommendationOptions) -> [RecipeMatch] {
        let selected = Set(normalizer.unique(options.stuff))
        let pantry = Set(normalizer.unique(options.pantry ?? []))
        let keyword = (options.keyword ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let avoided = Set(normalizer.unique(options.avoid ?? []))
        let tool = options.tool ?? ""
        if selected.isEmpty {
            return []
        }

        var matches: [RecipeMatch] = []
        for entry in catalog {
            let recipe = entry.recipe
            let requiredStuff = entry.requiredStuff
            if !tool.isEmpty && !recipe.tools.contains(tool) {
                continue
            }
            if !keyword.isEmpty && !recipe.name.contains(keyword) {
                continue
            }
            let catchAll = Self.isCatchAll(requiredStuff)
            if !catchAll && requiredStuff.contains(where: { avoided.contains($0) }) {
                continue
            }
            if !Self.matchesTaste(recipe, methodGroups: options.methodGroups, noSpicy: options.noSpicy ?? false, difficulty: options.difficulty) {
                continue
            }

            let matchedStuff = requiredStuff.filter { selected.contains($0) }
            let pantryStuff = requiredStuff.filter { !selected.contains($0) && pantry.contains($0) }
            let unownedStuff = requiredStuff.filter { !selected.contains($0) && !pantry.contains($0) }
            let missingStuff = catchAll ? [] : unownedStuff
            let optionalStuff = catchAll ? unownedStuff.filter { !avoided.contains($0) } : []

            // 只靠常备调料不推荐；缺两样以上不推荐
            if matchedStuff.isEmpty || missingStuff.count > 2 {
                continue
            }
            if catchAll && matchedStuff.count < Self.catchAllMinMatch {
                continue
            }
            if options.mode == .strict && !selected.allSatisfy({ requiredStuff.contains($0) }) {
                continue
            }
            if options.mode == .survival && !missingStuff.isEmpty {
                continue
            }

            matches.append(RecipeMatch(
                recipe: recipe,
                sourceIndex: entry.sourceIndex,
                requiredStuff: requiredStuff,
                matchedStuff: matchedStuff,
                pantryStuff: pantryStuff,
                missingStuff: missingStuff,
                optionalStuff: optionalStuff
            ))
        }

        return matches.sorted { a, b in
            if a.missingStuff.count != b.missingStuff.count {
                return a.missingStuff.count < b.missingStuff.count
            }
            // 大杂烩是兜底选项，排在同组的正经菜后面
            let aCatchAll = Self.isCatchAll(a.requiredStuff)
            let bCatchAll = Self.isCatchAll(b.requiredStuff)
            if aCatchAll != bCatchAll {
                return !aCatchAll
            }
            if a.matchedStuff.count != b.matchedStuff.count {
                return a.matchedStuff.count > b.matchedStuff.count
            }
            // 同组里视频质量高的在前，见 docs/decisions/0010-recipe-quality-score.md
            let aScore = a.recipe.score ?? Self.defaultScore
            let bScore = b.recipe.score ?? Self.defaultScore
            if aScore != bScore {
                return aScore > bScore
            }
            return a.sourceIndex < b.sourceIndex
        }
    }
}
