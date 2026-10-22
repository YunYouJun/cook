import CookCore
import SwiftData
import XCTest
@testable import Cook

@MainActor
final class PrivateRecipePersistenceTests: XCTestCase {
    private func recipe(id: String = "custom-test", name: String = "测试蒸蛋") -> PrivateRecipe {
        PrivateRecipe(customId: id, name: name, stuff: ["鸡蛋"], tools: ["蒸锅"], link: "https://example.com/recipe", note: "合成测试数据", createdAt: 1000, updatedAt: 1000)
    }

    func testCreateEditDeleteAndStaleEditProtection() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let original = recipe()
        try PrivateRecipePersistence.save(original, replacing: nil, in: container)
        var edited = original
        edited.name = "修改后的蒸蛋"
        edited.updatedAt += 1
        try PrivateRecipePersistence.save(edited, replacing: original, in: container)
        XCTAssertThrowsError(try PrivateRecipePersistence.save(original, replacing: original, in: container))
        let context = ModelContext(container)
        XCTAssertEqual(try PrivateRecipePersistence.snapshot(in: context).recipes, [edited])
        try PrivateRecipePersistence.remove(edited, in: container)
        let deleted = try PrivateRecipePersistence.snapshot(in: ModelContext(container))
        XCTAssertTrue(deleted.recipes.isEmpty)
        XCTAssertEqual(deleted.deletedIDs, [original.customId])
        XCTAssertThrowsError(try PrivateRecipePersistence.save(edited, replacing: edited, in: container))
    }

    func testImportIsAtomicAndRepeatedCopiesAreDeduplicated() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let original = recipe()
        try PrivateRecipePersistence.save(original, replacing: nil, in: container)
        var conflict = original
        conflict.name = "文件中的版本"
        let new = recipe(id: "custom-new")
        XCTAssertEqual(try PrivateRecipePersistence.importRecipes([conflict, new], keepCopies: true, in: container), 2)
        XCTAssertEqual(try PrivateRecipePersistence.importRecipes([conflict, new], keepCopies: true, in: container), 0)
        var invalid = recipe(id: "custom-invalid")
        invalid.stuff = []
        XCTAssertThrowsError(try PrivateRecipePersistence.importRecipes([recipe(id: "would-add"), invalid], keepCopies: true, in: container))
        let snapshot = try PrivateRecipePersistence.snapshot(in: ModelContext(container))
        XCTAssertEqual(snapshot.recipes.count, 3)
        XCTAssertEqual(snapshot.recipes.first { $0.customId == original.customId }, original)
        XCTAssertFalse(snapshot.recipes.contains { $0.customId == "would-add" })
    }

    func testDeletedIDDoesNotReviveFromAnOldFile() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let original = recipe()
        try PrivateRecipePersistence.save(original, replacing: nil, in: container)
        try PrivateRecipePersistence.remove(original, in: container)
        XCTAssertEqual(try PrivateRecipePersistence.importRecipes([original], keepCopies: false, in: container), 0)
        XCTAssertEqual(try PrivateRecipePersistence.importRecipes([original], keepCopies: true, in: container), 1)
        let restored = try PrivateRecipePersistence.snapshot(in: ModelContext(container)).recipes
        XCTAssertNotEqual(restored[0].customId, original.customId)
        XCTAssertEqual(restored[0].copiedFrom, original.customId)
    }

    func testDuplicateCloudRowsHaveDeterministicDeletionPrecedence() throws {
        let first = try PrivateRecipeRecord(recipe())
        let second = try PrivateRecipeRecord(recipe())
        second.deletedAt = Date(timeIntervalSince1970: 1)
        XCTAssertTrue(try PrivateRecipeSnapshot([first, second]).recipes.isEmpty)
        XCTAssertTrue(try PrivateRecipeSnapshot([second, first]).recipes.isEmpty)
    }

    func testAddingModelMigratesExistingStoreWithoutChangingBuiltInReferences() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("migration.store")
        // Exact models already shipped before custom recipes, including schema 2's FridgeItem.
        try autoreleasepool {
            let schema = Schema([Favorite.self, HistoryEntry.self, Preferences.self, FridgeItem.self])
            let config = ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none)
            let container = try ModelContainer(for: schema, configurations: config)
            let context = ModelContext(container)
            context.insert(Favorite(recipeId: 42))
            context.insert(HistoryEntry(recipeId: 42))
            context.insert(Preferences(pantry: ["盐"]))
            context.insert(FridgeItem(name: "鸡蛋", addedAt: Date(), expiresAt: Date()))
            try context.save()
        }
        let schema = Schema(Persistence.models)
        let config = ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none)
        let container = try ModelContainer(for: schema, configurations: config)
        let context = ModelContext(container)
        XCTAssertEqual(try context.fetch(FetchDescriptor<Favorite>()).map(\.recipeId), [42])
        XCTAssertEqual(try context.fetch(FetchDescriptor<HistoryEntry>()).map(\.recipeId), [42])
        XCTAssertEqual(try context.fetch(FetchDescriptor<Preferences>()).first?.pantry, ["盐"])
        XCTAssertEqual(try context.fetch(FetchDescriptor<FridgeItem>()).first?.name, "鸡蛋")
        try PrivateRecipePersistence.save(recipe(), replacing: nil, in: container)
        XCTAssertEqual(try PrivateRecipePersistence.snapshot(in: ModelContext(container)).recipes.count, 1)
    }
}
