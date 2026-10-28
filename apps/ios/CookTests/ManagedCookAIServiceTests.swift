import CookCore
import Foundation
import XCTest
@testable import Cook

private actor ManagedAITransportStub {
    var requests: [URLRequest] = []
    let status: Int
    let body: Data
    let failure: URLError.Code?
    let responseURL: URL

    init(status: Int = 200, body: Data = Data(), failure: URLError.Code? = nil,
         responseURL: URL = URL(string: "https://api.yunle.fun/cook-ai-intent")!) {
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
final class ManagedCookAIServiceTests: XCTestCase {
    private let endpoint = URL(string: "https://api.yunle.fun/cook-ai-intent")!
    private let goals: [DietaryGoal] = [.lessAddedSugar, .balancedPairing]
    private let accessToken = "test-yunlefun-bearer-not-a-live-token"

    private func credential(token: String? = nil, expiresAt: Date = Date().addingTimeInterval(3_600)) -> YunlefunCredential {
        YunlefunCredential(accessToken: token ?? accessToken, refreshToken: "test-refresh-not-for-ai-service",
                           subject: "test-account-subject", expiresAt: expiresAt, deviceID: "test-device-id")
    }

    private func data() throws -> CookData { try RecipeStore.loadBundled(inMemory: true).data }

    private func service(_ transport: ManagedAITransportStub, endpoint: URL? = nil) -> ManagedCookAIService {
        ManagedCookAIService(endpoint: endpoint ?? self.endpoint, transport: { try await transport.send($0) })
    }

    private func capability(enabled: Bool = true, active: Bool = true, expireAt: Double? = Date().addingTimeInterval(86_400).timeIntervalSince1970 * 1_000,
                            version: Int = 1, extra: [String: Any] = [:]) throws -> Data {
        var membership: [String: Any] = ["isActive": active]
        if let expireAt { membership["expireAt"] = expireAt }
        var body: [String: Any] = ["ok": true, "serviceVersion": version, "enabled": enabled, "membership": membership]
        body.merge(extra) { _, new in new }
        return try JSONSerialization.data(withJSONObject: body)
    }

    private func intent(_ data: CookData, change: (inout SearchIntent) -> Void = { _ in }) -> SearchIntent {
        var query = CookQuery()
        query.stuff = ["鸡蛋"]
        var result = SearchIntent(dataVersion: data.meta.dataVersion, source: .deepseek, query: query)
        change(&result)
        return result
    }

    private func envelope(_ intent: SearchIntent) throws -> Data {
        let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(intent))
        return try JSONSerialization.data(withJSONObject: ["ok": true, "intent": object])
    }

    private func assertCapabilityFailure(_ transport: ManagedAITransportStub, expected: ManagedCookAIError,
                                         credential: YunlefunCredential? = nil,
                                         file: StaticString = #filePath, line: UInt = #line) async {
        do {
            _ = try await service(transport).capabilities(credential: credential ?? self.credential())
            XCTFail("Unexpected capability success", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? ManagedCookAIError, expected, file: file, line: line)
            XCTAssertFalse(error.localizedDescription.contains(accessToken), file: file, line: line)
        }
    }

    func testCapabilitiesUsesAuthenticatedAccountWithoutClientMembershipClaims() async throws {
        let expiration = Date().addingTimeInterval(86_400)
        let transport = ManagedAITransportStub(body: try capability(expireAt: expiration.timeIntervalSince1970 * 1_000))
        let result = try await service(transport).capabilities(credential: credential())
        XCTAssertTrue(result.enabled)
        XCTAssertTrue(result.membershipIsActive)
        XCTAssertEqual(try XCTUnwrap(result.membershipExpiresAt).timeIntervalSince1970, expiration.timeIntervalSince1970, accuracy: 0.001)
        let requests = await transport.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(request.url, endpoint)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(accessToken)")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Cache-Control"), "no-store")
        XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        let body = try XCTUnwrap(request.httpBody)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: body) as? [String: String], ["action": "capabilities"])
        let text = String(decoding: body, as: UTF8.self)
        for privateValue in [accessToken, "test-refresh-not-for-ai-service", "test-account-subject", "test-device-id"] {
            XCTAssertFalse(text.contains(privateValue))
        }
    }

    func testInactiveAndDisabledCapabilitiesRemainDistinctFromUsableService() async throws {
        for (active, expireAt) in [(false, Optional<Double>.none), (true, Date().addingTimeInterval(3_600).timeIntervalSince1970 * 1_000),
                                   (false, Date().addingTimeInterval(-3_600).timeIntervalSince1970 * 1_000)] {
            let transport = ManagedAITransportStub(body: try capability(enabled: false, active: active, expireAt: expireAt))
            let result = try await service(transport).capabilities(credential: credential())
            XCTAssertFalse(result.enabled)
            XCTAssertEqual(result.membershipIsActive, active)
        }
    }

    func testMalformedStaleExpiredAndContradictoryCapabilityResponsesFailClosed() async throws {
        for body in [Data("invalid-json".utf8), Data("[]".utf8), try capability(version: 2),
                     try capability(enabled: true, active: false), try capability(expireAt: nil),
                     try capability(expireAt: Date().addingTimeInterval(-60).timeIntervalSince1970 * 1_000),
                     try capability(extra: ["uid": "client-claim"]),
                     try capability(extra: ["serviceVersion": true]),
                     try capability(extra: ["enabled": 1]),
                     try capability(extra: ["ok": false]), Data(repeating: 32, count: 65_537)] {
            await assertCapabilityFailure(ManagedAITransportStub(body: body), expected: .invalidResponse)
        }
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: capability()) as? [String: Any])
        json["membership"] = ["isActive": true, "expireAt": "2099-01-01"]
        await assertCapabilityFailure(ManagedAITransportStub(body: try JSONSerialization.data(withJSONObject: json)), expected: .invalidResponse)
        json["membership"] = ["isActive": true, "expireAt": Date().addingTimeInterval(3_600).timeIntervalSince1970 * 1_000, "uid": "client-claim"]
        await assertCapabilityFailure(ManagedAITransportStub(body: try JSONSerialization.data(withJSONObject: json)), expected: .invalidResponse)
    }

    func testOnlyTrustedHTTPSBusinessEndpointsCanBeConfigured() async throws {
        let transport = ManagedAITransportStub()
        for raw in ["http://api.yunle.fun/cook-ai", "https://other.example/cook-ai", "https://api.yunle.fun.evil.example/cook-ai",
                    "https://user:password@api.yunle.fun/cook-ai", "https://api.yunle.fun/cook-ai-intent?token=x",
                    "https://api.yunle.fun/cook-ai-intent#fragment", "https://api.yunle.fun",
                    "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/v1/functions/cook-ai-intent"] {
            let invalid = service(transport, endpoint: try XCTUnwrap(URL(string: raw)))
            XCTAssertFalse(invalid.isConfigured, raw)
            do {
                _ = try await invalid.capabilities(credential: credential())
                XCTFail("Untrusted endpoint accepted: \(raw)")
            } catch { XCTAssertEqual(error as? ManagedCookAIError, .unconfigured, raw) }
        }
        XCTAssertTrue(service(transport).isConfigured)
        let httpGateway = URL(string: "https://yunlefun-8g7ybcxc7345c490.ap-shanghai.app.tcloudbase.com/cook-ai-intent")!
        XCTAssertFalse(service(transport, endpoint: httpGateway).isConfigured)
        XCTAssertFalse(service(transport, endpoint: httpGateway.appendingPathComponent("other")).isConfigured)
        let requests = await transport.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testExpiredEmptyAndHeaderUnsafeCredentialsNeverReachTransport() async throws {
        let transport = ManagedAITransportStub()
        for invalid in [credential(token: ""), credential(token: "bad\nheader"), credential(expiresAt: Date().addingTimeInterval(-1))] {
            await assertCapabilityFailure(transport, expected: .loginRequired, credential: invalid)
        }
        let requests = await transport.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testParseSendsOnlyCurrentTextAndVersionContractThenValidatesIntent() async throws {
        let data = try data()
        let expected = intent(data)
        let transport = ManagedAITransportStub(body: try envelope(expected))
        let result = try await service(transport).parse(text: "鸡蛋", credential: credential(), data: data, availableGoals: goals)
        XCTAssertEqual(result, expected)
        let requests = await transport.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(accessToken)")
        let body = try XCTUnwrap(request.httpBody)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["action", "schemaVersion", "policyVersion", "dataVersion", "text"])
        XCTAssertEqual(json["action"] as? String, "parse")
        XCTAssertEqual(json["text"] as? String, "鸡蛋")
        XCTAssertEqual(json["dataVersion"] as? String, data.meta.dataVersion)
        XCTAssertEqual(json["schemaVersion"] as? Int, SearchIntent.currentSchemaVersion)
        XCTAssertEqual(json["policyVersion"] as? Int, SearchIntent.currentPolicyVersion)
        for forbidden in ["uid", "member", "membership", "apiKey", "refreshToken", "recipes", "history", "profile"] { XCTAssertNil(json[forbidden]) }
        XCTAssertFalse(String(decoding: body, as: UTF8.self).contains(accessToken))
    }

    func testManagedResultsPreserveEveryLocalRestrictionWithoutBroadeningORFilters() async throws {
        let data = try data()
        var query = CookQuery()
        query.methodGroups = Recommender.methodGroups.map(\.label)
        query.difficulty = Recommender.difficultyLevels
        let remote = SearchIntent(dataVersion: data.meta.dataVersion, source: .deepseek, query: query)
        let transport = ManagedAITransportStub(body: try envelope(remote))
        let text = "鸡蛋，不要洋葱，不吃辣，用电饭煲，蒸，简单点，减少添加糖，孕期怎么吃"
        let local = LocalSearchIntentParser(data: data, availableGoals: goals).parse(text)
        let result = try await service(transport).parse(text: text, credential: credential(), data: data, availableGoals: goals)
        XCTAssertEqual(result.query.stuff, local.query.stuff)
        XCTAssertEqual(result.query.avoid, local.query.avoid)
        XCTAssertEqual(result.query.noSpicy, local.query.noSpicy)
        XCTAssertEqual(result.query.tool, local.query.tool)
        XCTAssertEqual(result.query.methodGroups, local.query.methodGroups)
        XCTAssertEqual(result.query.difficulty, local.query.difficulty)
        XCTAssertEqual(result.goal, local.goal)
        XCTAssertEqual(result.unresolved, local.unresolved)
    }

    func testUnknownStaleWrongSourceAndConflictingRemoteIntentsAreRejected() async throws {
        let data = try data()
        let valid = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(intent(data))) as? [String: Any])
        var variants: [[String: Any]] = []
        for (key, value) in [("dataVersion", "stale"), ("source", "mock"), ("goal", "weight-aware")] {
            var changed = valid
            changed[key] = value
            variants.append(changed)
        }
        var unknown = valid
        unknown["recipeIds"] = [333]
        variants.append(unknown)
        for object in variants {
            let body = try JSONSerialization.data(withJSONObject: ["ok": true, "intent": object])
            let transport = ManagedAITransportStub(body: body)
            do {
                _ = try await service(transport).parse(text: "鸡蛋", credential: credential(), data: data, availableGoals: goals)
                XCTFail("Unsafe intent accepted")
            } catch { XCTAssertTrue(error is SearchIntentError) }
        }
        let conflict = intent(data) { $0.query.stuff.append("洋葱") }
        let transport = ManagedAITransportStub(body: try envelope(conflict))
        do {
            _ = try await service(transport).parse(text: "鸡蛋，不要洋葱", credential: credential(), data: data, availableGoals: goals)
            XCTFail("Local exclusion was relaxed")
        } catch { XCTAssertEqual(error as? SearchIntentError, .conflictingConditions) }
    }

    func testUnsupportedOrNegatedGoalCannotBecomeASupportedGoal() async throws {
        let data = try data()
        let remote = intent(data) { $0.goal = .balancedPairing }
        for text in ["鸡蛋，孕期怎么吃", "鸡蛋，控制体重", "鸡蛋，不需要均衡搭配", "鸡蛋，减少添加糖和均衡搭配"] {
            let transport = ManagedAITransportStub(body: try envelope(remote))
            do {
                _ = try await service(transport).parse(text: text, credential: credential(), data: data, availableGoals: goals)
                XCTFail("Unsupported goal mapping accepted: \(text)")
            } catch { XCTAssertEqual(error as? ManagedCookAIError, .invalidResponse) }
        }
    }

    func testBackendErrorsAreBoundedFriendlyAndNeverReflectSensitiveBody() async throws {
        let cases: [(Int, String, ManagedCookAIError)] = [
            (401, "LOGIN_REQUIRED", .loginRequired), (403, "MEMBERSHIP_REQUIRED", .membershipRequired),
            (403, "ACCOUNT_UNAVAILABLE", .accountUnavailable), (409, "VERSION_MISMATCH", .versionMismatch),
            (429, "RATE_LIMITED", .rateLimited), (429, "BUDGET_EXHAUSTED", .budgetExhausted),
            (503, "SERVICE_UNAVAILABLE", .unavailable), (502, "INVALID_MODEL_RESPONSE", .invalidResponse),
            (504, "MODEL_TIMEOUT", .timedOut),
        ]
        for (status, code, expected) in cases {
            let body = try JSONSerialization.data(withJSONObject: ["ok": false, "code": code, "message": accessToken])
            let transport = ManagedAITransportStub(status: status, body: body)
            await assertCapabilityFailure(transport, expected: expected)
            let requests = await transport.requests
            XCTAssertEqual(requests.count, 1)
        }
        await assertCapabilityFailure(ManagedAITransportStub(status: 418), expected: .invalidResponse)
        await assertCapabilityFailure(ManagedAITransportStub(status: 500), expected: .unavailable)
        await assertCapabilityFailure(ManagedAITransportStub(status: 302), expected: .invalidResponse)
        await assertCapabilityFailure(ManagedAITransportStub(body: try capability(), responseURL: URL(string: "https://other.example/cook-ai")!), expected: .invalidResponse)
    }

    func testTimeoutOfflineAndCancellationRetainTheirMeaning() async throws {
        await assertCapabilityFailure(ManagedAITransportStub(failure: .timedOut), expected: .timedOut)
        await assertCapabilityFailure(ManagedAITransportStub(failure: .notConnectedToInternet), expected: .offline)
        do {
            _ = try await service(ManagedAITransportStub(failure: .cancelled)).capabilities(credential: credential())
            XCTFail("Cancellation was swallowed")
        } catch { XCTAssertTrue(error is CancellationError) }
        let transport = ManagedAITransportStub()
        let task = Task { try await self.service(transport).capabilities(credential: self.credential()) }
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("Cancelled request accepted")
        } catch { XCTAssertTrue(error is CancellationError) }
        let requests = await transport.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testInvalidInputDoesNotSendParseRequest() async throws {
        let data = try data()
        let transport = ManagedAITransportStub()
        for text in ["", " \n", String(repeating: "字", count: 1_001)] {
            do {
                _ = try await service(transport).parse(text: text, credential: credential(), data: data, availableGoals: goals)
                XCTFail("Invalid input accepted")
            } catch { XCTAssertEqual(error as? ManagedCookAIError, .invalidInput) }
        }
        let requests = await transport.requests
        XCTAssertTrue(requests.isEmpty)
    }
}
