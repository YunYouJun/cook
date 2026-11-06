import SwiftUI

/// Consent and status stay in native controls; all sync work belongs to the store.
struct AccountSyncSettingsSection: View {
    @Environment(YunlefunAccountStore.self) private var accounts
    let data: AccountUserDataStore
    let copyAppleData: () throws -> Void
    private enum Confirmation { case enable, copy }
    @State private var confirmation: Confirmation?
    @State private var isChanging = false
    @State private var copyFailure = false

    var body: some View {
        Section {
            if data.ownerID != nil {
                Toggle("同步收藏和浏览历史", isOn: Binding(get: { data.isEnabled }, set: { enabled in
                    if enabled { confirmation = .enable }
                    else { changeEnabled(false) }
                }))
                .disabled(data.cacheFailed || isChanging)
                .accessibilityIdentifier("sync.enabled")
                if data.isEnabled {
                    Text("当前使用云乐坊账号的收藏和浏览历史")
                        .font(.footnote).foregroundStyle(.secondary)
                    LabeledContent("账号记录", value: "\(data.snapshot.document.favorites.count) 个收藏，\(data.snapshot.document.histories.count) 条浏览记录")
                        .accessibilityIdentifier("sync.counts")
                    Button { data.requestSync() } label: {
                        HStack {
                            Label("立即同步", systemImage: "arrow.triangle.2.circlepath")
                            Spacer()
                            if data.isSyncing { ProgressView() }
                        }
                    }
                    .disabled(data.isSyncing)
                    .accessibilityIdentifier("sync.now")
                    Button("复制本机与 iCloud 收藏和历史") { confirmation = .copy }
                        .accessibilityIdentifier("sync.copyLocal")
                    if let date = data.lastSyncedAt {
                        LabeledContent("上次同步", value: date.formatted(date: .abbreviated, time: .shortened))
                    }
                }
                if let message = data.message {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                        .accessibilityIdentifier("sync.message")
                }
            } else {
                NavigationLink("登录云乐坊账号") { YunlefunAccountView() }
            }
        } header: {
            Text("会员跨平台同步")
        } footer: {
            Text("有效云乐坊会员可与网页版同步。启用后使用独立账号记录，首次不会上传本机与 iCloud 内容。关闭或退出后恢复 Apple 设备记录；账号记录保留在本机，登录同一账号后可继续使用。冰箱、偏好和私人菜谱仍由本机与 iCloud 管理。")
        }
        .confirmationDialog(confirmation == .copy ? "复制到当前云乐坊账号？" : "启用账号同步？",
                            isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } }),
                            titleVisibility: .visible, presenting: confirmation) { action in
            switch action {
            case .enable:
                Button("启用同步") { changeEnabled(true) }
            case .copy:
                Button("复制收藏和浏览历史") {
                    do { try copyAppleData() }
                    catch { copyFailure = true }
                }
            }
        } message: {
            switch $0 {
            case .enable:
                Text("收藏和浏览历史将通过腾讯云 CloudBase 与当前云乐坊账号同步。原来的本机与 iCloud 记录保留，需要时可以主动复制。")
            case .copy:
                Text("本机与 iCloud 中现有的收藏和浏览历史会加入当前账号，并上传到云端；原记录保留。")
            }
        }
        .alert("复制未能保存", isPresented: $copyFailure) {
            Button("好", role: .cancel) {}
        } message: { Text("原有数据已保留，请检查设备储存空间后重试。") }
        .onChange(of: data.ownerID) { confirmation = nil }
    }

    private func changeEnabled(_ enabled: Bool) {
        isChanging = true
        Task {
            await data.setEnabled(enabled)
            isChanging = false
        }
    }
}
