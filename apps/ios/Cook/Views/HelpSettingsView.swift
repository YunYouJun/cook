import SwiftUI

struct HelpSettingsView: View {
    var body: some View {
        List {
            Section {
                NavigationLink { AppFeedbackView() } label: {
                    SettingsRowLabel("意见反馈", symbol: "bubble.left.and.bubble.right", color: .green,
                                     subtitle: "反馈使用问题与功能建议")
                }
                .accessibilityIdentifier("help.feedback")
                NavigationLink { AppFeedbackView(purpose: .contribution) } label: {
                    SettingsRowLabel("菜谱投稿", symbol: "text.book.closed", color: .orange,
                                     subtitle: "分享好菜和做法链接")
                }
                .accessibilityIdentifier("help.contribution")
            }
            Section {
                externalLink("微信客服", subtitle: "通过微信联系云乐坊客服", image: Image("wechat").renderingMode(.template), color: .green,
                             url: AppLinks.shared.wecomKf.absoluteString,
                             accessibilityHint: "打开微信客服页面")
                    .accessibilityIdentifier("help.wechatSupport")
                externalLink("支持中心", subtitle: "访问云乐坊支持服务", image: Image(systemName: "questionmark.circle"), color: .blue,
                             url: "https://support.yunle.fun/?product=cook")
                    .accessibilityIdentifier("help.support")
                externalLink("需求墙", subtitle: "查看公开建议，为想要的功能投票", image: Image(systemName: "lightbulb"), color: .purple,
                             url: "https://support.yunle.fun/feedback?product=cook")
                    .accessibilityIdentifier("help.requests")
            }
            Section {
                externalLink("App Store 评价", subtitle: "分享你使用云食册的体验", image: Image(systemName: "star.bubble"), color: .orange,
                             url: "https://apps.apple.com/app/id6824589542?action=write-review",
                             accessibilityHint: "打开 App Store 撰写评价")
                    .accessibilityIdentifier("help.appStoreReview")
            }
        }
        .navigationTitle("帮助与反馈")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func externalLink(_ title: String, subtitle: String, image: Image, color: Color, url: String,
                              accessibilityHint: String = "在浏览器中打开") -> some View {
        Link(destination: URL(string: url)!) {
            HStack(spacing: 12) {
                SettingsRowLabel(title, image: image, color: color, subtitle: subtitle)
                Image(systemName: "arrow.up.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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
