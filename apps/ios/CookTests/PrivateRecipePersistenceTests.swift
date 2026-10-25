import CookCore
import CoreData
import SwiftData
import XCTest
#if canImport(UIKit)
    import SwiftUI
    import UIKit
#endif
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

    func testDeletionRemovesPrivateTextFromEveryDuplicateButRetainsIdentity() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let original = recipe()
        let context = ModelContext(container)
        context.insert(try PrivateRecipeRecord(original))
        context.insert(try PrivateRecipeRecord(original))
        try context.save()

        try PrivateRecipePersistence.remove(original, in: container)

        let check = ModelContext(container)
        let records = try check.fetch(FetchDescriptor<PrivateRecipeRecord>())
        XCTAssertEqual(records.count, 2)
        XCTAssertTrue(records.allSatisfy { $0.payload.isEmpty && $0.deletedAt != nil && $0.customId == original.customId })
        let snapshot = try PrivateRecipeSnapshot(records)
        XCTAssertTrue(snapshot.recipes.isEmpty)
        XCTAssertEqual(snapshot.deletedIDs, [original.customId])
        XCTAssertEqual(try PrivateRecipePersistence.importRecipes([original], keepCopies: false, in: container), 0)
    }

    func testDuplicateCloudRowsHaveDeterministicDeletionPrecedence() throws {
        let first = try PrivateRecipeRecord(recipe())
        let second = try PrivateRecipeRecord(recipe())
        second.deletedAt = Date(timeIntervalSince1970: 1)
        XCTAssertTrue(try PrivateRecipeSnapshot([first, second]).recipes.isEmpty)
        XCTAssertTrue(try PrivateRecipeSnapshot([second, first]).recipes.isEmpty)
    }

    func testDeletionCannotBeRevivedByANewerOfflineDuplicate() throws {
        let deleted = try PrivateRecipeRecord(recipe())
        deleted.deletedAt = Date(timeIntervalSince1970: 2)
        deleted.updatedAt = deleted.deletedAt!
        deleted.payload = ""
        var offlineEdit = recipe()
        offlineEdit.updatedAt = 3000
        let active = try PrivateRecipeRecord(offlineEdit)
        for records in [[deleted, active], [active, deleted]] {
            let snapshot = try PrivateRecipeSnapshot(records)
            XCTAssertTrue(snapshot.recipes.isEmpty)
            XCTAssertEqual(snapshot.deletedIDs, [deleted.customId])
        }
    }

    func testReconcileScrubsPayloadReturnedByAnOfflineFieldMerge() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let original = recipe()
        try PrivateRecipePersistence.save(original, replacing: nil, in: container)
        try PrivateRecipePersistence.remove(original, in: container)
        // Production replay: CloudKit retained deletedAt but imported the offline payload/updatedAt.
        let incoming = ModelContext(container)
        let merged = try XCTUnwrap(incoming.fetch(FetchDescriptor<PrivateRecipeRecord>()).first)
        let deletion = try XCTUnwrap(merged.deletedAt)
        merged.payload = try PrivateRecipeRecord(original).payload
        merged.updatedAt = Date(timeIntervalSince1970: 1)
        try incoming.save()

        XCTAssertEqual(try PrivateRecipePersistence.reconcileDeletions(in: container), 1)
        let check = ModelContext(container)
        let redacted = try XCTUnwrap(check.fetch(FetchDescriptor<PrivateRecipeRecord>()).first)
        XCTAssertEqual(redacted.payload, "")
        XCTAssertEqual(redacted.deletedAt, deletion)
        XCTAssertGreaterThanOrEqual(redacted.updatedAt, deletion)
        XCTAssertTrue(try PrivateRecipePersistence.snapshot(in: check).recipes.isEmpty)
        XCTAssertEqual(try PrivateRecipePersistence.reconcileDeletions(in: container, save: { _ in XCTFail("An unchanged tombstone must not be saved again") }), 0)
    }

    func testReconcileRedactsDuplicatesButPreservesOtherRecipesAndPendingEdits() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = ModelContext(container)
        let deleted = try PrivateRecipeRecord(recipe())
        deleted.deletedAt = Date(timeIntervalSince1970: 2)
        deleted.payload = "unreadable deleted payload"
        var newer = recipe()
        newer.updatedAt = 3000
        context.insert(deleted)
        context.insert(try PrivateRecipeRecord(newer))
        let unrelated = recipe(id: "custom-unrelated")
        context.insert(try PrivateRecipeRecord(unrelated))
        try context.save()
        context.autosaveEnabled = false
        let pending = try PrivateRecipeRecord(recipe(id: "custom-pending"))
        context.insert(pending)

        XCTAssertEqual(try PrivateRecipePersistence.reconcileDeletions(in: container), 2)
        let check = ModelContext(container)
        let rows = try check.fetch(FetchDescriptor<PrivateRecipeRecord>())
        let tombstones = rows.filter { $0.customId == deleted.customId }
        XCTAssertEqual(tombstones.count, 2)
        XCTAssertTrue(tombstones.allSatisfy { $0.deletedAt != nil && $0.payload.isEmpty })
        XCTAssertEqual(try PrivateRecipeSnapshot(rows).recipes, [unrelated])
        XCTAssertTrue(context.hasChanges)
        XCTAssertTrue(context.insertedModelsArray.contains { $0 === pending })
        XCTAssertFalse(rows.contains { $0.customId == pending.customId })
    }

    func testFailedRedactionCanRetryWithoutLosingDeletionOrOtherData() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = ModelContext(container)
        let merged = try PrivateRecipeRecord(recipe())
        merged.deletedAt = Date(timeIntervalSince1970: 2)
        context.insert(merged)
        try context.save()
        XCTAssertThrowsError(try PrivateRecipePersistence.reconcileDeletions(in: container, save: { _ in throw CocoaError(.fileWriteOutOfSpace) }))
        let afterFailure = try XCTUnwrap(ModelContext(container).fetch(FetchDescriptor<PrivateRecipeRecord>()).first)
        XCTAssertFalse(afterFailure.payload.isEmpty)
        XCTAssertNotNil(afterFailure.deletedAt)
        XCTAssertEqual(try PrivateRecipePersistence.reconcileDeletions(in: container), 1)
        XCTAssertTrue(try XCTUnwrap(ModelContext(container).fetch(FetchDescriptor<PrivateRecipeRecord>()).first).payload.isEmpty)
    }

    func testOfflineAdditionSurvivesStoreReopenAndAnotherRecipesImportedDeletion() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("offline.store")
        func openStore() throws -> ModelContainer {
            let schema = Schema(Persistence.models)
            return try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none))
        }
        let original = recipe(id: "custom-existing")
        let addition = recipe(id: "custom-offline-addition", name: "离线新增复测")
        var edited = original
        edited.stuff.append("番茄")
        edited.updatedAt = 3000
        try autoreleasepool {
            let container = try openStore()
            try PrivateRecipePersistence.save(original, replacing: nil, in: container)
            try PrivateRecipePersistence.save(addition, replacing: nil, in: container)
            try PrivateRecipePersistence.save(edited, replacing: original, in: container)
            XCTAssertEqual(Set(try PrivateRecipePersistence.snapshot(in: ModelContext(container)).recipes.map(\.customId)), [original.customId, addition.customId])
        }
        try autoreleasepool {
            let container = try openStore()
            let context = ModelContext(container)
            XCTAssertEqual(try PrivateRecipePersistence.snapshot(in: context).recipes.first { $0.customId == addition.customId }, addition, "A successful save must survive closing and reopening the disk store before reconnect")
            // Replay only the remote deletion of the existing identity, retaining its offline payload.
            let existing = try XCTUnwrap(context.fetch(FetchDescriptor<PrivateRecipeRecord>()).first { $0.customId == original.customId })
            existing.deletedAt = Date(timeIntervalSince1970: 4)
            try context.save()
            try PrivateRecipePersistence.reconcileDeletions(in: container)
        }
        try autoreleasepool {
            let snapshot = try PrivateRecipePersistence.snapshot(in: ModelContext(openStore()))
            XCTAssertEqual(snapshot.recipes, [addition])
            XCTAssertEqual(snapshot.deletedIDs, [original.customId])
        }
    }

    #if canImport(UIKit)
        func testRootObserverRedactsExistingTombstoneOnMount() async throws {
            try await checkAutomaticRedaction(importAfterMount: false)
        }

        func testRootObserverRedactsLateImportWithoutOpeningPrivateRecipes() async throws {
            try await checkAutomaticRedaction(importAfterMount: true)
        }

        private func checkAutomaticRedaction(importAfterMount: Bool) async throws {
            let container = Persistence.makeContainer(inMemory: true)
            let incoming = ModelContext(container)
            let record = try PrivateRecipeRecord(recipe())
            let offlinePayload = record.payload
            record.deletedAt = Date(timeIntervalSince1970: 2)
            if importAfterMount { record.payload = "" }
            incoming.insert(record)
            try incoming.save()

            let mounted = expectation(description: "Deletion observer mounted outside the private-recipes screen")
            let host = UIHostingController(rootView: Color.clear
                .modifier(PrivateRecipeDeletionSync())
                .modelContainer(container)
                .onAppear { mounted.fulfill() })
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
            window.rootViewController = host
            window.makeKeyAndVisible()
            defer { window.isHidden = true; window.rootViewController = nil }
            await fulfillment(of: [mounted], timeout: 3)

            if importAfterMount {
                // A distinct context models an import; no direct call to the cleanup helper.
                let remote = ModelContext(container)
                let merged = try XCTUnwrap(remote.fetch(FetchDescriptor<PrivateRecipeRecord>()).first)
                merged.payload = offlinePayload
                merged.updatedAt = Date(timeIntervalSince1970: 1)
                try remote.save()
            }
            for _ in 0 ..< 150 {
                let row = try XCTUnwrap(ModelContext(container).fetch(FetchDescriptor<PrivateRecipeRecord>()).first)
                if row.payload.isEmpty, let deletion = row.deletedAt, row.updatedAt >= deletion { return }
                try await Task.sleep(nanoseconds: 20_000_000)
            }
            XCTFail("The root observer did not redact the imported tombstone")
        }
    #endif

    func testAddingModelMigratesExistingStoreWithoutChangingBuiltInReferences() throws {
        try checkMigration(throughCoreData: false)
    }

    func testSchemaInitializerModelMigratesOldStoreBeforeSwiftDataOpensIt() throws {
        try checkMigration(throughCoreData: true)
    }

    private func checkMigration(throughCoreData: Bool) throws {
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
        if throughCoreData {
            // The Debug initializer opens this old store with Core Data before SwiftData.
            // Exercise that extra migration/unload path without contacting iCloud.
            try autoreleasepool {
                let model = try XCTUnwrap(NSManagedObjectModel.makeManagedObjectModel(for: Persistence.models))
                XCTAssertEqual(Set(model.entities.compactMap(\.name)), ["Favorite", "HistoryEntry", "Preferences", "FridgeItem", "PrivateRecipeRecord"])
                let description = NSPersistentStoreDescription(url: url)
                description.shouldAddStoreAsynchronously = false
                description.setOption(true as NSNumber, forKey: NSPersistentHistoryTrackingKey)
                let bridge = NSPersistentContainer(name: "Cook", managedObjectModel: model)
                bridge.persistentStoreDescriptions = [description]
                var loadError: Error?
                bridge.loadPersistentStores { _, error in loadError = error }
                if let loadError { throw loadError }
                for store in bridge.persistentStoreCoordinator.persistentStores {
                    try bridge.persistentStoreCoordinator.remove(store)
                }
            }
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
