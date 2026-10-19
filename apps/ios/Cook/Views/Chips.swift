import SwiftUI

/// 可多选的小标签；在 List 里用 plain 样式，避免点一个标签触发整行
struct Chip: View {
    let title: String
    var emoji: String? = nil
    let isSelected: Bool
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
                if let emoji, !emoji.isEmpty {
                    Text(emoji)
                        .accessibilityHidden(true)
                }
                Text(title)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .font(.subheadline)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .foregroundStyle(isSelected ? Color.white : Color.primary)
            .background(isSelected ? Color.accentColor : Color(uiColor: .secondarySystemFill), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .modifier(SelectBounce(isSelected: isSelected, enabled: !reduceMotion))
        .onGeometryChange(for: CGRect.self) { proxy in
            proxy.frame(in: .global)
        } action: { frame in
            frameBox.frame = frame
        }
        .accessibilityLabel(title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
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

/// 一组标签，标题下面可以带一行说明
struct ChipSection<Content: View>: View {
    let title: String
    var note: String? = nil
    /// 跟着系统字号放大，字大时一行少放几个，免得菜名被截断（不能是 private，否则逐一成员构造器也会变成 private）
    @ScaledMetric(relativeTo: .subheadline) var minChipWidth: CGFloat = 92
    @ViewBuilder let content: () -> Content

    var body: some View {
        Section {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: minChipWidth), spacing: 8)], spacing: 8) {
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

/// 多选列表的一行：右侧打勾
struct CheckRow: View {
    let title: String
    let isSelected: Bool
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
