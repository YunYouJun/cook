import CookCore
import Foundation
import XCTest

@MainActor final class MembershipPurchaseTests: XCTestCase {
    func testAccountMarkerMatchesAuthoritativeNodeSettlementVectors() {
        // Produced by www.yunle.fun cloudfunctions/wxpay-order/lib/iap-settlement.js
        // at 0d9622c549464842a59186832cbdc33b5e727da7, using the actual exported function.
        for (user, token) in [
            ("alice", "ff339005-7476-82b1-9184-ea67c9516aa8"),
            ("bob", "cabb8aed-6b17-833b-ad66-1b077b8ade2f"),
            ("测试账号-1", "5afff4ae-ffe5-81bc-8c58-00216c02428b"),
        ] {
            XCTAssertEqual(MembershipAccountToken.forUser(user), UUID(uuidString: token))
        }
    }

    func testCancellationAndPendingDoNotSettleOrFinish() async {
        for result in [MembershipPurchaseResult.cancelled, .pending] {
            let fixture = Fixture()
            fixture.commerce.result = result
            await fixture.coordinator.loadProducts()
            await fixture.coordinator.purchase(productID: "cook.test.member")
            XCTAssertEqual(fixture.commerce.purchasedToken, fixture.current?.accountToken)
            XCTAssertEqual(fixture.backend.settled.count, 0)
            XCTAssertEqual(fixture.commerce.finished, [])
            XCTAssertEqual(fixture.coordinator.notice, result.isCancelled ? .cancelled : .pendingApproval)
        }
    }

    func testUnconfirmedPaymentSurvivesRestartAndFinishesOnlyAfterServerReceipt() async {
        let fixture = Fixture()
        fixture.backend.fail = true
        fixture.commerce.result = .transaction(fixture.transaction)
        fixture.commerce.queue = [fixture.transaction]
        await fixture.coordinator.loadProducts()
        await fixture.coordinator.purchase(productID: "cook.test.member")
        XCTAssertEqual(fixture.coordinator.pendingTransactionIDs, ["100"])
        XCTAssertEqual(fixture.coordinator.notice, .awaitingConfirmation)
        XCTAssertEqual(fixture.commerce.finished, [])
        XCTAssertEqual(fixture.commerce.queue.count, 1)

        fixture.backend.fail = false
        let restarted = fixture.makeCoordinator()
        await restarted.start()
        XCTAssertEqual(fixture.backend.settled, ["100", "100"])
        XCTAssertEqual(fixture.commerce.finished, ["100"])
        XCTAssertEqual(restarted.pendingTransactionIDs, [])
        XCTAssertEqual(fixture.commerce.restoreCalls, 0, "Startup must never prompt App Store sync")
    }

    func testFailureBeforeAStoreKitTransactionDoesNotClaimPaymentIsAwaitingConfirmation() async {
        let fixture = Fixture()
        fixture.commerce.purchaseFails = true
        await fixture.coordinator.loadProducts()
        await fixture.coordinator.purchase(productID: "cook.test.member")
        XCTAssertEqual(fixture.coordinator.notice, .unavailable)
        XCTAssertEqual(fixture.coordinator.pendingTransactionIDs, [])
        XCTAssertEqual(fixture.backend.settled, [])
        XCTAssertEqual(fixture.commerce.finished, [])
    }

    func testRestoreReplaysHistoryWithoutLocallyExtendingMembership() async {
        let fixture = Fixture()
        fixture.commerce.history = [fixture.transaction, fixture.transaction]
        let expiry = Date(timeIntervalSince1970: 2_000_000_000)
        fixture.backend.expiry = expiry
        await fixture.coordinator.restore()
        await fixture.coordinator.restore()
        XCTAssertEqual(fixture.backend.settled, ["100", "100"], "The server receives replays and must enforce idempotency")
        XCTAssertEqual(fixture.coordinator.entitlement?.expiresAt, expiry)
        XCTAssertEqual(fixture.commerce.restoreCalls, 2)
    }

    func testMissingAndOtherAccountTokensNeverClaimTransactions() async {
        for token in [nil, UUID()] as [UUID?] {
            let fixture = Fixture()
            fixture.commerce.history = [MembershipTransaction(id: "100", productID: "cook.test.member", accountToken: token, environment: .sandbox)]
            await fixture.coordinator.restore()
            XCTAssertEqual(fixture.backend.settled, [])
            XCTAssertEqual(fixture.commerce.finished, [])
            XCTAssertEqual(fixture.coordinator.notice, .anotherAccount)
        }
    }

    func testWrongEnvironmentNeverReachesSettlementEvenForXcodeTransactions() async {
        for environment in [MembershipStoreEnvironment.production, .xcode] {
            let fixture = Fixture()
            fixture.commerce.queue = [MembershipTransaction(id: "100", productID: "cook.test.member", accountToken: fixture.current?.accountToken, environment: environment)]
            await fixture.coordinator.retryUnfinished()
            XCTAssertEqual(fixture.backend.settled, [])
            XCTAssertEqual(fixture.commerce.finished, [])
            XCTAssertEqual(fixture.coordinator.pendingTransactionIDs, ["100"])
        }
    }

    func testMalformedReceiptsLeavePaymentUnfinished() async {
        for receipt in [
            MembershipSettlementReceipt(transactionID: "101", environment: .sandbox, confirmed: true),
            MembershipSettlementReceipt(transactionID: "100", environment: .production, confirmed: true),
            MembershipSettlementReceipt(transactionID: "100", environment: .sandbox, confirmed: false),
        ] {
            let fixture = Fixture()
            fixture.commerce.queue = [fixture.transaction]
            fixture.backend.receipt = receipt
            await fixture.coordinator.retryUnfinished()
            XCTAssertEqual(fixture.commerce.finished, [])
            XCTAssertEqual(fixture.coordinator.pendingTransactionIDs, ["100"])
        }
    }

    func testSwitchingAccountsWhileApplePaymentIsOpenCannotClaimItsResult() async {
        let fixture = Fixture()
        fixture.commerce.beforePurchaseReturns = {
            fixture.current = MembershipPurchaseIdentity(userID: "bob", sessionID: UUID(), accountToken: UUID())
            fixture.coordinator.accountChanged()
        }
        fixture.commerce.result = .transaction(fixture.transaction)
        await fixture.coordinator.loadProducts()
        await fixture.coordinator.purchase(productID: "cook.test.member")
        XCTAssertEqual(fixture.backend.settled, [])
        XCTAssertEqual(fixture.commerce.finished, [])
        XCTAssertNil(fixture.coordinator.notice)
        XCTAssertNil(fixture.coordinator.entitlement)
    }

    func testAccountChangeAfterServerCommitKeepsOriginalTransactionUnfinished() async {
        let fixture = Fixture()
        fixture.commerce.queue = [fixture.transaction]
        let original = fixture.current
        fixture.backend.beforeReceiptReturns = {
            fixture.current = nil
            fixture.coordinator.accountChanged()
        }
        await fixture.coordinator.retryUnfinished()
        XCTAssertEqual(fixture.backend.settled, ["100"])
        XCTAssertEqual(fixture.commerce.finished, [])
        XCTAssertNil(fixture.coordinator.entitlement)
        fixture.current = original
        fixture.backend.beforeReceiptReturns = nil
        await fixture.coordinator.retryUnfinished()
        XCTAssertEqual(fixture.backend.settled, ["100", "100"])
        XCTAssertEqual(fixture.commerce.finished, ["100"])
    }

    func testConcurrentDeliverySharesOneSettlementAndFinish() async {
        let fixture = Fixture()
        fixture.commerce.queue = [fixture.transaction]
        let began = expectation(description: "Server settlement began")
        fixture.backend.pause = true
        fixture.backend.didBegin = { began.fulfill() }
        let first = Task { await fixture.coordinator.retryUnfinished() }
        await fulfillment(of: [began], timeout: 2)
        let replayRead = expectation(description: "Concurrent replay reached the same queue")
        fixture.commerce.didReadQueue = { replayRead.fulfill() }
        let second = Task { await fixture.coordinator.retryUnfinished() }
        await fulfillment(of: [replayRead], timeout: 2)
        fixture.backend.release()
        await first.value
        await second.value
        XCTAssertEqual(fixture.backend.settled, ["100"])
        XCTAssertEqual(fixture.commerce.finished, ["100"])
    }

    func testRevocationReconcilesAtServerAndPreservesIndependentWebEntitlement() async {
        let fixture = Fixture()
        fixture.backend.expiry = Date(timeIntervalSince1970: 2_000_000_000)
        fixture.commerce.history = [MembershipTransaction(id: "100", productID: "cook.test.member", accountToken: fixture.current?.accountToken, environment: .sandbox, revoked: true)]
        await fixture.coordinator.restore()
        XCTAssertEqual(fixture.backend.revoked, [true])
        XCTAssertEqual(fixture.coordinator.entitlement?.expiresAt, fixture.backend.expiry)
        XCTAssertTrue(fixture.coordinator.entitlement!.isActive(at: Date(timeIntervalSince1970: 1_999_999_999)))
        XCTAssertFalse(fixture.coordinator.entitlement!.isActive(at: fixture.backend.expiry!))
    }

    func testEntitlementOfAnotherUserCannotUnlockMembership() async {
        let fixture = Fixture()
        fixture.backend.snapshotOwner = "bob"
        fixture.backend.expiry = Date(timeIntervalSince1970: 2_000_000_000)
        await fixture.coordinator.refresh()
        XCTAssertNil(fixture.coordinator.entitlement)
        XCTAssertEqual(fixture.coordinator.notice, .unavailable)
    }

    func testUnrelatedProductsNeverBecomePurchasable() async {
        let fixture = Fixture()
        fixture.commerce.catalog = [MembershipProduct(id: "another.app.member", title: "Other", displayPrice: "$1")]
        await fixture.coordinator.loadProducts()
        XCTAssertEqual(fixture.coordinator.products, [])
        await fixture.coordinator.purchase(productID: "another.app.member")
        XCTAssertNil(fixture.commerce.purchasedToken)
    }
}

private extension MembershipPurchaseResult {
    var isCancelled: Bool { if case .cancelled = self { return true }; return false }
}

@MainActor private final class Fixture {
    var current: MembershipPurchaseIdentity? = MembershipPurchaseIdentity(userID: "alice", sessionID: UUID(), accountToken: UUID())
    let commerce = CommerceFixture()
    let backend = SettlementFixture()
    var transaction: MembershipTransaction {
        MembershipTransaction(id: "100", productID: "cook.test.member", accountToken: current?.accountToken, environment: .sandbox)
    }
    lazy var coordinator = makeCoordinator()
    func makeCoordinator() -> MembershipPurchaseCoordinator {
        MembershipPurchaseCoordinator(commerce: commerce, settlement: backend, productIDs: ["cook.test.member"], identity: { [weak self] in self?.current })
    }
}

@MainActor private final class CommerceFixture: MembershipCommerce {
    var catalog = [MembershipProduct(id: "cook.test.member", title: "Fixture", displayPrice: "¥10.00")]
    var result: MembershipPurchaseResult = .cancelled
    var queue: [MembershipTransaction] = []
    var history: [MembershipTransaction] = []
    var finished: [String] = []
    var purchasedToken: UUID?
    var restoreCalls = 0
    var purchaseFails = false
    var beforePurchaseReturns: (() -> Void)?
    var didReadQueue: (() -> Void)?
    func products() async throws -> [MembershipProduct] { catalog }
    func purchase(productID: String, accountToken: UUID) async throws -> MembershipPurchaseResult {
        if purchaseFails { throw MembershipPurchaseFailure.invalidResponse }
        purchasedToken = accountToken; beforePurchaseReturns?(); return result
    }
    func unfinished() async throws -> [MembershipTransaction] { didReadQueue?(); return queue }
    func restore() async throws -> [MembershipTransaction] { restoreCalls += 1; return history }
    func finish(transactionID: String) async throws {
        finished.append(transactionID); queue.removeAll { $0.id == transactionID }
    }
    func updates() -> AsyncStream<MembershipTransactionEvent> { AsyncStream { $0.finish() } }
}

@MainActor private final class SettlementFixture: MembershipSettling {
    let environment = MembershipStoreEnvironment.sandbox
    var fail = false
    var pause = false
    var snapshotOwner: String?
    var expiry: Date?
    var settled: [String] = []
    var revoked: [Bool] = []
    var receipt: MembershipSettlementReceipt?
    var beforeReceiptReturns: (() -> Void)?
    var didBegin: (() -> Void)?
    private var waiting: CheckedContinuation<Void, Never>?
    func entitlement(for identity: MembershipPurchaseIdentity) async throws -> MembershipEntitlement {
        MembershipEntitlement(userID: snapshotOwner ?? identity.userID, expiresAt: expiry)
    }
    func settle(_ transaction: MembershipTransaction, for identity: MembershipPurchaseIdentity) async throws -> MembershipSettlementReceipt {
        settled.append(transaction.id); revoked.append(transaction.revoked)
        if fail { throw MembershipPurchaseFailure.invalidResponse }
        if pause { await withCheckedContinuation { waiting = $0; didBegin?() } }
        beforeReceiptReturns?()
        return receipt ?? MembershipSettlementReceipt(transactionID: transaction.id, environment: environment, confirmed: true)
    }
    func release() { pause = false; waiting?.resume(); waiting = nil }
}
