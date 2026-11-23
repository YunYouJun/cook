import SafariServices
import SwiftUI

/// 类似系统设置的账号摘要；登录与会话提示仍可直接从首页操作。
struct YunlefunAccountSection: View {
    @Environment(YunlefunAccountStore.self) private var accounts

    var body: some View {
        Section {
            if let account = accounts.account {
                NavigationLink {
                    YunlefunAccountView()
                } label: {
                    HStack(spacing: 14) {
                        AccountAvatar(url: account.avatarURL, size: 56)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(account.displayName).font(.title3.weight(.semibold))
                                .foregroundStyle(.primary)
                            Text("云乐坊账号").font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 8)
                        AccountActivityIndicator()
                    }
                    .padding(.vertical, 6)
                }
                .accessibilityValue(accounts.isBusy ? "正在更新账号" : "")
                .accessibilityIdentifier("account.profile")
            } else {
                AccountSignInButton()
                WeChatSignInButton()
                WeChatSignupLink()
            }
            AccountSessionStatus()
        }
    }
}

struct YunlefunAccountView: View {
    private typealias WebsiteEntry = YunlefunAccountStore.WebsiteEntry

    @Environment(YunlefunAccountStore.self) private var accounts
    @Environment(\.openURL) private var openURL
    @State private var browser = SettingsWebsitePresenter()
    @State private var browserAuthorization = SystemYunlefunAuthorization()
    @State private var preparingWebsite: (entry: WebsiteEntry, task: Task<Void, Never>)?
    @State private var handoffDelegate: AccountWebHandoffDelegate?
    @State private var websiteUnavailable = false
    private static let settingsURL = URL(string: "https://www.yunle.fun/settings?tab=account")!

    var body: some View {
        List {
            if let account = accounts.account {
                Section {
                    VStack(spacing: 12) {
                        AccountAvatar(url: account.avatarURL, size: 72)
                        VStack(spacing: 4) {
                            Text(account.displayName).font(.title2.weight(.semibold))
                            if let username = account.username, !username.isEmpty {
                                Text("@\(username)").font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .overlay(alignment: .topTrailing) { AccountActivityIndicator() }
                    .accessibilityElement(children: .combine)
                    .accessibilityValue(accounts.isBusy ? "正在更新账号" : "")
                    .listRowBackground(Color.clear)
                }
                Section {
                    let canResume = accounts.approvedWebsite?.entry == .manage
                    websiteRow(canResume ? "继续打开账号管理" : "管理云乐坊账号", symbol: "person.crop.circle", color: .indigo,
                               subtitle: canResume ? "沿用本次浏览器授权" : "个人资料与登录方式",
                               isOpening: preparingWebsite?.entry == .manage) {
                        openAccountWebsite(.manage)
                    }
                        .disabled(accounts.isBusy || preparingWebsite != nil)
                        .accessibilityIdentifier("account.manage")
                    if accounts.canUseWeChat {
                        WeChatAuthorizationButton(binding: true)
                        if let message = accounts.weChatBindingMessage {
                            Text(message).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    if accounts.canUseWeChat {
                        Text("绑定后可用微信登录此账号。")
                    }
                }
            } else {
                Section {
                    AccountSignInButton()
                    WeChatSignInButton()
                    WeChatSignupLink()
                }
            }
            if accounts.message != nil || accounts.canRetry {
                Section { AccountSessionStatus() }
            }
            if accounts.account != nil {
                Section {
                    let canResume = accounts.approvedWebsite?.entry == .delete
                    websiteRow(canResume ? "继续打开注销入口" : "申请注销账号", symbol: "person.crop.circle.badge.minus", color: .gray,
                               subtitle: canResume ? "沿用本次浏览器授权" : nil,
                               isOpening: preparingWebsite?.entry == .delete) {
                        openAccountWebsite(.delete)
                    }
                        .disabled(accounts.isBusy || preparingWebsite != nil)
                        .accessibilityIdentifier("account.delete")
                    Button("退出登录", role: .destructive) { Task { await accounts.signOut() } }
                        .disabled(accounts.isBusy)
                        .accessibilityIdentifier("account.signOut")
                } footer: {
                    Text("注销需要在云乐坊网站核对账号并确认。退出登录仅影响这台设备，保留现有的收藏、冰箱和私人菜谱。")
                }
            }
        }
        .navigationTitle("云乐坊账号")
        .navigationBarTitleDisplayMode(.inline)
        .background { browser.presentationAnchor }
        .onDisappear { preparingWebsite?.task.cancel() }
        .onChange(of: accounts.sessionRevision) {
            preparingWebsite?.task.cancel()
            handoffDelegate?.cancel()
            handoffDelegate = nil
        }
        .task(id: accounts.approvedWebsite?.destination.request) {
            guard let website = accounts.approvedWebsite else { return }
            do {
                try await Task.sleep(for: .seconds(website.validity.remaining()))
                try Task.checkCancellation()
                accounts.discardApprovedWebsite(website)
            } catch {}
        }
        .alert("暂时无法打开账号管理", isPresented: $websiteUnavailable) {
            Button("好", role: .cancel) {}
        } message: {
            Text("请检查网络后，从这里重试。")
        }
    }

    private func openAccountWebsite(_ entry: WebsiteEntry) {
        guard preparingWebsite == nil, let session = accounts.websiteSession else { return }
        let transport: YunlefunAccountWebHandoff.Transport = ProcessInfo.processInfo.isiOSAppOnMac ? .systemBrowser : .inAppSafari
        let task = Task {
            defer { preparingWebsite = nil }
            do {
                if transport == .systemBrowser, let website = accounts.approvedWebsite, website.entry == entry {
                    if website.canResume(in: session) {
                        await openApprovedWebsite(website)
                        return
                    }
                    accounts.discardApprovedWebsite(website)
                }
                let supportsHandoff = try await accounts.supportsAccountWebHandoff(in: transport)
                guard !Task.isCancelled, accounts.websiteSession == session else { return }
                guard supportsHandoff else {
                    if transport == .systemBrowser { openURL(Self.settingsURL) }
                    else if !browser.open(Self.settingsURL) { websiteUnavailable = true }
                    return
                }
                let handoff = try YunlefunAccountWebHandoff()
                if transport == .systemBrowser {
                    try await openAccountInSystemBrowser(handoff, session: session, entry: entry)
                    return
                }
                let delegate = AccountWebHandoffDelegate(handoff: handoff) { fields in
                    try await accounts.approveAccountWebHandoff(fields: fields, handoff: handoff, for: session)
                }
                if browser.open(handoff.startURL, delegate: delegate) {
                    handoffDelegate = delegate // Safari's delegate is weak.
                } else { websiteUnavailable = true }
            } catch is CancellationError {
                // The user closed the system authorization window.
            } catch {
                if !Task.isCancelled, accounts.websiteSession == session { websiteUnavailable = true }
            }
        }
        preparingWebsite = (entry, task)
    }

    private func openAccountInSystemBrowser(_ handoff: YunlefunAccountWebHandoff, session: YunlefunAccountStore.WebsiteSession,
                                            entry: WebsiteEntry) async throws {
        let callback = try await browserAuthorization.authorizeInBrowser(
            handoff.systemBrowserStartURL, expiresAfter: YunlefunAccountWebHandoff.lifetime
        )
        try Task.checkCancellation()
        guard let completion = handoff.systemBrowserCompletion(for: callback) else {
            throw YunlefunAccountError.invalidCallback
        }
        let website = try await accounts.approveAccountWebsiteForBrowser(completion, handoff: handoff, for: session, entry: entry)
        await openApprovedWebsite(website)
    }

    private func openApprovedWebsite(_ website: YunlefunAccountStore.ApprovedWebsite) async {
        await accounts.openApprovedAccountWebsite(website) { url in
            await withCheckedContinuation { continuation in
                openURL(url) { continuation.resume(returning: $0) }
            }
        }
    }

    private func websiteRow(_ title: String, symbol: String, color: Color, subtitle: String? = nil,
                            isOpening: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                SettingsRowLabel(title, symbol: symbol, color: color, subtitle: subtitle)
                if isOpening {
                    ProgressView().controlSize(.small).accessibilityHidden(true)
                } else {
                    Image(systemName: "arrow.up.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(ProcessInfo.processInfo.isiOSAppOnMac ? "在默认浏览器打开云乐坊网站" : "在 App 内打开云乐坊网站")
    }
}

@MainActor
private final class AccountWebHandoffDelegate: NSObject, SFSafariViewControllerDelegate {
    private var handoff: YunlefunAccountWebHandoff?
    private var approval: Task<Void, Never>?
    private let approve: ([String: String]) async throws -> Void

    init(handoff: YunlefunAccountWebHandoff, approve: @escaping ([String: String]) async throws -> Void) {
        self.handoff = handoff
        self.approve = approve
    }

    func safariViewController(_ controller: SFSafariViewController, initialLoadDidRedirectTo url: URL) {
        guard let fields = handoff?.approvalFields(for: url) else { return }
        handoff = nil
        approval = Task { [approve] in
            // The website displays a retry message if no approval arrives.
            try? await approve(fields)
        }
    }

    func safariViewControllerDidFinish(_ controller: SFSafariViewController) {
        cancel()
    }

    func cancel() {
        handoff = nil
        approval?.cancel()
        approval = nil
    }
}

private struct WeChatSignInButton: View {
    @Environment(YunlefunAccountStore.self) private var accounts

    var body: some View {
        if accounts.canUseWeChat {
            WeChatAuthorizationButton(binding: false)
        }
    }
}

private struct WeChatAuthorizationButton: View {
    @Environment(YunlefunAccountStore.self) private var accounts
    @Environment(\.openURL) private var openURL
    @State private var confirming = false
    let binding: Bool

    private var title: String { binding ? "绑定微信到当前账号" : "使用微信登录" }

    var body: some View {
        Button { confirming = true } label: {
            SettingsRowLabel(title, symbol: "bubble.left.and.bubble.right", color: .green)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(accounts.isBusy)
        .accessibilityIdentifier(binding ? "account.bindWeChat" : "account.signInWithWeChat")
        .confirmationDialog(title, isPresented: $confirming, titleVisibility: .visible) {
            Button("同意并继续") {
                Task {
                    if binding { await accounts.bindWeChat() }
                    else { await accounts.signInWithWeChat() }
                }
            }
            Button("查看微信 OpenSDK 个人信息处理规则") {
                openURL(URL(string: "https://weixin.qq.com/cgi-bin/readtemplate?t=opensdk_agreement&lang=zh_CN")!)
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("继续会打开微信。云食册仅使用微信授权信息来验证身份并登录或绑定云乐坊账号。")
        }
    }
}

private struct AccountSignInButton: View {
    @Environment(YunlefunAccountStore.self) private var accounts

    var body: some View {
        if #available(iOS 17.4, *) {
            Button { Task { await accounts.signIn() } } label: {
                HStack(spacing: 14) {
                    AccountAvatar(url: nil, size: 56)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("登录云乐坊账号").font(.headline)
                        Text("不登录也能使用做菜功能").font(.subheadline).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    AccountActivityIndicator()
                }
                .padding(.vertical, 6)
            }
            .disabled(accounts.isBusy)
            .accessibilityValue(accounts.isBusy ? "正在处理账号" : "")
            .accessibilityIdentifier("account.signIn")
            YunlefunAppAuthorizationStatus()
        } else {
            Text("升级至 iOS 17.4 后可登录云乐坊账号").foregroundStyle(.secondary)
        }
    }
}

struct YunlefunAppAuthorizationStatus: View {
    @Environment(YunlefunAccountStore.self) private var accounts

    var body: some View {
        if accounts.isAwaitingYunlefunApp {
            Text("请在云乐坊 App 中确认授权").font(.footnote).foregroundStyle(.secondary)
            Button("改用网页登录") { accounts.continueYunlefunOnWeb() }
            Button("取消登录", role: .cancel) { accounts.cancelYunlefunAppAuthorization() }
        }
    }
}

/// 在原账号行内预留进度位置，避免授权开始或取消时插入列表行、挤压文字。
private struct AccountActivityIndicator: View {
    @Environment(YunlefunAccountStore.self) private var accounts

    var body: some View {
        Color.clear
            .frame(width: 24, height: 24)
            .overlay {
                if accounts.isBusy {
                    ProgressView()
                        .controlSize(.small)
                        .tint(.secondary)
                }
            }
            .accessibilityHidden(true)
    }
}

private struct AccountSessionStatus: View {
    @Environment(YunlefunAccountStore.self) private var accounts

    var body: some View {
        if let message = accounts.message {
            Text(message).font(.footnote).foregroundStyle(.secondary)
                .accessibilityIdentifier("account.message")
        }
        if accounts.canRetry {
            Button("重新检查登录状态") { Task { await accounts.retry() } }
                .disabled(accounts.isBusy)
            Button("退出本机账号", role: .destructive) { Task { await accounts.signOut() } }
                .disabled(accounts.isBusy)
        }
    }
}

private struct AccountAvatar: View {
    let url: URL?
    let size: CGFloat

    var body: some View {
        AsyncImage(url: url) { phase in
            if let image = phase.image {
                image.resizable().scaledToFill()
            } else {
                Image(systemName: "person.crop.circle.fill")
                    .resizable().scaledToFit().foregroundStyle(.gray.opacity(0.45))
            }
        }
        .frame(width: size, height: size)
        .background(Color(uiColor: .tertiarySystemGroupedBackground), in: Circle())
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}
