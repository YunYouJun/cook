import CookCore
import Foundation
import Observation
import os
import SwiftData

/// 同一次查询的候选与食材匹配，供找菜与食材选择共用。
struct CookCandidateSnapshot {
    let recipes: [Recipe]
    let matchesByID: [Int: RecipeMatch]
}

/// Meal ingredients and filters are transient; the default dietary goal stays on this device.
/// Pantry, exclusions, spice preference, and mode use SwiftData and iCloud.
@Observable
final class CookSession {
    var selectedStuff: [String] = []
    var tool: String?
    var methodGroups: [String] = []
    var difficulty: [String] = []
    var keyword = ""
    var discoveryScene: CookDiscoveryScene? = nil
    private(set) var dietaryGoal: DietaryGoal?
    private(set) var hasCompletedDietaryIntroduction: Bool
    @ObservationIgnored private let dietaryDefaults: UserDefaults?
    static let dietaryGoalKey = "cook.dietary.rememberedGoal"
    static let dietaryIntroductionKey = "cook.dietary.introductionCompleted"

    private let context: ModelContext
    private let defaults: PreferenceValues
    private var preferences: Preferences?
    private static let logger = Logger(subsystem: "fun.yunle.cook", category: "Preferences")

    init(defaultPantry: [String], context: ModelContext, dietaryDefaults: UserDefaults? = nil) {
        self.context = context
        self.dietaryDefaults = dietaryDefaults
        let remembered = dietaryDefaults?.string(forKey: Self.dietaryGoalKey).flatMap(DietaryGoal.init(rawValue:))
        dietaryGoal = remembered
        hasCompletedDietaryIntroduction = dietaryDefaults?.bool(forKey: Self.dietaryIntroductionKey) == true || remembered != nil
        defaults = PreferenceValues(pantry: defaultPantry)
        refreshPreferences()
    }

    /// Dismissing the introduction changes only local presentation state.
    func completeDietaryIntroduction() {
        hasCompletedDietaryIntroduction = true
        dietaryDefaults?.set(true, forKey: Self.dietaryIntroductionKey)
    }

    func selectDietaryGoal(_ goal: DietaryGoal?) {
        completeDietaryIntroduction()
        dietaryGoal = goal
        if let goal {
            dietaryDefaults?.set(goal.rawValue, forKey: Self.dietaryGoalKey)
        } else {
            dietaryDefaults?.removeObject(forKey: Self.dietaryGoalKey)
        }
    }

    func matches(in store: RecipeStore, ignoringKeyword: Bool = false) -> [RecipeMatch] {
        var options = self.options
        if ignoringKeyword { options.keyword = nil }
        let matches: [RecipeMatch]
        if let dietaryGoal {
            matches = store.dietaryCatalog?.recommend(options, goal: dietaryGoal, using: store.recommender) ?? []
        } else {
            matches = store.recommender.recommend(options)
        }
        guard let discoveryScene else { return matches }
        return matches.filter { CookDiscovery.matches($0.recipe, using: discoveryScene.filters) }
    }

    func candidates(in store: RecipeStore, ignoringKeyword: Bool = false) -> [Recipe] {
        candidateSnapshot(in: store, ignoringKeyword: ignoringKeyword).recipes
    }

    /// 每次从当前条件和已绑定偏好生成值快照，不跨条件或 SwiftData 记录缓存。
    func candidateSnapshot(in store: RecipeStore, ignoringKeyword: Bool = false) -> CookCandidateSnapshot {
        if !selectedStuff.isEmpty {
            let matches = matches(in: store, ignoringKeyword: ignoringKeyword)
            return CookCandidateSnapshot(
                recipes: matches.map(\.recipe),
                matchesByID: Dictionary(matches.map { ($0.recipe.id, $0) }, uniquingKeysWith: { first, _ in first })
            )
        }
        var options = self.options
        if ignoringKeyword { options.keyword = nil }
        let recipes: [Recipe]
        if let dietaryGoal {
            recipes = store.dietaryCatalog?.candidates(options, goal: dietaryGoal, using: store.recommender) ?? []
        } else {
            recipes = store.recommender.candidates(options)
        }
        // 核心推荐器的全目录分支不看菜名；会话统一保证各入口的关键词语义。
        let keyword = (options.keyword ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return CookCandidateSnapshot(
            recipes: recipes.filter { recipe in
                (keyword.isEmpty || recipe.name.contains(keyword))
                    && (discoveryScene.map { CookDiscovery.matches(recipe, using: $0.filters) } ?? true)
            },
            matchesByID: [:]
        )
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
        methodGroups.count + difficulty.count + (noSpicy ? 1 : 0) + (dietaryGoal == nil ? 0 : 1) + (discoveryScene == nil ? 0 : 1)
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

    /// 本餐选过食材、菜名或筛选方向，可直接按这些条件找菜。
    var hasSelection: Bool {
        !selectedStuff.isEmpty || tool != nil || dietaryGoal != nil || discoveryScene != nil
            || !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !methodGroups.isEmpty || !difficulty.isEmpty
    }

    /// 找菜页和筛选弹层共用结果数；nil 表示本餐还没有选择，区别于真正的零结果。
    func matchCount(in store: RecipeStore) -> Int? {
        guard hasSelection else { return nil }
        return candidates(in: store).count
    }

    func matchCount(using recommender: Recommender) -> Int? {
        // 没有目标核对目录时不能据普通推荐器报告目标结果数。
        guard hasSelection, dietaryGoal == nil else { return nil }
        let recipes = selectedStuff.isEmpty ? recommender.candidates(options) : recommender.recommend(options).map(\.recipe)
        let keyword = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        return recipes.filter { recipe in
            (keyword.isEmpty || recipe.name.contains(keyword))
                && (discoveryScene.map { CookDiscovery.matches(recipe, using: $0.filters) } ?? true)
        }.count
    }

    /// 冰箱页「用这些做菜」：换成这几样食材，厨具和菜名搜索清掉，免得结果莫名变少
    func use(_ stuff: [String]) {
        selectedStuff = uniqueStrings(stuff)
        tool = nil
        keyword = ""
    }

    /// 清空本次找菜条件；长期忌口、常备调料和默认饮食目标继续保留。
    func clearStuff() {
        selectedStuff = []
        tool = nil
        keyword = ""
        methodGroups = []
        difficulty = []
        discoveryScene = nil
        dietaryGoal = nil
    }

    func selectDiscoveryScene(_ scene: CookDiscoveryScene?) {
        discoveryScene = scene
    }

    func toggleDiscoveryScene(_ scene: CookDiscoveryScene) {
        discoveryScene = discoveryScene == scene ? nil : scene
    }

    /// 菜名输入替换搜索词并保留已有条件；食材或句式输入合入条件，并撤销上次菜名搜索。
    @discardableResult
    func applyDiscoveryInput(_ input: String, in store: RecipeStore) -> CookDiscoveryInputResult {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return CookDiscoveryInputResult(kind: .empty, message: "输入菜名、食材，或说说想怎么吃。")
        }

        let parsed = store.queryParser.parse(text)
        var query = parsed
        // 统一入口不把「麻婆」「饼」等菜名碎片当成新食材。
        query.unknown = []
        let hasOtherConditions = !query.avoid.isEmpty || query.tool != nil || query.noSpicy
            || !query.difficulty.isEmpty || !query.methodGroups.isEmpty
        let onlyKnownIngredients = !query.stuff.isEmpty && parsed.unknown.isEmpty && !hasOtherConditions
        let isExactDish = store.recipes.contains { $0.name == text }
        let isPartialDish = store.recipes.contains { $0.name.contains(text) }
        let isUnrecognizedPhrase = query.isEmpty || (!parsed.unknown.isEmpty && !hasOtherConditions)

        if isExactDish || (!onlyKnownIngredients && isPartialDish) || isUnrecognizedPhrase {
            keyword = text
            return CookDiscoveryInputResult(
                kind: .keyword,
                message: "按菜名「\(text)」查找，已有食材、场景和筛选继续生效。",
                keyword: text
            )
        }

        let replacedKeyword = !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        keyword = ""
        apply(query, normalizer: store.data.normalizer)
        var message = "已按「\(store.describe(query))」筛选。"
        if replacedKeyword { message += "已清除上次菜名搜索。" }
        if !parsed.unknown.isEmpty { message += "其余文字没有作为食材添加。" }
        return CookDiscoveryInputResult(kind: .conditions, message: message, query: query)
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
        discoveryScene = nil
        dietaryGoal = nil
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
