import CookCore
import XCTest
@testable import Cook

@MainActor
final class KitchenStepGuidanceModelTests: XCTestCase {
    private let text = RecipeText(blocks: [
        .heading("必备原料和工具"),
        .list(ordered: false, start: 1, items: ["鸡蛋 2 个", "炒锅"]),
        .heading("操作"),
        .list(ordered: true, start: 1, items: ["热锅，加入食用油", "翻炒 20 秒"]),
    ])

    private func prompt(stepIndex: Int = 0) throws -> StepGuidancePrompt {
        try StepGuidancePrompt(
            recipe: Recipe(id: 42, name: "炒蛋", stuff: ["鸡蛋"]),
            text: text,
            dataVersion: "353bc8dab0ea",
            step: RecipePresentation(text: text).steps[stepIndex],
            servings: 1,
            kind: .explain,
            question: nil
        )
    }

    private func response(_ prompt: StepGuidancePrompt, answer: String = "先把锅烧热。") -> StepGuidanceResponse {
        StepGuidanceResponse(
            draft: StepGuidanceDraft(status: .answer, answer: answer,
                                     sourceIDs: [prompt.currentStepSourceID]),
            for: prompt
        )
    }

    func testLateSuccessCannotReplaceANewerRequest() async throws {
        let first = try prompt()
        let second = try prompt(stepIndex: 1)
        let firstStarted = expectation(description: "first request started")
        let releaseFirst = expectation(description: "first request released")
        var continuation: CheckedContinuation<Void, Never>?
        let model = KitchenStepGuidanceModel()

        model.submit(first) { _ in
            await withCheckedContinuation { value in
                continuation = value
                firstStarted.fulfill()
            }
            return self.response(first, answer: "迟到的旧回答。")
        }
        await fulfillment(of: [firstStarted], timeout: 2)
        model.submit(second) { _ in self.response(second, answer: "新步骤回答。") }
        for _ in 0 ..< 100 where model.phase != .answer {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(model.response?.requestID, second.request.requestID)
        continuation?.resume()
        releaseFirst.fulfill()
        await fulfillment(of: [releaseFirst], timeout: 1)
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(model.response?.answer, "新步骤回答。")
        XCTAssertEqual(model.response?.requestID, second.request.requestID)
    }

    func testInvalidationCancelsAndClearsLateErrorAndAnswer() async throws {
        let prompt = try prompt()
        let started = expectation(description: "request started")
        var continuation: CheckedContinuation<Void, Never>?
        let model = KitchenStepGuidanceModel()
        model.submit(prompt) { _ in
            await withCheckedContinuation { value in
                continuation = value
                started.fulfill()
            }
            throw StepGuidanceError.offline
        }
        await fulfillment(of: [started], timeout: 2)
        model.invalidate()
        continuation?.resume()
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(model.phase, .idle)
        XCTAssertNil(model.prompt)
        XCTAssertNil(model.response)
        XCTAssertNil(model.message)
    }

    func testGuidanceNeverChangesCookingProgressPreparationServingsOrTimer() async throws {
        let workbench = KitchenWorkbench(defaults: nil, notifications: .disabled)
        workbench.togglePrepared("鸡蛋", recipeID: 42)
        workbench.toggleStep("1|热锅，加入食用油", recipeID: 42)
        workbench.setServings(2, recipeID: 42, quantities: [])
        workbench.updateTimer(recipeID: 42, recipeName: "炒蛋") { timer in
            timer.configure(seconds: 120)
            timer.start(at: Date(timeIntervalSince1970: 1_000))
        }
        let before = workbench.state(for: 42)
        let prompt = try prompt()
        let model = KitchenStepGuidanceModel()
        model.submit(prompt) { _ in self.response(prompt) }
        for _ in 0 ..< 100 where model.phase != .answer {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(workbench.state(for: 42), before)
    }
}
