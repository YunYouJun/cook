import CookCore
import SwiftData
import UIKit
import XCTest
@testable import Cook

@MainActor
final class DietarySessionTests: XCTestCase {
    // mainContext 不能延长容器的生命周期；每个测试实例持有自己的内存容器。
    private lazy var container = Persistence.makeContainer(inMemory: true)

    private func defaults() -> (UserDefaults, String) {
        let name = "cook.dietary.tests.\(UUID().uuidString)"
        return (UserDefaults(suiteName: name)!, name)
    }

    func testGoalSymbolsAreAvailableOnTheRunningSystem() {
        for goal in DietaryGoal.allCases {
            XCTAssertNotNil(UIImage(systemName: goal.symbol), "Missing native symbol for \(goal.rawValue)")
            XCTAssertNotNil(UIImage(systemName: DietaryGoalAppearance(goal: goal).symbol), "Missing goal appearance for \(goal.rawValue)")
        }
        XCTAssertNotNil(UIImage(systemName: DietaryGoalAppearance(goal: nil).symbol), "Missing unrestricted goal appearance")
    }

    func testSelectingAGoalDoesNotCreateSyncedPreferencesOrPersistWithoutOptIn() throws {
        let (defaults, name) = defaults()
        defer { defaults.removePersistentDomain(forName: name) }
        let context = container.mainContext
        let session = CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults)
        session.selectDietaryGoal(.lessAddedSugar)
        XCTAssertTrue(session.hasSelection)
        XCTAssertEqual(session.activeFilterCount, 1)
        XCTAssertNil(defaults.string(forKey: CookSession.dietaryGoalKey))
        XCTAssertNil(CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults).dietaryGoal)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 0)
        XCTAssertFalse(context.hasChanges)
    }

    func testRememberClearThisMealAndExplicitCancellationHaveDistinctPersistence() throws {
        let (defaults, name) = defaults()
        defer { defaults.removePersistentDomain(forName: name) }
        let context = container.mainContext
        let session = CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults)
        session.selectDietaryGoal(.lessAddedSugar)
        session.rememberDietaryGoal(true)
        session.selectDietaryGoal(.balancedPairing)
        XCTAssertEqual(defaults.string(forKey: CookSession.dietaryGoalKey), DietaryGoal.balancedPairing.rawValue)
        session.clearStuff()
        XCTAssertNil(session.dietaryGoal)
        XCTAssertEqual(session.rememberedDietaryGoal, .balancedPairing)
        let reloaded = CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults)
        XCTAssertEqual(reloaded.dietaryGoal, .balancedPairing)
        reloaded.selectDietaryGoal(nil)
        XCTAssertNil(defaults.string(forKey: CookSession.dietaryGoalKey))
        XCTAssertFalse(reloaded.remembersDietaryGoal)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 0)
    }

    func testGoalCountBrowsingRandomAndIngredientMatchesUseTheSameReviewedPool() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let context = container.mainContext
        let session = CookSession(defaultPantry: store.data.pantry.defaults, context: context)
        XCTAssertNil(session.matchCount(in: store))
        session.selectDietaryGoal(.lessAddedSugar)
        XCTAssertEqual(session.matchCount(in: store), 12)
        XCTAssertEqual(session.candidates(in: store).count, 12)
        session.keyword = "菠菜"
        XCTAssertEqual(session.candidates(in: store).map(\.id), [630])
        XCTAssertEqual(session.candidates(in: store, ignoringKeyword: true).count, 12)
        session.keyword = ""
        session.addStuff("牛肉")
        XCTAssertTrue(session.matches(in: store).isEmpty)
        XCTAssertTrue(session.candidates(in: store).isEmpty)
        session.selectDietaryGoal(nil)
        XCTAssertFalse(session.matches(in: store).isEmpty)
        XCTAssertEqual(session.matches(in: store), store.recommender.recommend(session.options))
    }

    func testClearingFiltersPreservesAvoidPreferencesAndDoesNotRestoreRememberedGoalInSession() throws {
        let (defaults, name) = defaults()
        defer { defaults.removePersistentDomain(forName: name) }
        let context = container.mainContext
        let session = CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults)
        session.toggleAvoid("猪肉")
        session.selectDietaryGoal(.lessAddedSugar)
        session.rememberDietaryGoal(true)
        session.toggleMethodGroup("蒸")
        session.clearFilters()
        XCTAssertEqual(session.avoid, ["猪肉"])
        XCTAssertNil(session.dietaryGoal)
        XCTAssertEqual(session.rememberedDietaryGoal, .lessAddedSugar)
        XCTAssertEqual(session.activeFilterCount, 0)
    }

    func testMissingDietaryDataKeepsOrdinaryRecipesAndCanCancelSavedGoal() throws {
        let bundled = try RecipeStore.loadBundled(inMemory: true)
        let missing = RecipeStore(data: bundled.data)
        let session = CookSession(defaultPantry: bundled.data.pantry.defaults, context: container.mainContext)
        session.addStuff("鸡蛋")
        session.toggleAvoid("猪肉")
        let ordinary = session.matches(in: missing)
        XCTAssertFalse(ordinary.isEmpty)
        XCTAssertTrue(missing.dietaryGoals.isEmpty)
        XCTAssertNotNil(missing.dietaryUnavailableMessage)
        session.selectDietaryGoal(.lessAddedSugar)
        XCTAssertTrue(session.candidates(in: missing).isEmpty)
        session.selectDietaryGoal(nil)
        XCTAssertEqual(session.matches(in: missing), ordinary)
        XCTAssertEqual(session.avoid, ["猪肉"])
        XCTAssertEqual(missing.recipes.count, bundled.recipes.count)
    }

    func testWithdrawnGoalExplainsUnavailabilityWhileOtherGoalsStayUsable() throws {
        let bundled = try RecipeStore.loadBundled(inMemory: true)
        let source = try XCTUnwrap(Bundle.main.url(forResource: "CookData", withExtension: nil))
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("cook-dietary-withdrawn-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        for name in ["recipe-text.json", "recipe-amounts.json"] {
            try FileManager.default.copyItem(at: source.appendingPathComponent(name), to: directory.appendingPathComponent(name))
        }
        var document = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: source.appendingPathComponent(DietaryCatalog.fileName))) as? [String: Any])
        document["enabledGoals"] = [DietaryGoal.balancedPairing.rawValue]
        try JSONSerialization.data(withJSONObject: document).write(to: directory.appendingPathComponent(DietaryCatalog.fileName))
        let store = RecipeStore(data: bundled.data, dataDirectory: directory)
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        session.selectDietaryGoal(.lessAddedSugar)
        XCTAssertTrue(session.candidates(in: store).isEmpty)
        XCTAssertNotNil(store.dietaryUnavailableMessage(for: session.dietaryGoal))
        XCTAssertNil(store.dietaryUnavailableMessage)
        XCTAssertEqual(store.dietaryGoals, [.balancedPairing])
        session.selectDietaryGoal(.balancedPairing)
        XCTAssertEqual(session.candidates(in: store).count, 32)
        XCTAssertNil(store.dietaryUnavailableMessage(for: session.dietaryGoal))
    }
}
