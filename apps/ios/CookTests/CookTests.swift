import CookCore
import SwiftData
import XCTest
@testable import Cook

@MainActor
final class CookTests: XCTestCase {
    func testBundledDataLoads() throws {
        let store = try RecipeStore.loadBundled()
        XCTAssertEqual(store.recipes.count, store.data.meta.total)
        XCTAssertEqual(store.data.meta.schemaVersion, CookData.supportedSchemaVersion)
        XCTAssertFalse(store.data.food.vegetable.isEmpty)
    }

    func testSessionRecommendsAndRemembersPreferences() throws {
        let suite = "CookTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let store = try RecipeStore.loadBundled()
        let session = CookSession(defaultPantry: ["油", "盐"], defaults: defaults)
        session.toggleStuff("番茄")
        session.toggleStuff("鸡蛋")
        XCTAssertFalse(store.recommender.recommend(session.options).isEmpty)

        session.togglePantry("糖")
        session.toggleAvoid("香菜")
        session.setNoSpicy(true)
        let reloaded = CookSession(defaultPantry: [], defaults: defaults)
        XCTAssertEqual(reloaded.pantry, ["油", "盐", "糖"])
        XCTAssertEqual(reloaded.avoid, ["香菜"])
        XCTAssertTrue(reloaded.noSpicy)
        XCTAssertTrue(reloaded.selectedStuff.isEmpty, "已选食材不跨启动保留")
    }

    func testFavoritesAndHistory() throws {
        // 要持有容器，否则它被释放后 context 会崩溃
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext

        context.toggleFavorite(1)
        XCTAssertTrue(context.isFavorite(1))
        context.toggleFavorite(1)
        XCTAssertFalse(context.isFavorite(1))

        let start = Date(timeIntervalSince1970: 0)
        for id in 0 ..< 5 {
            context.recordVisit(id, at: start.addingTimeInterval(Double(id)), limit: 3)
        }
        context.recordVisit(2, at: start.addingTimeInterval(10), limit: 3)
        let visits = try context.fetch(FetchDescriptor<RecipeVisit>(sortBy: [SortDescriptor(\.visitedAt, order: .reverse)]))
        XCTAssertEqual(visits.map(\.recipeId), [2, 4, 3])
    }

    func testThumbnailAndFilter() throws {
        let store = try RecipeStore.loadBundled()
        let recipe = Recipe(id: -1, name: "番茄炒蛋", stuff: ["番茄", "鸡蛋"], tools: ["一口大锅"])
        XCTAssertEqual(store.thumbnail(for: recipe), "🍅")
        XCTAssertEqual(store.meta(for: recipe), "炒锅")
        XCTAssertEqual(store.filter([recipe], query: "西红柿").count, 1, "按食材别名也能搜到")
        XCTAssertEqual(store.filter([recipe], query: "牛肉").count, 0)
    }
}
