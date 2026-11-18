import SwiftUI

/// Membership checks send account authorization, never cooking text or a personal API key.
struct AISettingsView: View {
    @Environment(CookAISettings.self) private var settings
    @Environment(YunlefunAccountStore.self) private var accounts
    @State private var showsKeyEditor = false

    var body: some View {
        Form {
            Section {
                Button { settings.selectProvider(.local) } label: {
                    providerLabel(.local, detail: "无需联网", accessory: selectionMark(for: .local))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(settings.provider == .local ? .isSelected : [])
                .accessibilityIdentifier("aiSettings.provider.local")

                managedProvider
                    .accessibilityIdentifier("aiSettings.managed")

                personalProvider
            } header: {
                Text("识别方式")
            } footer: {
                Text("普通搜索始终在本机。只有确认使用 AI 时，才会发送本次内容。")
            }
        }
        .accessibilityIdentifier("aiSettings.form")
        .navigationTitle("AI 设置")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showsKeyEditor) {
            DeepSeekKeyEditor()
        }
        .task(id: accounts.sessionRevision) { await settings.refreshManagedCapability(accounts: accounts) }
    }

    @ViewBuilder
    private var managedProvider: some View {
        switch settings.managedStatus {
        case .checking:
            providerLabel(.managedDeepSeek, detail: "正在核验会员资格…", isChecking: true)
        case .loginRequired:
            NavigationLink { YunlefunAccountView() } label: {
                providerLabel(.managedDeepSeek, detail: "登录云乐坊后核验资格")
            }
        case .ready where settings.isManagedAvailable:
            Button { settings.selectProvider(.managedDeepSeek) } label: {
                providerLabel(.managedDeepSeek, detail: "云乐坊会员专享 · 无需 API Key", accessory: selectionMark(for: .managedDeepSeek))
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(settings.provider == .managedDeepSeek ? .isSelected : [])
        case .unconfigured:
            providerLabel(.managedDeepSeek, detail: "此版本暂未开放")
        default:
            let detail = switch settings.managedStatus {
            case .membershipRequired: "当前账号未开通云乐坊会员"
            case .expired: "云乐坊会员已到期"
            case .ready: "会员资格需要重新核验"
            default: settings.managedStatus.message
            }
            Button {
                Task { await settings.refreshManagedCapability(accounts: accounts) }
            } label: {
                providerLabel(.managedDeepSeek, detail: detail, accessory: "arrow.clockwise")
            }
            .buttonStyle(.plain)
            .disabled(accounts.isBusy)
            .accessibilityHint("重新核验会员资格")
        }
    }

    @ViewBuilder
    private var personalProvider: some View {
        if settings.hasAPIKey {
            HStack(spacing: 8) {
                Button { settings.selectProvider(.ownDeepSeek) } label: {
                    providerLabel(.ownDeepSeek, detail: "仅支持 DeepSeek · 已保存", accessory: selectionMark(for: .ownDeepSeek))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(settings.provider == .ownDeepSeek ? .isSelected : [])
                .accessibilityIdentifier("aiSettings.provider.own-deepseek")

                Button { showsKeyEditor = true } label: {
                    Image(systemName: "pencil")
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("管理 DeepSeek API Key")
                .accessibilityIdentifier("aiSettings.editKey")
            }
        } else {
            Button { showsKeyEditor = true } label: {
                providerLabel(.ownDeepSeek, detail: "仅支持 DeepSeek · 添加 Key", accessory: "chevron.right")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("aiSettings.addKey")
        }
    }

    private func providerLabel(_ provider: CookAIProvider, detail: String, accessory: String? = nil, isChecking: Bool = false) -> some View {
        let (title, symbol, color): (String, String, Color) = switch provider {
        case .local: ("仅本地识别", "iphone", .green)
        case .managedDeepSeek: ("会员内置服务", "star.fill", .orange)
        case .ownDeepSeek: ("个人 API Key", "key.fill", .blue)
        }
        return HStack(spacing: 12) {
            SettingsRowLabel(title, symbol: symbol, color: color, subtitle: detail)
            Group {
                if isChecking {
                    ProgressView().controlSize(.small)
                } else if let accessory {
                    Image(systemName: accessory)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(accessory == "chevron.right" ? Color.secondary : Color.accentColor)
                }
            }
            .frame(width: isChecking || accessory != nil ? 22 : 0)
            .accessibilityHidden(true)
        }
        .padding(.vertical, 3)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    private func selectionMark(for provider: CookAIProvider) -> String? {
        settings.provider == provider ? "checkmark" : nil
    }
}

private struct DeepSeekKeyEditor: View {
    @Environment(CookAISettings.self) private var settings
    @Environment(\.dismiss) private var dismiss
    @State private var keyInput = ""
    @State private var errorMessage: String?
    @State private var confirmsDeletion = false
    @State private var connectionCheck: Task<Void, Never>?
    @State private var connectionResult: Result<DeepSeekIntentService.ConnectionStatus, DeepSeekIntentError>?
    @FocusState private var keyIsFocused: Bool

    private static let keyPortal = URL(string: "https://platform.deepseek.com/api_keys")!

    private var canSave: Bool { !keyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField(settings.hasAPIKey ? "粘贴或输入新的 API Key" : "粘贴或输入 API Key", text: $keyInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.asciiCapable)
                        .focused($keyIsFocused)
                        .privacySensitive()
                        .accessibilityLabel("DeepSeek API Key")
                        .accessibilityIdentifier("aiSettings.keyInput")

                    PasteButton(payloadType: String.self) { strings in
                        Task { @MainActor in
                            if let key = strings.first { keyInput = key }
                        }
                    }
                    .accessibilityLabel("粘贴 API Key")

                    Button(action: testConnection) {
                        HStack {
                            Label(connectionCheck == nil ? "测试连接" : "正在测试连接…", systemImage: "link")
                            if connectionCheck != nil {
                                Spacer()
                                ProgressView().controlSize(.small).accessibilityHidden(true)
                            }
                        }
                    }
                    .disabled(!canSave || connectionCheck != nil)
                    .accessibilityIdentifier("aiSettings.testConnection")

                    if let connectionResult {
                        let (message, symbol, color) = connectionFeedback(connectionResult)
                        Label {
                            Text(message)
                        } icon: {
                            Image(systemName: symbol).foregroundStyle(color)
                        }
                        .font(.subheadline)
                        .accessibilityIdentifier("aiSettings.connectionStatus")
                    }

                    Link(destination: Self.keyPortal) {
                        Label("前往 DeepSeek 获取 Key", systemImage: "arrow.up.right.square")
                    }

                    if let errorMessage {
                        Label(errorMessage, systemImage: "exclamationmark.circle")
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("aiSettings.message")
                    }
                } header: {
                    HStack(spacing: 8) {
                        // Official DeepSeek API documentation site mark.
                        Image("deepseek-mark")
                            .renderingMode(.original)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 23, height: 18)
                            .accessibilityHidden(true)
                        Text("DeepSeek")
                            .accessibilityAddTraits(.isHeader)
                    }
                    .textCase(nil)
                } footer: {
                    Text("只有点「测试连接」才会把输入的 Key 发给 DeepSeek 核验，不发送做菜内容，也不调用模型。Key 只保存在本机；智能识别由你的 DeepSeek 账号计费。")
                }

                if settings.hasAPIKey {
                    Section {
                        Button("删除本机 Key", role: .destructive) { confirmsDeletion = true }
                            .accessibilityIdentifier("aiSettings.deleteKey")
                    }
                }
            }
            .navigationTitle(settings.hasAPIKey ? "管理 API Key" : "添加 API Key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .accessibilityIdentifier("aiSettings.cancelKey")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(settings.hasAPIKey ? "保存新 Key" : "保存并使用") { saveKey() }
                        .disabled(!canSave)
                        .accessibilityIdentifier("aiSettings.saveKey")
                }
            }
            .confirmationDialog("删除本机 DeepSeek Key？", isPresented: $confirmsDeletion, titleVisibility: .visible) {
                Button("删除 Key", role: .destructive) {
                    if settings.deleteAPIKey() { dismiss() }
                    else { errorMessage = settings.message }
                }
            } message: {
                Text(settings.provider == .ownDeepSeek ? "删除后将切回本地识别。" : "之后可以重新添加。")
            }
            .onChange(of: keyInput) {
                cancelConnectionCheck()
                connectionResult = nil
                errorMessage = nil
            }
            .onDisappear { cancelConnectionCheck() }
        }
    }

    private func testConnection() {
        guard canSave, connectionCheck == nil else { return }
        keyIsFocused = false
        connectionResult = nil
        let key = keyInput
        connectionCheck = Task {
            defer {
                if !Task.isCancelled, key == keyInput { connectionCheck = nil }
            }
            let result: Result<DeepSeekIntentService.ConnectionStatus, DeepSeekIntentError>
            do {
                result = .success(try await DeepSeekIntentService().checkConnection(key: key))
            } catch is CancellationError {
                return
            } catch {
                result = .failure(error as? DeepSeekIntentError ?? .unavailable)
            }
            guard !Task.isCancelled, key == keyInput else { return }
            connectionResult = result
        }
    }

    private func cancelConnectionCheck() {
        connectionCheck?.cancel()
        connectionCheck = nil
    }

    private func connectionFeedback(_ result: Result<DeepSeekIntentService.ConnectionStatus, DeepSeekIntentError>) -> (String, String, Color) {
        switch result {
        case .success(.available):
            return ("连接成功，Key 可用。", "checkmark.circle.fill", .green)
        case .success(.insufficientBalance):
            return ("Key 有效，但 DeepSeek 余额不足。", "exclamationmark.circle.fill", .orange)
        case let .failure(error):
            let message = switch error {
            case .authentication: "Key 无效或已失效，请检查后重试。"
            case .timedOut: "连接超时，请重试。"
            case .offline: "无法连接 DeepSeek，请检查网络。"
            case .insufficientBalance, .rateLimited, .invalidKey: error.localizedDescription
            default: "DeepSeek 暂时无法核验 Key，请稍后重试。"
            }
            return (message, "exclamationmark.circle.fill", .red)
        }
    }

    private func saveKey() {
        let isAdding = !settings.hasAPIKey
        guard settings.saveAPIKey(keyInput) else {
            errorMessage = settings.message
            return
        }
        if isAdding { settings.selectProvider(.ownDeepSeek) }
        dismiss()
    }
}

#Preview {
    NavigationStack { AISettingsView() }
        .environment(CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: ManagedCookAIService(endpoint: nil)))
        .environment(YunlefunAccountStore(vault: MemoryYunlefunAccountVault()))
}
