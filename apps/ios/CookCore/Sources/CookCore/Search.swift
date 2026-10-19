import Foundation

/// 搜索条件，字段和 TS 的 SearchOptions 一致，可直接解码共享用例
public struct SearchOptions: Codable, Hashable, Sendable {
    public var stuff: [String]?
    /// 厨具，子串匹配
    public var tool: String?
    public var difficulty: String?
    /// 标签，子串匹配
    public var tag: String?
    /// 做法，子串匹配
    public var method: String?
    /// 最多返回几条，默认 10
    public var limit: Int?

    public init(stuff: [String]? = nil, tool: String? = nil, difficulty: String? = nil, tag: String? = nil, method: String? = nil, limit: Int? = nil) {
        self.stuff = stuff
        self.tool = tool
        self.difficulty = difficulty
        self.tag = tag
        self.method = method
        self.limit = limit
    }
}

public struct SearchResultItem: Hashable, Sendable {
    public var recipe: Recipe
    public var matchedStuff: [String]
    public var missingStuff: [String]
}

public struct SearchResult: Hashable, Sendable {
    public var recipes: [SearchResultItem]
    /// 选的食材里同时出现的相克组合
    public var warnings: [IncompatibleRule]
}

/// 搜索（App Intents、Spotlight 等用），移植自 packages/cook/src/utils/search.ts：
/// 有一样食材命中就算，按命中数从多到少、食材数从少到多排序。
public func searchRecipes(
    _ recipes: [Recipe],
    incompatible: [IncompatibleRule],
    options: SearchOptions,
    normalizer: StuffNormalizer
) -> SearchResult {
    let stuffLower = normalizer.normalize(options.stuff ?? []).map { $0.lowercased() }
    let tool = options.tool ?? ""
    let difficulty = options.difficulty ?? ""
    let tag = options.tag ?? ""
    let method = options.method ?? ""
    let limit = options.limit ?? 10

    func isSelected(_ item: String) -> Bool {
        stuffLower.contains(item.lowercased())
    }

    var matched = recipes.enumerated().filter { _, recipe in
        if !stuffLower.isEmpty && !recipe.stuff.contains(where: isSelected) {
            return false
        }
        if !tool.isEmpty && !recipe.tools.contains(where: { $0.contains(tool) }) {
            return false
        }
        if !difficulty.isEmpty && recipe.difficulty != difficulty {
            return false
        }
        if !tag.isEmpty && !recipe.tags.contains(where: { $0.contains(tag) }) {
            return false
        }
        if !method.isEmpty && !recipe.methods.contains(where: { $0.contains(method) }) {
            return false
        }
        return true
    }

    if !stuffLower.isEmpty {
        // 和 JS 的稳定排序一致：相同时保持原顺序
        matched.sort { a, b in
            let aCount = a.element.stuff.filter(isSelected).count
            let bCount = b.element.stuff.filter(isSelected).count
            if aCount != bCount {
                return aCount > bCount
            }
            if a.element.stuff.count != b.element.stuff.count {
                return a.element.stuff.count < b.element.stuff.count
            }
            return a.offset < b.offset
        }
    }

    // 和 Array.prototype.slice(0, limit) 一致，负数表示去掉末尾几条
    let count = limit >= 0 ? min(limit, matched.count) : max(0, matched.count + limit)
    let items = matched.prefix(count).map { _, recipe in
        SearchResultItem(
            recipe: recipe,
            matchedStuff: recipe.stuff.filter(isSelected),
            missingStuff: recipe.stuff.filter { !isSelected($0) }
        )
    }

    var warnings: [IncompatibleRule] = []
    if stuffLower.count >= 2 {
        warnings = incompatible.filter { isSelected($0.foodA) && isSelected($0.foodB) }
    }

    return SearchResult(recipes: items, warnings: warnings)
}
