import CookCore
import Foundation
import XCTest

final class UserDataDocumentTests: XCTestCase {
    struct Fixture: Decodable {
        let name: String
        let now: Double
        let a: UserDataDocument
        let b: UserDataDocument
        let expected: UserDataDocument
    }

    func testSharedMergeCasesInBothDirectionsAndRepeatedly() throws {
        for fixture in try SharedFixtureTests.fixture([Fixture].self, "user-data-sync.json") {
            XCTAssertEqual(fixture.a.merging(fixture.b, now: fixture.now), fixture.expected, fixture.name)
            XCTAssertEqual(fixture.b.merging(fixture.a, now: fixture.now), fixture.expected, fixture.name)
            XCTAssertEqual(fixture.expected.merging(fixture.a, now: fixture.now), fixture.expected, fixture.name)
        }
    }

    func testSharedHistoryClearCases() throws {
        struct ClearCase: Decodable {
            let name: String
            let now: Double
            let current: UserDataDocument
            let peer: UserDataDocument
            let expectedCutoff: Double
            let expectedRemovals: [UserDataDocument.Removal]
            let expectedVisits: [UserDataDocument.Removal]
        }
        for fixture in try SharedFixtureTests.fixture([ClearCase].self, "user-data-history-clear.json") {
            let cleared = fixture.current.clearingHistory(now: fixture.now)
            XCTAssertTrue(cleared.histories.isEmpty, fixture.name)
            XCTAssertEqual(cleared.historyClearedAt, fixture.expectedCutoff, fixture.name)
            XCTAssertEqual(cleared.historyRemovals, fixture.expectedRemovals, fixture.name)
            XCTAssertEqual(cleared.clearingHistory(now: fixture.now), cleared, fixture.name)
            let merged = cleared.merging(fixture.peer, now: fixture.now)
            let visits = merged.histories.map { UserDataDocument.Removal(name: $0.recipe.name, time: $0.time) }
            XCTAssertEqual(visits, fixture.expectedVisits, fixture.name)
            XCTAssertEqual(merged.favorites, fixture.current.favorites, fixture.name)
        }
    }

    func testUnknownVersionAndInvalidTimeAreRejected() throws {
        var value = UserDataDocument()
        value.version = 99
        XCTAssertThrowsError(try UserDataDocument.decode(JSONEncoder().encode(value)))
        value.version = 1
        value.favorites = [.init(id: 1, time: -1)]
        XCTAssertThrowsError(try value.validate())
    }

    func testSharedQuotaBoundaryCasesConverge() throws {
        struct Boundary: Decodable {
            let name: String
            let kind: String
            let now: Double
            let leftStart: Int
            let rightStart: Int
            let count: Int
            let expectedStart: Int
            let expectedCount: Int
            func document(start: Int, count: Int) -> UserDataDocument {
                var value = UserDataDocument()
                if kind == "favorites" {
                    value.favoriteRemovals = (start..<(start + count)).map { .init(id: $0, time: now) }
                } else {
                    value.historyRemovals = (start..<(start + count)).map { .init(name: String(format: "recipe-%04d", $0), time: now) }
                }
                return value
            }
        }
        for fixture in try SharedFixtureTests.fixture([Boundary].self, "user-data-sync-boundaries.json") {
            let a = fixture.document(start: fixture.leftStart, count: fixture.count)
            let b = fixture.document(start: fixture.rightStart, count: fixture.count)
            let expected = fixture.document(start: fixture.expectedStart, count: fixture.expectedCount)
            XCTAssertEqual(a.merging(b, now: fixture.now), expected, fixture.name)
            XCTAssertEqual(b.merging(a, now: fixture.now), expected, fixture.name)
            XCTAssertEqual(expected.merging(b, now: fixture.now), expected, fixture.name)
        }
    }

    func testHistoryLimitAndTombstoneLifetime() throws {
        var value = UserDataDocument()
        let recipe = UserDataDocument.RecipeSnapshot(recipe: Recipe(id: 1, name: "A", stuff: [], tools: []))
        value.histories = (1...205).map { .init(recipe: recipe, time: Double($0)) }
        value.favoriteRemovals = [.init(id: 1, time: 1)]
        let merged = value.merging(UserDataDocument(), now: 180 * 24 * 60 * 60 * 1000 + 2)
        XCTAssertEqual(merged.histories.count, 200)
        XCTAssertEqual(merged.histories.first?.time, 6)
        XCTAssertTrue(merged.favoriteRemovals.isEmpty)
    }
}
