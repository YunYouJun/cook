import Foundation
import Observation

public enum MembershipStoreEnvironment: String, Codable, Sendable { case production, sandbox, xcode }
public enum MembershipProductKind: Sendable { case nonRenewing, autoRenewable }

public struct MembershipProduct: Equatable, Sendable, Identifiable {
    public let id: String
    public let title: String
    public let displayPrice: String
    public init(id: String, title: String, displayPrice: String) {
        self.id = id; self.title = title; self.displayPrice = displayPrice
    }
}

public struct MembershipPurchaseIdentity: Equatable, Sendable {
    public let userID: String
    public let sessionID: UUID
    public let accountToken: UUID
    public init(userID: String, sessionID: UUID, accountToken: UUID) {
        self.userID = userID; self.sessionID = sessionID; self.accountToken = accountToken
    }
}

public struct MembershipTransaction: Equatable, Sendable {
    public let id: String
    public let productID: String
    public let accountToken: UUID?
    public let environment: MembershipStoreEnvironment
    public let revoked: Bool
    public init(id: String, productID: String, accountToken: UUID?, environment: MembershipStoreEnvironment, revoked: Bool = false) {
        self.id = id; self.productID = productID; self.accountToken = accountToken
        self.environment = environment; self.revoked = revoked
    }
}

public enum MembershipPurchaseResult: Sendable { case transaction(MembershipTransaction), cancelled, pending }
public enum MembershipTransactionEvent: Sendable { case transaction(MembershipTransaction), unverified }

@MainActor public protocol MembershipCommerce: AnyObject {
    func products() async throws -> [MembershipProduct]
    func purchase(productID: String, accountToken: UUID) async throws -> MembershipPurchaseResult
    func unfinished() async throws -> [MembershipTransaction]
    /// Only the user-initiated restore action may synchronize the App Store.
    func restore() async throws -> [MembershipTransaction]
    func finish(transactionID: String) async throws
    func updates() -> AsyncStream<MembershipTransactionEvent>
}

public struct MembershipEntitlement: Equatable, Sendable {
    public let userID: String
    public let expiresAt: Date?
    public init(userID: String, expiresAt: Date?) { self.userID = userID; self.expiresAt = expiresAt }
    public func isActive(at now: Date) -> Bool { expiresAt.map { $0 > now } ?? false }
}

public struct MembershipSettlementReceipt: Sendable {
    public let transactionID: String
    public let environment: MembershipStoreEnvironment
    public let confirmed: Bool
    public init(transactionID: String, environment: MembershipStoreEnvironment, confirmed: Bool) {
        self.transactionID = transactionID; self.environment = environment; self.confirmed = confirmed
    }
}

@MainActor public protocol MembershipSettling: AnyObject {
    var environment: MembershipStoreEnvironment { get }
    func preparePurchase(productID: String, for identity: MembershipPurchaseIdentity) async throws
    func entitlement(for identity: MembershipPurchaseIdentity) async throws -> MembershipEntitlement
    /// Reconcile revocations as well as purchases; never extend an already settled transaction.
    func settle(_ transaction: MembershipTransaction, for identity: MembershipPurchaseIdentity) async throws -> MembershipSettlementReceipt
}

public enum MembershipPurchaseNotice: Equatable, Sendable {
    case cancelled, pendingApproval, awaitingConfirmation, confirmed, unavailable, unverified, anotherAccount
}
public enum MembershipPurchaseFailure: Error { case invalidResponse, accountChanged, unverified, environmentMismatch }

/// Account-scoped orchestration. Only the server decides effective membership;
/// StoreKit's persistent unfinished queue owns recovery across process restarts.
@MainActor @Observable public final class MembershipPurchaseCoordinator {
    public private(set) var products: [MembershipProduct] = []
    public private(set) var entitlement: MembershipEntitlement?
    public private(set) var pendingTransactionIDs: Set<String> = []
    public private(set) var notice: MembershipPurchaseNotice?
    public private(set) var isBusy = false
    private let commerce: MembershipCommerce
    private let settlement: MembershipSettling
    private let identity: () -> MembershipPurchaseIdentity?
    private let productIDs: Set<String>
    private var owner: MembershipPurchaseIdentity?
    private var operation: UUID?
    @ObservationIgnored private var observer: Task<Void, Never>?
    @ObservationIgnored private var inFlight: [String: Task<Void, Error>] = [:]

    public init(commerce: MembershipCommerce, settlement: MembershipSettling, productIDs: Set<String>,
                identity: @escaping () -> MembershipPurchaseIdentity?) {
        self.commerce = commerce; self.settlement = settlement; self.productIDs = productIDs; self.identity = identity
    }

    deinit { observer?.cancel() }

    public func accountChanged() {
        owner = identity(); entitlement = nil; pendingTransactionIDs = []; notice = nil
        operation = nil; isBusy = false
        // A started Apple payment cannot be cancelled by signing out. Its result
        // remains unfinished and is picked up only by its original account.
    }

    public func start() async {
        if observer == nil {
            let stream = commerce.updates()
            observer = Task { [weak self] in
                for await event in stream {
                    guard let self, !Task.isCancelled else { return }
                    self.synchronizeOwner()
                    guard let owner = self.owner else { continue }
                    switch event {
                    case .transaction(let transaction): await self.process([transaction], owner: owner)
                    case .unverified: self.notice = .unverified
                    }
                }
            }
        }
        await refresh()
        await retryUnfinished()
    }

    public func loadProducts() async {
        do {
            let loaded = try await commerce.products()
            guard Set(loaded.map(\.id)).count == loaded.count,
                  loaded.allSatisfy({ productIDs.contains($0.id) && !$0.title.isEmpty && !$0.displayPrice.isEmpty }) else {
                throw MembershipPurchaseFailure.invalidResponse
            }
            products = loaded
        } catch { products = []; notice = .unavailable }
    }

    public func refresh() async {
        synchronizeOwner()
        guard let owner else { return }
        do {
            let value = try await settlement.entitlement(for: owner)
            try requireCurrent(owner)
            guard value.userID == owner.userID, value.expiresAt?.timeIntervalSince1970.isFinite ?? true else {
                throw MembershipPurchaseFailure.invalidResponse
            }
            entitlement = value
        } catch {
            if isCurrent(owner) {
                entitlement = nil
                notice = pendingTransactionIDs.isEmpty ? .unavailable : .awaitingConfirmation
            }
        }
    }

    public func purchase(productID: String) async {
        synchronizeOwner()
        guard !isBusy, let owner, products.contains(where: { $0.id == productID }) else { return }
        let operationID = UUID()
        operation = operationID; isBusy = true; notice = nil
        defer { if operation == operationID { operation = nil; isBusy = false } }
        do {
            try await settlement.preparePurchase(productID: productID, for: owner)
            try requireCurrent(owner)
            switch try await commerce.purchase(productID: productID, accountToken: owner.accountToken) {
            case .cancelled: if isCurrent(owner) { notice = .cancelled }
            case .pending: if isCurrent(owner) { notice = .pendingApproval }
            case .transaction(let transaction):
                try requireCurrent(owner)
                await process([transaction], owner: owner)
            }
        } catch MembershipPurchaseFailure.unverified { if isCurrent(owner) { notice = .unverified } }
        catch { if isCurrent(owner) { notice = .unavailable } }
    }

    public func retryUnfinished() async {
        synchronizeOwner()
        guard let owner else { return }
        do { await process(try await commerce.unfinished(), owner: owner) }
        catch { if isCurrent(owner) { notice = .awaitingConfirmation } }
    }

    public func restore() async {
        synchronizeOwner()
        guard !isBusy, let owner else { return }
        let operationID = UUID()
        operation = operationID; isBusy = true; notice = nil
        defer { if operation == operationID { operation = nil; isBusy = false } }
        do {
            await process(try await commerce.restore(), owner: owner, refreshAfter: false)
            if isCurrent(owner) { await refresh() }
        } catch is CancellationError { if isCurrent(owner) { notice = .cancelled } }
        catch { if isCurrent(owner) { notice = .awaitingConfirmation } }
    }

    private func process(_ values: [MembershipTransaction], owner: MembershipPurchaseIdentity, refreshAfter: Bool = true) async {
        var seen = Set<String>()
        var touchedAccount = false
        for transaction in values where seen.insert(transaction.id).inserted {
            guard isCurrent(owner) else { return }
            guard productIDs.contains(transaction.productID) else { continue }
            guard transaction.accountToken == owner.accountToken else { notice = .anotherAccount; continue }
            touchedAccount = true
            pendingTransactionIDs.insert(transaction.id)
            do {
                // A listener, purchase result and restore may deliver the same ID
                // concurrently. Coalesce locally; server idempotency remains required.
                let key = transaction.id + ":" + owner.sessionID.uuidString
                if let pending = inFlight[key] { try await pending.value }
                else {
                    let pending = Task { try await self.confirm(transaction, owner: owner) }
                    inFlight[key] = pending
                    defer { inFlight[key] = nil }
                    try await pending.value
                }
                if isCurrent(owner) { pendingTransactionIDs.remove(transaction.id); notice = .confirmed }
            } catch { if isCurrent(owner) { notice = .awaitingConfirmation } }
        }
        if refreshAfter, touchedAccount, isCurrent(owner) { await refresh() }
    }

    private func confirm(_ transaction: MembershipTransaction, owner: MembershipPurchaseIdentity) async throws {
        try requireCurrent(owner)
        guard transaction.id.range(of: #"^[0-9]{1,32}$"#, options: .regularExpression) != nil else {
            throw MembershipPurchaseFailure.invalidResponse
        }
        guard transaction.environment == settlement.environment else { throw MembershipPurchaseFailure.environmentMismatch }
        let receipt = try await settlement.settle(transaction, for: owner)
        try requireCurrent(owner)
        guard receipt.confirmed, receipt.transactionID == transaction.id, receipt.environment == transaction.environment else {
            throw MembershipPurchaseFailure.invalidResponse
        }
        // Revocation reconciliation changes only the server's Apple grant. A
        // separately purchased Web entitlement is never removed by this client.
        try await commerce.finish(transactionID: transaction.id)
        try requireCurrent(owner)
    }

    private func synchronizeOwner() { if owner != identity() { accountChanged() } }
    private func isCurrent(_ value: MembershipPurchaseIdentity) -> Bool { owner == value && identity() == value }
    private func requireCurrent(_ value: MembershipPurchaseIdentity) throws {
        guard isCurrent(value) else { throw MembershipPurchaseFailure.accountChanged }
    }
}
