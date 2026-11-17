import XCTest
@testable import CookCore

final class CookingProgressTests: XCTestCase {
    private func calendar(_ zone: String = "Asia/Shanghai") -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        return calendar
    }

    private func date(_ value: String) -> Date {
        ISO8601DateFormatter().date(from: value)!
    }

    func testCookedDeduplicatesLocalDayButAllowsNextDayAndDistinctNamespaces() throws {
        var progress = CookingProgress()
        let now = date("2026-11-18T15:59:00Z")
        let calendar = calendar()
        let first = try XCTUnwrap(progress.recordCooked(.catalog(42), at: now, calendar: calendar))
        XCTAssertNil(progress.recordCooked(.catalog(42), at: now.addingTimeInterval(30), calendar: calendar))
        XCTAssertNotNil(progress.recordCooked(.personal("42"), at: now, calendar: calendar))
        XCTAssertNotNil(progress.recordCooked(.catalog(42), at: now.addingTimeInterval(60), calendar: calendar))
        XCTAssertEqual(progress.cookedRecords(for: .catalog(42)).count, 2)
        XCTAssertTrue(progress.learned.isEmpty)
        progress.removeCooked(first.id)
        XCTAssertEqual(progress.cookedRecords(for: .catalog(42)).count, 1)
        XCTAssertEqual(progress.cookedRecords(for: .personal("42")).count, 1)
    }

    func testDayBoundaryUsesCalendarRatherThanTwentyFourHoursAcrossDST() {
        let calendar = calendar("America/Los_Angeles")
        var progress = CookingProgress()
        let before = date("2026-03-08T08:30:00Z")
        let after = date("2026-03-09T07:30:00Z")
        XCTAssertEqual(after.timeIntervalSince(before), 23 * 3600)
        XCTAssertNotNil(progress.recordCooked(.catalog(1), at: before, calendar: calendar))
        XCTAssertNil(progress.recordCooked(.catalog(1), at: date("2026-03-09T06:59:00Z"), calendar: calendar))
        XCTAssertNotNil(progress.recordCooked(.catalog(1), at: after, calendar: calendar))
    }

    func testExplicitLearningAddsTodayDespitePreviousCooksAndNeverRepeatsCelebration() throws {
        var progress = CookingProgress()
        let now = date("2026-11-18T10:00:00Z")
        progress.recordCooked(.catalog(1), at: now.addingTimeInterval(-86400))
        let confirmation = try XCTUnwrap(progress.confirmLearned(.catalog(1), name: "番茄炒蛋", at: now))
        XCTAssertEqual(confirmation.learned.learnedAt, now)
        XCTAssertEqual(confirmation.learnedCount, 1)
        XCTAssertNotNil(confirmation.autoCookedRecordID)
        XCTAssertEqual(progress.cooked.filter(\.createdFromLearned).count, 1)
        XCTAssertNil(progress.confirmLearned(.catalog(1), at: now.addingTimeInterval(86400)))
        XCTAssertEqual(progress.cooked.count, 2)
        XCTAssertEqual(progress.learned.count, 1)
        XCTAssertEqual(progress.confirmLearned(.personal("one"), at: now)?.learnedCount, 2)
    }

    func testImmediateUndoPreservesExistingCookAndOnlyRollsBackImplicitCook() throws {
        let now = date("2026-11-18T10:00:00Z")
        var existing = CookingProgress()
        let cooked = try XCTUnwrap(existing.recordCooked(.catalog(1), at: now))
        let first = try XCTUnwrap(existing.confirmLearned(.catalog(1), at: now))
        XCTAssertNil(first.autoCookedRecordID)
        existing.undoConfirmation(first)
        XCTAssertEqual(existing.cooked, [cooked])
        XCTAssertTrue(existing.learned.isEmpty)

        var implicit = CookingProgress()
        implicit.recordCooked(.catalog(2), at: now.addingTimeInterval(-86400))
        let second = try XCTUnwrap(implicit.confirmLearned(.catalog(2), at: now))
        implicit.recordCooked(.personal("unrelated"), at: now)
        implicit.undoConfirmation(second)
        implicit.undoConfirmation(second)
        XCTAssertEqual(implicit.cooked.count, 2)
        XCTAssertFalse(implicit.cooked.contains { $0.id == second.autoCookedRecordID })
        XCTAssertTrue(implicit.learned.isEmpty)
    }

    func testRegularUndoAndCookingDeletionRemainIndependent() throws {
        var progress = CookingProgress()
        let confirmation = try XCTUnwrap(progress.confirmLearned(.catalog(1)))
        progress.removeLearned(.catalog(1))
        XCTAssertEqual(progress.cooked.count, 1)
        let replacement = try XCTUnwrap(progress.confirmLearned(.catalog(1)))
        XCTAssertNil(replacement.autoCookedRecordID)
        progress.undoConfirmation(confirmation)
        XCTAssertEqual(progress.learned.first?.id, replacement.id)
        progress.removeCooked(try XCTUnwrap(progress.cooked.first).id)
        XCTAssertNotNil(progress.learnedRecord(for: .catalog(1)))
        XCTAssertNil(progress.confirmLearned(.catalog(1)))
        XCTAssertTrue(progress.cooked.isEmpty)
    }

    func testStaleImmediateUndoDoesNotDeleteReplacementCookOnSameDay() throws {
        var progress = CookingProgress()
        let now = date("2026-11-18T10:00:00Z")
        let confirmation = try XCTUnwrap(progress.confirmLearned(.catalog(1), at: now))
        progress.removeCooked(try XCTUnwrap(confirmation.autoCookedRecordID))
        let replacement = try XCTUnwrap(progress.recordCooked(.catalog(1), at: now))
        progress.undoConfirmation(confirmation)
        XCTAssertEqual(progress.cooked, [replacement])
        XCTAssertTrue(progress.learned.isEmpty)
    }

    func testTimelineBadgesOnlyLearningDayAndKeepsOrphanMilestones() throws {
        var progress = CookingProgress()
        let now = date("2026-11-18T10:00:00Z")
        let confirmation = try XCTUnwrap(progress.confirmLearned(.personal("deleted-recipe"), name: "家常蒸蛋", at: now))
        progress.recordCooked(.personal("deleted-recipe"), at: now.addingTimeInterval(86400))
        let all = progress.entries(calendar: calendar())
        XCTAssertEqual(all.count, 2)
        XCTAssertNil(all[0].learned)
        XCTAssertEqual(all[1].learned?.id, confirmation.id)
        XCTAssertEqual(progress.entries(learnedOnly: true).count, 1)
        progress.removeCooked(try XCTUnwrap(confirmation.autoCookedRecordID))
        let orphan = try XCTUnwrap(progress.entries(calendar: calendar()).last)
        XCTAssertEqual(orphan.recipeName, "家常蒸蛋")
        XCTAssertNil(orphan.cookedRecordID)
        XCTAssertEqual(orphan.learned?.id, confirmation.id)
        XCTAssertTrue(progress.entries(for: .catalog(1)).isEmpty)
    }

    func testLegacyMigrationAndCodableRoundTripKeepOriginalDatesWithoutLearning() throws {
        let now = date("2026-11-18T10:00:00Z")
        let progress = CookingProgress(legacyDates: ["catalog:42": now, "personal:42": now.addingTimeInterval(-86400)])
        XCTAssertEqual(progress.cooked.count, 2)
        XCTAssertEqual(progress.cookedRecords(for: .catalog(42)).first?.cookedAt, now)
        XCTAssertTrue(progress.learned.isEmpty)
        XCTAssertTrue(progress.cooked.allSatisfy { !$0.createdFromLearned })
        XCTAssertEqual(try JSONDecoder().decode(CookingProgress.self, from: JSONEncoder().encode(progress)), progress)
    }
}
