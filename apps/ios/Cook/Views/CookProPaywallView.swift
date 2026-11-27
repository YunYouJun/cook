import CookCore
import StoreKit
import SwiftUI

enum CookProHighlight: String, Hashable, Identifiable {
    case general
    case fridge
    case privateRecipes
    case showcase
    case byok
    case cloudAI

    var id: String { rawValue }

    var title: String {
        switch self {
        case .general: return "解锁云食册更多能力"
        case .fridge: return "冰箱扩容至不限食材"
        case .privateRecipes: return "私房菜谱不限数量"
        case .showcase: return "解锁全部 3D 菜品展示"
        case .byok: return "使用个人 DeepSeek Key"
        case .cloudAI: return "订阅海外云端 AI"
        }
    }

    var detail: String {
        switch self {
        case .general:
            return "一次买断本机高级功能；已有数据永不删除、不锁定，全程无需登录。"
        case .fridge:
            return "免费版在库食材上限为 \(CookEntitlementPolicy.freeFridgeInStockLimit) 样。已有食材可随时编辑、消耗或删除。"
        case .privateRecipes:
            return "免费版可新建 \(CookEntitlementPolicy.freePrivateRecipeLimit) 道私房菜谱。已有菜谱会始终完整保留在本机与你的 iCloud 中。"
        case .showcase:
            return "第 1 道特色菜支持免费体验 3D 展示。解锁 Cook Pro 后，可浏览全部学会菜品的 3D 展示。"
        case .byok:
            return "解锁 Cook Pro 后，可在设置中保存你自己的 DeepSeek API Key。"
        case .cloudAI:
            return "无需注册或登录云乐坊账号，订阅后直接开启智能找菜、冰箱批量识图与步骤原文讲解。"
        }
    }
}

struct CookProPaywallView: View {
    var highlight: CookProHighlight = .general
    var showsDismissButton = false

    @Environment(CookProStore.self) private var pro
    @Environment(CookAISettings.self) private var ai
    @Environment(YunlefunAccountStore.self) private var accounts
    @Environment(\.purchase) private var purchase
    @Environment(\.dismiss) private var dismiss

    private static let termsURL = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                headerCard

                if let message = pro.noticeMessage {
                    noticeBanner(message, isError: pro.noticeIsError)
                }

                if CookFeatures.proStoreEnabled {
                    buyoutCard
                }

                if pro.isAISubscriptionAllowedInCurrentRegion {
                    cloudAISection
                } else if pro.isProStoreAvailableInCurrentRegion || pro.hasProAccess {
                    mainlandComplianceCard
                }

                footerLinks
            }
            .padding(20)
        }
        .accessibilityIdentifier("proPaywall.scroll")
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Cook Pro")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsDismissButton {
                ToolbarItem(placement: .cancellationAction) {
                    Button("完成") { dismiss() }
                        .accessibilityIdentifier("proPaywall.dismiss")
                }
            }
        }
        .task {
            await pro.refreshAll()
        }
    }

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: pro.hasActiveAISubscription || pro.hasProAccess ? "checkmark.seal.fill" : "sparkles")
                    .font(.title2)
                    .foregroundStyle(pro.hasActiveAISubscription || pro.hasProAccess ? .green : .orange)
                VStack(alignment: .leading, spacing: 2) {
                    Text(statusHeadline)
                        .font(.headline)
                    Text(highlight.title)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            Text(highlight.detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private var statusHeadline: String {
        if pro.hasActiveAISubscription && pro.isAISubscriptionAllowedInCurrentRegion {
            return "云端 AI 订阅生效中（含 Cook Pro 全部权益）"
        }
        if pro.snapshot.hasProLifetime {
            return "已解锁 Cook Pro 永久买断"
        }
        return "免费版 · 核心菜谱与搜索永久免费"
    }

    private var buyoutCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Cook Pro 永久买断")
                        .font(.title3.weight(.semibold))
                    Text("一次购买 · 无需登录 · 永久解锁本机高级功能")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let product = pro.proProduct {
                    Text(product.displayPrice)
                        .font(.title3.weight(.bold))
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                featureRow("冰箱在库食材不限数量（免费 \(CookEntitlementPolicy.freeFridgeInStockLimit) 样）")
                featureRow("私房菜谱不限数量（免费 \(CookEntitlementPolicy.freePrivateRecipeLimit) 道）")
                featureRow("全部学会菜品 3D 模型展示（免费体验第 1 道）")
                if pro.isAISubscriptionAllowedInCurrentRegion {
                    featureRow("支持在「AI 设置」中配置个人 DeepSeek API Key")
                }
                featureRow("已有食材与菜谱永不删除、不锁定")
            }

            if pro.snapshot.hasProLifetime {
                Label("已永久解锁", systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.green)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(Color.green.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            } else if let product = pro.proProduct {
                Button {
                    Task { await buy(CookProProductID.proLifetime) }
                } label: {
                    Group {
                        if pro.purchasingProductID == CookProProductID.proLifetime {
                            ProgressView().frame(maxWidth: .infinity)
                        } else {
                            Text("立即买断（\(product.displayPrice)）").frame(maxWidth: .infinity)
                        }
                    }
                }
                .prominentButtonStyle()
                .controlSize(.large)
                .disabled(pro.hasBusyOperation)
                .accessibilityIdentifier("proPaywall.buyPro")
            } else if pro.isLoadingProducts {
                ProgressView("正在读取 App Store 价格…")
                    .font(.footnote)
            } else {
                Text("暂未读取到 App Store 价格。请检查网络与 App Store 账号后重试。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("重新加载价格") {
                    Task { await pro.refreshAll() }
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .disabled(pro.hasBusyOperation)
                .accessibilityIdentifier("proPaywall.reloadPrice")
            }
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private var cloudAISection: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("海外云端 AI 订阅（免登录）")
                    .font(.title3.weight(.semibold))
                Text("包含全部 Cook Pro 权益 + 3 项云端 AI 能力，由 App Store 直接验证，无需注册云乐坊账号。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(alignment: .leading, spacing: 8) {
                featureRow("AI 智能找菜语义解析（DeepSeek）")
                featureRow("冰箱拍照一键批量识图入库")
                featureRow("烹饪步骤原文 AI 讲解与实时提问")
                featureRow("已包含上述全部 Cook Pro 本机无限权益")
            }

            if let expiry = pro.snapshot.aiSubscriptionExpiresAt, pro.hasActiveAISubscription {
                Label("订阅生效中 · 到期时间 \(expiry.formatted(date: .abbreviated, time: .omitted))",
                      systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.green)
            }

            if pro.aiSubscriptionProducts.isEmpty {
                HStack {
                    if pro.isLoadingProducts {
                        ProgressView().controlSize(.small)
                        Text("正在加载订阅方案…")
                    } else {
                        Text("暂未读取到订阅方案，请稍后刷新。")
                    }
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            } else {
                VStack(spacing: 10) {
                    ForEach(pro.aiSubscriptionProducts) { option in
                        subscriptionOptionRow(option)
                    }
                }
            }

            Text("确认购买即通过你的 Apple 账户扣款。订阅会在当前周期结束前 24 小时内自动续期，除非提前至少 24 小时在 App Store 账户设置中取消。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private func subscriptionOptionRow(_ option: CookProProductOption) -> some View {
        let isCurrent = pro.snapshot.aiSubscriptionProductID == option.id && pro.hasActiveAISubscription
        return Button {
            Task { await buy(option.id) }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(option.title)
                            .font(.subheadline.weight(.semibold))
                        if option.kind == .aiYearly {
                            Text("推荐")
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.orange.opacity(0.18), in: Capsule())
                                .foregroundStyle(.orange)
                        }
                    }
                    if let period = option.periodLabel {
                        Text(period)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if pro.purchasingProductID == option.id {
                    ProgressView().controlSize(.small)
                } else {
                    Text(isCurrent ? "当前方案 · \(option.displayPrice)" : option.displayPrice)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(isCurrent ? .green : .accentColor)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(uiColor: .tertiarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(pro.hasBusyOperation)
        .accessibilityIdentifier("proPaywall.subscribe.\(option.id)")
    }

    private var mainlandComplianceCard: some View {
        Text("当前地区版本仅提供 Cook Pro 本机永久买断，不含自动续期订阅或在线生成式 AI 服务。")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }

    private var footerLinks: some View {
        VStack(spacing: 12) {
            Button {
                Task {
                    await pro.restorePurchases()
                    if ai.onlineAIEnabled {
                        await ai.refreshManagedCapability(accounts: accounts, pro: pro)
                    }
                }
            } label: {
                if pro.isRestoring {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text("恢复购买").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(pro.hasBusyOperation)
            .accessibilityIdentifier("proPaywall.restore")

            if pro.isAISubscriptionAllowedInCurrentRegion || pro.hasActiveAISubscription {
                Button("管理 App Store 订阅") {
                    Task { await pro.openManageSubscriptions() }
                }
                .font(.footnote)
                .accessibilityIdentifier("proPaywall.manageSubscriptions")
            }

            HStack(spacing: 20) {
                NavigationLink("隐私政策") { PrivacyPolicyView() }
                    .accessibilityIdentifier("proPaywall.privacy")
                Link("使用条款（EULA）", destination: Self.termsURL)
                    .accessibilityIdentifier("proPaywall.terms")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .padding(.top, 4)
    }

    private func featureRow(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .font(.footnote)
                .foregroundStyle(Color.accentColor)
                .padding(.top, 2)
            Text(text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func noticeBanner(_ message: String, isError: Bool) -> some View {
        Label(message, systemImage: isError ? "exclamationmark.triangle.fill" : "info.circle.fill")
            .font(.subheadline)
            .foregroundStyle(isError ? .red : .primary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(
                (isError ? Color.red : Color.accentColor).opacity(0.1),
                in: RoundedRectangle(cornerRadius: 12)
            )
            .accessibilityIdentifier("proPaywall.notice")
    }

    private func buy(_ productID: String) async {
        await pro.purchase(productID, using: purchase)
        guard CookProProductID.aiSubscriptionIDs.contains(productID),
              pro.hasActiveAISubscription,
              pro.isAISubscriptionAllowedInCurrentRegion else { return }
        await ai.refreshManagedCapability(accounts: accounts, pro: pro)
        if ai.provider == .local && ai.isManagedAvailable {
            ai.selectProvider(.managedDeepSeek)
        }
    }
}

#Preview("Cook Pro · 中国大陆") {
    NavigationStack {
        CookProPaywallView(highlight: .fridge)
    }
    .environment(CookProStore.isolated(
        storefrontCountryCode: "CHN",
        products: [
            CookProProductOption(
                id: CookProProductID.proLifetime,
                title: "Cook Pro 永久买断",
                subtitle: "解锁无限冰箱食材、无限私房菜谱与全部 3D 展示",
                displayPrice: "¥28.00",
                periodLabel: "一次买断 · 永久有效",
                kind: .proLifetime
            ),
        ],
        mainlandAIEnabled: false,
        overseasAIEnabled: true
    ))
    .environment(CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: ManagedCookAIService(endpoint: nil), onlineAIEnabled: false))
    .environment(YunlefunAccountStore(vault: MemoryYunlefunAccountVault()))
}

#Preview("Cook Pro · 海外地区") {
    NavigationStack {
        CookProPaywallView(highlight: .cloudAI)
    }
    .environment(CookProStore.isolated(
        storefrontCountryCode: "USA",
        products: [
            CookProProductOption(
                id: CookProProductID.proLifetime,
                title: "Cook Pro 永久买断",
                subtitle: "解锁本机全部高级功能",
                displayPrice: "$3.99",
                periodLabel: "一次买断 · 永久有效",
                kind: .proLifetime
            ),
            CookProProductOption(
                id: CookProProductID.aiYear,
                title: "云端 AI 年度订阅",
                subtitle: "包含 Pro 买断权益 + 全年云端 AI",
                displayPrice: "$19.99",
                periodLabel: "每年自动续期，更划算",
                kind: .aiYearly
            ),
            CookProProductOption(
                id: CookProProductID.aiMonth,
                title: "云端 AI 月度订阅",
                subtitle: "包含 Pro 买断权益 + 月度云端 AI",
                displayPrice: "$2.99",
                periodLabel: "每月自动续期，可随时取消",
                kind: .aiMonthly
            ),
        ],
        mainlandAIEnabled: false,
        overseasAIEnabled: true
    ))
    .environment(CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: ManagedCookAIService(endpoint: nil), onlineAIEnabled: true))
    .environment(YunlefunAccountStore(vault: MemoryYunlefunAccountVault()))
}
