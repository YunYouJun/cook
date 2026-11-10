@testable import CookCore
import XCTest

final class StepGuidanceTests: XCTestCase {
    private let recipe = Recipe(id: 42, name: "番茄炒蛋", stuff: ["番茄", "鸡蛋"])
    private let text = RecipeText(blocks: [
        .heading("必备原料和工具"),
        .list(ordered: false, start: 1, items: ["番茄 1 个", "鸡蛋 2 个", "炒锅"]),
        .heading("操作"),
        .list(ordered: true, start: 1, items: [
            "番茄切块",
            "热锅，加入 4ml 食用油",
            "翻炒 20 秒",
        ]),
    ])

    private func prompt(kind: StepGuidanceKind = .explain, question: String? = nil,
                        stepIndex: Int = 1) throws -> StepGuidancePrompt {
        let presentation = RecipePresentation(text: text)
        return try StepGuidancePrompt(
            recipe: recipe,
            text: text,
            dataVersion: "353bc8dab0ea",
            step: presentation.steps[stepIndex],
            servings: 2,
            kind: kind,
            question: question,
            requestID: "11111111-1111-4111-8111-111111111111",
            contextRevision: "22222222-2222-4222-8222-222222222222"
        )
    }

    func testPromptBindsStableStepFingerprintAndOnlyWhitelistedSources() throws {
        let prompt = try prompt()
        XCTAssertEqual(prompt.request.dataVersion, "353bc8dab0ea")
        XCTAssertEqual(prompt.request.stepID, "1|热锅，加入 4ml 食用油")
        XCTAssertEqual(prompt.request.sourceFingerprint, RecipeAmountAnnotations.fingerprint(text))
        XCTAssertEqual(prompt.currentStepSourceID, "step:1|热锅，加入 4ml 食用油")
        XCTAssertEqual(Set(prompt.sources.map(\.id)), Set([
            "recipe:name",
            "step:1|番茄切块",
            "step:1|热锅，加入 4ml 食用油",
            "step:1|翻炒 20 秒",
            "ingredient:1",
            "ingredient:2",
            "ingredient:3",
        ]))
        XCTAssertTrue(prompt.systemPrompt.contains("untrusted data"))
        XCTAssertTrue(prompt.userMessage.contains("不要替我执行"))
    }

    func testQuestionIsBoundedAndExplanationCannotCarryAQuestion() throws {
        XCTAssertThrowsError(try prompt(kind: .question, question: " ")) {
            XCTAssertEqual($0 as? StepGuidanceError, .invalidQuestion)
        }
        XCTAssertThrowsError(try prompt(kind: .question, question: String(repeating: "字", count: 301))) {
            XCTAssertEqual($0 as? StepGuidanceError, .invalidQuestion)
        }
        XCTAssertThrowsError(try prompt(kind: .explain, question: "为什么")) {
            XCTAssertEqual($0 as? StepGuidanceError, .invalidQuestion)
        }
    }

    func testResponseRequiresCurrentStepAndKnownCitations() throws {
        let prompt = try prompt(kind: .question, question: "要炒多久？")
        let current = prompt.currentStepSourceID
        XCTAssertNoThrow(try StepGuidanceValidator.validate(
            StepGuidanceDraft(status: .answer, answer: "原文写明翻炒 20 秒。",
                              sourceIDs: [current, "step:1|翻炒 20 秒"]),
            for: prompt
        ))
        XCTAssertThrowsError(try StepGuidanceValidator.validate(
            StepGuidanceDraft(status: .answer, answer: "保持中火。", sourceIDs: ["step:unknown"]),
            for: prompt
        )) {
            XCTAssertEqual($0 as? StepGuidanceError, .unknownSource)
        }
        XCTAssertThrowsError(try StepGuidanceValidator.validate(
            StepGuidanceDraft(status: .answer, answer: "保持中火。", sourceIDs: ["recipe:name"]),
            for: prompt
        )) {
            XCTAssertEqual($0 as? StepGuidanceError, .unknownSource)
        }
    }

    func testResponseMetadataMustMatchTheExactContextRevision() throws {
        let firstPrompt = try prompt()
        let valid = StepGuidanceResponse(
            draft: StepGuidanceDraft(status: .answer, answer: "热锅后加入原文的食用油。",
                                     sourceIDs: [firstPrompt.currentStepSourceID]),
            for: firstPrompt
        )
        XCTAssertNoThrow(try StepGuidanceValidator.validate(valid, for: firstPrompt))
        let stale = StepGuidanceResponse(
            draft: StepGuidanceDraft(status: valid.status, answer: valid.answer, sourceIDs: valid.sourceIDs),
            for: firstPrompt
        )
        let otherPrompt = try prompt(stepIndex: 2)
        XCTAssertThrowsError(try StepGuidanceValidator.validate(stale, for: otherPrompt)) {
            XCTAssertEqual($0 as? StepGuidanceError, .staleResponse)
        }
    }

    func testNumbersAndTemperaturesCannotBeAddedBeyondCitedSource() throws {
        let prompt = try prompt()
        let current = prompt.currentStepSourceID
        XCTAssertNoThrow(try StepGuidanceValidator.validate(
            StepGuidanceDraft(status: .answer, answer: "冷锅时先加入 4ml 食用油，再开火。", sourceIDs: [current]),
            for: prompt
        ))
        for answer in ["改成 8ml 食用油。", "大火加热两分钟。", "油温到 180℃。", "先做第 2 步。"] {
            XCTAssertThrowsError(try StepGuidanceValidator.validate(
                StepGuidanceDraft(status: .answer, answer: answer, sourceIDs: [current]),
                for: prompt
            )) {
                XCTAssertEqual($0 as? StepGuidanceError, .unsafeNumbers, answer)
            }
        }
    }

    func testSourceDoesNotSayIsAValidAnswerWhenItStillCitesCurrentStep() throws {
        let prompt = try prompt(kind: .question, question: "需要放多少辣椒？")
        let answer = StepGuidanceDraft(
            status: .sourceDoesNotSay,
            answer: "原方没有写辣椒用量，请按原方确认或不要自行添加。",
            sourceIDs: [prompt.currentStepSourceID]
        )
        XCTAssertNoThrow(try StepGuidanceValidator.validate(answer, for: prompt))
    }

    func testNumericExpressionsKeepTheirCompleteValueAndUnit() throws {
        struct NumericCase: Decodable {
            let name: String
            let sources: [String]
            let answer: String
            let allowed: Bool
        }
        let url = SharedFixtureTests.dataDirectory.appendingPathComponent("fixtures/step-guidance-numbers.json")
        let cases = try JSONDecoder().decode([NumericCase].self, from: Data(contentsOf: url))
        for item in cases {
            let text = RecipeText(blocks: [
                .heading("操作"),
                .list(ordered: true, start: 1, items: item.sources),
            ])
            let presentation = RecipePresentation(text: text)
            let prompt = try StepGuidancePrompt(
                recipe: recipe, text: text, dataVersion: "fixture-v1",
                step: XCTUnwrap(presentation.steps.first),
                servings: 2, kind: .explain, question: nil
            )
            let draft = StepGuidanceDraft(
                status: .answer, answer: item.answer,
                sourceIDs: presentation.steps.map { "step:\($0.id)" }
            )
            if item.allowed {
                XCTAssertNoThrow(try StepGuidanceValidator.validate(draft, for: prompt), item.name)
            } else {
                XCTAssertThrowsError(try StepGuidanceValidator.validate(draft, for: prompt), item.name) {
                    XCTAssertEqual($0 as? StepGuidanceError, .unsafeNumbers, item.name)
                }
            }
        }
    }

    func testStrictDraftDecodingRejectsUnknownFieldsAndMalformedCitations() throws {
        let prompt = try prompt()
        let current = prompt.currentStepSourceID
        let valid: [String: Any] = [
            "status": "answer",
            "answer": "按原文操作。",
            "sourceIDs": [current],
        ]
        XCTAssertNoThrow(try StepGuidanceValidator.decodeDraft(
            JSONSerialization.data(withJSONObject: valid),
            for: prompt
        ))
        var unknown = valid
        unknown["actions"] = ["completeStep"]
        XCTAssertThrowsError(try StepGuidanceValidator.decodeDraft(
            JSONSerialization.data(withJSONObject: unknown),
            for: prompt
        )) {
            XCTAssertEqual($0 as? StepGuidanceError, .invalidResponse)
        }
    }
}
