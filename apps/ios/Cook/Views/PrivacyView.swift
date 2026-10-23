import SwiftUI

/// 和公开文档使用同一份正文；随 App 打包，断网也能阅读。
struct PrivacyPolicyView: View {
    static func bundledText(bundle: Bundle = .main) -> String? {
        guard let url = bundle.url(forResource: "privacy", withExtension: "md") else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let text = Self.bundledText() {
                    ForEach(Array(text.components(separatedBy: "\n\n").enumerated()), id: \.offset) { _, block in
                        if block.hasPrefix("# ") {
                            Text(String(block.dropFirst(2)))
                                .font(.title.bold())
                                .accessibilityAddTraits(.isHeader)
                        } else if block.hasPrefix("## ") {
                            Text(String(block.dropFirst(3)))
                                .font(.headline)
                                .accessibilityAddTraits(.isHeader)
                        } else {
                            Text((try? AttributedString(markdown: block)) ?? AttributedString(block))
                        }
                    }
                } else {
                    ContentUnavailableView("隐私政策暂时无法读取", systemImage: "doc.text", description: Text("请联系 me@yunyoujun.cn 获取隐私政策。"))
                }
            }
            .textSelection(.enabled)
            .frame(maxWidth: 680, alignment: .leading)
            .padding()
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("隐私政策")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct SignalConsentView: View {
    @Environment(FeedbackClient.self) private var feedback
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("愿意帮助改进推荐吗？")
                        .font(.title2.bold())
                        .accessibilityAddTraits(.isHeader)
                    Text("分享后续选过的食材组合、厨具、推荐方式、没有结果的情况，以及从推荐列表点开的菜谱和位置，用来补菜和调整排序。")
                    Text("同时发送 App 版本和每次启动随机生成的临时标识，用于限制请求次数。不发送输入原文、账号、广告标识符或设备硬件标识符。")
                    Text("数据发送至腾讯云 CloudBase。不分享也能使用全部功能，之后可在「我的 → 隐私」随时更改。")
                    NavigationLink("隐私政策") { PrivacyPolicyView() }
                }
                Section {
                    Button("同意分享") { choose(true) }
                        .accessibilityIdentifier("consent.allow")
                    Button("不分享，继续使用") { choose(false) }
                        .accessibilityIdentifier("consent.decline")
                }
            }
            .navigationTitle("推荐统计")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func choose(_ enabled: Bool) {
        feedback.setSignalConsent(enabled)
        dismiss()
    }
}
