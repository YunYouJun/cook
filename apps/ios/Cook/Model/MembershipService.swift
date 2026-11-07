import CookCore
import CoreFoundation
import Foundation

enum MembershipPlan: String, CaseIterable, Identifiable {
    case month, year, subscriptionMonth, subscriptionYear
    var id: String { "fun.yunle.cook." + suffix }
    private var suffix: String {
        switch self {
        case .month: return "member_month"
        case .year: return "member_year"
        case .subscriptionMonth: return "subscription_month"
        case .subscriptionYear: return "subscription_year"
        }
    }
    var renews: Bool { self == .subscriptionMonth || self == .subscriptionYear }
    var kind: MembershipProductKind { renews ? .autoRenewable : .nonRenewing }
    static var catalog: [String: MembershipProductKind] {
        Dictionary(uniqueKeysWithValues: allCases.map { ($0.id, $0.kind) })
    }
}

enum MembershipServiceFailure: Error { case unavailable, invalidResponse, notReady }

@MainActor protocol MembershipServicing: MembershipSettling {
    func supportedProducts(for identity: MembershipPurchaseIdentity) async throws -> Set<String>
}

/// Official authenticated Functions API. No browser SDK envelopes, cookies,
/// client UID authorization or inferred cross-application purchase endpoint.
@MainActor final class MembershipService: MembershipServicing {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    let environment: MembershipStoreEnvironment
    private let accounts: YunlefunAccountStore
    private let purchasesEnabled: Bool
    private let transport: Transport
    private static let gateway = URL(string: "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/v1/functions/")!

    init(accounts: YunlefunAccountStore, purchasesEnabled: Bool,
         environment: MembershipStoreEnvironment? = nil, transport: Transport? = nil) {
        self.accounts = accounts; self.purchasesEnabled = purchasesEnabled
        self.environment = environment ?? (Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt" ? .sandbox : .production)
        if let transport { self.transport = transport }
        else {
            let config = URLSessionConfiguration.ephemeral
            config.httpShouldSetCookies = false; config.httpCookieStorage = nil
            config.urlCredentialStorage = nil; config.urlCache = nil
            config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 30
            let session = URLSession(configuration: config, delegate: MembershipRedirectPolicy(), delegateQueue: nil)
            self.transport = { try await session.data(for: $0) }
        }
    }

    static func identity(accounts: YunlefunAccountStore) -> MembershipPurchaseIdentity? {
        guard let account = accounts.account else { return nil }
        return MembershipPurchaseIdentity(userID: account.id, sessionID: accounts.sessionRevision,
                                          accountToken: MembershipAccountToken.forUser(account.id))
    }

    func entitlement(for identity: MembershipPurchaseIdentity) async throws -> MembershipEntitlement {
        guard environment != .xcode else { throw MembershipServiceFailure.notReady }
        let body = try await request(environment == .sandbox ? "account-api-sandbox" : "account-api",
                                     fields: ["action": "getAccount"], identity: identity)
        if environment == .sandbox { try requireLane(body) }
        guard let membership = body["membership"] as? [String: Any], let activeValue = membership["isActive"] as? NSNumber,
              CFGetTypeID(activeValue) == CFBooleanGetTypeID() else {
            throw MembershipServiceFailure.invalidResponse
        }
        let active = activeValue.boolValue
        if let value = membership["expireAt"] as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() {
            throw MembershipServiceFailure.invalidResponse
        }
        guard membership["expireAt"] == nil || membership["expireAt"] is NSNull || membership["expireAt"] is NSNumber else {
            throw MembershipServiceFailure.invalidResponse
        }
        let milliseconds = (membership["expireAt"] as? NSNumber)?.doubleValue
        guard milliseconds.map({ $0.isFinite && (0...253_402_300_799_000).contains($0) }) ?? true,
              !active || milliseconds.map({ $0 > 0 }) == true else { throw MembershipServiceFailure.invalidResponse }
        return MembershipEntitlement(userID: identity.userID,
                                     expiresAt: active ? milliseconds.map { Date(timeIntervalSince1970: $0 / 1000) } : nil)
    }

    func supportedProducts(for identity: MembershipPurchaseIdentity) async throws -> Set<String> {
        guard purchasesEnabled, environment != .xcode else { return [] }
        let body = try await request(purchaseFunction, fields: ["action": "getCapabilities"], identity: identity)
        try requireLane(body)
        guard body["application"] as? String == "cook", body["bundleId"] as? String == "fun.yunle.cook",
              let products = body["products"] as? [[String: String]],
              products.count <= MembershipPlan.allCases.count else { throw MembershipServiceFailure.invalidResponse }
        var accepted = Set<String>()
        for product in products {
            guard let id = product["id"], let plan = MembershipPlan.allCases.first(where: { $0.id == id }),
                  product["kind"] == (plan.renews ? "autoRenewable" : "nonRenewing"),
                  accepted.insert(id).inserted else { throw MembershipServiceFailure.invalidResponse }
        }
        return accepted
    }

    func preparePurchase(productID: String, for identity: MembershipPurchaseIdentity) async throws {
        guard try await supportedProducts(for: identity).contains(productID) else { throw MembershipServiceFailure.notReady }
    }

    func settle(_ transaction: MembershipTransaction, for identity: MembershipPurchaseIdentity) async throws -> MembershipSettlementReceipt {
        guard purchasesEnabled, environment != .xcode, transaction.environment == environment,
              MembershipPlan.catalog[transaction.productID] != nil,
              transaction.accountToken == identity.accountToken else { throw MembershipServiceFailure.notReady }
        let body = try await request(purchaseFunction, fields: ["action": "verifyPurchase", "transactionId": transaction.id], identity: identity)
        try requireLane(body)
        guard body["application"] as? String == "cook", body["bundleId"] as? String == "fun.yunle.cook", body["outTradeNo"] as? String == "iap_" + transaction.id,
              Self.isTrue(body["granted"]) || Self.isTrue(body["alreadyProcessed"]) || Self.isTrue(body["revoked"]) else {
            throw MembershipServiceFailure.invalidResponse
        }
        return MembershipSettlementReceipt(transactionID: transaction.id, environment: environment, confirmed: true)
    }

    private static func isTrue(_ value: Any?) -> Bool {
        guard let value = value as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else { return false }
        return value.boolValue
    }

    private var purchaseFunction: String { environment == .sandbox ? "cook-iap-order-sandbox" : "cook-iap-order" }
    private func requireLane(_ value: [String: Any]) throws {
        guard value["ledgerEnvironment"] as? String == (environment == .sandbox ? "Sandbox" : "Production") else {
            throw MembershipPurchaseFailure.environmentMismatch
        }
    }

    private func request(_ function: String, fields: [String: Any], identity: MembershipPurchaseIdentity) async throws -> [String: Any] {
        guard Self.identity(accounts: accounts) == identity else { throw MembershipPurchaseFailure.accountChanged }
        let credential = try await accounts.credentialForRequest()
        guard credential.subject == identity.userID, Self.identity(accounts: accounts) == identity,
              !credential.accessToken.isEmpty, credential.accessToken.utf8.count <= 16_384,
              credential.accessToken.unicodeScalars.allSatisfy({ (33...126).contains($0.value) }) else {
            throw MembershipPurchaseFailure.accountChanged
        }
        let url = Self.gateway.appendingPathComponent(function)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"; request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("Bearer \(credential.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.httpBody = try JSONSerialization.data(withJSONObject: fields)
        let (data, response) = try await transport(request)
        try Task.checkCancellation()
        guard Self.identity(accounts: accounts) == identity else { throw MembershipPurchaseFailure.accountChanged }
        guard let http = response as? HTTPURLResponse, http.url == url, data.count <= 256 * 1024,
              (200..<300).contains(http.statusCode),
              let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              body["code"] == nil else { throw MembershipServiceFailure.unavailable }
        return body
    }
}

private final class MembershipRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
