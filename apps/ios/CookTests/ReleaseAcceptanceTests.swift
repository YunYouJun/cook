import CookCore
import SwiftData
import SwiftUI
import UIKit
import XCTest
@testable import Cook

/// Local simulator substitutes for the remaining release checks. These do not
/// claim to reproduce an Apple-account switch or a CloudKit server session.
@MainActor
final class ReleaseAcceptanceTests: XCTestCase {
    private func directory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    private func open(_ url: URL) throws -> ModelContainer {
        let schema = Schema(Persistence.models)
        return try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none))
    }

    private func recipe(_ name: String = "离线菜谱") -> PrivateRecipe {
        PrivateRecipe(customId: "custom-release-acceptance", name: name, stuff: ["鸡蛋"], tools: ["电饭煲"],
                      link: "https://example.com/recipe", note: "本地保存", createdAt: 1000, updatedAt: 1000)
    }

    func testLocalOnlyStoreRetainsAllFiveModelsAcrossReopen() throws {
        let url = try directory().appendingPathComponent("local.store")
        let expected = recipe()
        try autoreleasepool {
            let container = try open(url)
            let context = container.mainContext
            try context.setFavorite(1, isFavorite: true)
            context.recordVisit(2)
            let session = CookSession(defaultPantry: ["盐"], context: context)
            session.toggleAvoid("花生")
            session.setNoSpicy(true)
            context.insert(FridgeItem(name: "土豆", addedAt: Date(), expiresAt: FridgeDay.stored(Date())))
            try context.save()
            try PrivateRecipePersistence.save(expected, replacing: nil, in: container)
        }
        try autoreleasepool {
            let container = try open(url)
            let context = container.mainContext
            XCTAssertTrue(try context.isFavorite(1))
            XCTAssertEqual(try context.fetch(FetchDescriptor<HistoryEntry>()).filter { $0.deletedAt == nil }.map(\.recipeId), [2])
            XCTAssertEqual(try context.latestPreferences()?.avoid, ["花生"])
            XCTAssertEqual(try context.latestPreferences()?.noSpicy, true)
            XCTAssertEqual(try context.fetch(FetchDescriptor<FridgeItem>()).map(\.name), ["土豆"])
            XCTAssertEqual(try PrivateRecipePersistence.snapshot(in: context).recipes, [expected])
        }
    }

    func testSeparateLocalStoresKeepSameRecipeIdentityIndependent() throws {
        let root = try directory()
        let firstURL = root.appendingPathComponent("first.store")
        let secondURL = root.appendingPathComponent("second.store")
        try autoreleasepool {
            let first = try open(firstURL)
            try first.mainContext.setFavorite(1, isFavorite: true)
            try PrivateRecipePersistence.save(recipe("第一份本地数据"), replacing: nil, in: first)
        }
        try autoreleasepool {
            let second = try open(secondURL)
            XCTAssertEqual(try second.mainContext.fetchCount(FetchDescriptor<Favorite>()), 0)
            XCTAssertTrue(try PrivateRecipePersistence.snapshot(in: second.mainContext).recipes.isEmpty)
            try PrivateRecipePersistence.save(recipe("第二份本地数据"), replacing: nil, in: second)
            try PrivateRecipePersistence.remove(recipe("第二份本地数据"), in: second)
        }
        try autoreleasepool {
            let first = try open(firstURL)
            XCTAssertTrue(try first.mainContext.isFavorite(1))
            XCTAssertEqual(try PrivateRecipePersistence.snapshot(in: first.mainContext).recipes, [recipe("第一份本地数据")])
            let second = try open(secondURL)
            XCTAssertEqual(try PrivateRecipePersistence.snapshot(in: second.mainContext).deletedIDs, [recipe().customId])
        }
    }

    func testOfflineFavoriteChangesAndHistoryClearPersistIndependently() throws {
        let url = try directory().appendingPathComponent("offline.store")
        try autoreleasepool {
            let container = try open(url)
            try container.mainContext.setFavorite(1, isFavorite: true)
            try container.mainContext.setFavorite(2, isFavorite: true)
            container.mainContext.recordVisit(1)
            container.mainContext.recordVisit(2)
            try PrivateRecipePersistence.save(recipe(), replacing: nil, in: container)
        }
        try autoreleasepool {
            let container = try open(url)
            let context = container.mainContext
            XCTAssertTrue(try context.isFavorite(1))
            try context.setFavorite(1, isFavorite: false)
            try context.setFavorite(3, isFavorite: true)
            context.clearHistory()
        }
        try autoreleasepool {
            let container = try open(url)
            let context = container.mainContext
            XCTAssertFalse(try context.isFavorite(1))
            XCTAssertTrue(try context.isFavorite(2))
            XCTAssertTrue(try context.isFavorite(3))
            let history = try context.fetch(FetchDescriptor<HistoryEntry>())
            XCTAssertEqual(history.count, 2)
            XCTAssertTrue(history.allSatisfy { $0.deletedAt != nil })
            XCTAssertEqual(try PrivateRecipePersistence.snapshot(in: context).recipes, [recipe()])
            context.recordVisit(1)
            XCTAssertEqual(try context.fetch(FetchDescriptor<HistoryEntry>()).filter { $0.deletedAt == nil }.map(\.recipeId), [1])
        }
    }

    func testPrivateRecipesAndPersonalCatalogRoundTripThroughFiles() throws {
        let root = try directory()
        let recipesURL = root.appendingPathComponent("recipes.json")
        try Data(PrivateRecipeFile(recipes: [recipe()]).export().utf8).write(to: recipesURL, options: .atomic)
        let parsed = try PrivateRecipeFile.parse(String(contentsOf: recipesURL, encoding: .utf8))
        let url = root.appendingPathComponent("imported.store")
        try autoreleasepool {
            let container = try open(url)
            try container.mainContext.setFavorite(2, isFavorite: true)
            XCTAssertEqual(try PrivateRecipePersistence.importRecipes(parsed.recipes, keepCopies: true, in: container), 1)
            XCTAssertEqual(try PrivateRecipePersistence.importRecipes(parsed.recipes, keepCopies: true, in: container), 0)
        }
        try autoreleasepool {
            let container = try open(url)
            XCTAssertEqual(try PrivateRecipePersistence.snapshot(in: container.mainContext).recipes, [recipe()])
            XCTAssertTrue(try container.mainContext.isFavorite(2))
        }
        let source = PersonalCatalogStore(url: root.appendingPathComponent("source-catalog.json"))
        try source.update { value in
            var next = value
            next.items = [.init(category: .vegetable, name: "秋葵"), .init(category: .tools, name: "砂锅")]
            next.hidden = [.init(category: .vegetable, name: "香菜")]
            return next
        }
        let exportURL = root.appendingPathComponent("catalog-export.json")
        try Data(source.value.export().utf8).write(to: exportURL, options: .atomic)
        let incoming = try PersonalCatalog.parse(String(contentsOf: exportURL, encoding: .utf8))
        let destinationURL = root.appendingPathComponent("destination-catalog.json")
        let destination = PersonalCatalogStore(url: destinationURL)
        try destination.update { try $0.merging(incoming) }
        try destination.update { try $0.merging(incoming) }
        XCTAssertEqual(PersonalCatalogStore(url: destinationURL).value, source.value)
    }

    func testRootReceivesFirstPreferencesImportBeforeUserEdit() async throws {
        let container = Persistence.makeContainer(inMemory: true)
        let session = CookSession(defaultPantry: ["油", "盐"], context: container.mainContext)
        let mounted = expectation(description: "Root view appears before first import")
        let host = UIHostingController(rootView: RootView()
            .environment(try RecipeStore.loadBundled(inMemory: true))
            .environment(session)
            .environment(FeedbackClient(endpoint: nil, sendsSignals: false))
            .environment(FavoriteActions(context: container.mainContext))
            .modelContainer(container)
            .onAppear { mounted.fulfill() })
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true; window.rootViewController = nil }
        await fulfillment(of: [mounted], timeout: 3)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Preferences>()), 0)
        let incoming = ModelContext(container)
        let imported = Preferences(pantry: ["糖"])
        imported.avoid = ["香菜"]
        imported.noSpicy = true
        incoming.insert(imported)
        try incoming.save()
        // No manual refresh: exercise the production root's @Query observer.
        for _ in 0 ..< 150 {
            if session.avoid == ["香菜"] { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertEqual(session.pantry, ["糖"])
        XCTAssertEqual(session.avoid, ["香菜"])
        XCTAssertTrue(session.noSpicy)
        session.toggleAvoid("花生")
        XCTAssertEqual(session.avoid, ["香菜", "花生"])
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Preferences>()), 1)
    }

    func testNotificationResponseFromBackgroundCompletesOnMainAfterRouting() async throws {
        PendingFridgeHome.requested = false
        let container = Persistence.makeContainer(inMemory: true)
        let router = AppRouter()
        let appeared = expectation(description: "root is ready to receive navigation")
        let host = UIHostingController(rootView: RootView(router: router)
            .environment(try RecipeStore.loadBundled(inMemory: true))
            .environment(CookSession(defaultPantry: [], context: container.mainContext))
            .environment(FeedbackClient(endpoint: nil, sendsSignals: false))
            .environment(FavoriteActions(context: container.mainContext))
            .modelContainer(container)
            .onAppear { appeared.fulfill() })
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            PendingFridgeHome.requested = false
            window.isHidden = true
            window.rootViewController = nil
        }
        await fulfillment(of: [appeared], timeout: 3)
        XCTAssertEqual(router.tab, .cook)
        let routed = expectation(description: "fridge navigation on main")
        let completed = expectation(description: "system response completed on main")
        routed.assertForOverFulfill = true
        completed.assertForOverFulfill = true
        let observer = NotificationCenter.default.addObserver(forName: .showFridgeHome, object: nil, queue: nil) { _ in
            XCTAssertTrue(Thread.isMainThread)
            routed.fulfill()
        }
        defer { NotificationCenter.default.removeObserver(observer) }
        DispatchQueue.global().async {
            NotificationDelegate().handleResponse(identifier: "fridge-expiry-20261025") {
                XCTAssertTrue(Thread.isMainThread)
                completed.fulfill()
            }
        }
        await fulfillment(of: [routed, completed], timeout: 3, enforceOrder: true)
        // A mounted root consumes the pending flag while handling the broadcast.
        // Verify the visible route, rather than expecting an unconsumed cold-launch request.
        XCTAssertEqual(router.tab, .fridge)
        XCTAssertEqual(router.fridgeHomeRequests, 1)
        XCTAssertFalse(PendingFridgeHome.requested)
    }

    func testKitchenNotificationResponseCompletesOnMainWithoutFridgeNavigation() async {
        PendingFridgeHome.requested = false
        defer { PendingFridgeHome.requested = false }
        let completed = expectation(description: "non-fridge response completed on main")
        completed.assertForOverFulfill = true
        let observer = NotificationCenter.default.addObserver(forName: .showFridgeHome, object: nil, queue: nil) { _ in
            XCTFail("Kitchen notifications must not navigate to the fridge")
        }
        defer { NotificationCenter.default.removeObserver(observer) }
        DispatchQueue.global().async {
            NotificationDelegate().handleResponse(identifier: "kitchen-timer-1") {
                XCTAssertTrue(Thread.isMainThread)
                completed.fulfill()
            }
        }
        await fulfillment(of: [completed], timeout: 3)
        XCTAssertFalse(PendingFridgeHome.requested)
    }

    func testRootConsumesColdNotificationAndRoutesWarmNotification() async throws {
        let container = Persistence.makeContainer(inMemory: true)
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        PendingFridgeHome.requested = true
        let router = AppRouter()
        let host = UIHostingController(rootView: RootView(router: router)
            .environment(try RecipeStore.loadBundled(inMemory: true))
            .environment(session)
            .environment(FeedbackClient(endpoint: nil, sendsSignals: false))
            .environment(FavoriteActions(context: container.mainContext))
            .modelContainer(container))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            PendingFridgeHome.requested = false
            window.isHidden = true
            window.rootViewController = nil
        }
        for _ in 0 ..< 150 {
            if router.tab == .fridge, !PendingFridgeHome.requested { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertEqual(router.tab, .fridge, "Cold launch consumes the queued fridge navigation")
        XCTAssertFalse(PendingFridgeHome.requested)
        XCTAssertEqual(router.fridgeHomeRequests, 1)
        router.tab = .cook
        NotificationCenter.default.post(name: .showFridgeHome, object: nil)
        for _ in 0 ..< 150 {
            if router.tab == .fridge { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertEqual(router.tab, .fridge, "A warm notification returns to the fridge tab")
        XCTAssertEqual(router.fridgeHomeRequests, 2)
        XCTAssertFalse(PendingFridgeHome.requested)
    }
}
