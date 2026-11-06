import CookCore
import StoreKit
import SwiftUI

struct MembershipView: View {
    @Environment(MembershipStore.self) private var membership
    @Environment(YunlefunAccountStore.self) private var accounts
    @Environment(\.purchase) private var purchase
    @State private var managesSubscriptions = false

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Label { Text("云乐坊会员") } icon: { Image(systemName: "star.circle.fill").accessibilityHidden(true) }
                        .font(.title2.bold())
                    if membership.signedIn {
                        Text(membership.accountName).foregroundStyle(.secondary)
                        TimelineView(.periodic(from: .now, by: 60)) { context in
                            if let entitlement = membership.coordinator.entitlement {
                                if entitlement.isActive(at: context.date), let expiry = entitlement.expiresAt {
                                    Text("有效会员").font(.headline).accessibilityIdentifier("membership.active")
                                    Text("有效期至 \(expiry.formatted(date: .abbreviated, time: .omitted))")
                                        .foregroundStyle(.secondary)
                                } else {
                                    Text("尚未开通会员或会员已到期").accessibilityIdentifier("membership.inactive")
                                }
                            } else if membership.isLoading {
                                ProgressView("正在核对会员状态")
                            } else {
                                Text("暂时无法读取会员状态").foregroundStyle(.secondary)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        Text("登录购买时使用的云乐坊账号，即可读取 Web 和 iOS 的有效会员。")
                            .foregroundStyle(.secondary)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, 8)
                if membership.isTestingPurchases {
                    Text("内购测试权益，与正式会员分别核对。测试购买不会改变正式会员。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            Section {
                Label("收藏和浏览历史跨平台同步", systemImage: "icloud")
                Label("会员智能找菜", systemImage: "sparkles")
                Text("基础做菜功能无需会员。智能服务以当前可用能力和额度为准。")
                    .font(.footnote).foregroundStyle(.secondary)
            } header: {
                Text("会员可用功能").foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
            }
            if !membership.signedIn {
                Section {
                    Button("登录云乐坊账号") { Task { await accounts.signIn() } }
                        .disabled(accounts.isBusy)
                        .accessibilityIdentifier("membership.signIn")
                }
            } else if !membership.purchasesEnabled {
                Section { Text("App 内购买暂未开放。已购会员可在登录后核对现有权益。") }
            } else {
                products(renews: false)
                products(renews: true)
                Section {
                    Button("恢复购买") { Task { await membership.coordinator.restore() } }
                        .disabled(membership.isLoading || membership.coordinator.isBusy || accounts.isBusy)
                        .accessibilityIdentifier("membership.restore")
                    Button("管理自动续费") { managesSubscriptions = true }
                    if !membership.coordinator.pendingTransactionIDs.isEmpty {
                        Button("继续核对购买") { Task { await membership.coordinator.retryUnfinished() } }
                            .disabled(membership.coordinator.isBusy)
                            .accessibilityIdentifier("membership.retry")
                    }
                } footer: {
                    Text("恢复购买会向 App Store 同步记录，并核对当前云乐坊账号，不重复增加会员时长。")
                }
            }
            if let notice = membership.notice {
                Section {
                    Text(notice).fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("membership.message")
                }
            }
            Section {
                Button("重新核对会员状态") { Task { await membership.reload() } }
                    .disabled(membership.isLoading || membership.coordinator.isBusy)
                    .accessibilityIdentifier("membership.refresh")
                NavigationLink("隐私政策") { PrivacyPolicyView() }
                Link("使用条款", destination: URL(string: "https://cook.yunyoujun.cn/about/terms.html")!)
            }
            #if DEBUG
                if let switchAccount = membership.switchTestAccount {
                    Section("测试操作") {
                        Button("切换测试账号", action: switchAccount)
                            .accessibilityIdentifier("membership.fixture.switchAccount")
                    }
                }
            #endif
        }
        .accessibilityIdentifier("membership.content")
        .navigationTitle("会员")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .manageSubscriptionsSheet(isPresented: $managesSubscriptions)
        .task { await membership.reload() }
    }

    @ViewBuilder private func products(renews: Bool) -> some View {
        let products = membership.products.filter { membership.renews($0.id) == renews }
        Section {
            ForEach(products) { product in
                Button {
                    Task { await membership.purchase(product.id, using: purchase) }
                } label: {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(product.title).foregroundStyle(.primary)
                        Text(product.displayPrice).font(.headline).foregroundStyle(.tint)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 5)
                    .contentShape(Rectangle())
                }
                .disabled(membership.isLoading || membership.coordinator.isBusy || accounts.isBusy)
                .accessibilityIdentifier("membership.buy.\(product.id)")
            }
            if products.isEmpty {
                Text(membership.catalogMessage ?? "此类会员商品暂不可用，请稍后重试。")
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text(renews ? "自动续费" : "单次购买").foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
        } footer: {
            Text(renews
                 ? "按 App Store 显示的周期和价格自动续费，可在 App Store 管理。已有 Web 或单次时长不会暂停扣款；取消续费不影响已经生效的时长。"
                 : "仅本次扣款，不自动续费。购买绑定当前云乐坊账号，已付款的新周期按会员规则顺延。")
        }
    }
}
