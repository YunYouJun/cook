import CookCore
import Foundation
import Observation

/// Explicit cooking records on this device, independent of visits and favorites.
@MainActor
@Observable
final class CookedRecipeRecords {
    typealias RecipeID = CookingRecipeID
    static let storageKey = "cook.cookingProgress.v2"
    static let legacyStorageKey = "cook.cookedRecipes.v1"
    @ObservationIgnored private let defaults: UserDefaults?
    private(set) var progress: CookingProgress
    private(set) var storageIssue: String?

    private struct Document: Codable {
        let version: Int
        let progress: CookingProgress
    }

    init(defaults: UserDefaults? = nil) {
        self.defaults = defaults
        progress = CookingProgress()
        if let stored = defaults?.object(forKey: Self.storageKey) {
            guard let data = stored as? Data,
                  let document = try? JSONDecoder().decode(Document.self, from: data),
                  document.version == 2 else {
                storageIssue = "做菜足迹暂时无法读取，原始记录已保留。请勿清除 App 数据。"
                return
            }
            progress = document.progress
        } else {
            let dates = defaults?.dictionary(forKey: Self.legacyStorageKey)?.compactMapValues { $0 as? Date } ?? [:]
            progress = CookingProgress(legacyDates: dates)
            // Commit even an empty snapshot so deleted records cannot reappear from the legacy key.
            // Keep v1 untouched as a recovery source; v2 becomes authoritative after this write.
            do { try persist(progress) }
            catch { storageIssue = error.localizedDescription }
        }
    }

    func cookedRecords(for recipeID: RecipeID) -> [CookedRecord] {
        progress.cookedRecords(for: recipeID)
    }

    func learnedRecord(for recipeID: RecipeID) -> LearnedRecord? {
        progress.learnedRecord(for: recipeID)
    }

    func todayRecord(for recipeID: RecipeID, at date: Date = .now, calendar: Calendar = .current) -> CookedRecord? {
        progress.cookedRecord(for: recipeID, on: date, calendar: calendar)
    }

    @discardableResult
    func recordCooked(_ recipeID: RecipeID, name: String? = nil, at date: Date = .now,
                      calendar: Calendar = .current) throws -> CookedRecord? {
        try update { $0.recordCooked(recipeID, name: name, at: date, calendar: calendar) }
    }

    func removeCooked(_ id: UUID) throws {
        try update { $0.removeCooked(id) }
    }

    func confirmLearned(_ recipeID: RecipeID, name: String? = nil, at date: Date = .now,
                        calendar: Calendar = .current) throws -> LearningConfirmation? {
        try update { $0.confirmLearned(recipeID, name: name, at: date, calendar: calendar) }
    }

    func removeLearned(_ recipeID: RecipeID) throws {
        try update { $0.removeLearned(recipeID) }
    }

    func undoConfirmation(_ confirmation: LearningConfirmation) throws {
        try update { $0.undoConfirmation(confirmation) }
    }

    private func update<T>(_ change: (inout CookingProgress) -> T) throws -> T {
        if let storageIssue { throw StorageError.unreadable(storageIssue) }
        var next = progress
        let result = change(&next)
        if next != progress {
            try persist(next)
            progress = next
        }
        return result
    }

    private func persist(_ value: CookingProgress) throws {
        let data = try JSONEncoder().encode(Document(version: 2, progress: value))
        defaults?.set(data, forKey: Self.storageKey)
    }

    private enum StorageError: LocalizedError {
        case unreadable(String)
        var errorDescription: String? {
            switch self {
            case let .unreadable(message): message
            }
        }
    }
}
