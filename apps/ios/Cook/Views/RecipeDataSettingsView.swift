import SwiftUI
import UIKit

struct RecipeDataSettingsView: View {
    @Environment(RecipeStore.self) private var store
    @State private var copyCount = 0

    private var didCopyVersion: Bool { copyCount > 0 }

    var body: some View {
        List {
            Section {
                LabeledContent("菜谱数量", value: "\(store.recipes.count) 道")
                LabeledContent("提供方式", value: "随 App 内置")
            } footer: {
                Text("菜谱和筛选数据随 App 内置，可离线使用。")
            }

            Section {
                LabeledContent("版本标识") {
                    Text(store.data.meta.dataVersion)
                        .monospaced()
                        .textSelection(.enabled)
                        .accessibilityIdentifier("recipeData.version")
                }
                Button {
                    UIPasteboard.general.string = store.data.meta.dataVersion
                    copyCount += 1
                    UIAccessibility.post(notification: .announcement, argument: "已复制版本标识")
                } label: {
                    Label(didCopyVersion ? "已复制版本标识" : "复制版本标识", systemImage: didCopyVersion ? "checkmark" : "doc.on.doc")
                }
                .accessibilityIdentifier("recipeData.copyVersion")
            } footer: {
                Text("版本标识用于核对菜谱与筛选数据，反馈时可复制提供。")
            }

            Section {
                sourceLink("菜谱与食材数据", subtitle: "食用手册开源项目", symbol: "books.vertical.fill", color: .green,
                           url: URL(string: "https://github.com/YunYouJun/cook/tree/main/packages/data")!)
                    .accessibilityIdentifier("recipeData.catalogSource")
                if let source = store.textSource, let url = URL(string: source.url) {
                    sourceLink("图文做法", subtitle: source.name, symbol: "text.book.closed.fill", color: .orange, url: url)
                        .accessibilityIdentifier("recipeData.textSource")
                }
            } header: {
                Text("数据来源")
            } footer: {
                Text("每道菜的原始来源见菜谱详情。外部项目可能已更新，与 App 内置版本不同。")
            }
        }
        .navigationTitle("菜谱数据")
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.success, trigger: copyCount)
    }

    private func sourceLink(_ title: String, subtitle: String, symbol: String, color: Color, url: URL) -> some View {
        Link(destination: url) {
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
        .accessibilityHint("在浏览器中查看数据来源")
    }
}

#Preview("菜谱数据") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        NavigationStack {
            RecipeDataSettingsView().environment(store)
        }
    }
}

#Preview("菜谱数据 · 大字体") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        NavigationStack {
            RecipeDataSettingsView().environment(store)
        }
        .environment(\.dynamicTypeSize, .accessibility5)
    }
}

#Preview("菜谱数据 · 深色") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        NavigationStack {
            RecipeDataSettingsView().environment(store)
        }
        .preferredColorScheme(.dark)
    }
}
