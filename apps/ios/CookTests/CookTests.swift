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

    func testDuplicatePreferencesSelectLatestWithoutDeletingOlderValues() throws {
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

        let kept = try context.latestPreferences()
        XCTAssertTrue(kept === newer)
        XCTAssertEqual(kept?.pantry, ["油", "盐", "糖"])
        XCTAssertEqual(older.pantry, ["油"], "不能用另一条记录覆盖尚未确认来源的旧值")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 2)
    }

    func testFavoritesAndHistory() throws {
        // 要持有容器，否则它被释放后 context 会崩溃
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext

        try context.toggleFavorite(1)
        XCTAssertTrue(try context.isFavorite(1))
        try context.toggleFavorite(1)
        XCTAssertFalse(try context.isFavorite(1))
        let favorites = try context.fetch(FetchDescriptor<Favorite>())
        XCTAssertEqual(favorites.count, 1, "取消收藏是软删除，记录还在")
        XCTAssertNotNil(favorites.first?.deletedAt)
        try context.toggleFavorite(1)
        XCTAssertTrue(try context.isFavorite(1), "再收藏一次会恢复同一条记录")
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

    func testSentenceInputAppliesToSession() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let store = try RecipeStore.loadBundled()
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        session.toggleStuff("土豆")

        let query = store.queryParser.parse("冰箱里有西红柿和鸡蛋，不吃牛肉，不吃辣，想炒个菜")
        session.apply(query, normalizer: store.data.normalizer)
        XCTAssertEqual(session.selectedStuff, ["土豆", "番茄", "鸡蛋"])
        XCTAssertEqual(session.avoid, ["牛肉"])
        XCTAssertTrue(session.noSpicy)
        XCTAssertEqual(session.methodGroups, ["炒菜"])
        XCTAssertFalse(store.describe(query).isEmpty)

        // 词典里没有的食材也收下，按名字找菜
        session.apply(store.queryParser.parse("秋葵"), normalizer: store.data.normalizer)
        XCTAssertTrue(session.selectedStuff.contains("秋葵"))
        XCTAssertFalse(store.isCatalogStuff("秋葵"))

        // 上面那句设了「炒菜」，和电饭煲凑不出菜，先清掉筛选
        session.clearStuff()
        session.clearFilters()
        XCTAssertFalse(session.hasSelection)
        session.toggleTool("电饭煲")
        XCTAssertTrue(session.hasSelection, "只选厨具也能看结果")
        XCTAssertFalse(store.recommender.recipes(using: "电饭煲", options: session.options).isEmpty)
    }

    func testWebDataImportAndExport() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let store = try RecipeStore.loadBundled()
        let first = store.recipes[0]
        let second = store.recipes[1]

        // App 里先收藏了第二道菜，又取消了；之后才在网页版收藏的第一道菜要加进来
        try context.toggleFavorite(second.id, at: Date(timeIntervalSince1970: 100))
        try context.toggleFavorite(second.id, at: Date(timeIntervalSince1970: 200))
        let text = """
        {"app":"cook","version":1,"exportedAt":0,
         "favorites":[{"id":\(first.id),"time":150000},{"id":\(second.id),"time":150000},{"id":999999,"time":1}],
         "favoriteRemovals":[],
         "histories":[{"recipe":{"name":"\(first.name)"},"time":120000},{"recipe":{"name":"不存在的菜"},"time":1}],
         "historyRemovals":[],"historyClearedAt":0}
        """
        let data = try XCTUnwrap(WebUserData.parse(text))
        let added = try context.importWebData(data, recipeIdByName: store.recipeIdByName, validIds: Set(store.recipesById.keys))
        XCTAssertEqual(added.favorites, 1, "App 里取消得更晚的不加回来，不认识的 id 跳过")
        XCTAssertEqual(added.visits, 1)
        XCTAssertTrue(try context.isFavorite(first.id))
        XCTAssertFalse(try context.isFavorite(second.id))
        XCTAssertEqual(try activeVisits(context), [first.id])

        let again = try context.importWebData(data, recipeIdByName: store.recipeIdByName, validIds: Set(store.recipesById.keys))
        XCTAssertEqual(again.favorites + again.visits, 0, "重复导入不会再加")

        let exported = WebUserData.export(
            favorites: try context.fetch(FetchDescriptor<Favorite>()),
            visits: try context.fetch(FetchDescriptor<HistoryEntry>()),
            recipesById: store.recipesById
        )
        let roundTrip = try XCTUnwrap(WebUserData.parse(exported))
        XCTAssertEqual(roundTrip.favorites.map(\.recipeId), [first.id])
        XCTAssertEqual(roundTrip.visits.map(\.name), [first.name])
        XCTAssertNil(WebUserData.parse("{\"app\":\"other\",\"version\":1}"))
    }
}
