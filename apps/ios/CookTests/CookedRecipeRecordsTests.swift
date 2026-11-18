import CookCore
import XCTest
@testable import Cook

@MainActor
final class CookedRecipeRecordsTests: XCTestCase {
    private func defaults() throws -> UserDefaults {
        let suite = "cook.cookingProgress.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        return defaults
    }

    func testLegacyMigrationPreservesDatesAndIDsAndNeverResurrectsDeletedRecords() throws {
        let defaults = try defaults()
        let date = Date(timeIntervalSince1970: 1_000)
        defaults.set(["catalog:42": date, "personal:42": date], forKey: CookedRecipeRecords.legacyStorageKey)
        let records = CookedRecipeRecords(defaults: defaults)
        let first = try XCTUnwrap(records.cookedRecords(for: .catalog(42)).first)
        XCTAssertEqual(first.cookedAt, date)
        XCTAssertEqual(records.cookedRecords(for: .personal("42")).first?.cookedAt, date)
        XCTAssertTrue(records.progress.learned.isEmpty)
        XCTAssertNotNil(defaults.dictionary(forKey: CookedRecipeRecords.legacyStorageKey))
        XCTAssertEqual(CookedRecipeRecords(defaults: defaults).progress, records.progress)
        for record in records.progress.cooked { try records.removeCooked(record.id) }
        XCTAssertTrue(CookedRecipeRecords(defaults: defaults).progress.cooked.isEmpty)
    }

    func testLearningAndExactImmediateRollbackPersistAcrossReopen() throws {
        let defaults = try defaults()
        let records = CookedRecipeRecords(defaults: defaults)
        let now = Date(timeIntervalSince1970: 1_000)
        try records.recordCooked(.catalog(42), at: now.addingTimeInterval(-86400))
        let confirmation = try XCTUnwrap(records.confirmLearned(.catalog(42), name: "炒蛋", at: now))
        let reopened = CookedRecipeRecords(defaults: defaults)
        XCTAssertEqual(reopened.learnedRecord(for: .catalog(42))?.learnedAt, now)
        XCTAssertNil(try reopened.confirmLearned(.catalog(42), at: now))
        XCTAssertNil(try reopened.recordCooked(.catalog(42), at: now))
        try reopened.undoConfirmation(confirmation)
        let afterUndo = CookedRecipeRecords(defaults: defaults)
        XCTAssertTrue(afterUndo.progress.learned.isEmpty)
        XCTAssertEqual(afterUndo.progress.cooked.count, 1)
        XCTAssertEqual(afterUndo.progress.cooked.first?.cookedAt, now.addingTimeInterval(-86400))
    }

    func testOrdinaryUndoPreservesCookingAndDeletionPreservesLearningAfterReopen() throws {
        let defaults = try defaults()
        let records = CookedRecipeRecords(defaults: defaults)
        _ = try records.confirmLearned(.personal("42"))
        try records.removeLearned(.personal("42"))
        let reopened = CookedRecipeRecords(defaults: defaults)
        XCTAssertNil(reopened.learnedRecord(for: .personal("42")))
        XCTAssertEqual(reopened.progress.cooked.count, 1)
        _ = try reopened.confirmLearned(.personal("42"))
        try reopened.removeCooked(XCTUnwrap(reopened.progress.cooked.first).id)
        let final = CookedRecipeRecords(defaults: defaults)
        XCTAssertTrue(final.progress.cooked.isEmpty)
        XCTAssertNotNil(final.learnedRecord(for: .personal("42")))
    }

    func testUnreadableOrFutureStorageIsPreservedAndCannotBeOverwritten() throws {
        for data in [Data("not-json".utf8), Data(#"{"version":999,"progress":{"cooked":[],"learned":[]}}"#.utf8)] {
            let defaults = try defaults()
            defaults.set(data, forKey: CookedRecipeRecords.storageKey)
            defaults.set(["catalog:42": Date()], forKey: CookedRecipeRecords.legacyStorageKey)
            let records = CookedRecipeRecords(defaults: defaults)
            XCTAssertNotNil(records.storageIssue)
            XCTAssertThrowsError(try records.recordCooked(.catalog(7)))
            XCTAssertThrowsError(try records.confirmLearned(.catalog(7)))
            XCTAssertEqual(defaults.data(forKey: CookedRecipeRecords.storageKey), data)
        }
    }
}
