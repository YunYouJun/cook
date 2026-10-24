import CookCore
import SwiftUI

/// 已选忌口集中在顶部，名称不在当前词典中时也能查看和移除。
struct AvoidPicker: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @State private var query = ""

    private var searchTerm: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        let vegetables = matching(store.data.food.vegetable)
        let meat = matching(store.data.food.meat)
        let staples = matching(store.data.food.staple)

        List {
            Section {
                if session.avoid.isEmpty {
                    Text("还没有设置忌口")
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("avoid.emptySelection")
                } else {
                    AvoidGrid(names: session.avoid, removesSelection: true)
                }
            } header: {
                Text("已选忌口 · \(session.avoid.count) 种")
                    .accessibilityIdentifier("avoid.selectionSummary")
            } footer: {
                Text(session.avoid.isEmpty
                     ? "在下面点选不吃的食材，选择会自动保存。"
                     : "轻点已选标签即可移除，选择会自动保存。")
            }

            avoidSection("蔬菜", vegetables)
            avoidSection("肉和蛋", meat)
            avoidSection("主食", staples)
            if vegetables.isEmpty, meat.isEmpty, staples.isEmpty {
                Section {
                    Text("没有找到「\(searchTerm)」，换个名称试试。")
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("avoid.noSearchResults")
                }
            }
        }
        .navigationTitle("忌口")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索食材或别名")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("清空") { session.clearAvoid() }
                    .disabled(session.avoid.isEmpty)
            }
        }
        .sensoryFeedback(.selection, trigger: session.avoid)
    }

    private func matching(_ items: [StuffItem]) -> [StuffItem] {
        guard !searchTerm.isEmpty else { return items }
        let normalized = store.data.normalizer.unique([searchTerm]).first ?? searchTerm
        return items.filter {
            $0.name.localizedStandardContains(searchTerm)
                || $0.name == normalized
                || ($0.alias?.localizedStandardContains(searchTerm) ?? false)
        }
    }

    @ViewBuilder
    private func avoidSection(_ title: String, _ items: [StuffItem]) -> some View {
        if !items.isEmpty {
            Section(title) {
                AvoidGrid(names: items.map(\.name))
            }
        }
    }
}

private struct AvoidGrid: View {
    let names: [String]
    var removesSelection = false
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .subheadline) var minItemWidth: CGFloat = 100

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: minItemWidth), spacing: 8)], spacing: 8) {
            ForEach(names, id: \.self) { name in
                AvoidChip(
                    name: name,
                    emoji: store.emoji(forStuff: name),
                    isSelected: session.avoid.contains(name),
                    removesSelection: removesSelection
                ) {
                    session.toggleAvoid(name)
                }
            }
        }
        .id(dynamicTypeSize)
        .padding(.vertical, 4)
    }
}

private struct AvoidChip: View {
    let name: String
    let emoji: String?
    let isSelected: Bool
    let removesSelection: Bool
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let emoji {
                    Text(emoji)
                        .accessibilityHidden(true)
                }
                Text(name)
                    .fixedSize(horizontal: false, vertical: true)
                if removesSelection || isSelected {
                    Image(systemName: removesSelection ? "xmark" : "checkmark")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(removesSelection ? AnyShapeStyle(.secondary) : AnyShapeStyle(.tint))
                        .accessibilityHidden(true)
                }
            }
            .font(.subheadline)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 8)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundStyle(Color.primary)
            .background(isSelected ? Color.accentColor.opacity(0.10) : Color(uiColor: .secondarySystemFill), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: isSelected)
        .accessibilityLabel(removesSelection ? "移除忌口：\(name)" : name)
        .accessibilityValue(isSelected ? "已设为忌口" : "未设为忌口")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityHint(isSelected ? "轻点移除忌口" : "轻点设为忌口")
        .accessibilityIdentifier("avoid.\(removesSelection ? "remove" : "item").\(name)")
    }
}
