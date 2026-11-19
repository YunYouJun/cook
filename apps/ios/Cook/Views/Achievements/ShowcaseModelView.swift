import Combine
import RealityKit
import SwiftUI

struct ShowcaseModelView: View {
    let catalogRecipeID: Int?
    let name: String
    let learnedAt: Date
    let modelURL: URL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var lowPowerMode = ProcessInfo.processInfo.isLowPowerModeEnabled
    @State private var model: Entity?
    @State private var loadFailed = false
    @State private var yaw = 0.0
    @GestureState private var drag = 0.0
    @State private var still: (angle: Double, image: UIImage)?
    @State private var resetFeedback = false

    private var previewsDrag: Bool { !reduceMotion && !lowPowerMode }
    private var activeDrag: Double { previewsDrag ? drag : 0 }
    private var displayedAngle: Double { Self.normalized(yaw + activeDrag) }
    private var canRotate: Bool { still != nil && !loadFailed }

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
                    } else if canRotate {
                        controls.padding(.top, 20)
                        Text(previewsDrag ? "左右拖动可旋转菜品" : "左右拖动，松手后旋转菜品")
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
            if canRotate {
                modelContent.accessibilityAdjustableAction { direction in
                    switch direction {
                    case .increment: turn(by: 30)
                    case .decrement: turn(by: -30)
                    @unknown default: break
                    }
                }
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
                if activeDrag == 0, let still, still.angle == yaw {
                    Image(uiImage: still.image).resizable().scaledToFit()
                } else {
                    ShowcaseRenderer(model: model, angle: displayedAngle, settling: activeDrag == 0) { angle, image in
                        if let image { still = (angle, image) }
                        else { loadFailed = true }
                    }
                }
            } else {
                ProgressView("正在加载模型")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .simultaneousGesture(DragGesture(minimumDistance: 4)
            .updating($drag) { value, state, _ in
                if previewsDrag { state = rotation(for: value.translation) }
            }
            .onEnded { value in turn(by: rotation(for: value.translation)) },
            including: canRotate ? .all : .none)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(loadFailed ? "\(name)徽章" : !canRotate ? "正在加载模型" : "\(name)模型")
        .accessibilityValue(canRotate ? "\(Int(displayedAngle.rounded()) % 360) 度" : "")
    }

    private func rotation(for translation: CGSize) -> Double {
        guard abs(translation.width) > abs(translation.height) else { return 0 }
        return Double(translation.width) * 0.65
    }

    private func turn(by degrees: Double) {
        yaw = Self.normalized(yaw + degrees)
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
                yaw = 0
                resetFeedback.toggle()
            } label: {
                Label("复位", systemImage: "arrow.uturn.backward")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .frame(minHeight: 48)
                    .contentShape(Rectangle())
            }
            .accessibilityHint("恢复到初始角度")
            .disabled(displayedAngle == 0)
        }
        .font(.title3.weight(.medium))
        .buttonStyle(.borderless)
        .tint(CookingJournalPalette.warmInk)
        .padding(.horizontal, 4)
        .floatingGlass(in: Capsule())
        .sensoryFeedback(.alignment, trigger: resetFeedback)
    }

    private static func normalized(_ degrees: Double) -> Double {
        let remainder = degrees.truncatingRemainder(dividingBy: 360)
        return remainder < 0 ? remainder + 360 : remainder
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
    let angle: Double
    let settling: Bool
    let onStill: (Double, UIImage?) -> Void

    private static let usesLowMemoryRendering = ProcessInfo.processInfo.physicalMemory <= 4 * 1024 * 1024 * 1024

    final class Coordinator {
        let pivot = Entity()
        var pending: Task<Void, Never>?
        var angle: Double?
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
        let camera = PerspectiveCamera()
        camera.camera.fieldOfViewInDegrees = 28.841546
        camera.camera.near = 0.003
        anchor.addChild(camera)
        camera.look(at: [0, 0.0135, 0], from: [0.1577126, 0.2610783, 0.225237], relativeTo: nil)
        installShowcaseLighting(in: view, on: anchor)
        return view
    }

    func updateUIView(_ view: ARView, context: Context) {
        let state = context.coordinator
        guard angle != state.angle || settling != state.settling else { return }
        state.angle = angle
        state.settling = settling
        state.pivot.orientation = simd_quatf(angle: Float(angle) * .pi / 180, axis: [0, 1, 0])
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
                        onStill(angle, image)
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
