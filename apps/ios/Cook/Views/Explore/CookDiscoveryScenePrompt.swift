import SwiftUI

/// 筛选菜单中的可选场景，与食材和厨具共用当前条件。
struct CookDiscoveryScenePrompt: View {
    @Binding var selection: CookDiscoveryScene?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let motionReduced = reduceMotion
        VStack(alignment: .leading, spacing: 12) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { choices(wrapping: false) }
                VStack(alignment: .leading, spacing: 8) { choices(wrapping: true) }
            }
            if let selection {
                HStack(alignment: .top, spacing: 10) {
                    Text(selection.emoji)
                        .font(.title2)
                        .keyframeAnimator(initialValue: 0.0, trigger: selection) { view, angle in
                            view.rotationEffect(.degrees(motionReduced ? 0 : angle))
                        } keyframes: { _ in
                            CubicKeyframe(-5, duration: 0.12)
                            SpringKeyframe(0, duration: 0.35, spring: .snappy)
                        }
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(selection.story).font(.subheadline)
                        Text(selection.explanation)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .accessibilityIdentifier("discovery.explanation")
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            } else {
                Text("可选一项，再次点按取消；不选则不限。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("discovery.explanation")
            }
        }
        .padding(.vertical, 4)
        .sensoryFeedback(.selection, trigger: selection)
    }

    @ViewBuilder
    private func choices(wrapping: Bool) -> some View {
        ForEach(CookDiscoveryScene.allCases) { scene in
            Button {
                selection = selection == scene ? nil : scene
            } label: {
                Text(scene.label)
                    .font(.subheadline.weight(selection == scene ? .semibold : .regular))
                    .fixedSize(horizontal: !wrapping, vertical: true)
                    .frame(minHeight: 28)
            }
            .buttonStyle(.bordered)
            .tint(selection == scene ? .green : .secondary)
            .accessibilityAddTraits(selection == scene ? [.isSelected] : [])
            .accessibilityHint(selection == scene ? "再次点按取消这个场景" : "按这个想法筛选当前候选")
            .accessibilityIdentifier("discovery.scene.\(scene.rawValue)")
        }
    }
}
