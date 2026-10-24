import SwiftUI

struct YunlefunAccountSection: View {
    @Environment(YunlefunAccountStore.self) private var accounts

    var body: some View {
        Section {
            if let account = accounts.account {
                Label {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(account.displayName).font(.headline)
                        Text("云乐坊账号").font(.caption).foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "person.crop.circle.fill").font(.title).foregroundStyle(.tint)
                }
                .accessibilityIdentifier("account.profile")
                Link("管理云乐坊账号", destination: URL(string: "https://www.yunle.fun/settings?tab=account")!)
                Link("申请注销云乐坊账号", destination: URL(string: "https://www.yunle.fun/settings?tab=account")!)
                Button("退出登录", role: .destructive) { Task { await accounts.signOut() } }
                    .disabled(accounts.isBusy)
                    .accessibilityIdentifier("account.signOut")
            } else if #available(iOS 17.4, *) {
                Button { Task { await accounts.signIn() } } label: {
                    Label("登录云乐坊账号", systemImage: "person.crop.circle")
                }
                .disabled(accounts.isBusy)
                .accessibilityIdentifier("account.signIn")
            } else {
                Text("升级至 iOS 17.4 后可登录云乐坊账号")
                    .foregroundStyle(.secondary)
            }
            if accounts.isBusy {
                ProgressView("正在处理账号…")
            }
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
        } header: {
            Text("云乐坊账号")
        } footer: {
            Text("收藏、浏览记录、偏好、冰箱和私人菜谱继续通过 iCloud 同步。登录或退出云乐坊不会改变这些数据；与网页版暂不自动同步。")
        }
    }
}
