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
        try context.setFavorite(42, isFavorite: true)
        let removedAt = Date(timeIntervalSince1970: 100)
        try context.setFavorite(42, isFavorite: false, at: removedAt)
        XCTAssertThrowsError(try context.setFavorite(42, isFavorite: true, save: { _ in throw self.diskFull }))
        let record = try XCTUnwrap(context.fetch(FetchDescriptor<Favorite>()).first)
        XCTAssertEqual(record.deletedAt, removedAt)
        XCTAssertEqual(record.updatedAt, removedAt)
        XCTAssertFalse(try context.isFavorite(42))
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

    func testPrivacyPolicyIsIncludedInTheAppBundle() throws {
        let text = try XCTUnwrap(PrivacyPolicyView.bundledText())
        XCTAssertTrue(text.contains("云食册隐私政策"))
        XCTAssertTrue(text.contains("me@yunyoujun.cn"))
        XCTAssertTrue(text.contains("默认关闭"))
        XCTAssertTrue(text.contains("冰箱临期提醒"))
        XCTAssertTrue(text.contains("不随 iCloud 同步"))
    }
}
