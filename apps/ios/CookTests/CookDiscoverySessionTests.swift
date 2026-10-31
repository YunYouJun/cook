import CookCore
import SwiftData
import XCTest
@testable import Cook

@MainActor
final class CookDiscoverySessionTests: XCTestCase {
    private lazy var container = Persistence.makeContainer(inMemory: true)

    private func store() throws -> RecipeStore {
        var data = try RecipeStore.loadBundled(inMemory: true).data
        data.recipes = [
            Recipe(id: 1, name: "番茄炒蛋", stuff: ["番茄", "鸡蛋"], difficulty: "简单", tags: ["家常菜"], methods: ["炒"], tools: ["一口大锅"]),
            Recipe(id: 2, name: "番茄蛋汤", stuff: ["番茄", "鸡蛋"], difficulty: "简单", tags: ["汤", "家常菜"], methods: ["煮"], tools: ["一口大锅"]),
            Recipe(id: 3, name: "辣椒蛋汤", stuff: ["鸡蛋", "辣椒"], difficulty: "简单", tags: ["汤", "辣"], methods: ["煮"], tools: ["一口大锅"]),
            Recipe(id: 4, name: "蒸蛋", stuff: ["鸡蛋"], difficulty: "简单", methods: ["蒸"], tools: ["电饭煲"]),
            Recipe(id: 5, name: "番茄炖牛肉", stuff: ["番茄", "牛肉"], difficulty: "普通", tags: ["家常菜"], methods: ["炖"], tools: ["一口大锅"]),
            Recipe(id: 6, name: "蛋花汤", stuff: ["鸡蛋"], difficulty: "普通", tags: ["汤"], methods: ["煮"], tools: ["一口大锅"]),
        ]
        return RecipeStore(data: data, catalog: PersonalCatalogStore(url: nil))
    }

    func testSceneIntersectsSharedConditionsInMatchesSnapshotAndCounts() throws {
        let store = try store()
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        session.addStuff("鸡蛋")
        session.tool = "一口大锅"
        session.methodGroups = ["汤羹"]
        session.keyword = "蛋"
        session.setNoSpicy(true)
        let before = session.options
        XCTAssertEqual(Set(session.matches(in: store).map(\.recipe.id)), Set([2, 6]))

        session.selectDiscoveryScene(.easy)
        let snapshot = session.candidateSnapshot(in: store)

        XCTAssertEqual(snapshot.recipes.map(\.id), [2])
        XCTAssertEqual(session.matches(in: store).map(\.recipe.id), [2])
        XCTAssertEqual(snapshot.matchesByID[2], session.matches(in: store).first)
        XCTAssertEqual(session.matchCount(in: store), 1)
        XCTAssertEqual(session.matchCount(using: store.recommender), 1)
        XCTAssertEqual(session.options, before, "Scene constraints do not alter shared Core options")
        XCTAssertEqual(session.activeFilterCount, 3)

        session.keyword = "蒸"
        XCTAssertTrue(session.candidates(in: store).isEmpty)
        XCTAssertTrue(session.matches(in: store).isEmpty)
        XCTAssertEqual(session.matchCount(in: store), 0)
    }

    func testSceneAloneCanFindRecipesAndDoesNotPersist() throws {
        let store = try store()
        let context = container.mainContext
        let session = CookSession(defaultPantry: [], context: context)
        XCTAssertNil(session.discoveryScene)
        XCTAssertFalse(session.hasSelection)

        session.toggleDiscoveryScene(.soup)

        XCTAssertTrue(session.hasSelection)
        XCTAssertEqual(session.activeFilterCount, 1)
        XCTAssertEqual(Set(session.candidates(in: store).map(\.id)), Set([2, 3, 6]))
        XCTAssertTrue(session.candidateSnapshot(in: store).matchesByID.isEmpty)
        XCTAssertEqual(session.matchCount(in: store), 3)
        XCTAssertEqual(session.matchCount(using: store.recommender), 3)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 0)
        XCTAssertFalse(context.hasChanges)
        XCTAssertNil(CookSession(defaultPantry: [], context: context).discoveryScene)

        session.toggleDiscoveryScene(.soup)
        XCTAssertNil(session.discoveryScene)
        XCTAssertFalse(session.hasSelection)
        XCTAssertNil(session.matchCount(in: store))
    }

    func testRemovingOnlyScenePreservesGoalPreferencesAndEveryOtherCondition() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let defaultsName = "cook.discovery.scene.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsName))
        defer { defaults.removePersistentDomain(forName: defaultsName) }
        let context = container.mainContext
        let session = CookSession(defaultPantry: ["盐"], context: context, dietaryDefaults: defaults)
        session.selectDietaryGoal(.balancedPairing)
        session.addStuff("鸡蛋")
        session.tool = "一口大锅"
        session.methodGroups = ["汤羹"]
        session.difficulty = ["简单"]
        session.keyword = "蛋"
        session.toggleAvoid("香菜")
        session.setNoSpicy(true)
        let expected = session.candidateSnapshot(in: store)
        let before = session.options
        let preferenceCount = try context.fetchCount(FetchDescriptor<Preferences>())
        let pendingChanges = context.hasChanges
        session.selectDiscoveryScene(.home)

        session.selectDiscoveryScene(nil)

        XCTAssertEqual(session.options, before)
        XCTAssertEqual(session.candidates(in: store), expected.recipes)
        XCTAssertEqual(session.candidateSnapshot(in: store).matchesByID, expected.matchesByID)
        XCTAssertEqual(session.dietaryGoal, .balancedPairing)
        XCTAssertTrue(session.hasCompletedDietaryIntroduction)
        XCTAssertEqual(CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults).dietaryGoal, .balancedPairing)
        XCTAssertEqual(defaults.string(forKey: CookSession.dietaryGoalKey), DietaryGoal.balancedPairing.rawValue)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), preferenceCount)
        XCTAssertEqual(context.hasChanges, pendingChanges)
    }

    func testClearMealAndClearFiltersHaveExplicitPersistenceBoundaries() throws {
        let defaultsName = "cook.discovery.clear.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsName))
        defer { defaults.removePersistentDomain(forName: defaultsName) }
        let context = container.mainContext
        let session = CookSession(defaultPantry: ["盐"], context: context, dietaryDefaults: defaults)
        session.addStuff("鸡蛋")
        session.tool = "一口大锅"
        session.keyword = "蛋"
        session.methodGroups = ["汤羹"]
        session.difficulty = ["简单"]
        session.selectDiscoveryScene(.easy)
        session.selectDietaryGoal(.balancedPairing)
        session.toggleAvoid("牛肉")
        session.setNoSpicy(true)
        session.mode = .strict

        session.clearStuff()

        XCTAssertTrue(session.selectedStuff.isEmpty)
        XCTAssertNil(session.tool)
        XCTAssertEqual(session.keyword, "")
        XCTAssertTrue(session.methodGroups.isEmpty)
        XCTAssertTrue(session.difficulty.isEmpty)
        XCTAssertNil(session.discoveryScene)
        XCTAssertNil(session.dietaryGoal)
        XCTAssertFalse(session.hasSelection)
        XCTAssertEqual(session.avoid, ["牛肉"])
        XCTAssertEqual(session.pantry, ["盐"])
        XCTAssertTrue(session.noSpicy)
        XCTAssertEqual(session.mode, .strict)
        XCTAssertTrue(session.hasCompletedDietaryIntroduction)
        XCTAssertEqual(CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults).dietaryGoal, .balancedPairing)

        session.addStuff("鸡蛋")
        session.tool = "一口大锅"
        session.keyword = "蛋"
        session.methodGroups = ["汤羹"]
        session.difficulty = ["简单"]
        session.selectDiscoveryScene(.soup)
        session.clearFilters()
        XCTAssertNil(session.discoveryScene)
        XCTAssertTrue(session.methodGroups.isEmpty)
        XCTAssertTrue(session.difficulty.isEmpty)
        XCTAssertFalse(session.noSpicy)
        XCTAssertEqual(session.selectedStuff, ["鸡蛋"])
        XCTAssertEqual(session.tool, "一口大锅")
        XCTAssertEqual(session.keyword, "蛋")
        XCTAssertEqual(session.avoid, ["牛肉"])
        XCTAssertTrue(session.hasCompletedDietaryIntroduction)
        XCTAssertEqual(CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults).dietaryGoal, .balancedPairing)
    }

    func testExactAndPartialDishInputSearchesWithoutCreatingIngredientFragments() throws {
        let store = try store()
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        let exact = session.applyDiscoveryInput(" 番茄炒蛋 ", in: store)
        XCTAssertEqual(exact.kind, .keyword)
        XCTAssertEqual(exact.keyword, "番茄炒蛋")
        XCTAssertTrue(session.selectedStuff.isEmpty)
        XCTAssertEqual(session.candidates(in: store).map(\.id), [1])
        XCTAssertTrue(session.hasSelection)
        XCTAssertEqual(session.matchCount(in: store), 1)

        session.selectDiscoveryScene(.easy)
        session.tool = "一口大锅"
        session.setNoSpicy(true)
        let partial = session.applyDiscoveryInput("蛋汤", in: store)
        XCTAssertEqual(partial.kind, .keyword)
        XCTAssertEqual(session.keyword, "蛋汤")
        XCTAssertTrue(session.selectedStuff.isEmpty)
        XCTAssertEqual(session.discoveryScene, .easy)
        XCTAssertEqual(session.tool, "一口大锅")
        XCTAssertTrue(session.noSpicy)
        XCTAssertEqual(session.candidates(in: store).map(\.id), [2])
    }

    func testKnownIngredientAndSentenceInputClearOldKeywordButKeepOtherConditions() throws {
        let store = try store()
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        session.addStuff("土豆")
        session.keyword = "蒸蛋"
        session.tool = "一口大锅"
        session.selectDiscoveryScene(.home)
        session.selectDietaryGoal(.balancedPairing)
        session.toggleAvoid("香菜")

        let ingredient = session.applyDiscoveryInput("西红柿", in: store)
        XCTAssertEqual(ingredient.kind, .conditions)
        XCTAssertEqual(session.selectedStuff, ["土豆", "番茄"])
        XCTAssertEqual(session.keyword, "")
        XCTAssertTrue(ingredient.message.contains("已清除上次菜名搜索"))

        let sentence = session.applyDiscoveryInput("冰箱里有鸡蛋，不吃牛肉，不要辣，简单，想炒个菜", in: store)
        XCTAssertEqual(sentence.kind, .conditions)
        XCTAssertEqual(session.selectedStuff, ["土豆", "番茄", "鸡蛋"])
        XCTAssertEqual(session.avoid, ["香菜", "牛肉"])
        XCTAssertTrue(session.noSpicy)
        XCTAssertEqual(session.methodGroups, ["炒菜"])
        XCTAssertEqual(session.difficulty, ["简单"])
        XCTAssertEqual(session.tool, "一口大锅")
        XCTAssertEqual(session.discoveryScene, .home)
        XCTAssertEqual(session.dietaryGoal, .balancedPairing)
        XCTAssertTrue(sentence.query?.unknown.isEmpty == true)
    }

    func testUnknownTextAndUnrecognizedDishFragmentsRemainSearchTerms() throws {
        let store = try store()
        let context = container.mainContext
        let session = CookSession(defaultPantry: [], context: context)
        let unknown = session.applyDiscoveryInput("黑松露", in: store)
        XCTAssertEqual(unknown.kind, .keyword)
        XCTAssertEqual(session.keyword, "黑松露")
        XCTAssertTrue(session.selectedStuff.isEmpty)
        XCTAssertTrue(session.candidates(in: store).isEmpty)
        XCTAssertEqual(session.matchCount(in: store), 0)

        let fragment = session.applyDiscoveryInput("椒盐土豆角", in: store)
        XCTAssertEqual(fragment.kind, .keyword)
        XCTAssertEqual(session.keyword, "椒盐土豆角")
        XCTAssertTrue(session.selectedStuff.isEmpty, "A known ingredient inside an unknown dish must not be silently added")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 0)
        XCTAssertFalse(context.hasChanges)
    }

    func testBlankInputIsNoOpAndMethodOnlySelectionUsesTheSharedPool() throws {
        let store = try store()
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        session.keyword = "蛋"
        session.selectDiscoveryScene(.easy)
        let before = session.options
        XCTAssertEqual(session.applyDiscoveryInput(" \n ", in: store).kind, .empty)
        XCTAssertEqual(session.options, before)
        XCTAssertEqual(session.discoveryScene, .easy)

        session.clearStuff()
        session.methodGroups = ["汤羹"]
        XCTAssertTrue(session.hasSelection)
        XCTAssertEqual(session.matchCount(in: store), 3)
        XCTAssertEqual(session.matchCount(using: store.recommender), 3)
        XCTAssertEqual(Set(session.candidates(in: store).map(\.id)), Set([2, 3, 6]))
    }
}
