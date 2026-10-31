import CookCore
import Foundation

/// 找菜页的场景条件；仅用于本次会话，不写入个人偏好。
struct CookDiscoveryFilters: Equatable {
    var easyOnly = false
    var methodGroup: String? = nil
    var tag: String? = nil

    var isEmpty: Bool {
        !easyOnly && (methodGroup?.isEmpty ?? true) && (tag?.isEmpty ?? true)
    }
}

struct CookDiscoveryInputResult: Hashable {
    enum Kind: Hashable {
        case empty
        case conditions
        case keyword
    }

    let kind: Kind
    let message: String
    var query: CookQuery? = nil
    var keyword: String? = nil
}

enum CookDiscoveryScene: String, CaseIterable, Identifiable {
    case easy
    case soup
    case home

    var id: String { rawValue }

    var label: String {
        switch self {
        case .easy: return "省点心"
        case .soup: return "来点热乎的"
        case .home: return "换个家常口味"
        }
    }

    var emoji: String { self == .home ? "🍚" : "🍲" }

    var title: String {
        switch self {
        case .easy: return "下班了，想对自己好一点"
        case .soup: return "想端起一碗热乎的。"
        case .home: return "熟悉的饭桌，也想换道菜。"
        }
    }

    var story: String {
        switch self {
        case .easy: return "包放下，饭好好吃。今天更想……"
        case .soup: return "锅里咕嘟咕嘟，桌上多了一只汤碗。今天，想从一碗汤开始。"
        case .home: return "米饭已经盛好，想找道有家常味、又不是昨天那一道的菜。"
        }
    }

    var explanation: String {
        switch self {
        case .easy: return "先看看已标为「简单」的做法"
        case .soup: return "先看看标有「汤」的汤羹做法"
        case .home: return "先看看标有「家常菜」的做法"
        }
    }

    var filters: CookDiscoveryFilters {
        switch self {
        case .easy: return CookDiscoveryFilters(easyOnly: true)
        case .soup: return CookDiscoveryFilters(methodGroup: "汤羹")
        case .home: return CookDiscoveryFilters(tag: "家常菜")
        }
    }
}

/// 在 CookSession 的候选池上收窄、解释和换一批，不修改菜谱或会话。
enum CookDiscovery {
    static func filter(_ recipes: [Recipe], using filters: CookDiscoveryFilters) -> [Recipe] {
        recipes.filter { matches($0, using: filters) }
    }

    static func matches(_ recipe: Recipe, using filters: CookDiscoveryFilters) -> Bool {
        let methodGroup = filters.methodGroup.flatMap { $0.isEmpty ? nil : $0 }
        if let methodGroup, !Recommender.methodGroups.contains(where: { $0.label == methodGroup }) {
            return false
        }
        return (!filters.easyOnly || recipe.difficulty == "简单")
            && Recommender.matchesTaste(recipe, methodGroups: methodGroup.map { [$0] }, noSpicy: false, difficulty: nil)
            && (filters.tag.map { $0.isEmpty || recipe.tags.contains($0) } ?? true)
    }

    /// 仅说明实际记录的标签与做法，不推断用时、营养或健康效果。
    static func reason(for recipe: Recipe, filters: CookDiscoveryFilters) -> String {
        var reasons: [String] = []
        if filters.easyOnly, recipe.difficulty == "简单" {
            reasons.append("难度标为「简单」")
        }
        if let group = Recommender.methodGroups.first(where: { $0.label == filters.methodGroup }) {
            let methods = recipe.methods.flatMap { $0.split(whereSeparator: { $0 == "、" || $0 == "/" }).map(String.init) }
            if let tag = group.tags.first(where: { recipe.tags.contains($0) }) {
                reasons.append("标有「\(tag)」标签")
            } else if let method = group.methods.first(where: { methods.contains($0) }) {
                reasons.append("做法记录为「\(method)」")
            }
        }
        if let tag = filters.tag, !tag.isEmpty, recipe.tags.contains(tag) {
            reasons.append("标有「\(tag)」标签")
        }
        if !reasons.isEmpty {
            return uniqueStrings(reasons).joined(separator: " · ")
        }
        if let difficulty = recipe.difficulty, !difficulty.isEmpty {
            return "难度标为「\(difficulty)」"
        }
        if !recipe.stuff.isEmpty {
            return "记录食材：\(recipe.stuff.joined(separator: "、"))"
        }
        return "完整用料请查看做法"
    }

    /// 每批按菜名去重；先选上批没出现的菜，候选较少时补回，保留全部可选菜。
    static func sample(_ recipes: [Recipe], count: Int = 3, avoiding previous: [Recipe] = []) -> [Recipe] {
        guard count > 0 else { return [] }
        var names: Set<String> = []
        let unique = recipes.filter { names.insert($0.name.trimmingCharacters(in: .whitespacesAndNewlines)).inserted }
        let seen = Set(previous.map { $0.name.trimmingCharacters(in: .whitespacesAndNewlines) })
        let fresh = unique.filter { !seen.contains($0.name.trimmingCharacters(in: .whitespacesAndNewlines)) }
        let repeated = unique.filter { seen.contains($0.name.trimmingCharacters(in: .whitespacesAndNewlines)) }
        return Array((fresh.shuffled() + repeated.shuffled()).prefix(count))
    }
}
