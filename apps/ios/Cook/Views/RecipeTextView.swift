import CookCore
import SwiftUI

/// 图文做法：读 App 内置的正文离线显示，配图从对象存储加载，右上角可以打开原文
struct RecipeTextView: View {
    @Environment(RecipeStore.self) private var store
    let recipe: Recipe
    let text: RecipeText

    private var originalURL: URL? {
        guard let link = recipe.link, !link.isEmpty else { return nil }
        return URL(string: link)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ForEach(Array(text.blocks.enumerated()), id: \.offset) { _, block in
                    blockView(block)
                }
                footer
                    .padding(.top, 12)
            }
            .textSelection(.enabled)
            .padding()
            // iPad 上行太长不好读
            .frame(maxWidth: 680, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(recipe.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let originalURL {
                    Link(destination: originalURL) {
                        Label("打开原文", systemImage: "safari")
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func blockView(_ block: RecipeTextBlock) -> some View {
        switch block {
        case let .heading(heading):
            Text(markdown(heading))
                .font(.title3.bold())
                .padding(.top, 8)
                .accessibilityAddTraits(.isHeader)
        case let .paragraph(paragraph):
            if !paragraph.isEmpty {
                Text(markdown(paragraph))
            }
        case let .list(ordered, start, items):
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(ordered ? "\(start + index)." : "•")
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                        Text(markdown(item))
                    }
                }
            }
        case let .image(src, width, height, alt):
            if let url = store.textImageURL(src) {
                RecipeTextImage(url: url, aspectRatio: CGFloat(width) / CGFloat(max(height, 1)), alt: alt)
            }
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            // 没配置配图地址的数据包不显示图片，提示去原文看
            if text.imageCount > 0, !store.showsTextImages {
                Text("原文还有 \(text.imageCount) 张配图。")
            }
            if let source = store.textSource {
                Text("做法来自 \(source.name)（\(source.license)），用量和步骤以原文为准。")
            }
            if let originalURL {
                Link("打开原文", destination: originalURL)
            }
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
    }

    /// 正文是行内 Markdown（加粗、外链），保留列表项里的换行
    private func markdown(_ string: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: string, options: options)) ?? AttributedString(string)
    }
}

/// 一张配图：加载前按原图比例占位，不让正文跳动；加载失败就收起，不影响看文字
private struct RecipeTextImage: View {
    let url: URL
    let aspectRatio: CGFloat
    let alt: String?
    @State private var failed = false

    var body: some View {
        if !failed {
            AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
                switch phase {
                case let .success(image):
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                case .failure:
                    Color.clear
                        .onAppear { failed = true }
                default:
                    Rectangle()
                        .fill(.quaternary)
                        .overlay { ProgressView() }
                }
            }
            .aspectRatio(aspectRatio, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityElement()
            .accessibilityLabel(alt ?? "配图")
            .accessibilityAddTraits(.isImage)
        }
    }
}
