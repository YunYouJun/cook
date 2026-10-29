import SwiftUI

struct SearchIntentTag<Icon: View>: View {
    let title: String
    let removalLabel: String
    var tint: Color = .accentColor
    let action: () -> Void
    @ViewBuilder var icon: Icon
    @ScaledMetric(relativeTo: .subheadline) private var iconSize: CGFloat = 16

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                icon.font(.system(size: iconSize))
                    .frame(width: iconSize, height: iconSize)
                    .accessibilityHidden(true)
                Text(title)
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Image(systemName: "xmark")
                    .font(.caption2.weight(.semibold))
                    .accessibilityHidden(true)
            }
            .font(.subheadline)
            .foregroundStyle(tint)
        }
        .buttonStyle(SearchIntentTagStyle(tint: tint))
        .tint(tint)
        .accessibilityLabel(removalLabel)
        .accessibilityHint("仅从本次草稿移除")
    }
}

private struct SearchIntentTagStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    let tint: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(minWidth: 44, minHeight: 36)
            .background(tint.opacity(configuration.isPressed ? 0.22 : 0.12), in: Capsule())
            // Keep the compact background inside the full native button hit region.
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
            .opacity(isEnabled ? (configuration.isPressed ? 0.7 : 1) : 0.45)
    }
}

/// Tags keep their natural width and wrap before a long label would be squeezed.
struct SearchIntentTagFlow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrangement(width: proposal.width, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrangement(width: bounds.width, subviews: subviews)
        for (index, frame) in result.frames.enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                                  anchor: .topLeading, proposal: ProposedViewSize(frame.size))
        }
    }

    private func arrangement(width: CGFloat?, subviews: Subviews) -> (size: CGSize, frames: [CGRect]) {
        let availableWidth = width.flatMap { $0.isFinite ? max(44, $0) : nil } ?? .greatestFiniteMagnitude
        var frames: [CGRect] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var usedWidth: CGFloat = 0

        for subview in subviews {
            let idealWidth = subview.sizeThatFits(.unspecified).width
            let size = subview.sizeThatFits(ProposedViewSize(width: min(idealWidth, availableWidth), height: nil))
            if x > 0, x + size.width > availableWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            frames.append(CGRect(origin: CGPoint(x: x, y: y), size: size))
            usedWidth = max(usedWidth, x + size.width)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return (CGSize(width: usedWidth, height: y + rowHeight), frames)
    }
}
