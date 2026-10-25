import SwiftUI

/// 常备调料：小瓶罐排成架子，葱姜蒜单独一层；仍直接使用共享数据里的选项。
struct PantryPicker: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    private static let aromatics: Set<String> = ["葱", "姜", "蒜"]

    var body: some View {
        let seasonings = store.data.pantry.options.filter { !Self.aromatics.contains($0) }
        let aromatics = store.data.pantry.options.filter { Self.aromatics.contains($0) }

        List {
            Section("调料") {
                PantryShelf(names: seasonings)
            }
            Section {
                if !aromatics.isEmpty {
                    PantryShelf(names: aromatics)
                }
            } header: {
                if !aromatics.isEmpty { Text("葱姜蒜") }
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text("已备 \(session.pantry.count) 种 · 选择会自动保存")
                        .accessibilityIdentifier("pantry.selectionSummary")
                    Text("家里常备的调料不算缺少的食材。")
                }
            }
        }
        .navigationTitle("家里常备")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("恢复默认") { session.resetPantry(to: store.data.pantry.defaults) }
                    .disabled(Set(session.pantry) == Set(store.data.pantry.defaults))
            }
        }
        .sensoryFeedback(.selection, trigger: session.pantry)
    }
}

private struct PantryShelf: View {
    let names: [String]
    @Environment(CookSession.self) private var session
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .subheadline) var minItemWidth: CGFloat = 72

    private var columns: [GridItem] {
        if (1 ... 3).contains(names.count), !dynamicTypeSize.isAccessibilitySize {
            return Array(repeating: GridItem(.flexible(), spacing: 8), count: names.count)
        }
        return [GridItem(.adaptive(minimum: minItemWidth), spacing: 8)]
    }

    var body: some View {
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(names, id: \.self) { name in
                PantryItem(name: name, isSelected: session.pantry.contains(name)) {
                    session.togglePantry(name)
                }
            }
        }
        .id(dynamicTypeSize)
        .padding(.vertical, 6)
    }
}

private struct PantryItem: View {
    let name: String
    let isSelected: Bool
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .subheadline) var iconSize: CGFloat = 40

    private var imageName: String {
        switch name {
        case "油": return "pantry-oil"
        case "盐", "胡椒粉": return "pantry-shaker"
        case "糖", "淀粉": return "pantry-jar"
        case "蚝油": return "pantry-sauce"
        case "葱": return "pantry-scallion"
        case "姜": return "pantry-ginger"
        case "蒜": return "pantry-garlic"
        default: return "pantry-bottle"
        }
    }

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(imageName)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: iconSize, height: iconSize)
                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                    .padding(.top, 12)
                // 每个瓶罐底下的一小段搁板，保持视觉轻巧。
                Rectangle()
                    .fill(Color.primary.opacity(0.10))
                    .frame(height: 1)
                Text(name)
                    .font(.subheadline)
                    .foregroundStyle(Color.primary)
                    .fixedSize(horizontal: false, vertical: true)
                    .multilineTextAlignment(.center)
            }
            .padding(8)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(isSelected ? Color.accentColor.opacity(0.08) : Color.clear, in: RoundedRectangle(cornerRadius: 12))
            .overlay(alignment: .topTrailing) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.caption)
                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary.opacity(0.4))
                    .padding(6)
            }
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: isSelected)
        .accessibilityLabel(name)
        .accessibilityValue(isSelected ? "家里有" : "家里没有")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityHint(isSelected ? "轻点移出常备" : "轻点加入常备")
        .accessibilityIdentifier("pantry.item.\(name)")
    }
}
