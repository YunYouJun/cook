import CookCore
import Foundation
import SwiftData
import XCTest
@testable import Cook

@MainActor
final class PreferencesSyncTests: XCTestCase {
    func testUntouchedDefaultsDoNotCreateRowsOrOverwriteDelayedImport() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let session = CookSession(defaultPantry: ["油", "盐"], context: context)
        session.refreshPreferences()
        session.setNoSpicy(false)
        session.mode = .loose
        XCTAssertEqual(session.pantry, ["油", "盐"])
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 0)

        let remote = makePreferences(created: 10, updated: 20)
        context.insert(remote)
        try context.save()
        session.refreshPreferences()
        XCTAssertEqual(session.pantry, ["油", "糖"])
        XCTAssertEqual(session.avoid, ["香菜"])
        XCTAssertTrue(session.noSpicy)
        XCTAssertEqual(session.mode, .strict)

        let reloaded = CookSession(defaultPantry: ["油", "盐"], context: context)
        XCTAssertEqual(reloaded.avoid, ["香菜"])
        XCTAssertEqual(remote.updatedAt, Date(timeIntervalSince1970: 20))
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 1)
        XCTAssertFalse(context.hasChanges)
    }

    func testRefreshRebindsAfterInsertUpdateAndDeleteWithoutWriting() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let first = makePreferences(created: 10, updated: 20)
        context.insert(first)
        try context.save()
        let session = CookSession(defaultPantry: ["盐"], context: context)

        let imported = makePreferences(created: 30, updated: 40)
        imported.avoid = ["花生"]
        context.insert(imported)
        try context.save()
        session.refreshPreferences()
        XCTAssertEqual(session.avoid, ["花生"])

        first.avoid = ["牛肉"]
        first.updatedAt = Date(timeIntervalSince1970: 50)
        try context.save()
        session.refreshPreferences()
        XCTAssertEqual(session.avoid, ["牛肉"])
        for _ in 0 ..< 10 { session.refreshPreferences() }
        XCTAssertFalse(context.hasChanges)

        context.delete(first)
        try context.save()
        session.refreshPreferences()
        XCTAssertEqual(session.avoid, ["花生"])
        context.delete(imported)
        try context.save()
        session.refreshPreferences()
        XCTAssertEqual(session.pantry, ["盐"])
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 0)
    }

    func testWriteResolvesImportedPreferencesBeforeChangingOneField() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let session = CookSession(defaultPantry: ["盐"], context: context)
        let remote = makePreferences(created: 10, updated: 20)
        context.insert(remote)
        try context.save()

        // 模拟 @Query 尚未通知当前 session，此时用户立即编辑。
        session.toggleAvoid("花生")
        XCTAssertEqual(session.avoid, ["香菜", "花生"])
        XCTAssertEqual(session.pantry, ["油", "糖"])
        XCTAssertTrue(session.noSpicy)
        XCTAssertEqual(session.mode, .strict)
        XCTAssertTrue(try context.latestPreferences() === remote)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 1)
    }

    func testQueryInputMergesWithNewlyImportedAvoidValues() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let session = CookSession(defaultPantry: [], context: context)
        let remote = makePreferences(created: 10, updated: 20)
        context.insert(remote)
        try context.save()
        let normalizer = StuffNormalizer(aliases: [:])
        var query = CookQuery()
        query.avoid = ["花生"]

        session.apply(query, normalizer: normalizer)
        XCTAssertEqual(session.avoid, ["香菜", "花生"])
        XCTAssertEqual(session.pantry, ["油", "糖"])
    }

    func testLegacyDefaultDuplicatesArePreservedWithoutGuessingIntent() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let userEdited = makePreferences(created: 10, updated: 20)
        let legacyDefault = Preferences(pantry: ["油", "盐"])
        legacyDefault.createdAt = Date(timeIntervalSince1970: 30)
        legacyDefault.updatedAt = Date(timeIntervalSince1970: 30)
        context.insert(userEdited)
        context.insert(legacyDefault)
        try context.save()
        let before = [userEdited.snapshot, legacyDefault.snapshot]
        let session = CookSession(defaultPantry: ["油", "盐"], context: context)
        for _ in 0 ..< 10 { session.refreshPreferences() }

        XCTAssertEqual(session.pantry, legacyDefault.pantry, "旧记录没有用户编辑标记，保持既有按时间取最新的语义")
        XCTAssertEqual([userEdited.snapshot, legacyDefault.snapshot], before)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 2)
        XCTAssertFalse(context.hasChanges)
    }

    func testEqualTimestampsSelectSameValuesRegardlessOfFetchOrder() throws {
        let first = makePreferences(created: 10, updated: 20)
        let second = makePreferences(created: 10, updated: 20)
        second.avoid = ["花生"]
        XCTAssertEqual(Preferences.latest(in: [first, second])?.values, Preferences.latest(in: [second, first])?.values)
    }

    func testExplicitEditRemainsLatestWhenRemoteClockIsAhead() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let remote = makePreferences(created: 10, updated: 1000)
        let older = makePreferences(created: 20, updated: 900)
        context.insert(remote)
        context.insert(older)
        try context.save()

        let changed = try context.updatePreferences(defaults: PreferenceValues(pantry: []), at: Date(timeIntervalSince1970: 100)) {
            $0.avoid.append("花生")
        }
        XCTAssertTrue(changed === remote)
        XCTAssertGreaterThan(remote.updatedAt, Date(timeIntervalSince1970: 1000))
        XCTAssertTrue(try context.latestPreferences() === remote)
        XCTAssertEqual(older.updatedAt, Date(timeIntervalSince1970: 900))
    }

    func testSnapshotIncludesValueChangesWithUnchangedTimestamp() {
        let row = makePreferences(created: 10, updated: 20)
        let original = row.snapshot
        row.avoid.append("花生")
        XCTAssertNotEqual(row.snapshot, original)
        XCTAssertEqual(row.snapshot.updatedAt, original.updatedAt)
    }

    func testPreferencesSchemaKeepsOriginalFields() throws {
        let entity = try XCTUnwrap(Schema([Preferences.self]).entities.first)
        XCTAssertEqual(entity.properties.map(\.name).sorted(), ["avoid", "createdAt", "mode", "noSpicy", "pantry", "updatedAt"])
    }

    func testFirstEditCreatesOneRowAndPersistsAcrossContainerReload() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let schema = Schema(Persistence.models)
        let configuration = ModelConfiguration(schema: schema, url: directory.appendingPathComponent("preferences.store"), cloudKitDatabase: .none)
        do {
            let container = try ModelContainer(for: schema, configurations: configuration)
            let session = CookSession(defaultPantry: ["油", "盐"], context: container.mainContext)
            session.togglePantry("糖")
            session.toggleAvoid("香菜")
            session.setNoSpicy(true)
            session.mode = .strict
            XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Preferences>()), 1)
        }
        let reloaded = try ModelContainer(for: schema, configurations: configuration)
        let session = CookSession(defaultPantry: [], context: reloaded.mainContext)
        XCTAssertEqual(session.pantry, ["油", "盐", "糖"])
        XCTAssertEqual(session.avoid, ["香菜"])
        XCTAssertTrue(session.noSpicy)
        XCTAssertEqual(session.mode, .strict)
    }

    private func makePreferences(created: TimeInterval, updated: TimeInterval) -> Preferences {
        let record = Preferences(pantry: ["油", "糖"])
        record.avoid = ["香菜"]
        record.noSpicy = true
        record.mode = "strict"
        record.createdAt = Date(timeIntervalSince1970: created)
        record.updatedAt = Date(timeIntervalSince1970: updated)
        return record
    }
}
