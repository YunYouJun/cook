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
    private let emojiByStuff: [String: String]

    init(data: CookData) {
        self.data = data
        recommender = Recommender(recipes: data.recipes, normalizer: data.normalizer)
        recipesById = Dictionary(data.recipes.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
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

    func randomRecipe(excluding id: Int? = nil) -> Recipe? {
        let candidates = recipes.filter { $0.id != id }
        return candidates.randomElement() ?? recipes.randomElement()
    }
}
