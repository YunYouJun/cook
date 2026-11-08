import CookCore
import Foundation
import XCTest
@testable import Cook

private actor StepGuidanceTransportStub {
    var requests: [URLRequest] = []
    let status: Int
    let body: Data
    let failure: URLError.Code?
    let responseURL: URL

    init(status: Int = 200, body: Data = Data(), failure: URLError.Code? = nil,
         responseURL: URL = DeepSeekStepGuidanceService.endpoint) {
        self.status = status
        self.body = body
        self.failure = failure
        self.responseURL = responseURL
    }

    func send(_ request: URLRequest) throws -> (Data, URLResponse) {
        requests.append(request)
        if let failure { throw URLError(failure) }
        return (body, HTTPURLResponse(url: responseURL, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}

@MainActor
final class StepGuidanceServiceTests: XCTestCase {
    private let key = "sk-test-guidance-not-a-real-key"
    private let managedEndpoint = URL(string: "https://api.yunle.fun/cook-ai-guidance")!
    private let token = "test-yunlefun-guidance-bearer"

    private func text() -> RecipeText {
        RecipeText(blocks: [
            .heading("必备原料和工具"),
            .list(ordered: false, start: 1, items: ["鸡蛋 2 个", "炒锅"]),
            .heading("操作"),
            .list(ordered: true, start: 1, items: ["热锅，加入食用油", "翻炒 20 秒"]),
        ])
    }

    private func prompt(kind: StepGuidanceKind = .explain, question: String? = nil,
                        stepIndex: Int = 0) throws -> StepGuidancePrompt {
        let text = text()
        return try StepGuidancePrompt(
            recipe: Recipe(id: 42, name: "炒蛋", stuff: ["鸡蛋"]),
            text: text,
            dataVersion: "353bc8dab0ea",
            step: RecipePresentation(text: text).steps[stepIndex],
            servings: 2,
            kind: kind,
            question: question,
            requestID: "11111111-1111-4111-8111-111111111111",
            contextRevision: "22222222-2222-4222-8222-222222222222"
        )
    }

    private func credential() -> YunlefunCredential {
        YunlefunCredential(accessToken: token, refreshToken: "not-used",
                           subject: "test-subject", expiresAt: Date().addingTimeInterval(3_600),
                           deviceID: "test-device")
    }

    private func deepSeekEnvelope(status: String = "answer", answer: String = "先把锅烧热。",
                                  sourceIDs: [String]) throws -> Data {
        let content = try JSONSerialization.data(withJSONObject: [
            "status": status,
            "answer": answer,
            "sourceIDs": sourceIDs,
        ])
        return try JSONSerialization.data(withJSONObject: ["choices": [[
            "finish_reason": "stop",
            "message": ["role": "assistant", "content": String(decoding: content, as: UTF8.self)],
        ]]])
    }

    private func managedCapability(enabled: Bool = true) throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "ok": true,
            "serviceVersion": 1,
            "enabled": enabled,
            "membership": [
                "isActive": true,
                "expireAt": Date().addingTimeInterval(86_400).timeIntervalSince1970 * 1_000,
            ],
        ])
    }

    private func managedResponse(for prompt: StepGuidancePrompt) throws -> Data {
        let response = StepGuidanceResponse(
            draft: StepGuidanceDraft(
                status: .answer,
                answer: "先用中火把锅烧热，再按原文加入食用油。",
                sourceIDs: [prompt.currentStepSourceID]
            ),
            for: prompt
        )
        let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(response))
        return try JSONSerialization.data(withJSONObject: ["ok": true, "guidance": object])
    }

    func testBYOKRequestUsesFixedEndpointAndSendsOnlyPromptAndQuestion() async throws {
        let prompt = try prompt(kind: .question, question: "怎样判断锅热了？")
        let transport = StepGuidanceTransportStub(body: try deepSeekEnvelope(
            answer: "把锅烧热即可。", sourceIDs: [prompt.currentStepSourceID]
        ))
        let response = try await DeepSeekStepGuidanceService(transport: {
            try await transport.send($0)
        }).complete(prompt, key: key)
        XCTAssertEqual(response.requestID, prompt.request.requestID)

        let requests = await transport.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(request.url, DeepSeekStepGuidanceService.endpoint)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(key)")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Cache-Control"), "no-store")
        let body = try XCTUnwrap(request.httpBody)
        let raw = String(decoding: body, as: UTF8.self)
        XCTAssertFalse(raw.contains(key))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["model", "thinking", "response_format", "max_tokens", "stream", "messages"])
        XCTAssertEqual(json["model"] as? String, "deepseek-flash")
        let messages = try XCTUnwrap(json["messages"] as? [[String: String]])
        XCTAssertEqual(messages.count, 2)
        XCTAssertTrue(messages[0]["content"]?.contains("untrusted data") == true)
        XCTAssertTrue(messages[0]["content"]?.contains(prompt.currentStepSourceID) == true)
        XCTAssertEqual(messages[1], ["role": "user", "content": "怎样判断锅热了？"])
    }

    func testBYOKRejectsUnrelatedCitationsUnsafeNumbersAndStaleMetadata() async throws {
        let prompt = try prompt()
        for envelope in [
            try deepSeekEnvelope(answer: "先烧热锅。", sourceIDs: ["step:unknown"]),
            try deepSeekEnvelope(answer: "改成 8ml 油。", sourceIDs: [prompt.currentStepSourceID]),
        ] {
            let transport = StepGuidanceTransportStub(body: envelope)
            do {
                _ = try await DeepSeekStepGuidanceService(transport: {
                    try await transport.send($0)
                }).complete(prompt, key: key)
                XCTFail("Unsafe guidance accepted")
            } catch {
                XCTAssertTrue(error is StepGuidanceError)
            }
        }
    }

    func testManagedCapabilityIsIndependentAndStrict() async throws {
        let transport = StepGuidanceTransportStub(
            body: try managedCapability(),
            responseURL: managedEndpoint
        )
        let service = ManagedStepGuidanceService(endpoint: managedEndpoint, transport: {
            try await transport.send($0)
        })
        let capability = try await service.capabilities(credential: credential())
        XCTAssertTrue(capability.enabled)
        let requests = await transport.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.url, managedEndpoint)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(token)")
        let body = try XCTUnwrap(request.httpBody)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: body) as? [String: String],
                       ["action": "capabilities"])

        let disabled = StepGuidanceTransportStub(
            body: try managedCapability(enabled: false),
            responseURL: managedEndpoint
        )
        let disabledService = ManagedStepGuidanceService(endpoint: managedEndpoint, transport: {
            try await disabled.send($0)
        })
        let disabledCapability = try await disabledService.capabilities(credential: credential())
        XCTAssertFalse(disabledCapability.enabled)
        XCTAssertTrue(disabledCapability.membershipIsActive)
    }

    func testManagedRequestSendsNoRecipeTextOrIdentityClaimsAndReturnsValidatedSources() async throws {
        let prompt = try prompt(kind: .question, question: "是不是要一直翻炒？")
        let transport = StepGuidanceTransportStub(
            body: try managedResponse(for: prompt),
            responseURL: managedEndpoint
        )
        let service = ManagedStepGuidanceService(endpoint: managedEndpoint, transport: {
            try await transport.send($0)
        })
        let response = try await service.complete(prompt, credential: credential())
        XCTAssertEqual(response.stepID, prompt.request.stepID)
        let requests = await transport.requests
        let body = try XCTUnwrap(requests.first?.httpBody)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(Set(json.keys), [
            "action", "schemaVersion", "policyVersion", "dataVersion", "sourceFingerprint",
            "recipeID", "stepID", "servings", "kind", "requestID", "contextRevision", "question",
        ])
        XCTAssertEqual(json["action"] as? String, "guidance")
        XCTAssertEqual(json["question"] as? String, "是不是要一直翻炒？")
        let raw = String(decoding: body, as: UTF8.self)
        for forbidden in [prompt.recipeName, token, "userId", "member", "apiKey", "ingredients", "sources"] {
            XCTAssertFalse(raw.contains(forbidden), forbidden)
        }
    }

    func testManagedUndeployedAndSourceMismatchFailClosedWithoutRetry() async throws {
        for (status, code, expected) in [
            (404, nil, StepGuidanceError.notDeployed),
            (409, "SOURCE_MISMATCH", .sourceMismatch),
            (403, "MEMBERSHIP_REQUIRED", .membershipRequired),
            (429, "BUDGET_EXHAUSTED", .budgetExhausted),
        ] as [(Int, String?, StepGuidanceError)] {
            let body = try JSONSerialization.data(withJSONObject: ["ok": false, "code": code as Any])
            let transport = StepGuidanceTransportStub(status: status, body: body, responseURL: managedEndpoint)
            let service = ManagedStepGuidanceService(endpoint: managedEndpoint, transport: {
                try await transport.send($0)
            })
            do {
                _ = try await service.capabilities(credential: credential())
                XCTFail("Managed error accepted")
            } catch {
                XCTAssertEqual(error as? StepGuidanceError, expected)
            }
            let requests = await transport.requests
            XCTAssertEqual(requests.count, 1)
        }
    }

    func testGuidanceEndpointsCannotBeRedirectedOrConfiguredOnAnotherHost() async throws {
        for raw in [
            "http://api.yunle.fun/cook-ai-guidance",
            "https://api.yunle.fun/cook-ai-intent",
            "https://other.example/cook-ai-guidance",
            "https://api.yunle.fun/cook-ai-guidance?token=x",
        ] {
            let service = ManagedStepGuidanceService(endpoint: URL(string: raw)!)
            XCTAssertFalse(service.isConfigured, raw)
        }
        let transport = StepGuidanceTransportStub(
            body: try managedCapability(),
            responseURL: URL(string: "https://other.example/cook-ai-guidance")!
        )
        let service = ManagedStepGuidanceService(endpoint: managedEndpoint, transport: {
            try await transport.send($0)
        })
        do {
            _ = try await service.capabilities(credential: credential())
            XCTFail("Redirect-like response accepted")
        } catch {
            XCTAssertEqual(error as? StepGuidanceError, .invalidResponse)
        }
    }
}
