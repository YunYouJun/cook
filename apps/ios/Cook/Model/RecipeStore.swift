import CookCore
import Foundation
import Observation

enum RecipeStoreError: LocalizedError {
    case missingBundledData

    var errorDescription: String? {
        "App 里没有内置菜谱数据，构建前要在仓库根目录运行 pnpm convert"
    }
}

/// 内置的菜谱数据和由它派生的查询，启动时加载一次
@Observable
final class RecipeStore {
    let data: CookData
    let recommender: Recommender
    let recipesById: [Int: Recipe]
    /// 网页版的浏览记录按菜名认，导入时用
    let recipeIdByName: [String: Int]
    /// 整句识别，词表只建一次
    let queryParser: CookQueryParser
    private let emojiByStuff: [String: String]

    init(data: CookData) {
        self.data = data
        recommender = Recommender(recipes: data.recipes, normalizer: data.normalizer)
        recipesById = Dictionary(data.recipes.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        recipeIdByName = Dictionary(data.recipes.map { ($0.name, $0.id) }, uniquingKeysWith: { first, _ in first })
        queryParser = data.makeQueryParser()
        let items = data.food.vegetable + data.food.meat + data.food.staple
        emojiByStuff = Dictionary(items.map { ($0.name, $0.emoji) }, uniquingKeysWith: { first, _ in first })
    }

    static func loadBundled(bundle: Bundle = .main) throws -> RecipeStore {
        guard let url = bundle.url(forResource: "CookData", withExtension: nil) else {
            throw RecipeStoreError.missingBundledData
        }
        return RecipeStore(data: try CookData.load(from: url))
    }

    var recipes: [Recipe] {
        data.recipes
    }

    var stuffItems: [StuffItem] {
        data.food.vegetable + data.food.meat + data.food.staple
    }

    func emoji(forStuff name: String) -> String? {
        guard let emoji = emojiByStuff[name], !emoji.isEmpty else {
            return nil
        }
        return emoji
    }

    /// 列表和详情里代表这道菜的 emoji：大杂烩用锅，其余用第一样有 emoji 的食材
    func thumbnail(for recipe: Recipe) -> String {
        if recipe.tags.contains("杂烩") {
            return "🍲"
        }
        for item in data.normalizer.unique(recipe.stuff) {
            if let emoji = emoji(forStuff: item) {
                return emoji
            }
        }
        return "🍽️"
    }

    func toolLabel(_ tool: String) -> String {
        tool == "一口大锅" ? "炒锅" : tool
    }

    /// 厨具 · 难度
    func meta(for recipe: Recipe) -> String {
        let tools = recipe.tools.isEmpty ? "厨具未记录" : recipe.tools.map(toolLabel).joined(separator: " / ")
        if let difficulty = recipe.difficulty, !difficulty.isEmpty {
            return "\(tools) · \(difficulty)"
        }
        return tools
    }

    /// 按菜名或食材筛选
    func filter(_ recipes: [Recipe], query: String) -> [Recipe] {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            return recipes
        }
        let stuff = data.normalizer.normalize([text]).first ?? text
        return recipes.filter { recipe in
            recipe.name.contains(text) || recipe.stuff.contains(stuff)
        }
    }

    func randomRecipe(from pool: [Recipe]? = nil, excluding id: Int? = nil) -> Recipe? {
        let pool = pool ?? recipes
        let candidates = pool.filter { $0.id != id }
        return candidates.randomElement() ?? pool.randomElement()
    }

    /// 输入框联想：菜名或别名里含输入的食材，已选的不再出现
    func stuffSuggestions(for text: String, excluding selected: [String], limit: Int = 6) -> [StuffItem] {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return []
        }
        return Array(stuffItems
            .filter { !selected.contains($0.name) && ($0.name.contains(text) || ($0.alias?.contains(text) ?? false)) }
            .prefix(limit))
    }

    /// 词典里有的食材（做菜页标签里能点到的）
    func isCatalogStuff(_ name: String) -> Bool {
        emojiByStuff[name] != nil
    }

    /// 识别结果写成一行，让人知道哪些说法生效了；和 Web 的 describeQuery 一致
    func describe(_ query: CookQuery) -> String {
        var parts = [(query.stuff + query.unknown).joined(separator: "、")]
        if !query.avoid.isEmpty {
            parts.append("忌口\(query.avoid.joined(separator: "、"))")
        }
        if query.noSpicy {
            parts.append("不辣")
        }
        if let tool = query.tool {
            parts.append(toolLabel(tool))
        }
        parts += query.methodGroups + query.difficulty
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}
