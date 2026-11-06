#if DEBUG
import CookCore
import Foundation
import Observation

/// Public, device-local UI fixtures. Never creates credentials or calls cloud
/// endpoints. The real StoreKit variant uses only a .local test product.
@MainActor @Observable final class MembershipUIFixture: MembershipServicing {
    let environment = MembershipStoreEnvironment.xcode
    private let defaults: UserDefaults
    private let ids: Set<String>
    private let confirms: Bool
    private let ready: Bool
    private var owner: String
    private var session = UUID()
    var identity: MembershipPurchaseIdentity {
        MembershipPurchaseIdentity(userID: owner, sessionID: session, accountToken: MembershipAccountToken.forUser(owner))
    }
    var name: String { owner == "membership-ui-a" ? "测试账号 A" : "测试账号 B" }

    private init(defaults: UserDefaults, ids: Set<String>, environment: [String: String]) {
        self.defaults = defaults; self.ids = ids
        confirms = environment["COOK_MEMBERSHIP_FIXTURE_SETTLEMENT"] == "confirmed"
        ready = environment["COOK_MEMBERSHIP_FIXTURE_SETTLEMENT"] != "not-ready"
        owner = environment["COOK_MEMBERSHIP_FIXTURE_OWNER"] == "b" ? "membership-ui-b" : "membership-ui-a"
    }

    static func makeStore() -> MembershipStore {
        let arguments = ProcessInfo.processInfo.arguments
        let defaults = UserDefaults(suiteName: "cook.membership.ui-tests")!
        if arguments.contains("-ui-testing-reset-membership") {
            defaults.removePersistentDomain(forName: "cook.membership.ui-tests")
        }
        let realStoreKit = arguments.contains("-ui-testing-membership-storekit")
        let catalog: [String: MembershipProductKind] = realStoreKit
            ? ["fun.yunle.cook.local.nonrenewing": .nonRenewing,
               "fun.yunle.cook.local.autorenewing": .autoRenewable] : MembershipPlan.catalog
        let fixture = MembershipUIFixture(defaults: defaults, ids: Set(catalog.keys), environment: ProcessInfo.processInfo.environment)
        let commerce: MembershipCommerce = realStoreKit
            ? NativeMembershipStoreKit(catalog: catalog) : MembershipFixtureCommerce(defaults: defaults)
        let store = MembershipStore(commerce: commerce, service: fixture, catalog: catalog, purchasesEnabled: true,
                                    identity: { fixture.identity }, name: { fixture.name })
        if !realStoreKit {
            store.switchTestAccount = { [weak store] in
                fixture.owner = fixture.owner == "membership-ui-a" ? "membership-ui-b" : "membership-ui-a"
                fixture.session = UUID()
                store?.accountChanged()
                Task { await store?.reload() }
            }
        }
        return store
    }

    func supportedProducts(for identity: MembershipPurchaseIdentity) async throws -> Set<String> { ids }
    func preparePurchase(productID: String, for identity: MembershipPurchaseIdentity) async throws {
        guard ready, ids.contains(productID), identity == self.identity else { throw MembershipServiceFailure.notReady }
    }
    func entitlement(for identity: MembershipPurchaseIdentity) async throws -> MembershipEntitlement {
        let value = defaults.double(forKey: "expiry." + identity.userID)
        return MembershipEntitlement(userID: identity.userID, expiresAt: value > 0 ? Date(timeIntervalSince1970: value) : nil)
    }
    func settle(_ transaction: MembershipTransaction, for identity: MembershipPurchaseIdentity) async throws -> MembershipSettlementReceipt {
        guard confirms else { throw URLError(.notConnectedToInternet) }
        guard transaction.environment == .xcode, ids.contains(transaction.productID),
              identity == self.identity, transaction.accountToken == identity.accountToken else {
            throw MembershipPurchaseFailure.accountChanged
        }
        let key = "settled." + transaction.id
        let revokedKey = "revoked." + transaction.id
        let lastOrderKey = "lastOrder." + identity.userID
        if transaction.revoked {
            guard defaults.string(forKey: key) == identity.userID else { throw MembershipPurchaseFailure.invalidResponse }
            defaults.set(true, forKey: revokedKey)
            // This isolated fixture has only Apple grants. Preserve any later
            // independent grant; production calendar/refund rules stay server-owned.
            if defaults.string(forKey: lastOrderKey) == transaction.id {
                defaults.removeObject(forKey: "expiry." + identity.userID)
            }
            return MembershipSettlementReceipt(transactionID: transaction.id, environment: .xcode, confirmed: true)
        }
        if defaults.string(forKey: key) == nil {
            defaults.set(identity.userID, forKey: key)
            // A display fixture only: business calendar arithmetic is server-owned.
            defaults.set(Date().addingTimeInterval(30 * 86400).timeIntervalSince1970, forKey: "expiry." + identity.userID)
            defaults.set(transaction.id, forKey: lastOrderKey)
        }
        guard defaults.string(forKey: key) == identity.userID else { throw MembershipPurchaseFailure.accountChanged }
        // An older non-revoked snapshot cannot undo a reconciled fixture refund.
        if defaults.bool(forKey: revokedKey), defaults.string(forKey: lastOrderKey) == transaction.id {
            defaults.removeObject(forKey: "expiry." + identity.userID)
        }
        return MembershipSettlementReceipt(transactionID: transaction.id, environment: .xcode, confirmed: true)
    }
}

@MainActor private final class MembershipFixtureCommerce: MembershipCommerce {
    private struct Record: Codable {
        let id: String
        let productID: String
        let token: UUID
        var value: MembershipTransaction { MembershipTransaction(id: id, productID: productID, accountToken: token, environment: .xcode) }
    }
    private let defaults: UserDefaults
    init(defaults: UserDefaults) { self.defaults = defaults }
    private func records(_ key: String) -> [Record] {
        guard let data = defaults.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([Record].self, from: data)) ?? []
    }
    private func save(_ values: [Record], key: String) throws { defaults.set(try JSONEncoder().encode(values), forKey: key) }
    func products() async throws -> [MembershipProduct] {
        MembershipPlan.allCases.map {
            MembershipProduct(id: $0.id, title: ($0 == .month || $0 == .subscriptionMonth ? "月度会员" : "年度会员"), displayPrice: "¥1.00")
        }
    }
    func purchase(productID: String, accountToken: UUID) async throws -> MembershipPurchaseResult {
        switch ProcessInfo.processInfo.environment["COOK_MEMBERSHIP_FIXTURE_RESULT"] {
        case "cancelled": return .cancelled
        case "pending": return .pending
        default:
            let id = defaults.integer(forKey: "counter") + 100
            defaults.set(id - 99, forKey: "counter")
            let record = Record(id: String(id), productID: productID, token: accountToken)
            try save(records("unfinished") + [record], key: "unfinished")
            return .transaction(record.value)
        }
    }
    func unfinished() async throws -> [MembershipTransaction] { records("unfinished").map(\.value) }
    func restore() async throws -> [MembershipTransaction] { (records("unfinished") + records("history")).map(\.value) }
    func finish(transactionID: String) async throws {
        let queue = records("unfinished")
        if let record = queue.first(where: { $0.id == transactionID }), !records("history").contains(where: { $0.id == transactionID }) {
            try save(records("history") + [record], key: "history")
        }
        try save(queue.filter { $0.id != transactionID }, key: "unfinished")
    }
    func updates() -> AsyncStream<MembershipTransactionEvent> { AsyncStream { $0.finish() } }
}
#endif
