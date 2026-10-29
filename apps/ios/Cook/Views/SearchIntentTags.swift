import SwiftUI

struct SearchIntentTag<Icon: View>: View {
    let title: String
    let removalLabel: String
    var tint: Color = .accentColor
    let action: () -> Void
    @ViewBuilder var icon: Icon
    @ScaledMetric(relativeTo: .subheadline) private var iconSize: CGFloat = 18

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                icon.frame(width: iconSize, height: iconSize).accessibilityHidden(true)
                Text(title)
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Image(systemName: "xmark")
                    .font(.caption2.weight(.semibold))
                    .accessibilityHidden(true)
            }
            .font(.subheadline)
            .frame(maxWidth: .infinity, minHeight: 32)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.capsule)
        .tint(tint)
        .accessibilityLabel(removalLabel)
        .accessibilityHint("仅从本次草稿移除")
    }
}
