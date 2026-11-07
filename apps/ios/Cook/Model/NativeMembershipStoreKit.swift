import CookCore
import Foundation
import StoreKit
import SwiftUI

/// The caller supplies Cook's reviewed product catalog. No other application's
/// SKU or subscription model is inferred from the platform implementation.
@MainActor final class NativeMembershipStoreKit: MembershipCommerce {
    private let catalog: [String: MembershipProductKind]
    private var loaded: [String: Product] = [:]
    private var transactions: [String: StoreKit.Transaction] = [:]
    var purchaseAction: PurchaseAction?
    var purchaseError: StoreKitError?
    var syncAction: (() async throws -> Void)?
    var syncError: StoreKitError?

    init(catalog: [String: MembershipProductKind]) { self.catalog = catalog }

    func products() async throws -> [MembershipProduct] {
        let values = try await Product.products(for: catalog.keys)
        let accepted = values.filter { product in
            switch catalog[product.id] {
            case .nonRenewing: return product.type == .nonRenewable
            case .autoRenewable: return product.type == .autoRenewable
            case nil: return false
            }
        }
        loaded = Dictionary(uniqueKeysWithValues: accepted.map { ($0.id, $0) })
        return accepted.map { MembershipProduct(id: $0.id, title: $0.displayName, displayPrice: $0.displayPrice) }
            .sorted { $0.id < $1.id }
    }

    func purchase(productID: String, accountToken: UUID) async throws -> MembershipPurchaseResult {
        guard let product = loaded[productID] else { throw MembershipPurchaseFailure.invalidResponse }
        do {
            if let purchaseError { throw purchaseError }
            let outcome: Product.PurchaseResult
            if let purchaseAction { outcome = try await purchaseAction(product, options: [.appAccountToken(accountToken)]) }
            else { outcome = try await product.purchase(options: [.appAccountToken(accountToken)]) }
            switch outcome {
            case .success(let result):
                guard let value = try record(result) else { throw MembershipPurchaseFailure.invalidResponse }
                return .transaction(value)
            case .userCancelled: return .cancelled
            case .pending: return .pending
            @unknown default: throw MembershipPurchaseFailure.invalidResponse
            }
        } catch StoreKitError.userCancelled { return .cancelled }
    }

    func unfinished() async throws -> [MembershipTransaction] {
        var values: [MembershipTransaction] = []
        for await result in StoreKit.Transaction.unfinished {
            if let value = try record(result) { values.append(value) }
        }
        return values
    }

    func restore() async throws -> [MembershipTransaction] {
        do {
            if let syncError { throw syncError }
            if let syncAction { try await syncAction() }
            else { try await AppStore.sync() }
        }
        catch StoreKitError.userCancelled { throw CancellationError() }
        // currentEntitlements contains only the latest non-renewing purchase per
        // product. Replaying history lets the server reconcile every durable grant.
        var values: [String: MembershipTransaction] = [:]
        for await result in StoreKit.Transaction.all {
            if let value = try record(result) { values[value.id] = value }
        }
        return values.values.sorted { $0.id < $1.id }
    }

    func finish(transactionID: String) async throws {
        // Refresh StoreKit's synchronization snapshot before finishing, as in
        // the platform adapter; never finish an unverified transaction.
        for await result in StoreKit.Transaction.unfinished {
            if case .unverified(let value, _) = result, String(value.id) == transactionID {
                throw MembershipPurchaseFailure.unverified
            }
            if case .verified(let value) = result, String(value.id) == transactionID {
                guard try record(result) != nil else { throw MembershipPurchaseFailure.invalidResponse }
                await value.finish(); transactions[transactionID] = nil; return
            }
        }
        if let value = transactions[transactionID] {
            await value.finish(); transactions[transactionID] = nil
        }
    }

    func updates() -> AsyncStream<MembershipTransactionEvent> {
        AsyncStream { continuation in
            let observer = Task { [weak self] in
                for await result in StoreKit.Transaction.updates {
                    guard let self, !Task.isCancelled else { break }
                    do {
                        if let value = try self.record(result) { continuation.yield(.transaction(value)) }
                    } catch { continuation.yield(.unverified) }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in observer.cancel() }
        }
    }

    private func record(_ result: VerificationResult<StoreKit.Transaction>) throws -> MembershipTransaction? {
        // Filtering an unrelated SKU never makes its payload trusted.
        guard catalog[result.unsafePayloadValue.productID] != nil else { return nil }
        guard case .verified(let value) = result else { throw MembershipPurchaseFailure.unverified }
        let environment: MembershipStoreEnvironment
        switch value.environment {
        case .production: environment = .production
        case .sandbox: environment = .sandbox
        case .xcode: environment = .xcode
        default: throw MembershipPurchaseFailure.environmentMismatch
        }
        let id = String(value.id)
        transactions[id] = value
        return MembershipTransaction(id: id, productID: value.productID, accountToken: value.appAccountToken,
                                     environment: environment, revoked: value.revocationDate != nil)
    }
}
