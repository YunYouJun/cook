import SwiftUI

/// A window-local presentation bridge from the home search entry to the tab overlay.
struct CookSearchExpansion {
    let isPresented: Bool
    let hidesSourceField: Bool
    let open: (CGFloat, @escaping (InputNotice) -> Void) -> Void
}

struct CookExpandedSearchRequest {
    enum Phase {
        case visible
        case fadingOut
        case fadedOut
    }

    let sourceScrollOffset: CGFloat
    let onApplied: (InputNotice) -> Void
    var phase = Phase.visible

    var isClosing: Bool { phase != .visible }
}

struct CookExpandedSearchHeader {
    let sourceScrollOffset: CGFloat
    let close: () -> Void
}

/// Freeze the last rendered app frame above SwiftUI's layout, but below the
/// system keyboard. The navigation stack can then reflow invisibly during blur.
@MainActor
final class CookSearchDissolve {
    static let duration: TimeInterval = 0.22
    weak var anchor: UIView?
    private var frozenFrame: UIView?

    func freeze() -> Bool {
        guard let window = anchor?.window,
              let frame = window.snapshotView(afterScreenUpdates: false) else { return false }
        frame.frame = window.bounds
        frame.isUserInteractionEnabled = false
        frame.accessibilityElementsHidden = true
        window.addSubview(frame)
        frozenFrame = frame
        return true
    }

    func fade(completion: @escaping () -> Void) {
        guard let frame = frozenFrame else { return }
        UIView.animate(withDuration: Self.duration, delay: 0, options: .curveEaseInOut) {
            frame.alpha = 0
        } completion: { _ in
            guard self.frozenFrame === frame else { return }
            self.clear()
            completion()
        }
    }

    func clear() {
        frozenFrame?.removeFromSuperview()
        frozenFrame = nil
    }
}

struct CookSearchDissolveAnchor: UIViewRepresentable {
    let dissolve: CookSearchDissolve

    func makeUIView(context: Context) -> UIView {
        let anchor = UIView()
        anchor.isUserInteractionEnabled = false
        dissolve.anchor = anchor
        return anchor
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

private struct CookSearchExpansionKey: EnvironmentKey {
    static let defaultValue: CookSearchExpansion? = nil
}

private struct CookSearchSourceOffsetKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    var cookSearchExpansion: CookSearchExpansion? {
        get { self[CookSearchExpansionKey.self] }
        set { self[CookSearchExpansionKey.self] = newValue }
    }

    var cookSearchSourceOffset: CGFloat {
        get { self[CookSearchSourceOffsetKey.self] }
        set { self[CookSearchSourceOffsetKey.self] = newValue }
    }
}

extension View {
    func trackCookSearchSourceOffset() -> some View {
        modifier(CookSearchSourceOffset())
    }

    @ViewBuilder
    func cookSearchFieldMotion(from sourceOffset: CGFloat) -> some View {
        if sourceOffset > 0.5 {
            modifier(CookSearchFieldMotion(sourceOffset: sourceOffset))
        } else {
            self
        }
    }
}

private struct CookSearchSourceOffset: ViewModifier {
    @Environment(\.cookSearchExpansion) private var expansion
    @State private var offset: CGFloat = 0

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content
                .environment(\.cookSearchSourceOffset, offset)
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    guard expansion != nil else { return 0 }
                    let scrolled = geometry.contentOffset.y + geometry.contentInsets.top
                    return scrolled > 0.5 ? scrolled : 0
                } action: { _, value in
                    offset = value
                }
        } else {
            content
        }
    }
}

private struct CookSearchFieldMotion: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var displacement: CGFloat

    init(sourceOffset: CGFloat) {
        _displacement = State(initialValue: -sourceOffset)
    }

    func body(content: Content) -> some View {
        content
            .offset(y: reduceMotion ? 0 : displacement)
            .task {
                guard !reduceMotion, displacement != 0 else { return }
                // Use only the measured scroll distance; the two navigation stacks
                // don't share a reliable coordinate space during presentation.
                await Task.yield()
                guard !Task.isCancelled else { return }
                withAnimation(.easeOut(duration: 0.22)) {
                    displacement = 0
                }
            }
    }
}
