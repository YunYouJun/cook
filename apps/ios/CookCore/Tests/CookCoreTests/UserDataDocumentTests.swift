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

    func testUnknownVersionAndInvalidTimeAreRejected() throws {
        var value = UserDataDocument()
        value.version = 99
        XCTAssertThrowsError(try UserDataDocument.decode(JSONEncoder().encode(value)))
        value.version = 1
        value.favorites = [.init(id: 1, time: -1)]
        XCTAssertThrowsError(try value.validate())
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
