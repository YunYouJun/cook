import CookCore
import SwiftUI

/// 可多选的小标签；在 List 里用 plain 样式，避免点一个标签触发整行
struct Chip: View {
    struct SelectionColors {
        var fill: Color = .accentColor
        var foreground: Color = .white
    }

    let title: String
    var emoji: String? = nil
    var imageName: String? = nil
    let isSelected: Bool
    var selectionColors = SelectionColors()
    @ScaledMetric(relativeTo: .subheadline) var iconSize: CGFloat = 18
    /// 点击时报告标签在屏幕上的位置，做「入篮」动画用；不需要时不传
    var onTap: ((CGRect) -> Void)? = nil
    let action: () -> Void
    @Environment(\.accessibilityDifferentiateWithoutColor) private var differentiateWithoutColor
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var frameBox = FrameBox()

    var body: some View {
        Button {
            onTap?(frameBox.frame)
            action()
        } label: {
            HStack(spacing: 4) {
                if let imageName {
                    Image(imageName)
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: iconSize, height: iconSize)
                        .accessibilityHidden(true)
                } else if let emoji, !emoji.isEmpty {
                    Text(emoji)
                        .accessibilityHidden(true)
                }
                Text(title)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
            }
            .font(.subheadline)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundStyle(isSelected ? selectionColors.foreground : Color.primary)
            .background(isSelected ? selectionColors.fill : Color(uiColor: .secondarySystemFill), in: Capsule())
            .overlay(alignment: .topTrailing) {
                if isSelected && differentiateWithoutColor {
                    Image(systemName: "checkmark")
                        .font(.system(size: 8, weight: .bold))
                        .foregroundStyle(selectionColors.fill)
                        .frame(width: 14, height: 14)
                        .background(selectionColors.foreground, in: Circle())
                        .padding(.trailing, 4)
                        .offset(y: -4)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .contentShape(Capsule())
            // Animate the visual label, keeping the native Button and its accessibility node stable.
            .modifier(SelectBounce(isSelected: isSelected, enabled: !reduceMotion))
        }
        .buttonStyle(.plain)
        .onGeometryChange(for: CGRect.self) { proxy in
            proxy.frame(in: .global)
        } action: { frame in
            frameBox.frame = frame
        }
        .accessibilityLabel(title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// 做法图标读取共享资产；各端只维护展示映射，不混入推荐规则。
extension MethodGroup {
    var imageName: String? {
        switch label {
        case "炒菜": return "method-stir-fry"
        case "汤羹": return "method-soup"
        case "炖煮": return "method-stew"
        case "蒸": return "method-steam"
        case "炸煎": return "method-pan-fry"
        case "烤": return "method-bake"
        case "凉拌": return "method-salad"
        case "微波": return "method-microwave"
        default: return nil
        }
    }
}

/// 选中时轻轻按下再弹回；取消选中和减弱动态效果时不动
private struct SelectBounce: ViewModifier {
    let isSelected: Bool
    let enabled: Bool
    @State private var bounces = 0

    func body(content: Content) -> some View {
        content
            .keyframeAnimator(initialValue: CGFloat(1), trigger: bounces) { view, scale in
                view.scaleEffect(scale)
            } keyframes: { _ in
                SpringKeyframe(CGFloat(0.9), duration: 0.08)
                SpringKeyframe(CGFloat(1), duration: 0.3, spring: .bouncy)
            }
            .onChange(of: isSelected) { _, selected in
                if selected, enabled {
                    bounces += 1
                }
            }
    }
}

/// 分组末尾的「更多 / 收起」，和标签一样大，用主色文字区分
struct MoreChip: View {
    let isExpanded: Bool
    let hidden: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                    .imageScale(.small)
                    .accessibilityHidden(true)
                Text(isExpanded ? "收起" : "更多 \(hidden) 种")
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .font(.subheadline)
            // 网格最窄只有 92pt；给完整的数量文案留出空间，避免单行时仍被截断。
            .padding(.horizontal, 4)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundStyle(.tint)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isExpanded ? "收起不常用的食材" : "展开更多 \(hidden) 种食材")
    }
}

/// 一组标签，标题下面可以带一行说明
struct ChipSection<Content: View>: View {
    let title: String
    var note: String? = nil
    /// 跟着系统字号放大，字大时一行少放几个，免得菜名被截断（不能是 private，否则逐一成员构造器也会变成 private）
    @ScaledMetric(relativeTo: .subheadline) var minChipWidth: CGFloat = 92
    @ViewBuilder let content: () -> Content

    var body: some View {
        Section {
            // List 已按卡片加载；卡片内部一次布局所有标签，避免二次懒加载留下空行。
            AdaptiveChipGrid(minimumChipWidth: minChipWidth) {
                content()
            }
            .padding(.vertical, 4)
        } header: {
            Text(title)
        } footer: {
            if let note {
                Text(note)
            }
        }
    }
}

private struct AdaptiveChipGrid: Layout {
    let minimumChipWidth: CGFloat
    private let spacing = CookLayout.Spacing.small

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width.flatMap { $0.isFinite ? $0 : nil } ?? minimumChipWidth
        let grid = measure(width: width, subviews: subviews)
        let height = grid.rowHeights.reduce(0, +) + CGFloat(max(0, grid.rowHeights.count - 1)) * spacing
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let grid = measure(width: bounds.width, subviews: subviews)
        let chipProposal = ProposedViewSize(width: grid.chipWidth, height: nil)
        var top = bounds.minY
        for (row, height) in grid.rowHeights.enumerated() {
            let first = row * grid.columns
            for index in first..<min(first + grid.columns, subviews.count) {
                let x = bounds.minX + CGFloat(index - first) * (grid.chipWidth + spacing)
                subviews[index].place(at: CGPoint(x: x, y: top + height / 2), anchor: .leading, proposal: chipProposal)
            }
            top += height + spacing
        }
    }

    private func measure(width: CGFloat, subviews: Subviews) -> (columns: Int, chipWidth: CGFloat, rowHeights: [CGFloat]) {
        let columns = max(1, Int((width + spacing) / (minimumChipWidth + spacing)))
        let chipWidth = (width - CGFloat(columns - 1) * spacing) / CGFloat(columns)
        let chipProposal = ProposedViewSize(width: chipWidth, height: nil)
        var rowHeights: [CGFloat] = []
        for first in stride(from: 0, to: subviews.count, by: columns) {
            let row = subviews[first..<min(first + columns, subviews.count)]
            rowHeights.append(row.map { $0.sizeThatFits(chipProposal).height }.max() ?? 0)
        }
        return (columns, chipWidth, rowHeights)
    }
}

/// 多选列表的一行：右侧打勾
struct CheckRow: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title)
                    .foregroundStyle(Color.primary)
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// 厨具图标读取共享资产，复用做法中已有的器具图形。
extension StuffItem {
    var toolImageName: String? {
        switch name {
        case "烤箱": return "method-bake"
        case "空气炸锅": return "tool-air-fryer"
        case "微波炉": return "method-microwave"
        case "电饭煲": return "tool-rice-cooker"
        case "一口大锅": return "method-stir-fry"
        default: return nil
        }
    }
}
