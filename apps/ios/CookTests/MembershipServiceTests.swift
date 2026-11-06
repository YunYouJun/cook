import CookCore
import Foundation
import XCTest
@testable import Cook

@MainActor final class MembershipServiceTests: XCTestCase {
    private let month = "fun.yunle.cook.member_month"
    private var capabilities: [String: Any] {
        ["application": "cook", "bundleId": "fun.yunle.cook", "ledgerEnvironment": "Production",
         "products": [["id": month, "kind": "nonRenewing"]]]
    }
    private func accounts() async -> YunlefunAccountStore {
        let vault = MemoryYunlefunAccountVault()
        vault.credential = MembershipAccountFixture.credential
        let store = YunlefunAccountStore(service: MembershipAccountFixture(), vault: vault, authorization: MembershipAuthorizationFixture())
        await store.restore()
        return store
    }
    private func response(_ fields: [String: Any], status: Int = 200) throws -> Data {
        try JSONSerialization.data(withJSONObject: fields)
    }
    private func transaction(_ owner: MembershipPurchaseIdentity, environment: MembershipStoreEnvironment = .production) -> MembershipTransaction {
        MembershipTransaction(id: "70101", productID: month, accountToken: owner.accountToken, environment: environment)
    }

    func testCapabilitiesAreAuthenticatedAndOnlyExplicitlySupportedProductsCanBePurchased() async throws {
        let accounts = await accounts(), harness = MembershipHTTPFixture(data: try response(capabilities))
        let identity = try XCTUnwrap(MembershipService.identity(accounts: accounts))
        let service = MembershipService(accounts: accounts, purchasesEnabled: true, environment: .production, transport: { try await harness.send($0) })
        let products = try await service.supportedProducts(for: identity)
        XCTAssertEqual(products, [month])
        try await service.preparePurchase(productID: month, for: identity)
        do { try await service.preparePurchase(productID: "fun.yunle.cook.subscription_month", for: identity); XCTFail("Unsupported renewal must not open Apple payment") }
        catch MembershipServiceFailure.notReady { }
        for request in await harness.requests {
            XCTAssertEqual(request.url?.lastPathComponent, "cook-iap-order")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer public-test-access")
            XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: String])
            XCTAssertEqual(body, ["action": "getCapabilities"])
        }
    }

    func testWrongApplicationLaneKindDuplicatesAndWrappedCapabilitiesAreRejected() async throws {
        let accounts = await accounts(), identity = try XCTUnwrap(MembershipService.identity(accounts: accounts))
        var invalid: [[String: Any]] = [["result": capabilities]]
        for (key, value) in [("application", "apps"), ("bundleId", "fun.yunle.apps"), ("ledgerEnvironment", "Sandbox")] {
            var body = capabilities; body[key] = value; invalid.append(body)
        }
        for products in [[["id": month, "kind": "autoRenewable"]], [["id": month, "kind": "nonRenewing"], ["id": month, "kind": "nonRenewing"]]] {
            var body = capabilities; body["products"] = products; invalid.append(body)
        }
        for body in invalid {
            let harness = MembershipHTTPFixture(data: try response(body))
            let service = MembershipService(accounts: accounts, purchasesEnabled: true, environment: .production, transport: { try await harness.send($0) })
            do { _ = try await service.supportedProducts(for: identity); XCTFail("Invalid capabilities must fail closed") } catch { }
        }
    }

    func testSettlementRequiresDirectMatchingDurableReceiptAndDoesNotTransmitClientIdentity() async throws {
        let accounts = await accounts(), identity = try XCTUnwrap(MembershipService.identity(accounts: accounts))
        for flag in ["granted", "alreadyProcessed", "revoked"] {
            let harness = MembershipHTTPFixture(data: try response(["application": "cook", "bundleId": "fun.yunle.cook", "ledgerEnvironment": "Production", "outTradeNo": "iap_70101", flag: true]))
            let service = MembershipService(accounts: accounts, purchasesEnabled: true, environment: .production, transport: { try await harness.send($0) })
            let receipt = try await service.settle(transaction(identity), for: identity)
            XCTAssertTrue(receipt.confirmed); XCTAssertEqual(receipt.transactionID, "70101")
            let requests = await harness.requests
            let request = try XCTUnwrap(requests.first)
            let fields = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: String])
            XCTAssertEqual(fields, ["action": "verifyPurchase", "transactionId": "70101"])
        }
        for body: [String: Any] in [[:], ["result": ["granted": true]], ["application": "cook", "bundleId": "fun.yunle.cook", "ledgerEnvironment": "Production", "outTradeNo": "iap_70102", "granted": true], ["application": "cook", "bundleId": "fun.yunle.cook", "ledgerEnvironment": "Production", "outTradeNo": "iap_70101", "granted": 1]] {
            let harness = MembershipHTTPFixture(data: try response(body))
            let service = MembershipService(accounts: accounts, purchasesEnabled: true, environment: .production, transport: { try await harness.send($0) })
            do { _ = try await service.settle(transaction(identity), for: identity); XCTFail("Unconfirmed transaction must remain unfinished") } catch { }
        }
    }

    func testSandboxUsesSeparateEndpointsAndRequiresSandboxMembershipMarker() async throws {
        let accounts = await accounts(), identity = try XCTUnwrap(MembershipService.identity(accounts: accounts))
        let harness = MembershipHTTPFixture(data: try response(["ledgerEnvironment": "Sandbox", "membership": ["isActive": true, "expireAt": 1_900_000_000_000]]))
        let service = MembershipService(accounts: accounts, purchasesEnabled: true, environment: .sandbox, transport: { try await harness.send($0) })
        let entitlement = try await service.entitlement(for: identity)
        XCTAssertEqual(entitlement.userID, identity.userID)
        XCTAssertEqual(entitlement.expiresAt, Date(timeIntervalSince1970: 1_900_000_000))
        let requests = await harness.requests
        XCTAssertEqual(requests.first?.url?.lastPathComponent, "account-api-sandbox")
        do { _ = try await service.settle(transaction(identity), for: identity); XCTFail("Production may not fall back to Sandbox") } catch { }
        let count = await harness.count
        XCTAssertEqual(count, 1)
        await harness.setData(try response(["membership": ["isActive": false]]))
        do { _ = try await service.entitlement(for: identity); XCTFail("Missing lane must be rejected") } catch { }
    }

    func testDisabledAndXcodeServicesNeverReachTheGateway() async throws {
        let accounts = await accounts(), identity = try XCTUnwrap(MembershipService.identity(accounts: accounts))
        let harness = MembershipHTTPFixture(data: Data())
        for environment: MembershipStoreEnvironment in [.production, .xcode] {
            let service = MembershipService(accounts: accounts, purchasesEnabled: false, environment: environment, transport: { try await harness.send($0) })
            let ids = try await service.supportedProducts(for: identity)
            XCTAssertTrue(ids.isEmpty)
            do { try await service.preparePurchase(productID: month, for: identity); XCTFail() } catch { }
            do { _ = try await service.settle(transaction(identity, environment: environment), for: identity); XCTFail() } catch { }
        }
        let count = await harness.count
        XCTAssertEqual(count, 0)
    }

    func testRedirectHTTPFailureErrorEnvelopeAndOversizeResponseAreRejected() async throws {
        let accounts = await accounts(), identity = try XCTUnwrap(MembershipService.identity(accounts: accounts))
        for mode in 0..<4 {
            let harness = MembershipHTTPFixture(data: mode == 2 ? try response(["code": "INVALID_AUTH"]) : mode == 3 ? Data(repeating: 32, count: 262_145) : try response(capabilities), status: mode == 1 ? 503 : 200, redirects: mode == 0)
            let service = MembershipService(accounts: accounts, purchasesEnabled: true, environment: .production, transport: { try await harness.send($0) })
            do { _ = try await service.supportedProducts(for: identity); XCTFail("Invalid transport response must not enable a purchase") } catch { }
        }
    }

    func testAccountChangeDuringRequestRejectsTheOldResponse() async throws {
        let accounts = await accounts(), identity = try XCTUnwrap(MembershipService.identity(accounts: accounts))
        let data = try response(capabilities)
        let service = MembershipService(accounts: accounts, purchasesEnabled: true, environment: .production, transport: { request in
            await accounts.signOut()
            return (data, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        })
        do { _ = try await service.supportedProducts(for: identity); XCTFail("A stale account response may not authorize payment") }
        catch MembershipPurchaseFailure.accountChanged { }
        XCTAssertNil(accounts.account)
    }

    func testMalformedMembershipDoesNotBecomeActive() async throws {
        let accounts = await accounts(), identity = try XCTUnwrap(MembershipService.identity(accounts: accounts))
        for member: [String: Any] in [["isActive": 1, "expireAt": 1_900_000_000_000], ["isActive": true, "expireAt": true], ["isActive": true], ["isActive": true, "expireAt": "1900000000000"], ["isActive": true, "expireAt": -1]] {
            let harness = MembershipHTTPFixture(data: try response(["membership": member]))
            let service = MembershipService(accounts: accounts, purchasesEnabled: false, environment: .production, transport: { try await harness.send($0) })
            do { _ = try await service.entitlement(for: identity); XCTFail("Malformed expiry may not be shown as valid membership") } catch { }
        }
    }
}

private actor MembershipHTTPFixture {
    private var data: Data
    private let status: Int
    private let redirects: Bool
    private(set) var requests: [URLRequest] = []
    var count: Int { requests.count }
    init(data: Data, status: Int = 200, redirects: Bool = false) { self.data = data; self.status = status; self.redirects = redirects }
    func setData(_ data: Data) { self.data = data }
    func send(_ request: URLRequest) throws -> (Data, URLResponse) {
        requests.append(request)
        return (data, HTTPURLResponse(url: redirects ? URL(string: "https://other.example/")! : request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}

@MainActor private final class MembershipAccountFixture: YunlefunAccountServing {
    static var credential: YunlefunCredential { YunlefunCredential(accessToken: "public-test-access", refreshToken: "public-test-refresh", subject: "membership-service-test", expiresAt: Date().addingTimeInterval(3600), deviceID: "public-test-installation") }
    func signIn(fields: [String: String], deviceID: String) async throws -> YunlefunCredential { Self.credential }
    func refresh(_ credential: YunlefunCredential) async throws -> YunlefunCredential { Self.credential }
    func account(_ credential: YunlefunCredential) async throws -> YunlefunAccount { YunlefunAccount(id: credential.subject, name: "Public membership test", username: nil, status: "ACTIVE", isAnonymous: false) }
    func signOut(_ credential: YunlefunCredential) async throws { }
}
@MainActor private final class MembershipAuthorizationFixture: YunlefunAuthorizing {
    func authorize(_ transaction: YunlefunSSO) async throws -> URL { throw CancellationError() }
    func cancel() { }
}
