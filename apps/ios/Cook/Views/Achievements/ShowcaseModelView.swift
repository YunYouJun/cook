import Combine
import RealityKit
import SwiftUI

struct ShowcaseModelView: View {
    let catalogRecipeID: Int?
    let name: String
    let learnedAt: Date
    let modelURL: URL
    @Binding var viewpoint: ShowcaseViewpoint
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var lowPowerMode = ProcessInfo.processInfo.isLowPowerModeEnabled
    @State private var model: Entity?
    @State private var loadFailed = false
    @GestureState private var preview: ShowcaseViewpoint? = nil
    @State private var still: (viewpoint: ShowcaseViewpoint, image: UIImage)?
    @State private var resetFeedback = false

    private var previewsGesture: Bool { !reduceMotion && !lowPowerMode }
    private var activePreview: ShowcaseViewpoint? { previewsGesture ? preview : nil }
    private var displayedViewpoint: ShowcaseViewpoint { activePreview ?? viewpoint }
    private var canAdjust: Bool { still != nil && !loadFailed }

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 0) {
                    viewport
                        .aspectRatio(1, contentMode: .fit)
                        .frame(maxWidth: 420)
                    if loadFailed {
                        VStack(spacing: 8) {
                            Text(name).font(.headline)
                            Text("首次学会 \(CookingDates.plaque(learnedAt))")
                                .accessibilityLabel("首次学会 \(CookingDates.celebration(learnedAt))")
                            Text("模型暂不可用，已显示菜品徽章。")
                        }
                        .font(.footnote)
                        .padding(.top, 24)
                    } else if canAdjust {
                        controls.padding(.top, 20)
                        zoomControls.padding(.top, 8)
                        Text(previewsGesture ? "左右拖动旋转，双指缩放" : "拖动或双指缩放，松手后应用")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.top, 12)
                    }
                }
                .multilineTextAlignment(.center)
                .padding()
                .frame(maxWidth: .infinity, minHeight: geometry.size.height)
            }
        }
        .navigationTitle("3D 模型")
        .navigationBarTitleDisplayMode(.inline)
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange)
            .receive(on: DispatchQueue.main)) { _ in
                lowPowerMode = ProcessInfo.processInfo.isLowPowerModeEnabled
            }
        .task {
            do {
                if #available(iOS 18, *) {
                    let entity = try await Entity(contentsOf: modelURL)
                    try Task.checkCancellation()
                    model = entity
                    return
                }
                for try await entity in Entity.loadAsync(contentsOf: modelURL).values {
                    try Task.checkCancellation()
                    model = entity
                    return
                }
                if !Task.isCancelled { loadFailed = true }
            } catch {
                if !Task.isCancelled { loadFailed = true }
            }
        }
    }

    private var viewport: some View {
        Group {
            if canAdjust {
                modelContent.accessibilityAdjustableAction { direction in
                    switch direction {
                    case .increment: turn(by: 30)
                    case .decrement: turn(by: -30)
                    @unknown default: break
                    }
                }
                .accessibilityAction(named: Text("放大模型")) { zoom(by: ShowcaseViewpoint.zoomStep) }
                .accessibilityAction(named: Text("缩小模型")) { zoom(by: 1 / ShowcaseViewpoint.zoomStep) }
            } else {
                modelContent
            }
        }
        .accessibilityIdentifier("achievement.model")
    }

    private var modelContent: some View {
        ZStack {
            if loadFailed {
                LearnedDishBadge(catalogRecipeID: catalogRecipeID, size: 144)
            } else if let model {
                if activePreview == nil, let still, still.viewpoint == viewpoint {
                    Image(uiImage: still.image).resizable().scaledToFit()
                } else {
                    ShowcaseRenderer(model: model, viewpoint: displayedViewpoint, settling: activePreview == nil) { captured, image in
                        if let image { still = (captured, image) }
                        else { loadFailed = true }
                    }
                }
            } else {
                ProgressView("正在加载模型")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .simultaneousGesture(modelGesture, including: canAdjust ? .all : .none)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(loadFailed ? "\(name)徽章" : !canAdjust ? "正在加载模型" : "\(name)模型")
        .accessibilityValue(canAdjust ? viewpointDescription : "")
    }

    private var viewpointDescription: String {
        let angle = "\(displayedViewpoint.angleDegrees) 度"
        let zoom = displayedViewpoint.zoomPercent
        return zoom == 100 ? angle : "\(angle)，缩放 \(zoom)%"
    }

    private var modelGesture: some Gesture {
        DragGesture(minimumDistance: 4).simultaneously(with: MagnifyGesture())
            .updating($preview) { value, state, _ in
                if previewsGesture {
                    let next = adjustedViewpoint(for: value)
                    state = next == viewpoint ? nil : next
                }
            }
            .onEnded { value in viewpoint = adjustedViewpoint(for: value) }
    }

    private func adjustedViewpoint(for value: SimultaneousGesture<DragGesture, MagnifyGesture>.Value) -> ShowcaseViewpoint {
        let degrees = value.first.map { rotation(for: $0.translation) } ?? 0
        return viewpoint.adjusted(rotation: degrees, pinch: value.second.map { Double($0.magnification) })
    }

    private func rotation(for translation: CGSize) -> Double {
        guard abs(translation.width) > abs(translation.height) else { return 0 }
        return Double(translation.width) * 0.65
    }

    private func turn(by degrees: Double) {
        viewpoint = viewpoint.adjusted(rotation: degrees)
    }

    private func zoom(by scale: Double) {
        viewpoint = viewpoint.adjusted(pinch: scale)
    }

    private var controls: some View {
        HStack(spacing: 0) {
            RotationStepButton(title: "左转", symbol: "arrow.counterclockwise") {
                turn(by: -30)
            }
            RotationStepButton(title: "右转", symbol: "arrow.clockwise") {
                turn(by: 30)
            }
            Divider().frame(height: 20).accessibilityHidden(true)
            Button {
                viewpoint = .initial
                resetFeedback.toggle()
            } label: {
                Label("复位", systemImage: "arrow.uturn.backward")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .frame(minHeight: 48)
                    .contentShape(Rectangle())
            }
            .accessibilityHint("恢复初始角度和缩放比例")
            .disabled(displayedViewpoint == .initial)
        }
        .font(.title3.weight(.medium))
        .buttonStyle(.borderless)
        .tint(CookingJournalPalette.warmInk)
        .padding(.horizontal, 4)
        .floatingGlass(in: Capsule())
        .sensoryFeedback(.alignment, trigger: resetFeedback)
    }

    private var zoomControls: some View {
        HStack(spacing: 8) {
            Button { zoom(by: 1 / ShowcaseViewpoint.zoomStep) } label: {
                Label("缩小模型", systemImage: "minus.magnifyingglass").labelStyle(.iconOnly)
                    .frame(minWidth: 48, minHeight: 48)
                    .contentShape(Rectangle())
            }
            .disabled(viewpoint.magnification <= ShowcaseViewpoint.minimumMagnification)
            Text("\(displayedViewpoint.zoomPercent)%")
                .font(.subheadline.monospacedDigit())
                .accessibilityLabel("缩放 \(displayedViewpoint.zoomPercent)%")
            Button { zoom(by: ShowcaseViewpoint.zoomStep) } label: {
                Label("放大模型", systemImage: "plus.magnifyingglass").labelStyle(.iconOnly)
                    .frame(minWidth: 48, minHeight: 48)
                    .contentShape(Rectangle())
            }
            .disabled(viewpoint.magnification >= ShowcaseViewpoint.maximumMagnification)
        }
        .buttonStyle(.borderless)
        .tint(CookingJournalPalette.warmInk)
    }
}

private struct RotationStepButton: View {
    let title: LocalizedStringKey
    let symbol: String
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var taps = 0

    var body: some View {
        Button {
            action()
            taps += 1
        } label: {
            Label(title, systemImage: symbol)
                .labelStyle(.iconOnly)
                .symbolEffect(.bounce, value: reduceMotion ? 0 : taps)
                .padding(8)
                .frame(minWidth: 52, minHeight: 48)
                .contentShape(Rectangle())
        }
        .accessibilityHint("每次旋转 30 度")
        .sensoryFeedback(.selection, trigger: taps)
    }
}

/// Once the requested orientation is on screen, keep its snapshot and release the live renderer.
private struct ShowcaseRenderer: UIViewRepresentable {
    let model: Entity
    let viewpoint: ShowcaseViewpoint
    let settling: Bool
    let onStill: (ShowcaseViewpoint, UIImage?) -> Void

    private static let usesLowMemoryRendering = ProcessInfo.processInfo.physicalMemory <= 4 * 1024 * 1024 * 1024

    final class Coordinator {
        let pivot = Entity()
        let camera = PerspectiveCamera()
        var pending: Task<Void, Never>?
        var viewpoint: ShowcaseViewpoint?
        var settling = false
        var snapshotID: UUID?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> ARView {
        let view = ARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
        // Limit render buffers and the resulting snapshot on memory-constrained devices.
        if Self.usesLowMemoryRendering {
            view.contentScaleFactor = min(view.contentScaleFactor, 1)
        }
        view.isOpaque = false
        view.backgroundColor = .clear
        view.environment.background = .color(.clear)
        let anchor = AnchorEntity(world: .zero)
        view.scene.addAnchor(anchor)
        anchor.addChild(context.coordinator.pivot)
        context.coordinator.pivot.addChild(model)
        let camera = context.coordinator.camera
        camera.camera.fieldOfViewInDegrees = 28.841546
        camera.camera.near = 0.003
        anchor.addChild(camera)
        installShowcaseLighting(in: view, on: anchor)
        return view
    }

    func updateUIView(_ view: ARView, context: Context) {
        let state = context.coordinator
        guard viewpoint != state.viewpoint || settling != state.settling else { return }
        state.viewpoint = viewpoint
        state.settling = settling
        state.pivot.orientation = simd_quatf(angle: Float(viewpoint.angle) * .pi / 180, axis: [0, 1, 0])
        let focus: SIMD3<Float> = [0, 0.0135, 0]
        let initialPosition: SIMD3<Float> = [0.1577126, 0.2610783, 0.225237]
        let position = focus + (initialPosition - focus) / Float(viewpoint.magnification)
        state.camera.look(at: focus, from: position, relativeTo: nil)
        state.pending?.cancel()
        state.snapshotID = nil
        if settling {
            let snapshotID = UUID()
            state.snapshotID = snapshotID
            state.pending = Task { @MainActor [weak view, weak state] in
                do { try await Task.sleep(for: .milliseconds(200)) } catch { return }
                view?.snapshot(saveToHDR: false) { [weak state] image in
                    Task { @MainActor in
                        guard state?.snapshotID == snapshotID else { return }
                        onStill(viewpoint, image)
                    }
                }
            }
        }
    }

    static func dismantleUIView(_ view: ARView, coordinator: Coordinator) {
        coordinator.snapshotID = nil
        coordinator.pending?.cancel()
        view.scene.anchors.removeAll()
    }
}
