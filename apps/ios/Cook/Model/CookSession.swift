import CookCore
import Foundation
import Observation
import SwiftData

/// 做菜页的选择：已选食材和筛选条件只在本次使用；常备调料、忌口、不吃辣和推荐模式存在 SwiftData，随 iCloud 同步
@Observable
final class CookSession {
    var selectedStuff: [String] = []
    var tool: String?
    var methodGroups: [String] = []
    var difficulty: [String] = []
    var keyword = ""

    private let context: ModelContext
    private let preferences: Preferences

    init(defaultPantry: [String], context: ModelContext) {
        self.context = context
        preferences = context.preferences(defaultPantry: defaultPantry)
    }

    // 偏好直接读写模型，其他设备同步过来的修改也会刷新界面
    var pantry: [String] {
        preferences.pantry
    }

    var avoid: [String] {
        preferences.avoid
    }

    var noSpicy: Bool {
        preferences.noSpicy
    }

    var mode: RecommendationMode {
        get { RecommendationMode(rawValue: preferences.mode) ?? .loose }
        set { updatePreferences { $0.mode = newValue.rawValue } }
    }

    /// 做法、难度和不吃辣这几个筛选条件选了几个，做菜页的「筛选」按钮上显示
    var activeFilterCount: Int {
        methodGroups.count + difficulty.count + (noSpicy ? 1 : 0)
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
        updatePreferences { $0.pantry = Self.toggled($0.pantry, name) }
    }

    func resetPantry(to defaultPantry: [String]) {
        updatePreferences { $0.pantry = defaultPantry }
    }

    func toggleAvoid(_ name: String) {
        updatePreferences { $0.avoid = Self.toggled($0.avoid, name) }
    }

    func clearAvoid() {
        updatePreferences { $0.avoid = [] }
    }

    func setNoSpicy(_ value: Bool) {
        updatePreferences { $0.noSpicy = value }
    }

    /// 清掉做法、难度和不吃辣
    func clearFilters() {
        methodGroups = []
        difficulty = []
        setNoSpicy(false)
    }

    private func updatePreferences(_ change: (Preferences) -> Void) {
        change(preferences)
        preferences.updatedAt = Date()
        try? context.save()
    }

    static func toggled(_ list: [String], _ item: String) -> [String] {
        list.contains(item) ? list.filter { $0 != item } : list + [item]
    }
}
