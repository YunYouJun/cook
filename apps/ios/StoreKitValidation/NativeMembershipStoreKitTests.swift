#if targetEnvironment(simulator)
import CookCore
import StoreKit
import StoreKitTest
import XCTest
@testable import Cook

/// Real StoreKit daemon, local catalog and test-only settlement. No Apple
/// Sandbox API, network grant or production membership is involved.
@MainActor final class NativeMembershipStoreKitTests: XCTestCase {
    private static let productID = "fun.yunle.cook.local.nonrenewing"
    private static var session: SKTestSession?

    private func configuredSession() throws -> SKTestSession {
        print("StoreKit SDK host pid: \(ProcessInfo.processInfo.processIdentifier)")
        let value: SKTestSession
        if let existing = Self.session { value = existing }
        else {
            let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "MembershipProducts", withExtension: "storekit"))
            value = try SKTestSession(contentsOf: url)
            Self.session = value
        }
        value.resetToDefaultState(); value.disableDialogs = true; value.clearTransactions()
        return value
    }

    func testLocalizedCatalogTokenUnfinishedAndFinishedHistory() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        let commerce = NativeMembershipStoreKit(catalog: [Self.productID: .nonRenewing])
        commerce.syncAction = {}
        let token = UUID()
        let products = try await commerce.products()
        XCTAssertEqual(products.map(\.id), [Self.productID])
        XCTAssertFalse(try XCTUnwrap(products.first).displayPrice.isEmpty)
        let fixture = try await session.buyProduct(identifier: Self.productID, options: [.appAccountToken(token)])
        XCTAssertEqual(fixture.environment, .xcode)
        XCTAssertEqual(fixture.appAccountToken, token)
        let unfinished = try await waitForUnfinishedFixture(fixture, in: commerce)
        XCTAssertTrue(unfinished.contains { $0.id == String(fixture.id) })
        try await commerce.finish(transactionID: String(fixture.id))
        let restored = try await commerce.restore()
        XCTAssertTrue(restored.contains { $0.id == String(fixture.id) && $0.accountToken == token })
    }

    func testUnfinishedRealTransactionWaitsForSettlementAndRecoversAfterCoordinatorRestart() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        let commerce = NativeMembershipStoreKit(catalog: [Self.productID: .nonRenewing])
        let settlement = LocalSettlement()
        let owner = MembershipPurchaseIdentity(userID: "local-fixture", sessionID: UUID(), accountToken: UUID())
        let fixture = try await session.buyProduct(
            identifier: Self.productID,
            options: [.appAccountToken(owner.accountToken)]
        )
        XCTAssertEqual(fixture.environment, .xcode)
        XCTAssertEqual(fixture.appAccountToken, owner.accountToken)
        _ = try await waitForUnfinishedFixture(fixture, in: commerce)
        func coordinator() -> MembershipPurchaseCoordinator {
            MembershipPurchaseCoordinator(commerce: commerce, settlement: settlement, productIDs: [Self.productID], identity: { owner })
        }
        let original = coordinator()
        await original.start()
        XCTAssertEqual(original.notice, .awaitingConfirmation)
        let queued = try await commerce.unfinished()
        XCTAssertEqual(queued.count, 1)
        XCTAssertEqual(queued.first?.accountToken, owner.accountToken)
        settlement.available = true
        let restarted = coordinator()
        await restarted.start()
        XCTAssertEqual(restarted.pendingTransactionIDs, [])
        let remaining = try await commerce.unfinished()
        XCTAssertEqual(remaining.count, 0)
        XCTAssertEqual(settlement.confirmedIDs, Set(queued.map(\.id)))
    }

    func testCatalogTypeMismatchDoesNotOfferWrongSubscriptionModel() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        let correct = NativeMembershipStoreKit(catalog: [Self.productID: .nonRenewing])
        let available = try await correct.products()
        XCTAssertEqual(available.map(\.id), [Self.productID], "An empty/unavailable test catalog cannot prove type filtering")
        let commerce = NativeMembershipStoreKit(catalog: [Self.productID: .autoRenewable])
        let products = try await commerce.products()
        XCTAssertEqual(products, [])
    }

    // Purchase/owner/finish/cancel-renewal/restore assertions run through the
    // real membership scene in MembershipStoreKitUITests. A headless initial
    // subscription purchase can hang in the SDK before returning any result.
    func testAutoRenewingCatalogOnlyOffersDeclaredSubscriptionType() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        let id = "fun.yunle.cook.local.autorenewing"
        let commerce = NativeMembershipStoreKit(catalog: [id: .autoRenewable])
        let products = try await commerce.products()
        XCTAssertEqual(products.map(\.id), [id])
        XCTAssertFalse(try XCTUnwrap(products.first).displayPrice.isEmpty)
        let wrongType = NativeMembershipStoreKit(catalog: [id: .nonRenewing])
        let rejected = try await wrongType.products()
        XCTAssertEqual(rejected, [])
    }

    func testCancellationErrorsNeverReportSuccessfulPurchaseOrRestore() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        let commerce = NativeMembershipStoreKit(catalog: [Self.productID: .nonRenewing])
        let listener = listen(to: commerce)
        defer { listener.cancel() }
        _ = try await commerce.products()
        commerce.purchaseError = .userCancelled
        do {
            guard case .cancelled = try await commerce.purchase(productID: Self.productID, accountToken: UUID()) else {
                return XCTFail("Cancellation must never produce a successful payment")
            }
        }
        commerce.syncError = .userCancelled
        do {
            _ = try await commerce.restore()
            XCTFail("Restore cancellation must not report success")
        }
        catch is CancellationError { }
        let queue = try await commerce.unfinished()
        XCTAssertEqual(queue, [])
    }

    func testVerifiedTransactionUpdateIsDeliveredAndFinished() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        let commerce = NativeMembershipStoreKit(catalog: [Self.productID: .nonRenewing])
        _ = try await commerce.products()
        let token = UUID()
        let approved = expectation(description: "Approved transaction update")
        var delivered: MembershipTransaction?
        let stream = commerce.updates()
        let listener = Task {
            for await event in stream {
                if case .transaction(let value) = event, value.accountToken == token, delivered == nil {
                    delivered = value; approved.fulfill()
                }
            }
        }
        defer { listener.cancel() }
        let fixture = try await session.buyProduct(
            identifier: Self.productID,
            options: [.appAccountToken(token)]
        )
        XCTAssertEqual(fixture.environment, .xcode)
        XCTAssertEqual(fixture.appAccountToken, token)
        await fulfillment(of: [approved], timeout: 10)
        // Cancel while the stream is still being consumed so onTermination
        // stops its underlying observer before the next session reset.
        listener.cancel()
        await listener.value
        XCTAssertEqual(delivered?.accountToken, token)
        XCTAssertEqual(delivered?.environment, .xcode)
        try await commerce.finish(transactionID: try XCTUnwrap(delivered?.id))
    }

    // The UI runner drives the live refund from outside the App process.
    // This separate SDK case proves verified recovery from durable history.
    func testRefundIsRecoveredAsVerifiedRevocationFromHistory() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        let commerce = NativeMembershipStoreKit(catalog: [Self.productID: .nonRenewing])
        commerce.syncAction = {}
        _ = try await commerce.products()
        let token = UUID()
        // Recovery must also work without a live observer (for example after
        // process restart). The external-refund UI case separately requires
        // the running App to handle Transaction.updates without a refresh.
        let fixture = try await session.buyProduct(
            identifier: Self.productID,
            options: [.appAccountToken(token)]
        )
        XCTAssertEqual(fixture.environment, .xcode)
        XCTAssertEqual(fixture.appAccountToken, token)
        _ = try await waitForUnfinishedFixture(fixture, in: commerce)
        try await commerce.finish(transactionID: String(fixture.id))
        let beforeRefund = try await commerce.restore()
        let paid = try XCTUnwrap(beforeRefund.first { $0.id == String(fixture.id) })
        XCTAssertFalse(paid.revoked)
        XCTAssertEqual(paid.accountToken, token)
        XCTAssertEqual(paid.environment, .xcode)
        try session.refundTransaction(identifier: try XCTUnwrap(UInt(fixture.id)))
        // The mutation can return before Transaction.all reflects it. Wait
        // for the verified history itself, not an unrelated notification.
        // Read-only polling never repeats the refund or bypasses verification.
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(10))
        var history = try await commerce.restore()
        while !history.contains(where: { $0.id == String(fixture.id) && $0.revoked }), clock.now < deadline {
            try await Task.sleep(for: .milliseconds(100))
            history = try await commerce.restore()
        }
        let refunded = try XCTUnwrap(history.first { $0.id == String(fixture.id) })
        XCTAssertTrue(refunded.revoked, "Verified history must reflect the refund within 10 seconds")
        XCTAssertEqual(refunded.accountToken, token)
        XCTAssertEqual(refunded.environment, .xcode)
    }

    private func waitForUnfinishedFixture(_ fixture: StoreKit.Transaction, in commerce: NativeMembershipStoreKit,
                                          file: StaticString = #filePath, line: UInt = #line) async throws -> [MembershipTransaction] {
        // SKTestSession can return before the App's unfinished snapshot sees
        // the external purchase. Establish this input before testing recovery
        // or finish; never retry a purchase or coordinator operation. IDs can
        // be reused after reset, so also require this fixture's account token.
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(10))
        let matches: (MembershipTransaction) -> Bool = {
            $0.id == String(fixture.id) && $0.accountToken == fixture.appAccountToken
        }
        var queue = try await commerce.unfinished()
        while !queue.contains(where: matches), clock.now < deadline {
            try await Task.sleep(for: .milliseconds(100))
            queue = try await commerce.unfinished()
        }
        let pending = try XCTUnwrap(queue.first(where: matches),
                                   "StoreKit must publish the unfinished fixture within 10 seconds", file: file, line: line)
        XCTAssertEqual(pending.environment, .xcode, file: file, line: line)
        XCTAssertFalse(pending.revoked, file: file, line: line)
        return queue
    }

    private func listen(to commerce: NativeMembershipStoreKit) -> Task<Void, Never> {
        let stream = commerce.updates()
        return Task { for await _ in stream {} }
    }
}

@MainActor private final class LocalSettlement: MembershipSettling {
    let environment = MembershipStoreEnvironment.xcode
    var available = false
    var confirmedIDs: Set<String> = []
    func preparePurchase(productID: String, for identity: MembershipPurchaseIdentity) async throws { }
    func entitlement(for identity: MembershipPurchaseIdentity) async throws -> MembershipEntitlement {
        MembershipEntitlement(userID: identity.userID, expiresAt: nil)
    }
    func settle(_ transaction: MembershipTransaction, for identity: MembershipPurchaseIdentity) async throws -> MembershipSettlementReceipt {
        guard available else { throw URLError(.notConnectedToInternet) }
        confirmedIDs.insert(transaction.id)
        return MembershipSettlementReceipt(transactionID: transaction.id, environment: .xcode, confirmed: true)
    }
}
#endif
