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
                externalLink("支持中心", subtitle: "访问云乐坊支持服务", symbol: "questionmark.circle", color: .blue,
                             url: "https://support.yunle.fun/?product=cook")
                    .accessibilityIdentifier("help.support")
                externalLink("需求墙", subtitle: "查看公开建议，为想要的功能投票", symbol: "lightbulb", color: .purple,
                             url: "https://support.yunle.fun/feedback?product=cook")
                    .accessibilityIdentifier("help.requests")
            }
        }
        .navigationTitle("帮助与反馈")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func externalLink(_ title: String, subtitle: String, symbol: String, color: Color, url: String) -> some View {
        Link(destination: URL(string: url)!) {
            HStack(spacing: 12) {
                SettingsRowLabel(title, symbol: symbol, color: color, subtitle: subtitle)
                Image(systemName: "arrow.up.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("在浏览器中打开")
    }
}

struct SettingsRowLabel: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let title: String
    let symbol: String
    let color: Color
    let subtitle: String?

    init(_ title: String, symbol: String, color: Color, subtitle: String? = nil) {
        self.title = title
        self.symbol = symbol
        self.color = color
        self.subtitle = subtitle
    }

    var body: some View {
        HStack(alignment: dynamicTypeSize.isAccessibilitySize ? .top : .center, spacing: 12) {
            Image(systemName: symbol)
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
