import Combine
import RealityKit
import SwiftUI

struct TomatoEggAchievementView: View {
    let learnedAt: Date
    @Environment(\.dismiss) private var dismiss
    @State private var model: Entity?
    @State private var loadFailed = false
    @State private var yaw = 0.0
    @GestureState private var drag = 0.0
    @State private var still: (angle: Double, image: UIImage)?
    @State private var resetFeedback = false

    private var displayedAngle: Double {
        Self.normalized(yaw + drag)
    }

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 0) {
                        viewport
                            .aspectRatio(1, contentMode: .fit)
                            .frame(maxWidth: 420)
                        Text("番茄炒蛋").font(.title.bold())
                        Text("首次学会 \(CookingDates.plaque(learnedAt))")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel("首次学会 \(CookingDates.celebration(learnedAt))")
                            .padding(.top, 6)
                        if loadFailed {
                            Text("模型暂不可用，已显示菜品徽章。").font(.footnote)
                                .padding(.top, 24)
                        } else if model != nil {
                            controls.padding(.top, 28)
                            Text("左右拖动可旋转菜品")
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
            .navigationTitle("特殊成就")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .task {
            guard let url = Bundle.main.url(forResource: "TomatoEggAchievement", withExtension: "usdz")
            else { loadFailed = true; return }
            do {
                for try await entity in Entity.loadAsync(contentsOf: url).values {
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

    @ViewBuilder private var viewport: some View {
        ZStack {
            if loadFailed {
                LearnedDishBadge(catalogRecipeID: 250, size: 144)
            } else if let model {
                if drag == 0, let still, still.angle == yaw {
                    Image(uiImage: still.image).resizable().scaledToFit()
                } else {
                    TomatoEggRenderer(model: model, angle: displayedAngle, settling: drag == 0) { angle, image in
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
        .gesture(DragGesture(minimumDistance: 4)
            .updating($drag) { value, state, _ in state = value.translation.width * 0.65 }
            .onEnded { value in yaw = Self.normalized(yaw + value.translation.width * 0.65) })
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(loadFailed ? "番茄炒蛋徽章" : model == nil ? "正在加载模型" : "番茄炒蛋模型")
        .accessibilityValue(model == nil || loadFailed ? "" : "\(Int(displayedAngle.rounded()) % 360) 度")
        .accessibilityIdentifier("achievement.model")
    }

    private var controls: some View {
        HStack(spacing: 0) {
            RotationStepButton(title: "左转", symbol: "arrow.counterclockwise") {
                yaw = Self.normalized(yaw - 30)
            }
            RotationStepButton(title: "右转", symbol: "arrow.clockwise") {
                yaw = Self.normalized(yaw + 30)
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
            .tint(.red)
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
private struct TomatoEggRenderer: UIViewRepresentable {
    let model: Entity
    let angle: Double
    let settling: Bool
    let onStill: (Double, UIImage?) -> Void

    final class Coordinator {
        let pivot = Entity()
        var pending: Task<Void, Never>?
        var angle: Double?
        var settling = false
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> ARView {
        let view = ARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
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
        installTomatoEggLighting(in: view, on: anchor)
        return view
    }

    func updateUIView(_ view: ARView, context: Context) {
        let state = context.coordinator
        guard angle != state.angle || settling != state.settling else { return }
        state.angle = angle
        state.settling = settling
        state.pivot.orientation = simd_quatf(angle: Float(angle) * .pi / 180, axis: [0, 1, 0])
        state.pending?.cancel()
        if settling {
            state.pending = Task { @MainActor [weak view, weak state] in
                do { try await Task.sleep(for: .milliseconds(200)) } catch { return }
                view?.snapshot(saveToHDR: false) { [weak state] image in
                    Task { @MainActor in
                        guard state?.angle == angle, state?.settling == true else { return }
                        onStill(angle, image)
                    }
                }
            }
        }
    }

    static func dismantleUIView(_ view: ARView, coordinator: Coordinator) {
        coordinator.settling = false
        coordinator.pending?.cancel()
        view.scene.anchors.removeAll()
    }
}
