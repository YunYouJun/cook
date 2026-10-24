import CookCore
import Foundation
import SwiftData

/// A single JSON payload keeps the portable recipe fields together during CloudKit updates.
/// No unique constraint; all required fields have defaults as required by SwiftData + CloudKit.
@Model
final class PrivateRecipeRecord {
    var customId: String = ""
    var payload: String = ""
    var updatedAt: Date = Date()
    var deletedAt: Date?

    init(_ recipe: PrivateRecipe) throws {
        customId = recipe.customId
        payload = try PrivateRecipeFile(recipes: [recipe], exportedAt: recipe.updatedAt).export()
        updatedAt = Date(timeIntervalSince1970: Double(recipe.updatedAt) / 1000)
    }

    func recipe() throws -> PrivateRecipe {
        let recipes = try PrivateRecipeFile.parse(payload).recipes
        guard recipes.count == 1, let recipe = recipes.first, recipe.customId == customId else {
            throw PrivateRecipeFileError.invalid
        }
        return recipe
    }

    var deletionState: PrivateRecipeDeletionState {
        PrivateRecipeDeletionState(id: persistentModelID, customId: customId, updatedAt: updatedAt, deletedAt: deletedAt, hasPayload: !payload.isEmpty)
    }
}

/// Observe imported changes without copying recipe text into SwiftUI state.
struct PrivateRecipeDeletionState: Equatable {
    let id: PersistentIdentifier
    let customId: String
    let updatedAt: Date
    let deletedAt: Date?
    let hasPayload: Bool
}

struct PrivateRecipeSnapshot {
    let recipes: [PrivateRecipe]
    let deletedIDs: Set<String>

    init(_ records: [PrivateRecipeRecord]) throws {
        // Deletion is final for an identity. A later offline edit/duplicate must not revive it;
        // an explicit restore imports a copy with a new identity instead.
        deletedIDs = Set(records.filter { $0.deletedAt != nil }.map(\.customId))
        var latest: [String: PrivateRecipeRecord] = [:]
        for record in records where !deletedIDs.contains(record.customId) {
            if let prior = latest[record.customId] {
                // Deterministic tie break for active duplicates across devices.
                if record.updatedAt < prior.updatedAt { continue }
                if record.updatedAt == prior.updatedAt && record.payload <= prior.payload { continue }
            }
            latest[record.customId] = record
        }
        recipes = try latest.values.map { try $0.recipe() }
            .sorted { ($0.updatedAt, $0.customId) > ($1.updatedAt, $1.customId) }
    }
}

/// A separate context makes save failures roll back only this operation, not unrelated preference edits.
@MainActor
enum PrivateRecipePersistence {
    /// CloudKit may merge an offline payload into a row whose deletedAt already arrived.
    /// Reapply the deletion to every copy, so the next export also removes that text remotely.
    @discardableResult
    static func reconcileDeletions(in container: ModelContainer, save: (ModelContext) throws -> Void = { try $0.save() }) throws -> Int {
        let context = ModelContext(container)
        context.autosaveEnabled = false
        let records = try context.fetch(FetchDescriptor<PrivateRecipeRecord>())
        var deletions: [String: Date] = [:]
        for record in records {
            if let date = record.deletedAt {
                deletions[record.customId] = max(deletions[record.customId] ?? date, date)
            }
        }
        var changed = 0
        for record in records {
            guard let deletion = deletions[record.customId] else { continue }
            let updatedAt = max(record.updatedAt, deletion)
            guard !record.payload.isEmpty || record.deletedAt != deletion || record.updatedAt != updatedAt else { continue }
            record.payload = ""
            record.deletedAt = deletion
            record.updatedAt = updatedAt
            changed += 1
        }
        guard changed > 0 else { return 0 }
        do { try save(context) }
        catch {
            context.rollback()
            throw error
        }
        return changed
    }

    static func snapshot(in context: ModelContext) throws -> PrivateRecipeSnapshot {
        try PrivateRecipeSnapshot(context.fetch(FetchDescriptor<PrivateRecipeRecord>()))
    }

    static func save(_ recipe: PrivateRecipe, replacing original: PrivateRecipe?, in container: ModelContainer) throws {
        let context = ModelContext(container)
        context.autosaveEnabled = false
        let records = try context.fetch(FetchDescriptor<PrivateRecipeRecord>())
        let snapshot = try PrivateRecipeSnapshot(records)
        let current = snapshot.recipes.first { $0.customId == recipe.customId }
        // Do not overwrite an edit/delete that arrived while the editor was open.
        guard current == original, !snapshot.deletedIDs.contains(recipe.customId) else { throw EditError.changed }
        _ = try recipe.validated()
        guard current != nil || snapshot.recipes.count < PrivateRecipeFile.recipeLimit else { throw PrivateRecipeFileError.tooMany }
        if let currentRecord = records.filter({ $0.customId == recipe.customId }).max(by: { $0.updatedAt < $1.updatedAt }) {
            currentRecord.payload = try PrivateRecipeFile(recipes: [recipe], exportedAt: recipe.updatedAt).export()
            currentRecord.updatedAt = Date(timeIntervalSince1970: Double(recipe.updatedAt) / 1000)
        } else {
            context.insert(try PrivateRecipeRecord(recipe))
        }
        try context.save()
    }

    static func remove(_ recipe: PrivateRecipe, in container: ModelContainer) throws {
        let context = ModelContext(container)
        context.autosaveEnabled = false
        let records = try context.fetch(FetchDescriptor<PrivateRecipeRecord>())
        let snapshot = try PrivateRecipeSnapshot(records)
        guard snapshot.recipes.first(where: { $0.customId == recipe.customId }) == recipe else { throw EditError.changed }
        let now = Date(timeIntervalSince1970: max(Date().timeIntervalSince1970, Double(recipe.updatedAt + 1) / 1000))
        for record in records where record.customId == recipe.customId {
            // A tombstone needs only identity and time; do not retain the user's recipe text.
            record.payload = ""
            record.deletedAt = now
            record.updatedAt = now
        }
        // Keep identity-only tombstones: an old file must not silently resurrect a deleted identity.
        try context.save()
    }

    static func importRecipes(_ incoming: [PrivateRecipe], keepCopies: Bool, in container: ModelContainer) throws -> Int {
        let context = ModelContext(container)
        context.autosaveEnabled = false
        let snapshot = try snapshot(in: context)
        let plan = PrivateRecipeImport(current: snapshot.recipes, incoming: incoming, deletedIDs: snapshot.deletedIDs)
        let records = try plan.recordsToInsert(keepCopies: keepCopies, currentIDs: Set(snapshot.recipes.map(\.customId)).union(snapshot.deletedIDs))
        // Validate every item before inserting anything, then commit the whole import once.
        let models = try records.map { try PrivateRecipeRecord($0) }
        models.forEach { context.insert($0) }
        try context.save()
        return models.count
    }

    enum EditError: LocalizedError {
        case changed
        var errorDescription: String? { "这道菜谱已在其他窗口或设备上改变，请取消编辑后重新打开。你的修改尚未保存。" }
    }
}
