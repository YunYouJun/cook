import SwiftUI

/// Local configuration does not contact DeepSeek or inspect account membership.
struct AISettingsView: View {
    @Environment(CookAISettings.self) private var settings
    @State private var keyInput = ""

    var body: some View {
        Form {
            Section {
                providerButton(.local)
                providerButton(.ownDeepSeek)
                    .disabled(!settings.hasAPIKey)
                LabeledContent(CookAIProvider.managedDeepSeek.title, value: "尚未接通")
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("aiSettings.managed")
            } header: {
                Text("智能找菜")
            } footer: {
                Text("内置 DeepSeek 仅向云乐坊会员提供，须由服务端核验会员资格。目前尚未接通。普通用户和会员均可使用自己的 Key；普通搜索始终在本机完成。")
            }

            Section {
                LabeledContent("本机 API Key", value: settings.hasAPIKey ? "已保存" : "未保存")
                    .accessibilityIdentifier("aiSettings.keyStatus")
                SecureField(settings.hasAPIKey ? "输入新的 Key 以替换" : "输入 DeepSeek API Key", text: $keyInput)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .privacySensitive()
                    .accessibilityLabel("DeepSeek API Key")
                    .accessibilityIdentifier("aiSettings.keyInput")
                Button("保存 Key") {
                    if settings.saveAPIKey(keyInput) { keyInput = "" }
                }
                .disabled(keyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("aiSettings.saveKey")
                if settings.hasAPIKey {
                    Button("删除本机 Key", role: .destructive) {
                        if settings.deleteAPIKey() { keyInput = "" }
                    }
                    .accessibilityIdentifier("aiSettings.deleteKey")
                }
            } header: {
                Text("自己的 DeepSeek Key")
            } footer: {
                Text("Key 仅保存在这台设备的钥匙串，不进入 iCloud、做菜数据或导出文件。只有主动选择智能识别并确认发送时，本次文本才会直发 DeepSeek；调用费用由你的 DeepSeek 账号承担。保存 Key 或切换选项不会发送请求。")
            }
            if let message = settings.message {
                Section {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                        .accessibilityIdentifier("aiSettings.message")
                }
            }
        }
        .navigationTitle("AI 设置")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { keyInput = "" }
    }

    private func providerButton(_ provider: CookAIProvider) -> some View {
        Button {
            settings.selectProvider(provider)
        } label: {
            HStack {
                Text(provider.title).foregroundStyle(.primary)
                Spacer()
                if settings.provider == provider {
                    Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                }
            }
        }
        .accessibilityAddTraits(settings.provider == provider ? .isSelected : [])
        .accessibilityIdentifier("aiSettings.provider.\(provider.rawValue)")
    }
}

#Preview {
    NavigationStack { AISettingsView() }
        .environment(CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault()))
}
