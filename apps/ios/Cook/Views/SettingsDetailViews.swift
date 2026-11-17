import SafariServices
import SwiftUI

struct CookingPreferencesView: View {
    var body: some View {
        Form {
            Section {
                PreferenceRows()
            } footer: {
                Text("家里常备、忌口和不吃辣会用于筛选菜谱，也可以在做菜页的筛选中调整。")
            }
        }
        .navigationTitle("做菜偏好")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct ReminderSettingsView: View {
    var body: some View {
        Form {
            Section {
                FridgeReminderRows()
            } header: {
                Text("冰箱临期提醒")
            } footer: {
                Text("冰箱里的食材到期前一天和当天提醒一次。提醒只在这台设备上安排，不经过服务器。")
            }
        }
        .navigationTitle("提醒")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PrivacySettingsView: View {
    @Environment(FeedbackClient.self) private var feedback

    var body: some View {
        Form {
            Section {
                NavigationLink("隐私政策") { PrivacyPolicyView() }
            }
            if feedback.isConfigured {
                Section {
                    Toggle("帮助改进推荐", isOn: Binding(get: { feedback.signalsEnabled }, set: { feedback.setSignalConsent($0) }))
                        .accessibilityIdentifier("privacy.shareSignals")
                } footer: {
                    Text("默认关闭。开启表示同意向腾讯云 CloudBase 分享后续的食材组合、厨具、推荐方式和菜谱点击情况，以及 App 版本和每次启动的随机标识，用来补菜、调整排序和限制请求次数。不含输入原文、账号或设备硬件标识符。关闭不影响使用。详见隐私政策。")
                }
            }
        }
        .navigationTitle("隐私")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AboutSettingsView: View {
    @Environment(RecipeStore.self) private var store
    @State private var browser = SettingsWebsitePresenter()
    private static let legacyURL = URL(string: "https://v1.cook.yunyoujun.cn")!

    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "-"
        let build = info?["CFBundleVersion"] as? String ?? "-"
        return "\(version)（\(build)）"
    }

    var body: some View {
        List {
            Section("App 备案") {
                Text("苏ICP备2023020936号-2A")
                    .textSelection(.enabled)
                Link(destination: URL(string: "https://beian.miit.gov.cn/")!) {
                    Label("工信部备案查询", systemImage: "arrow.up.right.square")
                }
            }
            Section {
                LabeledContent("版本", value: appVersion)
                LabeledContent("菜谱", value: "\(store.recipes.count) 道")
                NavigationLink { RecipeDataSettingsView() } label: {
                    LabeledContent("数据版本", value: store.data.meta.dataVersion)
                }
                .accessibilityIdentifier("about.recipeData")
                Link("更新日志", destination: URL(string: "https://docs.yunyoujun.cn/projects/cook/changelog")!)
                Link("网页版：食用手册", destination: URL(string: "https://cook.yunyoujun.cn")!)
            }
            Section("开源项目") {
                githubLink("在 GitHub 上反馈", destination: URL(string: "https://github.com/YunYouJun/cook/issues")!)
                githubLink("参与讨论", destination: URL(string: "https://github.com/YunYouJun/cook/discussions")!)
            }
            Section {
                Button { browser.open(Self.legacyURL) } label: {
                    Text("怀旧版本")
                        .font(.footnote)
                        .underline()
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("在 App 内打开旧版网页")
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
        }
        .navigationTitle("关于云食册")
        .navigationBarTitleDisplayMode(.inline)
        .background { browser.presentationAnchor }
    }

    private func githubLink(_ title: String, destination: URL) -> some View {
        Link(destination: destination) {
            HStack(spacing: 12) {
                SettingsRowLabel(title, image: Image("github"), color: Color(white: 0.12))
                Image(systemName: "arrow.up.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("打开 GitHub")
    }
}

/// Present Safari modally from the scene containing this page, as required by SafariServices.
@MainActor
final class SettingsWebsitePresenter {
    private weak var anchor: UIView?

    var presentationAnchor: some View {
        Anchor(presenter: self)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    @discardableResult
    func open(_ url: URL, delegate: (any SFSafariViewControllerDelegate)? = nil) -> Bool {
        guard var presenter = anchor?.window?.rootViewController else { return false }
        while let presented = presenter.presentedViewController {
            guard !presented.isBeingDismissed, !(presented is SFSafariViewController) else { return false }
            presenter = presented
        }
        let safari = SFSafariViewController(url: url)
        safari.dismissButtonStyle = .done
        safari.delegate = delegate
        presenter.present(safari, animated: true)
        return true
    }

    private struct Anchor: UIViewRepresentable {
        let presenter: SettingsWebsitePresenter

        func makeUIView(context: Context) -> UIView {
            let anchor = UIView()
            presenter.anchor = anchor
            return anchor
        }

        func updateUIView(_ view: UIView, context: Context) {}
    }
}
