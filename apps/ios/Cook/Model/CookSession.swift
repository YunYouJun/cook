import CookCore
import Foundation
import Observation
import os
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
    private let defaults: PreferenceValues
    private var preferences: Preferences?
    private static let logger = Logger(subsystem: "fun.yunle.cook", category: "Preferences")

    init(defaultPantry: [String], context: ModelContext) {
        self.context = context
        defaults = PreferenceValues(pantry: defaultPantry)
        refreshPreferences()
    }

    /// 查询变化时重新绑定；只读、不保存，重复调用不会触发写入或查询循环。
    func refreshPreferences() {
        do {
            let latest = try context.latestPreferences()
            if preferences !== latest { preferences = latest }
        } catch {
            Self.logger.error("读取做菜偏好失败：\(error.localizedDescription, privacy: .public)")
        }
    }

    // 已绑定记录的属性更新由 SwiftData 跟踪；新增、删除记录由 RootView 的 @Query 触发重绑定。
    var pantry: [String] {
        preferences?.pantry ?? defaults.pantry
    }

    var avoid: [String] {
        preferences?.avoid ?? defaults.avoid
    }

    var noSpicy: Bool {
        preferences?.noSpicy ?? defaults.noSpicy
    }

    var mode: RecommendationMode {
        get { RecommendationMode(rawValue: preferences?.mode ?? defaults.mode) ?? .loose }
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

    func addStuff(_ name: String) {
        if !selectedStuff.contains(name) {
            selectedStuff.append(name)
        }
    }

    /// 选了食材或厨具，就能去看结果
    var hasSelection: Bool {
        !selectedStuff.isEmpty || tool != nil
    }

    /// 冰箱页「用这些做菜」：换成这几样食材，厨具和菜名搜索清掉，免得结果莫名变少
    func use(_ stuff: [String]) {
        selectedStuff = uniqueStrings(stuff)
        tool = nil
        keyword = ""
    }

    /// 清空做菜页：已选的食材和厨具
    func clearStuff() {
        selectedStuff = []
        tool = nil
    }

    /// 把一句话识别出的条件合进当前选择；忌口和「不吃辣」与手动勾选一样会一直保留（和 Web 的 applyQuery 一致）
    func apply(_ query: CookQuery, normalizer: StuffNormalizer) {
        let avoided = Set(normalizer.unique(query.avoid))
        selectedStuff = normalizer.unique(selectedStuff + query.stuff + query.unknown).filter { !avoided.contains($0) }
        if !avoided.isEmpty {
            updatePreferences { $0.avoid = normalizer.unique($0.avoid + query.avoid) }
        }
        if query.noSpicy {
            setNoSpicy(true)
        }
        if let tool = query.tool {
            self.tool = tool
        }
        methodGroups = uniqueStrings(methodGroups + query.methodGroups)
        difficulty = uniqueStrings(difficulty + query.difficulty)
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

    private func updatePreferences(_ change: (inout PreferenceValues) -> Void) {
        do {
            preferences = try context.updatePreferences(defaults: defaults, change)
        } catch {
            Self.logger.error("保存做菜偏好失败：\(error.localizedDescription, privacy: .public)")
            refreshPreferences()
        }
    }

    static func toggled(_ list: [String], _ item: String) -> [String] {
        list.contains(item) ? list.filter { $0 != item } : list + [item]
    }
}
