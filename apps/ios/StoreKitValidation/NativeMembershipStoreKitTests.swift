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
        let listener = listen(to: commerce)
        defer { listener.cancel() }
        let token = UUID()
        let products = try await commerce.products()
        XCTAssertEqual(products.map(\.id), [Self.productID])
        XCTAssertFalse(try XCTUnwrap(products.first).displayPrice.isEmpty)
        guard case .transaction(let value) = try await commerce.purchase(productID: Self.productID, accountToken: token) else {
            return XCTFail("A local verified payment is required")
        }
        XCTAssertEqual(value.environment, .xcode)
        XCTAssertEqual(value.accountToken, token)
        let unfinished = try await commerce.unfinished()
        XCTAssertTrue(unfinished.contains { $0.id == value.id })
        try await commerce.finish(transactionID: value.id)
        let restored = try await commerce.restore()
        XCTAssertTrue(restored.contains { $0.id == value.id && $0.accountToken == token })
    }

    func testRealStoreKitPaymentWaitsForSettlementAndRecoversAfterCoordinatorRestart() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        let commerce = NativeMembershipStoreKit(catalog: [Self.productID: .nonRenewing])
        let settlement = LocalSettlement()
        let owner = MembershipPurchaseIdentity(userID: "local-fixture", sessionID: UUID(), accountToken: UUID())
        func coordinator() -> MembershipPurchaseCoordinator {
            MembershipPurchaseCoordinator(commerce: commerce, settlement: settlement, productIDs: [Self.productID], identity: { owner })
        }
        let original = coordinator()
        await original.start()
        await original.loadProducts()
        await original.purchase(productID: Self.productID)
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

    func testCancellationInjectionNeverReportsSuccessfulPurchaseOrRestore() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        let commerce = NativeMembershipStoreKit(catalog: [Self.productID: .nonRenewing])
        let listener = listen(to: commerce)
        defer { listener.cancel() }
        _ = try await commerce.products()
        try await session.setSimulatedError(.generic(.userCancelled), forAPI: StoreKitPurchaseAPI())
        do {
            guard case .cancelled = try await commerce.purchase(productID: Self.productID, accountToken: UUID()) else {
                return XCTFail("Cancellation must never produce a successful payment")
            }
        } catch StoreKitError.unknown {
            // Xcode 27's failure injection converts generic(userCancelled) into
            // unknown. It is a failed SDK call, not proof of a user cancellation.
            // Keep this explicit: never turn unknown into a successful purchase.
        }
        try await session.setSimulatedError(.generic(.userCancelled), forAPI: StoreKitAppStoreSyncAPI())
        do { _ = try await commerce.restore(); XCTFail("Restore cancellation must not report success") }
        catch is CancellationError { }
        catch StoreKitError.unknown { }
        let queue = try await commerce.unfinished()
        XCTAssertEqual(queue, [])
    }

    func testPendingApprovalIsDeliveredThroughRealTransactionUpdates() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        session.askToBuyEnabled = true
        let commerce = NativeMembershipStoreKit(catalog: [Self.productID: .nonRenewing])
        _ = try await commerce.products()
        let token = UUID()
        let approved = expectation(description: "Approved transaction update")
        var delivered: MembershipTransaction?
        let stream = commerce.updates()
        let listener = Task {
            for await event in stream {
                if case .transaction(let value) = event, value.accountToken == token {
                    delivered = value; approved.fulfill(); break
                }
            }
        }
        defer { listener.cancel() }
        guard case .pending = try await commerce.purchase(productID: Self.productID, accountToken: token) else {
            return XCTFail("Ask to Buy should return pending")
        }
        let pending = try XCTUnwrap(session.allTransactions().first)
        try session.approveAskToBuyTransaction(identifier: pending.identifier)
        await fulfillment(of: [approved], timeout: 10)
        XCTAssertEqual(delivered?.accountToken, token)
        XCTAssertEqual(delivered?.environment, .xcode)
        try await commerce.finish(transactionID: try XCTUnwrap(delivered?.id))
    }

    func testRefundIsDeliveredAsVerifiedRevocation() async throws {
        let session = try configuredSession()
        defer { session.clearTransactions() }
        let commerce = NativeMembershipStoreKit(catalog: [Self.productID: .nonRenewing])
        _ = try await commerce.products()
        let revoked = expectation(description: "Verified refund update")
        var refundedID: String?
        let stream = commerce.updates()
        let listener = Task {
            for await event in stream {
                if case .transaction(let value) = event, value.revoked {
                    refundedID = value.id; revoked.fulfill(); break
                }
            }
        }
        defer { listener.cancel() }
        guard case .transaction(let value) = try await commerce.purchase(productID: Self.productID, accountToken: UUID()) else {
            return XCTFail("A local payment is required before its refund")
        }
        try await commerce.finish(transactionID: value.id)
        try session.refundTransaction(identifier: try XCTUnwrap(UInt(value.id)))
        await fulfillment(of: [revoked], timeout: 10)
        XCTAssertEqual(refundedID, value.id)
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
