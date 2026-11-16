import CookCore
import SwiftUI

/// Only show the assistance relevant to the current editing state.
struct CookSearchSupport: View {
    private static let exampleQueries = ["番茄炒蛋", "鸡蛋", "冰箱里有土豆和鸡蛋"]

    @Binding var text: String
    @FocusState.Binding var focused: Bool
    let submitExample: (String) -> Void
    let preview: (() -> Void)?
    let addIngredient: (String) -> Void
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(CookSearchHistory.self) private var history
    @State private var confirmingClear = false

    private var query: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(alignment: .leading, spacing: CookLayout.Spacing.xSmall) {
            if !query.isEmpty {
                suggestions
                if let preview {
                    Button {
                        focused = false
                        preview()
                    } label: {
                        Label("预览条件", systemImage: "slider.horizontal.3")
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(minHeight: CookLayout.minimumHitSize, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .foregroundStyle(CookPalette.primaryAction)
                    .accessibilityHint("先确认识别的条件，也可以在预览里选择智能识别")
                    .accessibilityIdentifier("intent.preview")
                }
            } else {
                examples
                if !history.entries.isEmpty {
                    recentSearches.padding(.top, CookLayout.Spacing.small)
                }
            }
        }
        .font(.subheadline)
        .buttonStyle(.plain)
        .confirmationDialog("清空这台设备上的搜索历史？", isPresented: $confirmingClear, titleVisibility: .visible) {
            Button("清空搜索历史", role: .destructive) { history.clear() }
        }
    }

    private var examples: some View {
        VStack(alignment: .leading, spacing: CookLayout.Spacing.xSmall) {
            Text("试试")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            CookSearchChipFlow(spacing: CookLayout.Spacing.xSmall) {
                ForEach(Self.exampleQueries, id: \.self) { example in
                    Button {
                        submitExample(example)
                    } label: {
                        Label(example, systemImage: "magnifyingglass")
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .foregroundStyle(CookPalette.primaryAction)
                    .buttonStyle(CookSearchChipStyle(tint: CookPalette.primaryAction))
                    .accessibilityHint("直接查找并应用这条示例")
                    .accessibilityIdentifier("cook.search.example.\(example)")
                }
            }
        }
    }

    private var recentSearches: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: CookLayout.Spacing.small) {
                Text("最近搜索").foregroundStyle(.secondary)
                Spacer(minLength: CookLayout.Spacing.small)
                Button("清空") { confirmingClear = true }
                    .foregroundStyle(.secondary)
                    .frame(minWidth: CookLayout.minimumHitSize, minHeight: CookLayout.minimumHitSize)
                    .accessibilityLabel("清空搜索历史")
                    .accessibilityIdentifier("cook.history.clear")
            }
            ForEach(history.entries, id: \.self) { entry in
                HStack(alignment: .top, spacing: CookLayout.Spacing.small) {
                    Button { refill(entry) } label: {
                        HStack(alignment: .firstTextBaseline, spacing: CookLayout.Spacing.small) {
                            Image(systemName: "clock").foregroundStyle(.secondary)
                            Text(entry)
                                .foregroundStyle(.primary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, minHeight: CookLayout.minimumHitSize, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel(entry)
                    .accessibilityHint("填入搜索框，确认后再查找")
                    .accessibilityIdentifier("cook.history.entry.\(entry)")
                    .privacySensitive()
                    Button { history.remove(entry) } label: {
                        Image(systemName: "xmark")
                            .foregroundStyle(.secondary)
                            .frame(minWidth: CookLayout.minimumHitSize, minHeight: CookLayout.minimumHitSize)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("删除搜索记录：\(entry)")
                    .accessibilityIdentifier("cook.history.remove.\(entry)")
                }
            }

        }
    }

    private var suggestions: some View {
        let ingredients = store.stuffSuggestions(for: query, excluding: session.selectedStuff, limit: 2)
        let names = uniqueStrings(store.recipes.filter { $0.name.localizedStandardContains(query) }.map(\.name))
            .prefix(3 - ingredients.count)
        return VStack(alignment: .leading, spacing: 0) {
            ForEach(ingredients, id: \.name) { item in
                Button {
                    addIngredient(item.name)
                    focused = false
                } label: {
                    suggestionLabel("添加\(item.name)", symbol: "plus.circle")
                }
                .accessibilityIdentifier("cook.suggestion.ingredient.\(item.name)")
            }
            ForEach(Array(names), id: \.self) { name in
                Button { refill(name) } label: {
                    suggestionLabel(name, symbol: "magnifyingglass")
                }
                .accessibilityHint("填入菜名，确认后再查找")
                .accessibilityIdentifier("cook.suggestion.recipe.\(name)")
            }
        }
    }

    private func suggestionLabel(_ title: String, symbol: String) -> some View {
        Label(title, systemImage: symbol)
            .foregroundStyle(.primary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, minHeight: CookLayout.minimumHitSize, alignment: .leading)
            .contentShape(Rectangle())
    }

    private func refill(_ value: String) {
        text = value
        focused = true
    }
}
