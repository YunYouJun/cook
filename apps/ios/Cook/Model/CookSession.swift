import CookCore
import Foundation
import Observation

/// 做菜页的选择：已选食材和筛选条件只在本次使用；常备调料、忌口和不吃辣会记住
@Observable
final class CookSession {
    var selectedStuff: [String] = []
    var tool: String?
    var mode: RecommendationMode = .loose
    var methodGroups: [String] = []
    var difficulty: [String] = []
    var keyword = ""
    private(set) var pantry: [String]
    private(set) var avoid: [String]
    private(set) var noSpicy: Bool

    private let defaults: UserDefaults

    enum Keys {
        static let pantry = "cook.pantry"
        static let avoid = "cook.avoid"
        static let noSpicy = "cook.noSpicy"
    }

    init(defaultPantry: [String], defaults: UserDefaults = .standard) {
        self.defaults = defaults
        pantry = defaults.stringArray(forKey: Keys.pantry) ?? defaultPantry
        avoid = defaults.stringArray(forKey: Keys.avoid) ?? []
        noSpicy = defaults.bool(forKey: Keys.noSpicy)
    }

    var options: RecommendationOptions {
        RecommendationOptions(
            stuff: selectedStuff,
            pantry: pantry,
            tool: tool,
            keyword: keyword,
            mode: mode,
            avoid: avoid,
            methodGroups: methodGroups,
            noSpicy: noSpicy,
            difficulty: difficulty
        )
    }

    func toggleStuff(_ name: String) {
        selectedStuff = Self.toggled(selectedStuff, name)
    }

    func clearStuff() {
        selectedStuff = []
    }

    /// 厨具只能选一个，再点一次取消
    func toggleTool(_ name: String) {
        tool = tool == name ? nil : name
    }

    func toggleMethodGroup(_ label: String) {
        methodGroups = Self.toggled(methodGroups, label)
    }

    func toggleDifficulty(_ level: String) {
        difficulty = Self.toggled(difficulty, level)
    }

    func togglePantry(_ name: String) {
        pantry = Self.toggled(pantry, name)
        defaults.set(pantry, forKey: Keys.pantry)
    }

    func resetPantry(to defaultPantry: [String]) {
        pantry = defaultPantry
        defaults.set(pantry, forKey: Keys.pantry)
    }

    func toggleAvoid(_ name: String) {
        avoid = Self.toggled(avoid, name)
        defaults.set(avoid, forKey: Keys.avoid)
    }

    func clearAvoid() {
        avoid = []
        defaults.set(avoid, forKey: Keys.avoid)
    }

    func setNoSpicy(_ value: Bool) {
        noSpicy = value
        defaults.set(value, forKey: Keys.noSpicy)
    }

    static func toggled(_ list: [String], _ item: String) -> [String] {
        list.contains(item) ? list.filter { $0 != item } : list + [item]
    }
}
