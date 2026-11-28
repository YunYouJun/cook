import CookCore
import Foundation
import XCTest

final class PersonalCatalogTests: XCTestCase {
    func testSharedFixtureAndQuery() throws {
        let text = try String(contentsOf: SharedFixtureTests.dataDirectory.appendingPathComponent("fixtures/personal-catalog.json"))
        XCTAssertThrowsError(try PersonalCatalog.parse(text.replacingOccurrences(of: "\"version\": 1", with: "\"version\": 1, \"future\": true")))
        let config = try PersonalCatalog.parse(text)
        XCTAssertEqual(try PersonalCatalog.parse(config.export()), config)
        XCTAssertEqual(try config.merging(config), config)
        let data = try SharedFixtureTests.loadData()
        let food = config.applying(to: data.food)
        XCTAssertFalse(food.vegetable.contains { $0.name == "番茄" })
        XCTAssertTrue(food.vegetable.contains { $0.name == "鹰嘴豆" })
        XCTAssertTrue(data.food.vegetable.contains { $0.name == "番茄" })
        let query = CookQueryParser(rules: data.query, food: food, aliases: data.aliases).parse("鹰嘴豆用塔吉锅")
        XCTAssertEqual(query.stuff, ["鹰嘴豆"])
        XCTAssertEqual(query.tool, "塔吉锅")

        var ignoredNameCatalog = PersonalCatalog()
        ignoredNameCatalog.items = [CatalogEntry(category: .staple, name: "藕粉")]
        let ignoredNameParser = CookQueryParser(rules: data.query, food: ignoredNameCatalog.applying(to: data.food, includeHidden: true), aliases: data.aliases)
        let haveQuery = ignoredNameParser.parse("家里有藕粉，不要辣")
        XCTAssertEqual(haveQuery.stuff, ["藕粉"])
        XCTAssertTrue(haveQuery.noSpicy)
        let avoidQuery = ignoredNameParser.parse("不吃藕粉，但是有排骨")
        XCTAssertEqual(avoidQuery.stuff, ["猪肉"])
        XCTAssertEqual(avoidQuery.avoid, ["藕粉"])
        if let input = ProcessInfo.processInfo.environment["COOK_CATALOG_BRIDGE_INPUT"], let output = ProcessInfo.processInfo.environment["COOK_CATALOG_BRIDGE_OUTPUT"] {
            let fromWeb = try PersonalCatalog.parse(String(contentsOfFile: input))
            XCTAssertEqual(fromWeb, config)
            try fromWeb.export().write(toFile: output, atomically: true, encoding: .utf8)
        }
    }
    func testInvalidAndMergeConflict() throws {
        var first = PersonalCatalog()
        first.items = [CatalogEntry(category: .staple, name: "鹰嘴豆")]
        var incoming = PersonalCatalog()
        incoming.items = [CatalogEntry(category: .vegetable, name: "鹰嘴豆")]
        XCTAssertEqual(try first.merging(incoming), first)
        for name in ["", " ", "a\nb", String(repeating: "😀", count: 16)] {
            var bad = first
            bad.items[0].name = name
            XCTAssertThrowsError(try bad.export())
        }
        first.items += incoming.items
        XCTAssertThrowsError(try first.export())
        first = PersonalCatalog(); first.version = 2
        XCTAssertThrowsError(try first.export())
    }
}
