import CookCore
import Foundation
import XCTest
@testable import Cook

/// 记下发出的请求，按需要返回云函数的结果
private final class FakeTransport: @unchecked Sendable {
    private let lock = NSLock()
    private var bodies: [[String: Any]] = []
    var status = 200
    var ok = true

    var sent: [[String: Any]] {
        lock.withLock { bodies }
    }

    func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        let body = try JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: Any] ?? [:]
        lock.withLock { bodies.append(body) }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        let reply: [String: Any] = ok ? ["ok": true] : ["ok": false, "code": "INVALID_REPORT"]
        let data = try JSONSerialization.data(withJSONObject: reply)
        return (data, response)
    }
}

@MainActor
final class FeedbackClientTests: XCTestCase {
    func testRecipeIssuesFollowAvailableSourcesAndRouteTestBuildsAwayFromProduction() {
        let text = Recipe(id: 708, name: "土豆炖排骨", link: "https://example.com/recipe", stuff: [])
        XCTAssertEqual(FeedbackClient.RecipeIssue.available(for: text), [.text, .stuff, .info, .other])
        var both = text
        both.bv = "BV1NE411Q7Jj"
        XCTAssertEqual(FeedbackClient.RecipeIssue.available(for: both), [.video, .text, .stuff, .info, .other])
        let production = URL(string: "https://api.yunle.fun/cook-feedback")!
        XCTAssertEqual(FeedbackClient.routedEndpoint(production, environment: "test")?.path, "/cook-feedback-test")
        XCTAssertEqual(FeedbackClient.routedEndpoint(production, environment: "production"), production)
    }

    func testTextReportCarriesSourceDetailsVersionAndContext() async throws {
        let transport = FakeTransport()
        let client = FeedbackClient(endpoint: endpoint, version: "2.0.0", environment: "test", build: "42", recipeVersion: "abc123", defaults: defaults, transport: { try await transport.send($0) })
        let recipe = Recipe(id: 708, name: "土豆炖排骨", link: "https://example.com/recipe", stuff: [])
        try await client.report(recipe, issue: .text, note: "一直加载", linkIssue: .loading)
        let body = try XCTUnwrap(transport.sent.first)
        XCTAssertEqual(body["sourceLink"] as? String, recipe.link)
        XCTAssertEqual(body["sourceBv"] as? String, "")
        XCTAssertEqual(body["kind"] as? String, "text")
        XCTAssertEqual(body["linkIssue"] as? String, "loading")
        XCTAssertEqual(body["environment"] as? String, "test")
        XCTAssertEqual(body["context"] as? [String: String], ["entryPoint": "recipe-detail", "origin": "", "path": "/recipe-detail", "build": "42", "recipeVersion": "abc123"])
    }
    private let endpoint = URL(string: "https://example.com/cook-feedback")!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: "FeedbackClientTests")
        defaults.removePersistentDomain(forName: "FeedbackClientTests")
    }

    private func makeClient(_ transport: FakeTransport, endpoint: URL? = nil, sendsSignals: Bool = true) -> FeedbackClient {
        FeedbackClient(
            endpoint: endpoint ?? self.endpoint,
            version: "2.0.0",
            sendsSignals: sendsSignals,
            defaults: defaults,
            transport: { try await transport.send($0) }
        )
    }

    func testSignalsAreSentOncePerSelection() async {
        let transport = FakeTransport()
        let client = makeClient(transport)
        client.setSignalConsent(true)

        await client.signalOpen(stuff: ["鸡蛋", "番茄"], tool: nil, mode: .loose, recipeId: 3, rank: 1)?.value
        XCTAssertNil(client.signalOpen(stuff: ["番茄", "鸡蛋"], tool: nil, mode: .loose, recipeId: 3, rank: 1), "顺序不同也算同一组")
        await client.signalEmpty(stuff: ["羊肉"], tool: "电饭煲", mode: .strict)?.value
        XCTAssertNil(client.signalEmpty(stuff: [], tool: nil, mode: .loose), "没选食材不报")

        let sent = transport.sent
        XCTAssertEqual(sent.count, 2)
        XCTAssertEqual(sent[0]["action"] as? String, "signal")
        XCTAssertEqual(sent[0]["type"] as? String, "open")
        XCTAssertEqual(sent[0]["recipeId"] as? Int, 3)
        XCTAssertEqual(sent[0]["rank"] as? Int, 1)
        XCTAssertEqual(sent[0]["deviceId"] as? String, client.reporter)
        XCTAssertEqual(sent[0]["platform"] as? String, "ios")
        XCTAssertEqual(sent[0]["version"] as? String, "2.0.0")
        XCTAssertEqual(sent[1]["type"] as? String, "empty")
        XCTAssertEqual(sent[1]["tool"] as? String, "电饭煲")
        XCTAssertEqual(sent[1]["mode"] as? String, "strict")
        XCTAssertEqual(sent[1]["platform"] as? String, "ios")
    }

    func testSignalsRespectTheSettingAndBuild() {
        let transport = FakeTransport()
        makeClient(transport).setSignalConsent(false)
        XCTAssertNil(makeClient(transport).signalEmpty(stuff: ["羊肉"], tool: nil, mode: .loose), "关掉「帮助改进推荐」后不报")

        makeClient(transport).setSignalConsent(true)
        XCTAssertNil(makeClient(transport, sendsSignals: false).signalEmpty(stuff: ["羊肉"], tool: nil, mode: .loose), "Debug 构建不报")
        XCTAssertTrue(transport.sent.isEmpty)
    }

    func testFreshInstallAndLegacySettingsDoNotGrantConsent() {
        let transport = FakeTransport()
        for legacy in [nil, true, false] as [Bool?] {
            defaults.removeObject(forKey: FeedbackClient.signalsKey)
            if let legacy { defaults.set(legacy, forKey: FeedbackClient.signalsKey) }
            let client = makeClient(transport)
            XCTAssertFalse(client.signalsEnabled)
            XCTAssertTrue(client.needsSignalConsent)
            XCTAssertNil(client.signalOpen(stuff: ["鸡蛋"], tool: nil, mode: .loose, recipeId: 3, rank: 0))
            XCTAssertNil(client.signalEmpty(stuff: ["鸡蛋"], tool: nil, mode: .loose))
        }
        XCTAssertTrue(transport.sent.isEmpty)
    }

    func testConsentSurvivesRelaunchAndDoesNotReplayEarlierActions() async {
        let transport = FakeTransport()
        let client = makeClient(transport)
        XCTAssertNil(client.signalEmpty(stuff: ["羊肉"], tool: nil, mode: .loose))
        client.setSignalConsent(true)
        XCTAssertTrue(transport.sent.isEmpty, "同意前的操作不补报")
        let relaunched = makeClient(transport)
        XCTAssertTrue(relaunched.signalsEnabled)
        XCTAssertFalse(relaunched.needsSignalConsent)
        await relaunched.signalEmpty(stuff: ["羊肉"], tool: nil, mode: .loose)?.value
        XCTAssertEqual(transport.sent.count, 1)

        relaunched.setSignalConsent(false)
        let declined = makeClient(transport)
        XCTAssertFalse(declined.signalsEnabled)
        XCTAssertFalse(declined.needsSignalConsent)
        XCTAssertNil(declined.signalEmpty(stuff: ["鸡蛋"], tool: nil, mode: .loose))
    }

    func testWithdrawingConsentCancelsQueuedSignals() async {
        let transport = FakeTransport()
        let client = makeClient(transport)
        client.setSignalConsent(true)
        let queued = client.signalEmpty(stuff: ["羊肉"], tool: nil, mode: .loose)
        client.setSignalConsent(false)
        await queued?.value
        XCTAssertTrue(transport.sent.isEmpty)
    }

    func testWithdrawingConsentCancelsInFlightSignals() async {
        let started = expectation(description: "请求已开始")
        let cancelled = expectation(description: "请求收到取消")
        let client = FeedbackClient(endpoint: endpoint, sendsSignals: true, defaults: defaults) { _ in
            started.fulfill()
            do {
                try await Task.sleep(for: .seconds(5))
                XCTFail("撤回同意后请求应被取消")
            } catch {
                cancelled.fulfill()
                throw error
            }
            throw URLError(.timedOut)
        }
        client.setSignalConsent(true)
        let task = client.signalEmpty(stuff: ["羊肉"], tool: nil, mode: .loose)
        await fulfillment(of: [started], timeout: 2)
        client.setSignalConsent(false)
        await task?.value
        await fulfillment(of: [cancelled], timeout: 1)
    }

    func testReporterIsRandomPerLaunch() {
        let transport = FakeTransport()
        XCTAssertNotEqual(makeClient(transport).reporter, makeClient(transport).reporter)
    }

    func testRecipeReportAndAppFeedback() async throws {
        let transport = FakeTransport()
        let client = makeClient(transport)
        let recipe = try RecipeStore.loadBundled().recipes[0]
        client.setSignalConsent(false)

        try await client.report(recipe, issue: .video, note: "  视频没了 ")
        try await client.send(.idea, note: "希望能按季节推荐", contact: " cook@example.com ")

        let sent = transport.sent
        XCTAssertEqual(sent[0]["action"] as? String, "report")
        XCTAssertEqual(sent[0]["recipeId"] as? Int, recipe.id)
        XCTAssertEqual(sent[0]["recipeName"] as? String, recipe.name)
        XCTAssertEqual(sent[0]["kind"] as? String, "video")
        XCTAssertEqual(sent[0]["note"] as? String, "视频没了")
        XCTAssertEqual(sent[0]["platform"] as? String, "ios")
        XCTAssertEqual(sent[0]["version"] as? String, "2.0.0")
        XCTAssertEqual(sent[1]["action"] as? String, "feedback")
        XCTAssertEqual(sent[1]["category"] as? String, "idea")
        XCTAssertEqual(sent[1]["contact"] as? String, "cook@example.com")
        XCTAssertEqual(sent[1]["platform"] as? String, "ios")
        XCTAssertEqual(sent[1]["version"] as? String, "2.0.0")
    }

    func testRejectedOrUnconfiguredSendsThrow() async {
        let transport = FakeTransport()
        transport.status = 400
        transport.ok = false
        do {
            try await makeClient(transport).send(.bug, note: "闪退", contact: "")
            XCTFail("云函数拒绝时应该报错")
        } catch {}

        let unconfigured = FeedbackClient(endpoint: nil, defaults: defaults, transport: { try await transport.send($0) })
        XCTAssertFalse(unconfigured.isConfigured)
        XCTAssertNil(unconfigured.signalEmpty(stuff: ["羊肉"], tool: nil, mode: .loose))
        do {
            try await unconfigured.send(.bug, note: "闪退", contact: "")
            XCTFail("没配置地址时应该报错")
        } catch {}
    }

    func testMailURLPrefillsSupportMailboxAndEscapesBody() throws {
        let url = FeedbackClient.mailURL(topic: "功能建议", lines: ["番茄 & 鸡蛋 + 葱", ""], version: "2.0.0")
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.scheme, "mailto")
        XCTAssertEqual(components.path, FeedbackClient.supportEmail)
        let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(query.count, 2, "正文里的 & 不能拆出多余的参数")
        XCTAssertEqual(query["subject"], "【云食册反馈】功能建议")
        XCTAssertEqual(query["body"], "番茄 & 鸡蛋 + 葱\n\n---\n来自 云食册（iOS 2.0.0）", "空行去掉，+ 不能变成空格")
    }

    func testTicketURLAcceptsOnlyMatchingPrivateSupportLinks() {
        let valid = "https://support.yunle.fun/tickets/ticket-one#access=private-capability"
        XCTAssertEqual(FeedbackClient.ticketURL(["ticketId": "ticket-one", "ticketUrl": valid])?.absoluteString, valid)
        for value in ["https://evil.example/tickets/ticket-one", "https://support.yunle.fun/tickets/other", "https://user@support.yunle.fun/tickets/ticket-one", "javascript:alert(1)", "https://support.yunle.fun/tickets/ticket-one?secret=value"] {
            XCTAssertNil(FeedbackClient.ticketURL(["ticketId": "ticket-one", "ticketUrl": value]))
        }
    }

    func testFeedbackReturnsTicketAndPreservesStableSubmissionKey() async throws {
        let url = "https://support.yunle.fun/tickets/ticket-one#access=private-capability"
        let client = FeedbackClient(endpoint: endpoint, defaults: defaults) { request in
            let body = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
            XCTAssertEqual(body["submissionId"] as? String, "stable-operation-key")
            let data = try JSONSerialization.data(withJSONObject: ["ok": true, "receipt": ["id": "feedback-one", "ticketId": "ticket-one", "ticketUrl": url]])
            return (data, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let receipt = try await client.send(.bug, note: "测试问题", contact: "", submissionId: "stable-operation-key")
        XCTAssertEqual(receipt?.id, "feedback-one")
        XCTAssertEqual(receipt?.ticketURL?.absoluteString, url)
    }

    func testCopiedMailPreservesCategoryContactAndPlatform() {
        let text = FeedbackClient.mailText(topic: "想要的菜没有", lines: ["番茄 & 鸡蛋 + 葱", "联系方式：cook+test@example.com"], version: "2.0.0")
        XCTAssertEqual(text, "收件人：kf@yunle.fun\n主题：【云食册反馈】想要的菜没有\n\n番茄 & 鸡蛋 + 葱\n联系方式：cook+test@example.com\n\n---\n来自 云食册（iOS 2.0.0）")
    }
}
