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
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext

        let store = try RecipeStore.loadBundled()
        let session = CookSession(defaultPantry: ["油", "盐"], context: context)
        session.toggleStuff("番茄")
        session.toggleStuff("鸡蛋")
        XCTAssertFalse(store.recommender.recommend(session.options).isEmpty)

        session.togglePantry("糖")
        session.toggleAvoid("香菜")
        session.setNoSpicy(true)
        session.mode = .strict
        session.toggleMethodGroup("炒菜")
        XCTAssertEqual(session.activeFilterCount, 2, "一个做法加不吃辣")

        let reloaded = CookSession(defaultPantry: [], context: context)
        XCTAssertEqual(reloaded.pantry, ["油", "盐", "糖"])
        XCTAssertEqual(reloaded.avoid, ["香菜"])
        XCTAssertTrue(reloaded.noSpicy)
        XCTAssertEqual(reloaded.mode, .strict)
        XCTAssertTrue(reloaded.selectedStuff.isEmpty, "已选食材不跨启动保留")
        XCTAssertTrue(reloaded.methodGroups.isEmpty, "做法筛选不跨启动保留")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 1)

        reloaded.clearFilters()
        XCTAssertFalse(reloaded.noSpicy)
        XCTAssertEqual(reloaded.activeFilterCount, 0)
    }

    func testDuplicatePreferencesMergeIntoOne() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        // 两台设备各建了一条，同步后都在本地
        let older = Preferences(pantry: ["油"])
        older.createdAt = Date(timeIntervalSince1970: 0)
        older.updatedAt = Date(timeIntervalSince1970: 0)
        let newer = Preferences(pantry: ["油", "盐", "糖"])
        newer.createdAt = Date(timeIntervalSince1970: 10)
        newer.updatedAt = Date(timeIntervalSince1970: 20)
        context.insert(older)
        context.insert(newer)
        try context.save()

        let kept = context.preferences(defaultPantry: [])
        XCTAssertEqual(kept.pantry, ["油", "盐", "糖"], "留最早那条，内容用最新修改的")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 1)
    }

    func testFavoritesAndHistory() throws {
        // 要持有容器，否则它被释放后 context 会崩溃
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext

        context.toggleFavorite(1)
        XCTAssertTrue(context.isFavorite(1))
        context.toggleFavorite(1)
        XCTAssertFalse(context.isFavorite(1))
        let favorites = try context.fetch(FetchDescriptor<Favorite>())
        XCTAssertEqual(favorites.count, 1, "取消收藏是软删除，记录还在")
        XCTAssertNotNil(favorites.first?.deletedAt)
        context.toggleFavorite(1)
        XCTAssertTrue(context.isFavorite(1), "再收藏一次会恢复同一条记录")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Favorite>()), 1)

        let start = Date(timeIntervalSince1970: 0)
        for id in 0 ..< 5 {
            context.recordVisit(id, at: start.addingTimeInterval(Double(id)), limit: 3)
        }
        context.recordVisit(2, at: start.addingTimeInterval(10), limit: 3)
        XCTAssertEqual(try activeVisits(context), [2, 4, 3])

        context.clearHistory(at: start.addingTimeInterval(20))
        XCTAssertEqual(try activeVisits(context), [])

        // 软删除超过保留期的记录会在下次记浏览时真正删掉
        let later = start.addingTimeInterval(20 + Persistence.tombstoneLifetime + 1)
        context.recordVisit(7, at: later, limit: 3)
        XCTAssertEqual(try context.fetch(FetchDescriptor<HistoryEntry>()).map(\.recipeId), [7])
    }

    private func activeVisits(_ context: ModelContext) throws -> [Int] {
        let descriptor = FetchDescriptor<HistoryEntry>(
            predicate: #Predicate<HistoryEntry> { $0.deletedAt == nil },
            sortBy: [SortDescriptor(\.visitedAt, order: .reverse)]
        )
        return try context.fetch(descriptor).map(\.recipeId)
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
