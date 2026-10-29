import CookCore
import SwiftData
import XCTest
@testable import Cook

@MainActor
final class SearchIntentDraftTests: XCTestCase {
    private lazy var container = Persistence.makeContainer(inMemory: true)

    func testPreviewEditingAndCancellationDoNotWriteSessionOrPreferences() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let context = container.mainContext
        let session = CookSession(defaultPantry: [], context: context)
        session.selectedStuff = ["番茄"]
        let draft = SearchIntentDraft(text: "鸡蛋，不要辣，减少添加糖")
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        XCTAssertTrue(draft.canApply)
        XCTAssertEqual(draft.intent?.query.stuff, ["鸡蛋"])
        draft.intent?.query.stuff = ["西葫芦"]
        draft.cancel()
        XCTAssertEqual(session.selectedStuff, ["番茄"])
        XCTAssertFalse(session.noSpicy)
        XCTAssertNil(session.dietaryGoal)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), 0)
        XCTAssertFalse(context.hasChanges)
    }

    func testOnlyConfirmedValidDraftIsAppliedAndCandidatesStayInsideReviewedPool() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let session = CookSession(defaultPantry: store.data.pantry.defaults, context: container.mainContext)
        session.toggleAvoid("猪肉")
        let draft = SearchIntentDraft(text: "不吃辣，减少添加糖")
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        try session.applySearchIntent(XCTUnwrap(draft.intent), in: store)
        XCTAssertEqual(session.avoid, ["猪肉"])
        XCTAssertTrue(session.noSpicy)
        XCTAssertEqual(session.dietaryGoal, .lessAddedSugar)
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

    func testApplyingConditionsKeepsExistingRecipeNameSearch() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let session = CookSession(defaultPantry: store.data.pantry.defaults, context: container.mainContext)
        session.selectDietaryGoal(.lessAddedSugar)
        let recipe = try XCTUnwrap(session.candidates(in: store).first)
        session.keyword = recipe.name
        let before = session.candidates(in: store).map(\.id)
        XCTAssertFalse(before.isEmpty)
        let draft = SearchIntentDraft(text: "减少添加糖")
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)

        try session.applySearchIntent(XCTUnwrap(draft.intent), in: store)

        XCTAssertEqual(session.keyword, recipe.name)
        XCTAssertEqual(session.candidates(in: store).map(\.id), before)
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

    func testTransportCancellationKeepsLocalDraftWithoutFailureMessage() async throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let draft = SearchIntentDraft(text: "鸡蛋，不要辣")
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        let local = draft.intent
        draft.parseRemotely { _ in throw CancellationError() }
        for _ in 0..<50 where draft.isLoading { await Task.yield() }

        XCTAssertFalse(draft.isLoading)
        XCTAssertEqual(draft.intent, local)
        XCTAssertNil(draft.message)
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

    func testReplacementRequestIgnoresLateSuccessAndFailure() async throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        for fails in [false, true] {
            let draft = SearchIntentDraft(text: "鸡蛋")
            draft.parseLocally(data: store.data, goals: store.dietaryGoals)
            let old = try XCTUnwrap(draft.intent)
            var first: CheckedContinuation<SearchIntent, Error>?
            draft.parseRemotely { _ in
                try await withCheckedThrowingContinuation { first = $0 }
            }
            for _ in 0..<50 where first == nil { await Task.yield() }
            let firstRequest = try XCTUnwrap(first)
            var second: CheckedContinuation<SearchIntent, Error>?
            draft.parseRemotely { _ in
                try await withCheckedThrowingContinuation { second = $0 }
            }
            for _ in 0..<50 where second == nil { await Task.yield() }
            let secondRequest = try XCTUnwrap(second)
            var current = old
            current.query.stuff = ["番茄"]
            current.source = .mock
            secondRequest.resume(returning: current)
            for _ in 0..<50 where draft.isLoading { await Task.yield() }
            if fails { firstRequest.resume(throwing: URLError(.timedOut)) }
            else { firstRequest.resume(returning: old) }
            for _ in 0..<20 { await Task.yield() }

            XCTAssertEqual(draft.intent, current)
            XCTAssertFalse(draft.isLoading)
            XCTAssertNil(draft.message)
        }
    }
}
