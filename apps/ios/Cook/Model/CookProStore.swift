import CookCore
import Foundation
import Observation
import StoreKit
import SwiftUI
import UIKit

struct CookProProductOption: Identifiable, Sendable, Equatable {
    enum Kind: Sendable, Equatable {
        case proLifetime
        case aiMonthly
        case aiYearly
    }

    let id: String
    let title: String
    let subtitle: String
    let displayPrice: String
    let periodLabel: String?
    let kind: Kind

    static func sortOrder(for id: String) -> Int {
        switch id {
        case CookProProductID.proLifetime: return 0
        case CookProProductID.aiYear: return 1
        case CookProProductID.aiMonth: return 2
        default: return 3
        }
    }

    static func kind(for id: String) -> Kind {
        switch id {
        case CookProProductID.aiMonth: return .aiMonthly
        case CookProProductID.aiYear: return .aiYearly
        default: return .proLifetime
        }
    }
}

struct CookProEntitlementReading: Sendable, Equatable {
    let snapshot: CookProSnapshot
    let aiSubscriptionJWS: String?

    static let empty = CookProEntitlementReading(snapshot: CookProSnapshot(), aiSubscriptionJWS: nil)
}

enum CookProPurchaseOutcome: Sendable, Equatable {
    case purchased(productID: String, entitlement: CookProEntitlementReading)
    case cancelled
    case pending
    case unverified
}

protocol CookProStoreBackend: Sendable {
    func currentStorefrontCountryCode() async -> String?
    func currentEntitlements() async -> CookProEntitlementReading
    func fetchProducts(ids: Set<String>) async throws -> [CookProProductOption]
    @MainActor func purchase(productID: String, purchaseAction: PurchaseAction?) async throws -> CookProPurchaseOutcome
    func restorePurchases() async throws
}

@MainActor
private final class LiveCookProStoreBackend: CookProStoreBackend {
    func currentStorefrontCountryCode() async -> String? {
        await Storefront.current?.countryCode
    }

    func currentEntitlements() async -> CookProEntitlementReading {
        let now = Date()
        var hasProLifetime = false
        var bestAIProductID: String?
        var bestAIExpiration: Date?
        var bestAIJWS: String?

        for await result in Transaction.currentEntitlements {
            guard case let .verified(transaction) = result else { continue }
            guard transaction.revocationDate == nil else { continue }

            if transaction.productID == CookProProductID.proLifetime {
                hasProLifetime = true
                continue
            }

            guard CookProProductID.aiSubscriptionIDs.contains(transaction.productID),
                  let expirationDate = transaction.expirationDate,
                  expirationDate > now else {
                continue
            }

            if bestAIExpiration == nil || expirationDate > bestAIExpiration! {
                bestAIProductID = transaction.productID
                bestAIExpiration = expirationDate
                bestAIJWS = result.jwsRepresentation
            }
        }

        let snapshot = CookProSnapshot(
            hasProLifetime: hasProLifetime,
            aiSubscriptionExpiresAt: bestAIExpiration,
            aiSubscriptionProductID: bestAIProductID,
            storefrontCountryCode: nil
        )
        return CookProEntitlementReading(snapshot: snapshot, aiSubscriptionJWS: bestAIJWS)
    }

    func fetchProducts(ids: Set<String>) async throws -> [CookProProductOption] {
        guard !ids.isEmpty else { return [] }
        let loaded = try await Product.products(for: ids)
        return loaded
            .sorted { CookProProductOption.sortOrder(for: $0.id) < CookProProductOption.sortOrder(for: $1.id) }
            .map { product in
                let kind = CookProProductOption.kind(for: product.id)
                let periodLabel: String? = switch kind {
                case .proLifetime: "一次买断 · 永久有效"
                case .aiMonthly: "每月自动续期，可随时取消"
                case .aiYearly: "每年自动续期，更划算"
                }
                return CookProProductOption(
                    id: product.id,
                    title: product.displayName,
                    subtitle: product.description,
                    displayPrice: product.displayPrice,
                    periodLabel: periodLabel,
                    kind: kind
                )
            }
    }

    func purchase(productID: String, purchaseAction: PurchaseAction?) async throws -> CookProPurchaseOutcome {
        guard let product = try await Product.products(for: [productID]).first else {
            throw StoreKitError.unknown
        }

        let result: Product.PurchaseResult
        if let purchaseAction {
            result = try await purchaseAction(product)
        } else {
            result = try await product.purchase()
        }
        switch result {
        case .success(.verified(let transaction)):
            await transaction.finish()
            let updated = await currentEntitlements()
            return .purchased(productID: productID, entitlement: updated)
        case .success(.unverified):
            return .unverified
        case .userCancelled:
            return .cancelled
        case .pending:
            return .pending
        @unknown default:
            return .pending
        }
    }

    func restorePurchases() async throws {
        try await AppStore.sync()
    }
}

private struct IsolatedCookProStoreBackend: CookProStoreBackend {
    let reading: CookProEntitlementReading
    let storefront: String?
    let initialProducts: [CookProProductOption]

    func currentStorefrontCountryCode() async -> String? { storefront }
    func currentEntitlements() async -> CookProEntitlementReading { reading }
    func fetchProducts(ids: Set<String>) async throws -> [CookProProductOption] {
        initialProducts.filter { ids.contains($0.id) }
    }

    @MainActor
    func purchase(productID: String, purchaseAction: PurchaseAction?) async throws -> CookProPurchaseOutcome {
        .cancelled
    }

    func restorePurchases() async throws {}
}

@MainActor
@Observable
final class CookProStore {
    let isIsolated: Bool
    private(set) var snapshot: CookProSnapshot
    private(set) var aiSubscriptionJWS: String?
    private(set) var storefrontCountryCode: String?
    private(set) var products: [CookProProductOption]
    private(set) var isLoadingProducts = false
    private(set) var purchasingProductID: String?
    private(set) var isRestoring = false
    private(set) var noticeMessage: String?
    private(set) var noticeIsError = false
    private(set) var hasLoadedEntitlements = false

    private let backend: CookProStoreBackend
    private let mainlandAIEnabledOverride: Bool?
    private let overseasAIEnabledOverride: Bool?
    private let watchUpdates: Bool

    @ObservationIgnored private var hasStarted = false
    @ObservationIgnored private var storefrontRevision = 0
    @ObservationIgnored private var transactionUpdatesTask: Task<Void, Never>?
    @ObservationIgnored private var storefrontUpdatesTask: Task<Void, Never>?

    init(
        backend: CookProStoreBackend,
        isolated: Bool,
        watchUpdates: Bool,
        initialSnapshot: CookProSnapshot = CookProSnapshot(),
        initialJWS: String? = nil,
        initialStorefront: String? = nil,
        initialProducts: [CookProProductOption] = [],
        mainlandAIEnabled: Bool? = nil,
        overseasAIEnabled: Bool? = nil
    ) {
        self.backend = backend
        self.isIsolated = isolated
        self.watchUpdates = watchUpdates
        self.snapshot = initialSnapshot
        self.aiSubscriptionJWS = initialJWS
        self.storefrontCountryCode = initialStorefront
        self.products = initialProducts
        self.mainlandAIEnabledOverride = mainlandAIEnabled
        self.overseasAIEnabledOverride = overseasAIEnabled
    }

    deinit {
        transactionUpdatesTask?.cancel()
        storefrontUpdatesTask?.cancel()
    }

    static func live(isolated: Bool) -> CookProStore {
        if isolated {
            return CookProStore.isolated()
        }
        return CookProStore(
            backend: LiveCookProStoreBackend(),
            isolated: false,
            watchUpdates: true
        )
    }

    static func isolated(
        snapshot: CookProSnapshot = CookProSnapshot(),
        aiSubscriptionJWS: String? = nil,
        storefrontCountryCode: String? = nil,
        products: [CookProProductOption] = [],
        mainlandAIEnabled: Bool? = nil,
        overseasAIEnabled: Bool? = nil
    ) -> CookProStore {
        let reading = CookProEntitlementReading(snapshot: snapshot, aiSubscriptionJWS: aiSubscriptionJWS)
        return CookProStore(
            backend: IsolatedCookProStoreBackend(
                reading: reading,
                storefront: storefrontCountryCode,
                initialProducts: products
            ),
            isolated: true,
            watchUpdates: false,
            initialSnapshot: snapshot,
            initialJWS: aiSubscriptionJWS,
            initialStorefront: storefrontCountryCode,
            initialProducts: products,
            mainlandAIEnabled: mainlandAIEnabled,
            overseasAIEnabled: overseasAIEnabled
        )
    }

    static var shouldBypassQuotaForUITests: Bool {
        #if DEBUG
            let args = ProcessInfo.processInfo.arguments
            return args.contains("-ui-testing") && !args.contains("-ui-testing-pro-paywall")
        #else
            return false
        #endif
    }

    var isMainlandChinaStorefront: Bool {
        CookStorefrontPolicy.isMainlandChina(countryCode: storefrontCountryCode)
    }

    var isOnlineAIAllowedInCurrentRegion: Bool {
        CookStorefrontPolicy.allowsOnlineAI(
            countryCode: storefrontCountryCode,
            mainlandAIEnabled: mainlandAIEnabledOverride ?? CookFeatures.onlineAIEnabled,
            overseasAIEnabled: overseasAIEnabledOverride ?? CookFeatures.overseasAIEnabled
        )
    }

    var isAISubscriptionAllowedInCurrentRegion: Bool {
        !isMainlandChinaStorefront && (overseasAIEnabledOverride ?? CookFeatures.overseasAIEnabled)
    }

    var appleAIAuthorization: CookAIAuthorization? {
        guard isAISubscriptionAllowedInCurrentRegion, hasActiveAISubscription,
              let jws = aiSubscriptionJWS else { return nil }
        let authorization = CookAIAuthorization.appleJWS(jws)
        return authorization.isValidForTransmission ? authorization : nil
    }

    var hasProAccess: Bool {
        snapshot.hasProAccess()
    }

    var hasActiveAISubscription: Bool {
        snapshot.hasActiveAISubscription()
    }

    var isProStoreAvailableInCurrentRegion: Bool {
        proProduct != nil
    }

    var effectiveProAccess: Bool {
        let hasKnownStorefront = storefrontCountryCode?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
        return !hasKnownStorefront || !isProStoreAvailableInCurrentRegion
            || !hasLoadedEntitlements || Self.shouldBypassQuotaForUITests || hasProAccess
    }

    var fridgeItemLimit: Int? {
        effectiveProAccess ? nil : CookEntitlementPolicy.freeFridgeInStockLimit
    }

    var privateRecipeLimit: Int? {
        effectiveProAccess ? nil : CookEntitlementPolicy.freePrivateRecipeLimit
    }

    var hasBusyOperation: Bool {
        purchasingProductID != nil || isRestoring
    }

    var proProduct: CookProProductOption? {
        guard CookFeatures.proStoreEnabled else { return nil }
        return products.first { $0.id == CookProProductID.proLifetime }
    }

    var aiSubscriptionProducts: [CookProProductOption] {
        guard isAISubscriptionAllowedInCurrentRegion else { return [] }
        return products
            .filter { CookProProductID.aiSubscriptionIDs.contains($0.id) }
            .sorted { CookProProductOption.sortOrder(for: $0.id) < CookProProductOption.sortOrder(for: $1.id) }
    }

    func canAddFridgeItem(currentInStockCount: Int) -> Bool {
        effectiveProAccess
            || CookEntitlementPolicy.canAddFridgeItem(currentInStockCount: currentInStockCount, hasProAccess: hasProAccess)
    }

    func canAddPrivateRecipe(currentCount: Int) -> Bool {
        effectiveProAccess
            || CookEntitlementPolicy.canAddPrivateRecipe(currentCount: currentCount, hasProAccess: hasProAccess)
    }

    func canOpenSpecialShowcase(index: Int) -> Bool {
        effectiveProAccess
            || CookEntitlementPolicy.canOpenSpecialShowcase(index: index, hasProAccess: hasProAccess)
    }

    func clearNotice() {
        noticeMessage = nil
        noticeIsError = false
    }

    func start() async {
        guard !hasStarted else { return }
        hasStarted = true
        startObserversIfNeeded()
        await refreshAll()
    }

    func refreshAll() async {
        await refreshEntitlements()
        await loadProducts()
    }

    func refreshEntitlements() async {
        let storefront = await backend.currentStorefrontCountryCode()
        if storefront != storefrontCountryCode {
            storefrontCountryCode = storefront
            storefrontRevision += 1
            products = []
        }
        let reading = await backend.currentEntitlements()
        snapshot = reading.snapshot
        aiSubscriptionJWS = reading.aiSubscriptionJWS
        hasLoadedEntitlements = true
    }

    func loadProducts() async {
        guard !isLoadingProducts else { return }
        isLoadingProducts = true
        defer { isLoadingProducts = false }

        var revision: Int
        repeat {
            revision = storefrontRevision
            var desiredIDs = isAISubscriptionAllowedInCurrentRegion ? CookProProductID.aiSubscriptionIDs : []
            // StoreKit resolves availability for this product across storefronts.
            if CookFeatures.proStoreEnabled {
                desiredIDs.insert(CookProProductID.proLifetime)
            }
            guard !desiredIDs.isEmpty else {
                products = []
                return
            }
            do {
                let fetchedProducts = try await backend.fetchProducts(ids: desiredIDs)
                if revision == storefrontRevision {
                    products = fetchedProducts
                }
            } catch {
                // Retain the last successful catalog for the same storefront on a transient failure.
            }
        } while revision != storefrontRevision
    }

    func purchase(_ productID: String, using purchaseAction: PurchaseAction? = nil) async {
        guard !hasBusyOperation else { return }
        if productID == CookProProductID.proLifetime, !isProStoreAvailableInCurrentRegion {
            noticeIsError = true
            noticeMessage = "暂未读取到 Cook Pro 的 App Store 价格，请重新加载后再试。"
            return
        }
        if CookProProductID.aiSubscriptionIDs.contains(productID), !isAISubscriptionAllowedInCurrentRegion {
            noticeIsError = true
            noticeMessage = "当前地区暂不支持云端 AI 订阅。"
            return
        }
        purchasingProductID = productID
        clearNotice()
        defer { purchasingProductID = nil }

        do {
            switch try await backend.purchase(productID: productID, purchaseAction: purchaseAction) {
            case let .purchased(purchasedID, entitlement):
                snapshot = entitlement.snapshot
                aiSubscriptionJWS = entitlement.aiSubscriptionJWS
                hasLoadedEntitlements = true
                noticeIsError = false
                if purchasedID == CookProProductID.proLifetime {
                    noticeMessage = "已解锁 Cook Pro 永久买断版。"
                } else {
                    noticeMessage = "云端 AI 订阅已开通，现可直接使用海外云端 AI 功能。"
                }
            case .cancelled:
                noticeIsError = false
                noticeMessage = "已取消购买。"
            case .pending:
                noticeIsError = false
                noticeMessage = "购买正在等待批准，批准后会自动生效。"
            case .unverified:
                noticeIsError = true
                noticeMessage = "App Store 暂未验证此次交易，请稍后重试。"
            }
        } catch {
            noticeIsError = true
            noticeMessage = "暂时无法完成购买，请检查网络与 App Store 账号后重试。"
        }
    }

    func restorePurchases() async {
        guard !hasBusyOperation else { return }
        isRestoring = true
        clearNotice()
        defer { isRestoring = false }

        do {
            try await backend.restorePurchases()
            await refreshEntitlements()
            await loadProducts()
            noticeIsError = false
            if hasActiveAISubscription || hasProAccess {
                noticeMessage = "已恢复此前购买的权益。"
            } else {
                noticeMessage = "未检测到可恢复的有效购买记录。"
            }
        } catch {
            noticeIsError = true
            noticeMessage = "暂时无法恢复购买，请稍后重试。"
        }
    }

    func openManageSubscriptions() async {
        if let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }) {
            do {
                try await AppStore.showManageSubscriptions(in: scene)
                return
            } catch {
                // Fall back to App Store subscription URL below.
            }
        }
        if let url = URL(string: "https://apps.apple.com/account/subscriptions") {
            await UIApplication.shared.open(url)
        }
    }

    private func startObserversIfNeeded() {
        guard watchUpdates else { return }

        if transactionUpdatesTask == nil {
            transactionUpdatesTask = Task { [weak self] in
                for await result in Transaction.updates {
                    guard !Task.isCancelled else { break }
                    if case let .verified(transaction) = result,
                       CookProProductID.allProductIDs.contains(transaction.productID) {
                        await transaction.finish()
                        await self?.refreshEntitlements()
                    }
                }
            }
        }

        if storefrontUpdatesTask == nil {
            storefrontUpdatesTask = Task { [weak self] in
                for await _ in Storefront.updates {
                    guard !Task.isCancelled else { break }
                    await self?.refreshAll()
                }
            }
        }
    }
}
