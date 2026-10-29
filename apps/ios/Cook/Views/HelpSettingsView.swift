import SwiftUI
import YunleSupportUI

struct HelpSettingsView: View {
    @State private var showingSupport = false
    var body: some View {
        List {
            Section {
                NavigationLink { AppFeedbackView() } label: {
                    SupportMenuLabel("意见反馈", icon: .system("bubble.left.and.bubble.right"),
                                     detail: .subtitle("反馈使用问题与功能建议"))
                }
                .supportMenuRow()
                .accessibilityIdentifier("help.feedback")
                NavigationLink { AppFeedbackView(purpose: .contribution) } label: {
                    SupportMenuLabel("菜谱投稿", icon: .system("text.book.closed"),
                                     detail: .subtitle("分享好菜和做法链接"))
                }
                .supportMenuRow()
                .accessibilityIdentifier("help.contribution")
            }
            Section {
                externalLink("微信客服", subtitle: "通过微信联系云乐坊客服", icon: .brand(.wechat),
                             url: AppLinks.shared.wecomKf.absoluteString,
                             accessibilityHint: "打开微信客服页面")
                    .accessibilityIdentifier("help.wechatSupport")
                Button { showingSupport = true } label: {
                    SupportMenuLabel("支持中心", icon: .system("questionmark.circle"),
                                     detail: .subtitle("查看帮助、在线反馈与工单"))
                }
                .supportMenuRow()
                    .accessibilityIdentifier("help.support")
                externalLink("需求墙", subtitle: "查看公开建议，为想要的功能投票", icon: .system("lightbulb"),
                             url: "https://support.yunle.fun/feedback?product=cook")
                    .accessibilityIdentifier("help.requests")
            }
            Section {
                externalLink("App Store 评价", subtitle: "分享你使用云食册的体验", icon: .system("star.bubble"),
                             url: "https://apps.apple.com/app/id6824589542?action=write-review",
                             accessibilityHint: "打开 App Store 撰写评价")
                    .accessibilityIdentifier("help.appStoreReview")
            }
        }
        .sheet(isPresented: $showingSupport) {
            SupportBrowser(configuration: SupportConfiguration(productID: "cook", appName: "云食册"), destination: .help)
        }
        .navigationTitle("帮助与反馈")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func externalLink(_ title: String, subtitle: String, icon: SupportMenuIcon, url: String,
                              accessibilityHint: String = "在浏览器中打开") -> some View {
        Link(destination: URL(string: url)!) {
            SupportMenuLabel(title, icon: icon, detail: .subtitle(subtitle), external: true)
        }
        .buttonStyle(.plain)
        .supportMenuRow()
        .accessibilityHint(accessibilityHint)
    }
}

struct SettingsRowLabel: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let title: String
    let image: Image
    let color: Color
    let subtitle: String?

    init(_ title: String, symbol: String, color: Color, subtitle: String? = nil) {
        self.init(title, image: Image(systemName: symbol), color: color, subtitle: subtitle)
    }

    init(_ title: String, image: Image, color: Color, subtitle: String? = nil) {
        self.title = title
        self.image = image
        self.color = color
        self.subtitle = subtitle
    }

    var body: some View {
        HStack(alignment: dynamicTypeSize.isAccessibilitySize ? .top : .center, spacing: 12) {
            image
                .resizable()
                .scaledToFit()
                .fontWeight(.medium)
                .frame(width: 18, height: 18)
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(color, in: RoundedRectangle(cornerRadius: 6))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

#Preview("帮助与反馈 · 浅色") {
    NavigationStack { HelpSettingsView() }
        .preferredColorScheme(.light)
}

#Preview("帮助与反馈 · 深色") {
    NavigationStack { HelpSettingsView() }
        .preferredColorScheme(.dark)
}

#Preview("帮助与反馈 · 大字体") {
    NavigationStack { HelpSettingsView() }
        .environment(\.dynamicTypeSize, .accessibility5)
}
