import CookCore
import SwiftData
import XCTest
@testable import Cook

@MainActor
final class CookCandidateSnapshotTests: XCTestCase {
    private lazy var container = Persistence.makeContainer(inMemory: true)

    func testBrowsingWithoutIngredientsOrToolHonorsTrimmedKeyword() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        session.keyword = " 番茄 \n"

        let snapshot = session.candidateSnapshot(in: store)
        let expected = store.recipes.filter { $0.name.contains("番茄") }

        XCTAssertFalse(expected.isEmpty)
        XCTAssertEqual(snapshot.recipes, expected)
        XCTAssertTrue(snapshot.matchesByID.isEmpty, "Browsing does not imply owning ingredients")
        XCTAssertEqual(session.candidates(in: store), expected)
        XCTAssertEqual(session.keyword, " 番茄 \n")
        XCTAssertTrue(session.selectedStuff.isEmpty)
        XCTAssertNil(session.tool)
    }

    func testRandomBrowsingCanExplicitlyIgnoreKeywordWithoutChangingIt() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        session.keyword = "没有这道菜的搜索词"

        XCTAssertTrue(session.candidateSnapshot(in: store).recipes.isEmpty)
        let random = session.candidateSnapshot(in: store, ignoringKeyword: true)
        XCTAssertEqual(random.recipes, store.recipes)
        XCTAssertTrue(random.matchesByID.isEmpty)
        XCTAssertEqual(session.candidates(in: store, ignoringKeyword: true), store.recipes)
        XCTAssertEqual(session.keyword, "没有这道菜的搜索词")
    }

    func testIngredientSnapshotKeepsRankedRecipesAndTheirExactMatches() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let session = CookSession(defaultPantry: store.data.pantry.defaults, context: container.mainContext)
        session.addStuff("鸡蛋")
        session.toggleAvoid("牛肉")
        session.setNoSpicy(true)
        let expected = session.matches(in: store)
        let before = session.options

        let snapshot = session.candidateSnapshot(in: store)

        XCTAssertFalse(expected.isEmpty)
        XCTAssertEqual(snapshot.recipes, expected.map(\.recipe))
        XCTAssertEqual(snapshot.matchesByID.count, expected.count)
        for match in expected {
            XCTAssertEqual(snapshot.matchesByID[match.recipe.id], match)
        }
        XCTAssertEqual(session.options, before)
        session.keyword = "没有这道菜的搜索词"
        XCTAssertTrue(session.candidateSnapshot(in: store).recipes.isEmpty)
        let random = session.candidateSnapshot(in: store, ignoringKeyword: true)
        XCTAssertEqual(random.recipes, expected.map(\.recipe))
        XCTAssertEqual(random.matchesByID, snapshot.matchesByID)
        XCTAssertEqual(session.keyword, "没有这道菜的搜索词")
    }

    func testFreshSnapshotReflectsReboundAndUpdatedSharedPreferences() throws {
        var data = try RecipeStore.loadBundled(inMemory: true).data
        data.recipes = [
            Recipe(id: 1, name: "蒸蛋", stuff: ["鸡蛋"]),
            Recipe(id: 2, name: "辣椒炒蛋", stuff: ["鸡蛋", "辣椒"], tags: ["辣"]),
        ]
        let store = RecipeStore(data: data, catalog: PersonalCatalogStore(url: nil))
        let context = container.mainContext
        let session = CookSession(defaultPantry: [], context: context)
        session.addStuff("鸡蛋")
        let initial = session.candidateSnapshot(in: store)
        XCTAssertEqual(Set(initial.recipes.map(\.id)), Set([1, 2]))

        let editor = CookSession(defaultPantry: [], context: context)
        editor.toggleAvoid("辣椒")
        session.refreshPreferences()
        let restricted = session.candidateSnapshot(in: store)
        XCTAssertEqual(restricted.recipes.map(\.id), [1])
        XCTAssertEqual(Set(restricted.matchesByID.keys), Set([1]))
        XCTAssertEqual(Set(initial.recipes.map(\.id)), Set([1, 2]), "A previous value snapshot remains unchanged")

        editor.clearAvoid()
        XCTAssertEqual(Set(session.candidateSnapshot(in: store).recipes.map(\.id)), Set([1, 2]))
        session.keyword = "蒸蛋"
        XCTAssertEqual(session.candidateSnapshot(in: store).recipes.map(\.id), [1])
    }

    func testReviewedSnapshotPreservesGoalPreferencesAndIngredientMatches() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let defaultsName = "cook.candidates.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsName))
        defer { defaults.removePersistentDomain(forName: defaultsName) }
        let context = container.mainContext
        let session = CookSession(defaultPantry: store.data.pantry.defaults, context: context, dietaryDefaults: defaults)
        session.selectDietaryGoal(.balancedPairing)
        session.toggleAvoid("香菜")
        session.setNoSpicy(true)
        let reviewed = session.candidateSnapshot(in: store)
        let anchor = try XCTUnwrap(reviewed.recipes.first { !$0.stuff.isEmpty })
        session.addStuff(try XCTUnwrap(anchor.stuff.first))
        let before = session.options
        let preferenceCount = try context.fetchCount(FetchDescriptor<Preferences>())
        let pendingChanges = context.hasChanges

        let snapshot = session.candidateSnapshot(in: store)

        XCTAssertFalse(snapshot.recipes.isEmpty)
        XCTAssertTrue(reviewed.matchesByID.isEmpty)
        XCTAssertEqual(snapshot.recipes, session.matches(in: store).map(\.recipe))
        for recipe in snapshot.recipes {
            XCTAssertNotNil(store.dietaryCatalog?.entry(for: recipe, goal: .balancedPairing))
            XCTAssertEqual(snapshot.matchesByID[recipe.id]?.recipe, recipe)
        }
        XCTAssertEqual(session.options, before)
        XCTAssertEqual(session.dietaryGoal, .balancedPairing)
        XCTAssertTrue(session.hasCompletedDietaryIntroduction)
        XCTAssertEqual(CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults).dietaryGoal, .balancedPairing)
        XCTAssertEqual(defaults.string(forKey: CookSession.dietaryGoalKey), DietaryGoal.balancedPairing.rawValue)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), preferenceCount)
        XCTAssertEqual(context.hasChanges, pendingChanges)
    }

    func testUnavailableGoalNeverFallsBackToOrdinarySnapshot() throws {
        let bundled = try RecipeStore.loadBundled(inMemory: true)
        let store = RecipeStore(data: bundled.data, catalog: PersonalCatalogStore(url: nil))
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        session.selectDietaryGoal(.lessAddedSugar)
        session.selectDiscoveryScene(.easy)

        let browsing = session.candidateSnapshot(in: store)
        XCTAssertTrue(browsing.recipes.isEmpty)
        XCTAssertTrue(browsing.matchesByID.isEmpty)
        XCTAssertEqual(session.matchCount(in: store), 0)
        XCTAssertNil(session.matchCount(using: store.recommender), "A core-only query cannot verify a dietary goal")
        session.addStuff("鸡蛋")
        let matching = session.candidateSnapshot(in: store)
        XCTAssertTrue(matching.recipes.isEmpty)
        XCTAssertTrue(matching.matchesByID.isEmpty)
        XCTAssertEqual(session.dietaryGoal, .lessAddedSugar)
        XCTAssertEqual(session.discoveryScene, .easy)
    }
}
