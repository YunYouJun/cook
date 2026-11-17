import XCTest
@testable import Cook

final class CookedRecipeRecordsTests: XCTestCase {
    @MainActor
    func testUndoPersistsWithoutAffectingOtherRecipes() throws {
        let suite = "cook.cookedRecipes.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let date = Date(timeIntervalSince1970: 1_000)
        let records = CookedRecipeRecords(defaults: defaults)
        records.setCooked(true, for: .catalog(42), at: date)
        records.setCooked(true, for: .personal("42"), at: date)
        records.setCooked(true, for: .catalog(42), at: date.addingTimeInterval(5))

        let reopened = CookedRecipeRecords(defaults: defaults)
        XCTAssertEqual(reopened.recordedAt(for: .catalog(42)), date)
        XCTAssertEqual(reopened.recordedAt(for: .personal("42")), date)
        XCTAssertNil(reopened.recordedAt(for: .catalog(7)))
        reopened.setCooked(false, for: .catalog(42))

        let afterUndo = CookedRecipeRecords(defaults: defaults)
        XCTAssertNil(afterUndo.recordedAt(for: .catalog(42)))
        XCTAssertEqual(afterUndo.recordedAt(for: .personal("42")), date)
    }
}
