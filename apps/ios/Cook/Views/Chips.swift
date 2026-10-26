import CookCore
import SwiftUI

/// 可多选的小标签；在 List 里用 plain 样式，避免点一个标签触发整行
struct Chip: View {
    let title: String
    var emoji: String? = nil
    var imageName: String? = nil
    let isSelected: Bool
    @ScaledMetric(relativeTo: .subheadline) var iconSize: CGFloat = 18
    /// 点击时报告标签在屏幕上的位置，做「入篮」动画用；不需要时不传
    var onTap: ((CGRect) -> Void)? = nil
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var frameBox = FrameBox()

    var body: some View {
        Button {
            onTap?(frameBox.frame)
            action()
        } label: {
            HStack(spacing: 4) {
                if isSelected {
                    Image(systemName: "checkmark").accessibilityHidden(true)
                } else if let imageName {
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
            .foregroundStyle(isSelected ? Color.white : Color.primary)
            .background(isSelected ? Color.accentColor : Color(uiColor: .secondarySystemFill), in: Capsule())
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

/// 做法图标只用于 iOS 展示，不混入多端共用的推荐规则。
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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ViewBuilder let content: () -> Content

    var body: some View {
        Section {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: minChipWidth), spacing: 8)], spacing: 8) {
                content()
            }
            // 字号改变会切换列数，重建网格，避免懒加载的旧单元格仍停留在原位置。
            .id(dynamicTypeSize)
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

/// 厨具图标只用于 iOS 展示，复用做法中已有的器具图形。
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
