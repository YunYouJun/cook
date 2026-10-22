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
}

struct PrivateRecipeSnapshot {
    let recipes: [PrivateRecipe]
    let deletedIDs: Set<String>

    init(_ records: [PrivateRecipeRecord]) throws {
        var latest: [String: PrivateRecipeRecord] = [:]
        for record in records {
            if let prior = latest[record.customId] {
                // Deterministic tie break across devices; deletion wins an equal timestamp.
                if record.updatedAt < prior.updatedAt { continue }
                if record.updatedAt == prior.updatedAt {
                    if prior.deletedAt != nil && record.deletedAt == nil { continue }
                    if (prior.deletedAt == nil) == (record.deletedAt == nil) && record.payload <= prior.payload { continue }
                }
            }
            latest[record.customId] = record
        }
        recipes = try latest.values.filter { $0.deletedAt == nil }.map { try $0.recipe() }
            .sorted { ($0.updatedAt, $0.customId) > ($1.updatedAt, $1.customId) }
        deletedIDs = Set(latest.values.filter { $0.deletedAt != nil }.map(\.customId))
    }
}

/// A separate context makes save failures roll back only this operation, not unrelated preference edits.
@MainActor
enum PrivateRecipePersistence {
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
            record.deletedAt = now
            record.updatedAt = now
        }
        // Keep tombstones: an old file must not silently resurrect a deleted identity.
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
