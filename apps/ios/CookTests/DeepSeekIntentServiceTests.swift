import CookCore
import Foundation
import XCTest
@testable import Cook

private actor DeepSeekTransportStub {
    var requests: [URLRequest] = []
    let status: Int
    let body: Data
    let failure: URLError.Code?
    let responseURL: URL

    init(status: Int = 200, body: Data = Data(), failure: URLError.Code? = nil,
         responseURL: URL = DeepSeekIntentService.endpoint) {
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
final class DeepSeekIntentServiceTests: XCTestCase {
    private let testKey = "sk-test-not-a-real-deepseek-key"
    private let availableGoals: [DietaryGoal] = [.lessAddedSugar, .balancedPairing]

    private func data() throws -> CookData { try RecipeStore.loadBundled(inMemory: true).data }

    private func draft(_ data: CookData, change: (inout SearchIntent) -> Void = { _ in }) -> SearchIntent {
        var query = CookQuery()
        query.stuff = ["鸡蛋"]
        var intent = SearchIntent(dataVersion: data.meta.dataVersion, source: .deepseek, query: query)
        change(&intent)
        return intent
    }

    private func envelope(_ content: Data, finishReason: String = "stop", role: String = "assistant") throws -> Data {
        try JSONSerialization.data(withJSONObject: ["choices": [[
            "finish_reason": finishReason,
            "message": ["role": role, "content": String(decoding: content, as: UTF8.self)],
        ]]])
    }

    private func service(_ transport: DeepSeekTransportStub) -> DeepSeekIntentService {
        DeepSeekIntentService(transport: { try await transport.send($0) })
    }

    private func assertFailure(_ transport: DeepSeekTransportStub, expected: DeepSeekIntentError,
                               text: String = "鸡蛋", data: CookData,
                               file: StaticString = #filePath, line: UInt = #line) async {
        do {
            _ = try await service(transport).parse(text: text, key: testKey, data: data, availableGoals: availableGoals)
            XCTFail("Unexpected success", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? DeepSeekIntentError, expected, file: file, line: line)
            XCTAssertFalse(error.localizedDescription.contains(testKey), file: file, line: line)
        }
    }

    func testRequestUsesFixedEndpointCurrentPublicContractAndOnlyCurrentText() async throws {
        let data = try data()
        let expected = draft(data)
        let transport = DeepSeekTransportStub(body: try envelope(JSONEncoder().encode(expected)))
        let result = try await service(transport).parse(text: "鸡蛋", key: testKey, data: data, availableGoals: availableGoals)
        XCTAssertEqual(result, expected)
        let requests = await transport.requests
        XCTAssertEqual(requests.count, 1)
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.url, DeepSeekIntentService.endpoint)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(testKey)")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Cache-Control"), "no-store")
        XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        XCTAssertEqual(request.timeoutInterval, 20)
        let body = try XCTUnwrap(request.httpBody)
        XCTAssertFalse(String(decoding: body, as: UTF8.self).contains(testKey))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["model", "thinking", "response_format", "max_tokens", "stream", "messages"])
        XCTAssertEqual(json["model"] as? String, "deepseek-flash")
        XCTAssertEqual(json["thinking"] as? [String: String], ["type": "disabled"])
        XCTAssertEqual(json["response_format"] as? [String: String], ["type": "json_object"])
        XCTAssertEqual(json["max_tokens"] as? Int, 2_048)
        XCTAssertEqual(json["stream"] as? Bool, false)
        let messages = try XCTUnwrap(json["messages"] as? [[String: String]])
        XCTAssertEqual(messages.count, 2)
        XCTAssertEqual(messages[1], ["role": "user", "content": "鸡蛋"])
        let system = try XCTUnwrap(messages[0]["content"])
        XCTAssertTrue(system.contains("json"))
        XCTAssertTrue(system.contains(data.meta.dataVersion))
        XCTAssertTrue(system.contains("less-added-sugar"))
        XCTAssertFalse(system.contains("weight-aware"))
    }

    func testModelOmissionsCannotRemoveLocalAvoidNoSpicyOrUnsupportedRequests() async throws {
        let data = try data()
        let transport = DeepSeekTransportStub(body: try envelope(JSONEncoder().encode(draft(data))))
        let result = try await service(transport).parse(text: "鸡蛋，不要洋葱，不吃辣，孕期怎么吃", key: testKey,
                                                       data: data, availableGoals: availableGoals)
        XCTAssertEqual(result.query.stuff, ["鸡蛋"])
        XCTAssertEqual(result.query.avoid, ["洋葱"])
        XCTAssertTrue(result.query.noSpicy)
        XCTAssertTrue(result.unresolved.contains { $0.contains("孕期") })
        XCTAssertNil(result.goal)
    }

    func testRemoteIngredientThatConflictsWithLocalAvoidIsRejected() async throws {
        let data = try data()
        let intent = draft(data) { $0.query.stuff = ["鸡蛋", "洋葱"] }
        let transport = DeepSeekTransportStub(body: try envelope(JSONEncoder().encode(intent)))
        do {
            _ = try await service(transport).parse(text: "鸡蛋，不要洋葱", key: testKey, data: data, availableGoals: availableGoals)
            XCTFail("Local exclusion was relaxed")
        } catch { XCTAssertEqual(error as? SearchIntentError, .conflictingConditions) }
    }

    func testUnsupportedHealthRequestCannotBecomeSupportedGoal() async throws {
        let data = try data()
        let intent = draft(data) { $0.goal = .balancedPairing }
        let transport = DeepSeekTransportStub(body: try envelope(JSONEncoder().encode(intent)))
        await assertFailure(transport, expected: .invalidResponse, text: "鸡蛋，孕期怎么吃", data: data)
    }

    func testRemoteOmissionsPreserveGoalToolIngredientsMethodsAndDifficultyWithoutBroadening() async throws {
        let data = try data()
        var empty = CookQuery()
        empty.difficulty = ["简单", "普通"]
        empty.methodGroups = Recommender.methodGroups.map(\.label)
        let remote = SearchIntent(dataVersion: data.meta.dataVersion, source: .deepseek, query: empty)
        let transport = DeepSeekTransportStub(body: try envelope(JSONEncoder().encode(remote)))
        let text = "鸡蛋，减少添加糖，用电饭煲，蒸，简单点"
        let local = LocalSearchIntentParser(data: data, availableGoals: availableGoals).parse(text)
        XCTAssertEqual(local.goal, .lessAddedSugar)
        XCTAssertEqual(local.query.tool, "电饭煲")
        XCTAssertFalse(local.query.methodGroups.isEmpty)
        XCTAssertFalse(local.query.difficulty.isEmpty)
        let result = try await service(transport).parse(text: text, key: testKey, data: data, availableGoals: availableGoals)
        XCTAssertEqual(result.goal, local.goal)
        XCTAssertEqual(result.query.tool, local.query.tool)
        XCTAssertEqual(result.query.stuff, local.query.stuff)
        XCTAssertEqual(result.query.methodGroups, local.query.methodGroups)
        XCTAssertEqual(result.query.difficulty, local.query.difficulty)
    }

    func testConflictingRemoteGoalOrToolAndNegatedGoalRequireRetry() async throws {
        let data = try data()
        for change: (inout SearchIntent) -> Void in [{ $0.goal = .balancedPairing }, { $0.query.tool = "微波炉" }] {
            let remote = draft(data, change: change)
            let transport = DeepSeekTransportStub(body: try envelope(JSONEncoder().encode(remote)))
            await assertFailure(transport, expected: .invalidResponse, text: "鸡蛋，减少添加糖，用电饭煲", data: data)
        }
        for text in ["鸡蛋，不要均衡搭配", "鸡蛋，减少添加糖和均衡搭配", "鸡蛋，控制体重"] {
            let remote = draft(data) { $0.goal = .balancedPairing }
            let transport = DeepSeekTransportStub(body: try envelope(JSONEncoder().encode(remote)))
            await assertFailure(transport, expected: .invalidResponse, text: text, data: data)
        }
    }

    func testStrictIntentValidationRejectsUnknownFieldsWrongSourceAndStaleVersion() async throws {
        let data = try data()
        var objects: [[String: Any]] = []
        let valid = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(draft(data))) as? [String: Any])
        var unknown = valid
        unknown["recipeIds"] = [1, 2]
        objects.append(unknown)
        var stale = valid
        stale["dataVersion"] = "stale"
        objects.append(stale)
        var wrongSource = valid
        wrongSource["source"] = "mock"
        objects.append(wrongSource)
        var invalidGoal = valid
        invalidGoal["goal"] = "weight-aware"
        objects.append(invalidGoal)
        for object in objects {
            let transport = DeepSeekTransportStub(body: try envelope(JSONSerialization.data(withJSONObject: object)))
            do {
                _ = try await service(transport).parse(text: "鸡蛋", key: testKey, data: data, availableGoals: availableGoals)
                XCTFail("Unsafe intent accepted")
            } catch { XCTAssertTrue(error is SearchIntentError) }
        }
    }

    func testIncompleteEmptyMalformedLargeAndRedirectedResponsesAreRejected() async throws {
        let data = try data()
        let content = try JSONEncoder().encode(draft(data))
        for body in [try envelope(content, finishReason: "length"), try envelope(content, role: "tool"),
                     try envelope(Data()), Data("not-json".utf8), Data(repeating: 32, count: 65_537)] {
            await assertFailure(DeepSeekTransportStub(body: body), expected: .invalidResponse, data: data)
        }
        await assertFailure(DeepSeekTransportStub(status: 302), expected: .unavailable, data: data)
        await assertFailure(DeepSeekTransportStub(body: try envelope(content), responseURL: URL(string: "https://other.example")!),
                            expected: .invalidResponse, data: data)
    }

    func testServiceErrorsUseFriendlyMessagesWithoutReflectingServerBodyOrRetrying() async throws {
        let data = try data()
        for (status, expected) in [(401, DeepSeekIntentError.authentication), (403, .authentication), (402, .insufficientBalance),
                                   (429, .rateLimited), (500, .unavailable), (503, .unavailable)] {
            let transport = DeepSeekTransportStub(status: status, body: Data("private request \(testKey)".utf8))
            await assertFailure(transport, expected: expected, data: data)
            let requests = await transport.requests
            XCTAssertEqual(requests.count, 1)
        }
        await assertFailure(DeepSeekTransportStub(failure: .timedOut), expected: .timedOut, data: data)
        await assertFailure(DeepSeekTransportStub(failure: .notConnectedToInternet), expected: .offline, data: data)
    }

    func testInvalidInputDoesNotReachTransportAndCancellationStaysCancellation() async throws {
        let data = try data()
        let transport = DeepSeekTransportStub()
        for text in ["", " \n", String(repeating: "鸡", count: 1_001)] {
            await assertFailure(transport, expected: .invalidInput, text: text, data: data)
        }
        do {
            _ = try await service(transport).parse(text: "鸡蛋", key: "bad\nheader", data: data, availableGoals: availableGoals)
            XCTFail("Invalid header accepted")
        } catch { XCTAssertEqual(error as? DeepSeekIntentError, .authentication) }
        let requests = await transport.requests
        XCTAssertEqual(requests.count, 0)
        let cancelled = DeepSeekTransportStub(failure: .cancelled)
        do {
            _ = try await service(cancelled).parse(text: "鸡蛋", key: testKey, data: data, availableGoals: availableGoals)
            XCTFail("Cancellation accepted")
        } catch { XCTAssertTrue(error is CancellationError) }
        let task = Task {
            try await self.service(transport).parse(text: "鸡蛋", key: self.testKey, data: data, availableGoals: self.availableGoals)
        }
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("Cancelled request accepted")
        } catch { XCTAssertTrue(error is CancellationError) }
    }
}
