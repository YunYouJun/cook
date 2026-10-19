import CookCore
import Foundation
import XCTest

/// 跑 packages/data/fixtures 里和 Web 共用的用例，结果（包括顺序）必须和 TS 实现完全一致。
/// 需要先在仓库根目录运行 `pnpm convert` 生成 recipe.json 和 recipe-meta.json。
final class SharedFixtureTests: XCTestCase {
    static let dataDirectory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // CookCoreTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // CookCore
        .deletingLastPathComponent() // ios
        .deletingLastPathComponent() // apps
        .deletingLastPathComponent() // 仓库根目录
        .appendingPathComponent("packages/data")

    static func loadData() throws -> CookData {
        let meta = dataDirectory.appendingPathComponent("recipe-meta.json")
        guard FileManager.default.fileExists(atPath: meta.path) else {
            throw XCTSkip("找不到 \(meta.path)，先在仓库根目录运行 pnpm convert")
        }
        return try CookData.load(from: dataDirectory)
    }

    static func fixture<T: Decodable>(_ type: T.Type, _ name: String) throws -> T {
        let url = dataDirectory.appendingPathComponent("fixtures/\(name)")
        return try JSONDecoder().decode(type, from: Data(contentsOf: url))
    }

    struct NormalizeCase: Decodable {
        var input: [String]
        var output: [String]
    }

    struct RecommendOutput: Decodable, Equatable {
        var id: Int
        var matched: [String]
        var pantry: [String]
        var missing: [String]
        var optional: [String]
    }

    struct RecommendCase: Decodable {
        var input: RecommendationOptions
        var output: [RecommendOutput]
    }

    struct RecommendFixtures: Decodable {
        var recipeCount: Int
        var normalize: [NormalizeCase]
        var recommend: [RecommendCase]
    }

    struct SearchOutput: Decodable, Equatable {
        var id: Int
        var matched: [String]
        var missing: [String]
    }

    struct SearchCase: Decodable {
        var input: SearchOptions
        var output: [SearchOutput]
        var warnings: [[String]]
    }

    struct SearchFixtures: Decodable {
        var recipeCount: Int
        var search: [SearchCase]
    }

    struct IncompatibleCase: Decodable {
        var input: [String]
        var output: [[String]]
    }

    struct IncompatibleFixtures: Decodable {
        var rules: [IncompatibleRule]
        var cases: [IncompatibleCase]
    }

    func testDataLoads() throws {
        let data = try Self.loadData()
        XCTAssertEqual(data.meta.schemaVersion, CookData.supportedSchemaVersion)
        XCTAssertEqual(data.recipes.count, data.meta.total)
        XCTAssertEqual(Set(data.recipes.map(\.id)).count, data.recipes.count, "菜谱 id 不能重复")
        XCTAssertFalse(data.food.vegetable.isEmpty)
        XCTAssertFalse(data.pantry.defaults.isEmpty)
    }

    func testNormalize() throws {
        let data = try Self.loadData()
        let fixtures = try Self.fixture(RecommendFixtures.self, "recommend.json")
        for item in fixtures.normalize {
            XCTAssertEqual(data.normalizer.unique(item.input), item.output, "输入：\(item.input)")
        }
    }

    func testRecommend() throws {
        let data = try Self.loadData()
        let fixtures = try Self.fixture(RecommendFixtures.self, "recommend.json")
        XCTAssertEqual(data.recipes.count, fixtures.recipeCount, "用例过期，运行 pnpm fixtures")
        let recommender = Recommender(recipes: data.recipes, normalizer: data.normalizer)
        for item in fixtures.recommend {
            let output = recommender.recommend(item.input).map { match in
                RecommendOutput(
                    id: match.recipe.id,
                    matched: match.matchedStuff,
                    pantry: match.pantryStuff,
                    missing: match.missingStuff,
                    optional: match.optionalStuff
                )
            }
            XCTAssertEqual(output, item.output, "输入：\(item.input)")
        }
    }

    func testSearch() throws {
        let data = try Self.loadData()
        let fixtures = try Self.fixture(SearchFixtures.self, "search.json")
        XCTAssertEqual(data.recipes.count, fixtures.recipeCount, "用例过期，运行 pnpm fixtures")
        for item in fixtures.search {
            let result = searchRecipes(data.recipes, incompatible: data.incompatibleRules, options: item.input, normalizer: data.normalizer)
            let output = result.recipes.map { SearchOutput(id: $0.recipe.id, matched: $0.matchedStuff, missing: $0.missingStuff) }
            XCTAssertEqual(output, item.output, "输入：\(item.input)")
            XCTAssertEqual(result.warnings.map { [$0.foodA, $0.foodB] }, item.warnings, "输入：\(item.input)")
        }
    }

    func testIncompatible() throws {
        let fixtures = try Self.fixture(IncompatibleFixtures.self, "incompatible.json")
        for item in fixtures.cases {
            let output = findIncompatibleRules(item.input, rules: fixtures.rules).map { [$0.foodA, $0.foodB] }
            XCTAssertEqual(output, item.output, "输入：\(item.input)")
        }
    }

    func testRejectsNewerSchema() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let meta = #"{"schemaVersion":99,"dataVersion":"000000000000","chunked":false,"total":0}"#
        try Data(meta.utf8).write(to: directory.appendingPathComponent("recipe-meta.json"))
        XCTAssertThrowsError(try CookData.load(from: directory)) { error in
            XCTAssertEqual(error as? CookDataError, .unsupportedSchemaVersion(99))
        }
    }
}
