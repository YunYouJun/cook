import CookCore
import Foundation
import Observation
import StoreKit
import SwiftUI

@MainActor @Observable final class MembershipStore {
    let coordinator: MembershipPurchaseCoordinator
    let purchasesEnabled: Bool
    let isTestingPurchases: Bool
    private(set) var supportedProductIDs: Set<String> = []
    private(set) var isLoading = false
    private(set) var catalogMessage: String?
    private let service: MembershipServicing
    private let commerce: MembershipCommerce
    private let identity: () -> MembershipPurchaseIdentity?
    private let name: () -> String
    private let renewableProductIDs: Set<String>
    @ObservationIgnored private var loadID: UUID?
    @ObservationIgnored private var loadingOwner: MembershipPurchaseIdentity?
    @ObservationIgnored private var purchaseActionID: UUID?
    #if DEBUG
        var switchTestAccount: (() -> Void)?
    #endif

    var signedIn: Bool { identity() != nil }
    var accountName: String { name() }
    var products: [MembershipProduct] { coordinator.products.filter { supportedProductIDs.contains($0.id) } }

    init(commerce: MembershipCommerce, service: MembershipServicing, catalog: [String: MembershipProductKind], purchasesEnabled: Bool,
         identity: @escaping () -> MembershipPurchaseIdentity?, name: @escaping () -> String) {
        self.commerce = commerce; self.service = service; self.identity = identity; self.name = name
        self.purchasesEnabled = purchasesEnabled; isTestingPurchases = service.environment != .production
        renewableProductIDs = Set(catalog.compactMap { id, kind in if case .autoRenewable = kind { return id }; return nil })
        coordinator = MembershipPurchaseCoordinator(commerce: commerce, settlement: service, productIDs: Set(catalog.keys), identity: identity)
    }

    func renews(_ id: String) -> Bool { renewableProductIDs.contains(id) }

    static func live(accounts: YunlefunAccountStore, isolated: Bool) -> MembershipStore {
        let enabled = CookFeatures.membershipPurchasesEnabled && !isolated
        let catalog = enabled ? MembershipPlan.catalog : [:]
        let disabledTransport: MembershipService.Transport = { _ in throw URLError(.unsupportedURL) }
        let service = MembershipService(accounts: accounts, purchasesEnabled: enabled,
                                        transport: isolated ? disabledTransport : nil)
        return MembershipStore(commerce: NativeMembershipStoreKit(catalog: catalog), service: service,
                               catalog: catalog, purchasesEnabled: enabled,
                               identity: { MembershipService.identity(accounts: accounts) },
                               name: { accounts.account?.displayName ?? "云乐坊账号" })
    }

    func reload() async {
        let operation = UUID(), owner = identity()
        if isLoading, loadingOwner == owner { return }
        loadingOwner = owner
        loadID = operation; isLoading = true; catalogMessage = nil
        defer { if loadID == operation { isLoading = false; loadID = nil; loadingOwner = nil } }
        await coordinator.start()
        guard identity() == owner, loadID == operation else { return }
        guard let owner, purchasesEnabled else { supportedProductIDs = []; return }
        do {
            let supported = try await service.supportedProducts(for: owner)
            guard identity() == owner, loadID == operation else { return }
            supportedProductIDs = supported
            await coordinator.loadProducts()
        } catch is CancellationError { }
        catch {
            if identity() == owner, loadID == operation {
                supportedProductIDs = []; catalogMessage = "暂时无法读取可用会员商品，请稍后重试。"
            }
        }
    }

    func accountChanged() {
        loadID = nil; loadingOwner = nil; isLoading = false; supportedProductIDs = []; catalogMessage = nil
        coordinator.accountChanged()
    }

    func purchase(_ id: String, using action: PurchaseAction) async {
        guard supportedProductIDs.contains(id), !coordinator.isBusy else { return }
        let actionID = UUID()
        purchaseActionID = actionID
        (commerce as? NativeMembershipStoreKit)?.purchaseAction = action
        defer {
            if purchaseActionID == actionID {
                (commerce as? NativeMembershipStoreKit)?.purchaseAction = nil
                purchaseActionID = nil
            }
        }
        await coordinator.purchase(productID: id)
    }

    var notice: String? {
        switch coordinator.notice {
        case .cancelled: return "已取消购买，未开通新的会员。"
        case .pendingApproval: return "购买正在等待批准，批准后会自动继续处理。"
        case .awaitingConfirmation: return "购买记录正在核对。未确认的交易会保留，恢复联网或重新打开 App 后可继续处理。"
        case .confirmed: return isTestingPurchases ? "测试购买记录已确认，不改变正式会员。" : "购买记录已确认，请查看更新后的会员状态。"
        case .unavailable: return "暂时无法完成操作，请稍后重试。"
        case .unverified: return "购买尚未通过 App Store 验证，交易会保留，请稍后重试。"
        case .anotherAccount: return "当前账号无法认领这笔购买，请使用购买时的云乐坊账号重试。"
        case nil: return nil
        }
    }
}
