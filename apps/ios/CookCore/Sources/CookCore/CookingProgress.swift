import Foundation

/// Namespaces match the original device-local cooked-recipe keys.
public enum CookingRecipeID: Hashable, Codable, Sendable {
    case catalog(Int)
    case personal(String)

    public init?(legacyKey: String) {
        if legacyKey.hasPrefix("catalog:"), let id = Int(legacyKey.dropFirst(8)) {
            self = .catalog(id)
        } else if legacyKey.hasPrefix("personal:"), !legacyKey.dropFirst(9).isEmpty {
            self = .personal(String(legacyKey.dropFirst(9)))
        } else {
            return nil
        }
    }
}

public struct CookedRecord: Identifiable, Equatable, Codable, Sendable {
    public let id: UUID
    public let recipeID: CookingRecipeID
    public let recipeName: String?
    public let cookedAt: Date
    public let createdFromLearned: Bool
}

public struct LearnedRecord: Identifiable, Equatable, Codable, Sendable {
    public let id: UUID
    public let recipeID: CookingRecipeID
    public let recipeName: String?
    public let learnedAt: Date
}

/// A receipt scopes immediate undo to this exact learning event and its implicit cook.
public struct LearningConfirmation: Identifiable, Equatable, Sendable {
    public var id: UUID { learned.id }
    public let learned: LearnedRecord
    public let autoCookedRecordID: UUID?
    public let learnedCount: Int
}

public struct CookingFootprintEntry: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let recipeID: CookingRecipeID
    public let recipeName: String?
    public let date: Date
    public let cookedRecordID: UUID?
    public let learned: LearnedRecord?
}

/// Pure state transitions; viewing recipes, completing steps and timers have no write path here.
public struct CookingProgress: Equatable, Codable, Sendable {
    public private(set) var cooked: [CookedRecord] = []
    public private(set) var learned: [LearnedRecord] = []

    public init(legacyDates: [String: Date] = [:]) {
        cooked = legacyDates.sorted { $0.key < $1.key }.compactMap { key, date in
            guard let recipeID = CookingRecipeID(legacyKey: key) else { return nil }
            return CookedRecord(id: UUID(), recipeID: recipeID, recipeName: nil, cookedAt: date, createdFromLearned: false)
        }
    }

    public func cookedRecords(for recipeID: CookingRecipeID) -> [CookedRecord] {
        cooked.filter { $0.recipeID == recipeID }.sorted { $0.cookedAt > $1.cookedAt }
    }

    public func learnedRecord(for recipeID: CookingRecipeID) -> LearnedRecord? {
        learned.first { $0.recipeID == recipeID }
    }

    public func cookedRecord(for recipeID: CookingRecipeID, on date: Date, calendar: Calendar = .current) -> CookedRecord? {
        cooked.first { $0.recipeID == recipeID && calendar.isDate($0.cookedAt, inSameDayAs: date) }
    }

    /// Returns nil for a repeated tap on the same local calendar day.
    @discardableResult
    public mutating func recordCooked(_ recipeID: CookingRecipeID, name: String? = nil,
                                      at date: Date = .now, calendar: Calendar = .current) -> CookedRecord? {
        guard cookedRecord(for: recipeID, on: date, calendar: calendar) == nil else { return nil }
        let record = CookedRecord(id: UUID(), recipeID: recipeID, recipeName: name, cookedAt: date, createdFromLearned: false)
        cooked.append(record)
        return record
    }

    public mutating func removeCooked(_ id: UUID) {
        cooked.removeAll { $0.id == id }
    }

    /// Only an explicit user confirmation calls this; existing milestones never celebrate again.
    @discardableResult
    public mutating func confirmLearned(_ recipeID: CookingRecipeID, name: String? = nil,
                                        at date: Date = .now, calendar: Calendar = .current) -> LearningConfirmation? {
        guard learnedRecord(for: recipeID) == nil else { return nil }
        var autoCookedID: UUID?
        if cookedRecord(for: recipeID, on: date, calendar: calendar) == nil {
            let record = CookedRecord(id: UUID(), recipeID: recipeID, recipeName: name, cookedAt: date, createdFromLearned: true)
            cooked.append(record)
            autoCookedID = record.id
        }
        let record = LearnedRecord(id: UUID(), recipeID: recipeID, recipeName: name, learnedAt: date)
        learned.append(record)
        return LearningConfirmation(learned: record, autoCookedRecordID: autoCookedID, learnedCount: learned.count)
    }

    /// Regular undo preserves every cooking record, including one originally added by learning.
    public mutating func removeLearned(_ recipeID: CookingRecipeID) {
        learned.removeAll { $0.recipeID == recipeID }
    }

    /// Stale or repeated receipts cannot undo a later confirmation or a replacement cooking record.
    public mutating func undoConfirmation(_ confirmation: LearningConfirmation) {
        guard learnedRecord(for: confirmation.learned.recipeID)?.id == confirmation.id else { return }
        removeLearned(confirmation.learned.recipeID)
        if let id = confirmation.autoCookedRecordID { removeCooked(id) }
    }

    public func entries(learnedOnly: Bool = false, for recipeID: CookingRecipeID? = nil,
                        calendar: Calendar = .current) -> [CookingFootprintEntry] {
        var entries: [CookingFootprintEntry] = []
        if !learnedOnly {
            entries = cooked.map { record in
                let milestone = learnedRecord(for: record.recipeID).flatMap {
                    calendar.isDate($0.learnedAt, inSameDayAs: record.cookedAt) ? $0 : nil
                }
                return CookingFootprintEntry(id: record.id, recipeID: record.recipeID, recipeName: record.recipeName,
                                             date: record.cookedAt, cookedRecordID: record.id, learned: milestone)
            }
        }
        // Keep milestones reachable even after their associated cooking record is deleted.
        for record in learned where learnedOnly || !entries.contains(where: { $0.learned?.id == record.id }) {
            entries.append(CookingFootprintEntry(id: record.id, recipeID: record.recipeID, recipeName: record.recipeName,
                                                date: record.learnedAt, cookedRecordID: nil, learned: record))
        }
        return entries.filter { recipeID == nil || $0.recipeID == recipeID }.sorted {
            if $0.date != $1.date { return $0.date > $1.date }
            return $0.id.uuidString < $1.id.uuidString
        }
    }
}
