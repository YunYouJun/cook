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
            }
            AccountSessionStatus()
        }
    }
}

struct YunlefunAccountView: View {
    @Environment(YunlefunAccountStore.self) private var accounts

    var body: some View {
        List {
            if let account = accounts.account {
                Section {
                    VStack(spacing: 12) {
                        AccountAvatar(url: account.avatarURL, size: 88)
                        VStack(spacing: 4) {
                            Text(account.displayName).font(.title2.weight(.semibold))
                            if let username = account.username, !username.isEmpty {
                                Text("@\(username)").font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .overlay(alignment: .topTrailing) { AccountActivityIndicator() }
                    .accessibilityElement(children: .combine)
                    .accessibilityValue(accounts.isBusy ? "正在更新账号" : "")
                    .listRowBackground(Color.clear)
                }
                Section {
                    Link(destination: URL(string: "https://www.yunle.fun/settings?tab=account")!) {
                        Label("管理云乐坊账号", systemImage: "person.crop.circle")
                    }
                    Link(destination: URL(string: "https://www.yunle.fun/settings?tab=account")!) {
                        Label("申请注销账号", systemImage: "person.crop.circle.badge.minus")
                    }
                } footer: {
                    Text("在云乐坊网站管理个人资料、登录方式与账号注销。")
                }
            } else {
                Section { AccountSignInButton() }
            }
            if accounts.message != nil || accounts.canRetry {
                Section { AccountSessionStatus() }
            }
            Section {
                NavigationLink { DataSyncSettingsView() } label: {
                    Label("数据与同步", systemImage: "icloud")
                }
            } footer: {
                Text("会员可在「数据与同步」主动启用收藏和浏览历史跨平台同步。账号记录与本机、iCloud 记录分别保留。")
            }
            if accounts.account != nil {
                Section {
                    Button("退出登录", role: .destructive) { Task { await accounts.signOut() } }
                        .disabled(accounts.isBusy)
                        .accessibilityIdentifier("account.signOut")
                } footer: {
                    Text("仅退出这台设备的云乐坊账号，保留现有的收藏、冰箱和私人菜谱。")
                }
            }
        }
        .navigationTitle("云乐坊账号")
        .navigationBarTitleDisplayMode(.inline)
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
