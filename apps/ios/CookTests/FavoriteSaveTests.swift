import CookCore
import SwiftData
import XCTest
@testable import Cook

@MainActor
final class FavoriteSaveTests: XCTestCase {
    private let diskFull = CocoaError(.fileWriteOutOfSpace)

    func testFailedFavoriteDoesNotPersistAndRetryKeepsTheIntendedState() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        var attempts = 0
        let actions = FavoriteActions(context: context) { context in
            attempts += 1
            if attempts == 1 { throw self.diskFull }
            try context.save()
        }
        actions.set(42, isFavorite: true)
        XCTAssertFalse(try context.isFavorite(42))
        XCTAssertFalse(try ModelContext(container).isFavorite(42), "失败的插入没有落盘")
        XCTAssertEqual(actions.savedCount, 0)
        let failure = try XCTUnwrap(actions.failure)
        XCTAssertTrue(failure.isFavorite)

        // 等待重试期间，另一个入口（或同步）已经收藏了这道菜。
        try context.setFavorite(42, isFavorite: true)
        actions.retry(failure)
        XCTAssertTrue(try context.isFavorite(42), "重试必须仍然是收藏，不是切换状态")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Favorite>()), 1)
        XCTAssertNil(actions.failure)
        XCTAssertEqual(actions.savedCount, 1)
    }

    func testFailedRemovalRestoresAllDuplicateFavorites() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let date = Date(timeIntervalSince1970: 100)
        context.insert(Favorite(recipeId: 42, at: date))
        context.insert(Favorite(recipeId: 42, at: date))
        try context.save()
        let actions = FavoriteActions(context: context) { _ in throw self.diskFull }
        actions.set(42, isFavorite: false)
        let records = try context.fetch(FetchDescriptor<Favorite>())
        XCTAssertEqual(records.count, 2)
        XCTAssertTrue(records.allSatisfy { $0.deletedAt == nil && $0.updatedAt == date })
        XCTAssertTrue(try ModelContext(container).isFavorite(42))
        XCTAssertEqual(actions.failure?.isFavorite, false)
        XCTAssertEqual(actions.savedCount, 0)
        actions.retry(try XCTUnwrap(actions.failure))
        XCTAssertNotNil(actions.failure, "连续失败仍保留可重试的错误")
    }

    func testFailedRevivalRestoresTheDeletionTimestamp() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        try context.setFavorite(42, isFavorite: true, at: Date(timeIntervalSince1970: 99))
        let removedAt = Date(timeIntervalSince1970: 100)
        try context.setFavorite(42, isFavorite: false, at: removedAt)
        XCTAssertThrowsError(try context.setFavorite(42, isFavorite: true, save: { _ in throw self.diskFull }))
        let record = try XCTUnwrap(context.fetch(FetchDescriptor<Favorite>()).first)
        XCTAssertEqual(record.deletedAt, removedAt)
        XCTAssertEqual(record.updatedAt, removedAt)
        XCTAssertFalse(try context.isFavorite(42))
    }

    func testReimportAfterLocalDeletionWithAnEarlierDeviceClock() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let file = try XCTUnwrap(WebUserData.parse("""
        {"app":"cook","version":1,"favorites":[{"id":1,"time":2000000000000}],
         "histories":[{"recipe":{"name":"蒸蛋羹"},"time":2000000000000}]}
        """))
        let recipes = ["蒸蛋羹": 1]
        let added = try context.importWebData(file, recipeIdByName: recipes, validIds: [1])
        XCTAssertEqual(added.favorites, 1)
        XCTAssertEqual(added.visits, 1)

        let deviceTime = Date(timeIntervalSince1970: 1800000000)
        try context.setFavorite(1, isFavorite: false, at: deviceTime)
        context.clearHistory(at: deviceTime)

        let again = try context.importWebData(file, recipeIdByName: recipes, validIds: [1])
        XCTAssertEqual(again.favorites, 0)
        XCTAssertEqual(again.visits, 0)
        XCTAssertFalse(try context.isFavorite(1))
        XCTAssertTrue(try XCTUnwrap(context.fetch(FetchDescriptor<HistoryEntry>()).first).deletedAt != nil)
    }

    func testPreflightFailureDoesNotDiscardUnrelatedPendingChanges() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let preferences = Preferences(pantry: ["油", "盐"])
        context.insert(preferences)
        XCTAssertThrowsError(try context.setFavorite(42, isFavorite: true, save: { _ in throw self.diskFull }))
        XCTAssertEqual(preferences.pantry, ["油", "盐"])
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 1)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Favorite>()), 0)
        XCTAssertTrue(context.hasChanges)
    }

    func testRollbackPreservesPreviouslyPendingChanges() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        context.insert(Preferences(pantry: ["油", "盐"]))
        let expiry = Date(timeIntervalSince1970: 1000)
        context.insert(FridgeItem(name: "鸡蛋", addedAt: Date(), expiresAt: expiry))
        var saves = 0
        XCTAssertThrowsError(try context.setFavorite(42, isFavorite: true, save: { context in
            saves += 1
            if saves == 2 { throw self.diskFull }
            try context.save()
        }))
        let reloaded = ModelContext(container)
        XCTAssertEqual(try reloaded.fetch(FetchDescriptor<Preferences>()).first?.pantry, ["油", "盐"])
        XCTAssertFalse(try reloaded.isFavorite(42))
        let fridge = try XCTUnwrap(reloaded.fetch(FetchDescriptor<FridgeItem>()).first)
        XCTAssertEqual(fridge.name, "鸡蛋", "收藏失败不能回滚其他页面刚保存的冰箱数据")
        XCTAssertEqual(fridge.expiresAt, expiry)
    }

    func testImportSaveFailureRollsBackFavoritesAndHistoryTogether() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        try context.setFavorite(9, isFavorite: true)
        context.recordVisit(9)
        let data = try XCTUnwrap(WebUserData.parse("""
        {"app":"cook","version":1,"favorites":[{"id":1,"time":1000}],
         "histories":[{"recipe":{"name":"新菜"},"time":1000}]}
        """))
        XCTAssertThrowsError(try context.importWebData(data, recipeIdByName: ["新菜": 1], validIds: [1, 9], save: { _ in throw self.diskFull }))
        XCTAssertTrue(try context.isFavorite(9))
        XCTAssertFalse(try context.isFavorite(1))
        XCTAssertEqual(try context.fetch(FetchDescriptor<HistoryEntry>()).map(\.recipeId), [9])
        let added = try context.importWebData(data, recipeIdByName: ["新菜": 1], validIds: [1, 9])
        XCTAssertEqual(added.favorites, 1)
        XCTAssertEqual(added.visits, 1)
    }

    func testHiddenRecipeImagesLeaveSourceAndAmountsIntact() throws {
        let directory = try XCTUnwrap(Bundle.main.url(forResource: "CookData", withExtension: nil))
        let data = try CookData.load(from: directory)
        let unrestricted = RecipeStore(data: data, dataDirectory: directory)
        let watermarked = "howtocook/1beadffd0179aea6.webp"
        let visibleDeclared = "howtocook/fcab1b3841f8e0c7.webp"
        let visibleUnwatermarked = "howtocook/ad77e0934f5592d6.webp"
        let filtered = RecipeStore(data: data, dataDirectory: directory, hiddenTextImagePaths: [watermarked])
        let recipe = try XCTUnwrap(filtered.recipesById[835])

        XCTAssertNotNil(unrestricted.textImageURL(watermarked))
        XCTAssertNil(filtered.textImageURL(watermarked))
        XCTAssertNotNil(filtered.textImageURL(visibleDeclared))
        XCTAssertNotNil(filtered.textImageURL(visibleUnwatermarked))
        XCTAssertNotNil(filtered.text(for: recipe))
        XCTAssertEqual(filtered.text(for: recipe), unrestricted.text(for: recipe))

        let bundled = try RecipeStore.loadBundled(inMemory: true)
        XCTAssertNil(bundled.textImageURL(watermarked))
        XCTAssertNotNil(bundled.textImageURL(visibleDeclared))
        XCTAssertNotNil(bundled.textImageURL(visibleUnwatermarked))
    }

    func testPrivacyPolicyIsIncludedInTheAppBundle() throws {
        let text = try XCTUnwrap(PrivacyPolicyView.bundledText())
        XCTAssertTrue(text.contains("云食册隐私政策"))
        XCTAssertTrue(text.contains("me@yunyoujun.cn"))
        let reminderPolicy = try XCTUnwrap(text.components(separatedBy: "\n\n").first {
            $0.contains("临期提醒默认关闭")
        })
        XCTAssertTrue(reminderPolicy.contains("不随 iCloud 同步"))
    }

    func testPrivacyPolicyExplainsDeviceLocalCookingFootprints() throws {
        let text = try XCTUnwrap(PrivacyPolicyView.bundledText())
        let footprintPolicy = try XCTUnwrap(text.components(separatedBy: "\n\n").first {
            $0.contains("只有你主动记录「做过」或「学会」时")
        })
        XCTAssertTrue(text.contains("### 本机做菜足迹"))
        XCTAssertTrue(footprintPolicy.contains("浏览菜谱、步骤完成或计时结束都不会自动记为学会"))
        XCTAssertTrue(footprintPolicy.contains("删除单次做过记录"))
        XCTAssertTrue(footprintPolicy.contains("撤销学会标记"))
        XCTAssertTrue(footprintPolicy.contains("足迹不加入 CloudKit、云乐坊账号同步、推荐统计或 AI 请求"))
    }
}
