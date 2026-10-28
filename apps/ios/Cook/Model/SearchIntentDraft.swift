import CookCore
import Foundation
import Observation

/// A disposable draft never writes the cooking session or synced preferences.
@MainActor @Observable
final class SearchIntentDraft {
    var text: String
    var intent: SearchIntent?
    var acceptsUnresolved = false
    private(set) var isLoading = false
    private(set) var message: String?
    @ObservationIgnored private var request: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()

    init(text: String) { self.text = text }

    var canApply: Bool {
        guard let intent, !isLoading else { return false }
        return (!intent.query.isEmpty || intent.goal != nil) && (intent.unresolved.isEmpty || acceptsUnresolved)
    }

    func inputChanged() {
        cancel()
        intent = nil
        message = nil
        acceptsUnresolved = false
    }

    func parseLocally(data: CookData, goals: [DietaryGoal]) {
        cancel()
        intent = LocalSearchIntentParser(data: data, availableGoals: goals).parse(text)
        message = nil
        acceptsUnresolved = false
    }

    /// The generation guard also protects against transports that ignore cancellation.
    func parseRemotely(using parse: @escaping @MainActor (String) async throws -> SearchIntent) {
        cancel()
        let id = generation
        let submitted = text
        isLoading = true
        message = nil
        request = Task {
            defer { if generation == id { isLoading = false; request = nil } }
            do {
                let result = try await parse(submitted)
                guard !Task.isCancelled, generation == id, text == submitted else { return }
                intent = result
                acceptsUnresolved = false
            } catch {
                guard !Task.isCancelled, generation == id else { return }
                message = (error as? DeepSeekIntentError)?.localizedDescription
                    ?? (error as? CookAISettingsError)?.localizedDescription
                    ?? "智能识别暂不可用，已保留本地条件，可以修改后继续。"
            }
        }
    }

    func cancel() {
        generation = UUID()
        request?.cancel()
        request = nil
        isLoading = false
    }
}

extension CookSession {
    /// Validate against the currently bundled catalog again at confirmation time.
    func applySearchIntent(_ intent: SearchIntent, in store: RecipeStore) throws {
        let validated = try SearchIntentValidator(data: store.data, availableGoals: store.dietaryGoals).validate(intent)
        func intersect(_ current: [String], _ proposed: [String]) throws -> [String] {
            if current.isEmpty { return proposed }
            if proposed.isEmpty { return current }
            let common = current.filter(proposed.contains)
            guard !common.isEmpty else { throw SearchIntentApplicationError.conflictingFilters }
            return common
        }
        let methods = try intersect(methodGroups, validated.query.methodGroups)
        let levels = try intersect(difficulty, validated.query.difficulty)
        if let tool, let proposed = validated.query.tool, tool != proposed { throw SearchIntentApplicationError.conflictingFilters }
        if let dietaryGoal, let proposed = validated.goal, dietaryGoal != proposed { throw SearchIntentApplicationError.conflictingFilters }
        var query = validated.query
        query.methodGroups = []
        query.difficulty = []
        apply(query, normalizer: store.data.normalizer)
        methodGroups = methods
        difficulty = levels
        if let goal = validated.goal { selectDietaryGoal(goal) }
        keyword = ""
    }
}

enum SearchIntentApplicationError: LocalizedError {
    case conflictingFilters
    var errorDescription: String? {
        "本次条件与当前的厨具、做法、难度或饮食目标冲突，请修改条件，或返回调整当前筛选。"
    }
}
