import SwiftUI

/// 首页只保留账号摘要和按用途分组的入口，具体设置由各二级页管理。
struct SettingsView: View {
    var body: some View {
        NavigationStack {
            List {
                YunlefunAccountSection()
                Section("我的厨房") {
                    NavigationLink { PrivateRecipesView() } label: {
                        SettingsRowLabel("我的菜谱", symbol: "book.closed.fill", color: .orange)
                    }
                    NavigationLink { PersonalCatalogView() } label: {
                        SettingsRowLabel("食材和厨具", symbol: "carrot.fill", color: .green)
                    }
                    NavigationLink { CookingPreferencesView() } label: {
                        SettingsRowLabel("做菜偏好", symbol: "slider.horizontal.3", color: .orange)
                    }
                }
                Section("设置") {
                    NavigationLink { AISettingsView() } label: {
                        SettingsRowLabel("AI 设置", symbol: "sparkles", color: .purple)
                    }
                    .accessibilityIdentifier("settings.ai")
                    NavigationLink { ReminderSettingsView() } label: {
                        SettingsRowLabel("提醒", symbol: "bell.badge.fill", color: .red)
                    }
                    NavigationLink { DataSyncSettingsView() } label: {
                        SettingsRowLabel("数据与同步", symbol: "icloud.fill", color: .blue)
                    }
                    NavigationLink { PrivacySettingsView() } label: {
                        SettingsRowLabel("隐私", symbol: "hand.raised.fill", color: .blue)
                    }
                    .accessibilityIdentifier("settings.privacy")
                }
                Section {
                    NavigationLink { HelpSettingsView() } label: {
                        SettingsRowLabel("帮助与反馈", symbol: "questionmark", color: .purple)
                    }
                    .accessibilityIdentifier("settings.help")
                    NavigationLink { AboutSettingsView() } label: {
                        SettingsRowLabel("关于云食册", symbol: "info", color: .gray)
                    }
                }
            }
            .accessibilityIdentifier("settings.list")
            .navigationTitle("我的")
        }
    }
}

#Preview {
    SettingsView().environment(YunlefunAccountStore(vault: MemoryYunlefunAccountVault()))
        .environment(CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault()))
}
