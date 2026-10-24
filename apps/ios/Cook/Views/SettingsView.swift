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
                    NavigationLink { ReminderSettingsView() } label: {
                        SettingsRowLabel("提醒", symbol: "bell.badge.fill", color: .red)
                    }
                    NavigationLink { DataSyncSettingsView() } label: {
                        SettingsRowLabel("数据与同步", symbol: "icloud.fill", color: .blue)
                    }
                    NavigationLink { PrivacySettingsView() } label: {
                        SettingsRowLabel("隐私", symbol: "hand.raised.fill", color: .blue)
                    }
                }
                Section {
                    NavigationLink { HelpSettingsView() } label: {
                        SettingsRowLabel("帮助与反馈", symbol: "questionmark", color: .purple)
                    }
                    NavigationLink { AboutSettingsView() } label: {
                        SettingsRowLabel("关于云食册", symbol: "info", color: .gray)
                    }
                }
            }
            .navigationTitle("我的")
        }
    }
}

private struct SettingsRowLabel: View {
    let title: String
    let symbol: String
    let color: Color

    init(_ title: String, symbol: String, color: Color) {
        self.title = title
        self.symbol = symbol
        self.color = color
    }

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(color, in: RoundedRectangle(cornerRadius: 6))
                .accessibilityHidden(true)
        }
    }
}

#Preview {
    SettingsView().environment(YunlefunAccountStore(vault: MemoryYunlefunAccountVault()))
}
