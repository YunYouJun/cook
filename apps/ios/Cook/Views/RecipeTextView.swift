import CookCore
import SwiftUI

/// 图文做法：读 App 内置的正文离线显示，右上角可以打开原文
struct RecipeTextView: View {
    let recipe: Recipe
    let text: RecipeText
    let source: RecipeTextLibrary.Source?

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
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            if text.images > 0 {
                Text("原文还有 \(text.images) 张配图。")
            }
            if let source {
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
