import CookCore
import Foundation
import SwiftData

/// 分类独立于库存，吃完最后一份食材后仍可继续使用该分类。
@Model
final class FridgeCategory {
    var identifier: UUID = UUID()
    var name: String = ""

    init(name: String) {
        self.name = name
    }

    static func nameError(_ name: String, takenNames: [String]) -> String? {
        if name.isEmpty { return "请输入分类名称。" }
        if name.count > 24 { return "分类名称最多 24 个字。" }
        if (FridgeBuiltInCategory.allCases.map(\.title) + ["自动归类"] + takenNames).contains(where: {
            $0.compare(name, options: [.caseInsensitive, .widthInsensitive]) == .orderedSame
        }) {
            return "已经有同名分类了。"
        }
        return nil
    }
}

enum FridgeBuiltInCategory: String, CaseIterable {
    case fruit, vegetable, meat, staple, uncategorized

    var title: String {
        switch self {
        case .fruit: return "水果"
        case .vegetable: return "蔬菜"
        case .meat: return "肉和蛋"
        case .staple: return "主食"
        case .uncategorized: return "未分类"
        }
    }

    static func automatic(for name: String, in food: FoodCatalog) -> Self {
        if FridgeFruit(rawValue: name) != nil { return .fruit }
        if food.vegetable.contains(where: { $0.name == name }) { return .vegetable }
        if food.meat.contains(where: { $0.name == name }) { return .meat }
        if food.staple.contains(where: { $0.name == name }) { return .staple }
        return .uncategorized
    }
}

enum FridgeCategoryChoice: Hashable {
    case automatic
    case builtIn(FridgeBuiltInCategory)
    case custom(UUID)

    init(storedKey: String?) {
        if let key = storedKey, let category = FridgeBuiltInCategory(rawValue: key) {
            self = .builtIn(category)
        } else if let key = storedKey, let identifier = UUID(uuidString: key) {
            self = .custom(identifier)
        } else {
            self = .automatic
        }
    }

    var storedKey: String? {
        switch self {
        case .automatic: return nil
        case let .builtIn(category): return category.rawValue
        case let .custom(identifier): return identifier.uuidString
        }
    }

    /// 分类删除后保留食材；原分类不存在时和旧记录一样自动归类。
    func available(in categories: [FridgeCategory]) -> Self {
        if case let .custom(identifier) = self, !categories.contains(where: { $0.identifier == identifier }) {
            return .automatic
        }
        return self
    }

    func resolved(for name: String, in food: FoodCatalog, custom categories: [FridgeCategory]) -> Self {
        let choice = available(in: categories)
        return choice == .automatic ? .builtIn(.automatic(for: name, in: food)) : choice
    }
}

/// 冰箱快选独立于菜谱食材词典，只有已有储存依据的水果才提供专属天数。
enum FridgeFruit: String, CaseIterable, Identifiable {
    case blueberry = "蓝莓"
    case watermelon = "西瓜"
    case apple = "苹果"
    case strawberry = "草莓"
    case raspberry = "树莓"
    case blackberry = "黑莓"
    case banana = "香蕉"
    case grape = "葡萄"
    case orange = "橙子"
    case pineapple = "菠萝"
    case peach = "桃"
    case nectarine = "油桃"
    case pear = "梨"

    var id: String { rawValue }

    var emoji: String {
        switch self {
        case .blueberry: return "🫐"
        case .watermelon: return "🍉"
        case .apple: return "🍎"
        case .strawberry: return "🍓"
        case .raspberry, .blackberry: return "🫐"
        case .banana: return "🍌"
        case .grape: return "🍇"
        case .orange: return "🍊"
        case .pineapple: return "🍍"
        case .peach, .nectarine: return "🍑"
        case .pear: return "🍐"
        }
    }
}
