import Foundation
import XCTest
@testable import Cook

final class WebUserDataTests: XCTestCase {
    func testManualImportCopiesOnlyActiveFileRecords() throws {
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        #else
        let bundle = Bundle(for: Self.self)
        #endif
        let fixtureURL = try XCTUnwrap(bundle.url(forResource: "user-data-file-import", withExtension: "json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as? [String: Any])
        let file = try JSONSerialization.data(withJSONObject: XCTUnwrap(fixture["file"]))
        let imported = try XCTUnwrap(WebUserData.parse(String(decoding: file, as: UTF8.self)))

        struct Favorite: Decodable {
            let id: Int
            let time: Double
        }
        struct Visit: Decodable {
            let name: String
            let time: Double
        }
        let expectedFavorites = try JSONDecoder().decode(
            [Favorite].self,
            from: JSONSerialization.data(withJSONObject: XCTUnwrap(fixture["expectedFavorites"]))
        )
        let expectedHistories = try JSONDecoder().decode(
            [Visit].self,
            from: JSONSerialization.data(withJSONObject: XCTUnwrap(fixture["expectedHistories"]))
        )
        XCTAssertEqual(imported.favorites.map(\.recipeId), expectedFavorites.map(\.id))
        XCTAssertEqual(imported.visits.map(\.name), expectedHistories.map(\.name))
        XCTAssertEqual(imported.favorites.map(\.timeMillis), expectedFavorites.map(\.time))
        XCTAssertEqual(imported.visits.map(\.timeMillis), expectedHistories.map(\.time))
        XCTAssertEqual(imported.latestVisits.map(\.name), ["蒸蛋羹", "拍黄瓜"])
        XCTAssertEqual(imported.latestVisits.map(\.timeMillis), [1700000000005.375, 1700000000007])
    }

    func testRejectsBooleanValuesAndFractionalVersion() throws {
        XCTAssertNil(WebUserData.parse(#"{"app":"cook","version":true}"#))
        XCTAssertNil(WebUserData.parse(#"{"app":"cook","version":1.5}"#))
        let parsed = try XCTUnwrap(WebUserData.parse(
            #"{"app":"cook","version":1,"favorites":[{"id":true,"time":10},{"id":2,"time":false},{"id":3,"time":20}],"histories":[{"recipe":{"name":"西红柿炒鸡蛋"},"time":true}]}"#
        ))
        XCTAssertEqual(parsed.favorites, [WebUserData.FavoriteItem(recipeId: 3, timeMillis: 20)])
        XCTAssertTrue(parsed.visits.isEmpty)
    }
}
