import CookCore
import SwiftData
import XCTest
@testable import Cook

@MainActor
final class SearchIntentDraftTests: XCTestCase {
    private lazy var container = Persistence.makeContainer(inMemory: true)

    func testPreviewEditingAndCancellationDoNotWriteSessionOrPreferences() throws {
        let defaultsName = "cook.search-intent.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsName))
        defer { defaults.removePersistentDomain(forName: defaultsName) }
        defaults.set(DietaryGoal.balancedPairing.rawValue, forKey: CookSession.dietaryGoalKey)
        let store = try RecipeStore.loadBundled(inMemory: true)
        let context = container.mainContext
        let session = CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults)
        session.selectedStuff = ["番茄"]
        let draft = SearchIntentDraft(text: "鸡蛋，不要辣，减少添加糖")
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        XCTAssertTrue(draft.canApply)
        XCTAssertEqual(draft.intent?.query.stuff, ["鸡蛋"])
        draft.intent?.query.stuff = ["西葫芦"]
        draft.cancel()
        XCTAssertEqual(session.selectedStuff, ["番茄"])
        XCTAssertFalse(session.noSpicy)
        XCTAssertEqual(session.dietaryGoal, .balancedPairing)
        XCTAssertEqual(defaults.string(forKey: CookSession.dietaryGoalKey), DietaryGoal.balancedPairing.rawValue)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 0)
        XCTAssertFalse(context.hasChanges)
    }

    func testOnlyConfirmedValidDraftIsAppliedAndCandidatesStayInsideReviewedPool() throws {
        let defaultsName = "cook.search-intent.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsName))
        defer { defaults.removePersistentDomain(forName: defaultsName) }
        let store = try RecipeStore.loadBundled(inMemory: true)
        let session = CookSession(defaultPantry: store.data.pantry.defaults, context: container.mainContext, dietaryDefaults: defaults)
        session.toggleAvoid("猪肉")
        let draft = SearchIntentDraft(text: "不吃辣，减少添加糖")
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        XCTAssertNil(defaults.string(forKey: CookSession.dietaryGoalKey))
        try session.applySearchIntent(XCTUnwrap(draft.intent), in: store)
        XCTAssertEqual(session.avoid, ["猪肉"])
        XCTAssertTrue(session.noSpicy)
        XCTAssertEqual(session.dietaryGoal, .lessAddedSugar)
        XCTAssertEqual(CookSession(defaultPantry: [], context: container.mainContext, dietaryDefaults: defaults).dietaryGoal, .lessAddedSugar)
        let candidates = session.candidates(in: store)
        XCTAssertFalse(candidates.isEmpty)
        for recipe in candidates {
            XCTAssertNotNil(store.dietaryCatalog?.entry(for: recipe, goal: .lessAddedSugar))
            XCTAssertFalse(store.data.normalizer.unique(recipe.stuff).contains("猪肉"))
        }
        let before = session.options
        var stale = try XCTUnwrap(draft.intent)
        stale.dataVersion = "stale"
        stale.query.avoid = ["鸡蛋"]
        XCTAssertThrowsError(try session.applySearchIntent(stale, in: store))
        XCTAssertEqual(session.selectedStuff, before.stuff)
        XCTAssertEqual(session.avoid, before.avoid)
        XCTAssertEqual(session.dietaryGoal, .lessAddedSugar)
        XCTAssertEqual(defaults.string(forKey: CookSession.dietaryGoalKey), DietaryGoal.lessAddedSugar.rawValue)
    }

    func testPendingRequestsRequireExplicitPartialConfirmationAndChangedTextInvalidatesPreview() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let draft = SearchIntentDraft(text: "鸡蛋，孕期能吃")
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        XCTAssertFalse(draft.canApply)
        draft.acceptsUnresolved = true
        XCTAssertTrue(draft.canApply)
        draft.text = "番茄"
        draft.inputChanged()
        XCTAssertNil(draft.intent)
        XCTAssertFalse(draft.canApply)
        XCTAssertFalse(draft.acceptsUnresolved)
    }

    func testConfirmationIntersectsFiltersAndRejectsConflictBeforeAnyMutation() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        session.difficulty = ["简单", "普通"]
        let draft = SearchIntentDraft(text: "鸡蛋，简单点")
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        try session.applySearchIntent(XCTUnwrap(draft.intent), in: store)
        XCTAssertEqual(session.difficulty, ["简单"])
        var conflict = try XCTUnwrap(draft.intent)
        conflict.query.difficulty = ["困难"]
        conflict.query.avoid = ["猪肉"]
        let before = session.selectedStuff
        XCTAssertThrowsError(try session.applySearchIntent(conflict, in: store))
        XCTAssertEqual(session.difficulty, ["简单"])
        XCTAssertEqual(session.selectedStuff, before)
        XCTAssertTrue(session.avoid.isEmpty)
    }

    func testFailedRemoteRequestKeepsLocalDraftAndSessionUntouched() async throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let draft = SearchIntentDraft(text: "鸡蛋，不要辣")
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        let local = draft.intent
        draft.parseRemotely { _ in throw URLError(.timedOut) }
        for _ in 0..<50 where draft.isLoading { await Task.yield() }
        XCTAssertFalse(draft.isLoading)
        XCTAssertEqual(draft.intent, local)
        XCTAssertNotNil(draft.message)
        XCTAssertTrue(draft.canApply)
    }

    func testCancelledLateResponseCannotReplaceNewLocalDraft() async throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let draft = SearchIntentDraft(text: "鸡蛋")
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        let old = try XCTUnwrap(draft.intent)
        var continuation: CheckedContinuation<SearchIntent, Error>?
        draft.parseRemotely { _ in
            try await withCheckedThrowingContinuation { continuation = $0 }
        }
        for _ in 0..<50 where continuation == nil { await Task.yield() }
        XCTAssertNotNil(continuation)
        draft.text = "番茄"
        draft.inputChanged()
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        continuation?.resume(returning: old)
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(draft.intent?.query.stuff, ["番茄"])
        XCTAssertFalse(draft.isLoading)
        XCTAssertNil(draft.message)
    }
}
