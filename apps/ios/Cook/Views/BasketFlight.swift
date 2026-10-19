import Observation
import SwiftUI

/// 选中食材时，从食材标签飞进底部「看看能做什么」按钮的一个 emoji（没有 emoji 时是食材名）
struct BasketFlight: Identifiable {
    let id = UUID()
    let label: String
    let isEmoji: Bool
    /// 起点，屏幕坐标
    let start: CGPoint
}

/// 「入篮」动画的状态。单独放一个对象里，飞行和弹跳只刷新飞行层和按钮，不让做菜页重新算推荐
@MainActor
@Observable
final class BasketAnimator {
    var flights: [BasketFlight] = []
    var bounces = 0
    /// 底部按钮的位置，屏幕坐标；只在布局变化时更新，不需要触发刷新
    @ObservationIgnored var target: CGRect = .zero

    func launch(label: String, isEmoji: Bool, from frame: CGRect) {
        guard frame != .zero, target != .zero else {
            return
        }
        flights.append(BasketFlight(label: label, isEmoji: isEmoji, start: CGPoint(x: frame.midX, y: frame.midY)))
    }

    func land(_ id: UUID) {
        flights.removeAll { $0.id == id }
        bounces += 1
    }
}

/// 盖在做菜页上的飞行层，不接收点击，也不给 VoiceOver 读
struct BasketFlightLayer: View {
    let animator: BasketAnimator

    var body: some View {
        GeometryReader { proxy in
            let origin = proxy.frame(in: .global).origin
            let end = CGPoint(x: animator.target.midX - origin.x, y: animator.target.midY - origin.y)
            ForEach(animator.flights) { flight in
                BasketFlightView(
                    flight: flight,
                    start: CGPoint(x: flight.start.x - origin.x, y: flight.start.y - origin.y),
                    end: end
                ) {
                    animator.land(flight.id)
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// 食材落进来时按钮轻轻弹一下
struct BasketBounce: ViewModifier {
    let animator: BasketAnimator

    func body(content: Content) -> some View {
        content
            .keyframeAnimator(initialValue: CGFloat(1), trigger: animator.bounces) { view, scale in
                view.scaleEffect(scale)
            } keyframes: { _ in
                SpringKeyframe(CGFloat(1.06), duration: 0.1)
                SpringKeyframe(CGFloat(1), duration: 0.25, spring: .bouncy)
            }
    }
}

/// 一次飞行：沿一条先上扬再落下的弧线飞到按钮，边飞边缩小变淡，落地后回调
private struct BasketFlightView: View {
    let flight: BasketFlight
    let start: CGPoint
    let end: CGPoint
    let onLand: () -> Void
    @State private var progress: CGFloat = 0

    var body: some View {
        label
            .modifier(ArcFlight(progress: progress, start: start, end: end))
            .onAppear {
                withAnimation(.timingCurve(0.25, 0.1, 0.25, 1, duration: 0.45)) {
                    progress = 1
                } completion: {
                    onLand()
                }
            }
    }

    @ViewBuilder
    private var label: some View {
        if flight.isEmoji {
            Text(flight.label)
                .font(.largeTitle)
        } else {
            Text(flight.label)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Color.accentColor, in: Capsule())
        }
    }
}

/// 二次贝塞尔弧线：控制点在起点上方，飞的时候先往上跳一下再落进按钮
private struct ArcFlight: ViewModifier, Animatable {
    var progress: CGFloat
    let start: CGPoint
    let end: CGPoint

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        let t = progress
        let control = CGPoint(x: (start.x + end.x) / 2, y: min(start.y, end.y) - 90)
        let x = (1 - t) * (1 - t) * start.x + 2 * (1 - t) * t * control.x + t * t * end.x
        let y = (1 - t) * (1 - t) * start.y + 2 * (1 - t) * t * control.y + t * t * end.y
        // 起飞时略放大，最后三分之一才变小变淡，飞的过程中看得清是哪样食材
        let fade = max(0, t - 0.65) / 0.35
        content
            .scaleEffect(1.2 - 0.25 * t - 0.4 * fade)
            .opacity(1 - 0.7 * fade)
            .position(x: x, y: y)
    }
}

/// 只记位置、不触发刷新：滚动时每个标签的位置都在变，存在 @State 里会让整页跟着重绘
final class FrameBox {
    var frame: CGRect = .zero
}
