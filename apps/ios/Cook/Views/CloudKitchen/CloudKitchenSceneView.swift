import Combine
import RealityKit
import SwiftUI
import UIKit

enum CloudKitchenSceneStatus { case loading, ready, failed }

/// RealityKit renders the shared USDZ locally. Only semantic actions cross the
/// boundary; the scene never owns ingredients or recommendation state.
struct CloudKitchenSceneView: UIViewRepresentable {
    let selected: [String]
    let cooking: Bool
    let dark: Bool
    let reduceMotion: Bool
    let onSelect: (String) -> Void
    let onCook: () -> Void
    let onStatus: (CloudKitchenSceneStatus) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> KitchenARView {
        let view = KitchenARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
        view.renderOptions = [.disableMotionBlur, .disableDepthOfField, .disableCameraGrain]
        view.isAccessibilityElement = false
        view.accessibilityIdentifier = "kitchen.scene"
        context.coordinator.start(in: view)
        return view
    }

    func updateUIView(_ view: KitchenARView, context: Context) {
        context.coordinator.update(self)
    }

    static func dismantleUIView(_ view: KitchenARView, coordinator: Coordinator) {
        coordinator.dispose()
        view.didLayout = nil
        view.accessibilityElements = nil
        view.scene.anchors.removeAll()
    }

    final class KitchenARView: ARView {
        var didLayout: (() -> Void)?

        override func layoutSubviews() {
            super.layoutSubviews()
            didLayout?()
        }
    }

    @MainActor
    final class Coordinator: NSObject {
        private var owner: CloudKitchenSceneView
        private weak var view: KitchenARView?
        private let anchor = AnchorEntity(world: .zero)
        private let camera = PerspectiveCamera()
        private var root: Entity?
        private var pot: Entity?
        private var potHome = Transform()
        private var ingredients: [String: Entity] = [:]
        private var homes: [String: Transform] = [:]
        private var load: AnyCancellable?
        private var animation: (any Cancellable)?
        private var elapsed: Float = 0
        private var cookingStarted: Float = 0
        private struct SelectionMotion {
            let entity: Entity
            let from: Transform
            let to: Transform
            let born: Float
        }
        private struct FoodFlight {
            let entity: Entity
            let start: SIMD3<Float>
            let scale: SIMD3<Float>
            let born: Float
        }
        private var selections: [String: SelectionMotion] = [:]
        private var flights: [FoodFlight] = []
        private var disposed = false
        private var accessibilityItems: [KitchenAccessibilityItem] = []

        init(_ owner: CloudKitchenSceneView) { self.owner = owner }

        func start(in view: KitchenARView) {
            self.view = view
            camera.camera.fieldOfViewInDegrees = 32
            anchor.addChild(camera)
            addLight(from: [-3, 8, 5], color: UIColor(red: 1, green: 0.95, blue: 0.85, alpha: 1), intensity: 2200, shadow: true)
            addLight(from: [5, 4, 3], color: UIColor(red: 0.79, green: 0.88, blue: 1, alpha: 1), intensity: 1000)
            addLight(from: [0, 5, -5], color: .white, intensity: 900)
            view.scene.addAnchor(anchor)
            view.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tap(_:))))
            view.didLayout = { [weak self] in self?.fitCamera() }
            updateBackground()
            guard let asset = CloudKitchenAsset.bundled,
                  let url = Bundle.main.url(forResource: asset.model, withExtension: "usdz") else {
                DispatchQueue.main.async { [weak self] in self?.owner.onStatus(.failed) }
                return
            }
            load = Entity.loadAsync(contentsOf: url)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] completion in
                    if case .failure = completion { self?.owner.onStatus(.failed) }
                } receiveValue: { [weak self] model in
                    guard let self, !self.disposed else { return }
                    self.install(model, asset: asset)
                }
        }

        private func addLight(from position: SIMD3<Float>, color: UIColor, intensity: Float, shadow: Bool = false) {
            let light = DirectionalLight()
            light.light.color = color
            light.light.intensity = intensity
            if shadow { light.shadow = .init(maximumDistance: 25, depthBias: 2) }
            light.look(at: [0, 1, 0], from: position, relativeTo: nil)
            anchor.addChild(light)
        }

        private func install(_ model: Entity, asset: CloudKitchenAsset) {
            root = model
            anchor.addChild(model)
            pot = model.findEntity(named: "CookPot")
            guard let pot else {
                owner.onStatus(.failed)
                return
            }
            potHome = pot.transform
            addHitArea(to: pot, radius: 0.55, height: 0.3)
            for ingredient in asset.ingredients {
                guard let entity = model.findEntity(named: ingredient.node) else { continue }
                ingredients[ingredient.name] = entity
                homes[ingredient.name] = entity.transform
                addHitArea(to: entity, radius: 0.27, height: 0.15)
            }
            guard ingredients.count == asset.ingredients.count else {
                owner.onStatus(.failed)
                return
            }
            makeAccessibilityItems(asset: asset)
            applySelection(animated: false)
            applyCooking()
            fitCamera()
            owner.onStatus(.ready)
        }

        private func addHitArea(to entity: Entity, radius: Float, height: Float) {
            let target = Entity()
            target.name = "HitArea"
            target.position.y = height
            target.components.set(CollisionComponent(shapes: [.generateSphere(radius: radius)], mode: .trigger))
            entity.addChild(target)
        }

        func update(_ next: CloudKitchenSceneView) {
            let selectionChanged = owner.selected != next.selected
            let cookingChanged = owner.cooking != next.cooking || owner.reduceMotion != next.reduceMotion
            let appearanceChanged = owner.dark != next.dark
            owner = next
            if next.reduceMotion {
                animation?.cancel()
                animation = nil
                applySelection(animated: false)
            }
            if selectionChanged { applySelection(animated: !next.reduceMotion) }
            if cookingChanged { applyCooking() }
            if appearanceChanged { updateBackground() }
        }

        private func applySelection(animated: Bool) {
            let selected = Set(owner.selected)
            for ingredient in CloudKitchenAsset.bundled?.ingredients ?? [] {
                let chosen = selected.contains(ingredient.name)
                root?.findEntity(named: "Selection_\(ingredient.id)")?.isEnabled = chosen
                guard let entity = ingredients[ingredient.name], var destination = homes[ingredient.name] else { continue }
                destination.translation.y += chosen ? 0.07 : 0
                entity.stopAllAnimations(recursive: false)
                if animated {
                    selections[ingredient.name] = SelectionMotion(entity: entity, from: entity.transform, to: destination, born: elapsed)
                    ensureAnimation()
                } else {
                    selections.removeValue(forKey: ingredient.name)
                    entity.transform = destination
                }
            }
            root?.findEntity(named: "PotFood")?.isEnabled = !selected.isEmpty
            updateAccessibilityFrames()
        }

        private func applyCooking() {
            flights.forEach { $0.entity.removeFromParent() }
            flights.removeAll()
            cookingStarted = elapsed
            pot?.transform = potHome
            root?.findEntity(named: "StoveFlame")?.isEnabled = owner.cooking
            for index in 0..<6 {
                root?.findEntity(named: "Steam_\(index)")?.isEnabled = owner.cooking
            }
            guard owner.cooking, !owner.reduceMotion, let root else { return }
            for (index, name) in owner.selected.enumerated() {
                guard let source = ingredients[name] else { continue }
                let clone = source.clone(recursive: true)
                clone.findEntity(named: "HitArea")?.removeFromParent()
                if let id = CloudKitchenAsset.bundled?.ingredients.first(where: { $0.name == name })?.id {
                    clone.findEntity(named: "Selection_\(id)")?.removeFromParent()
                }
                clone.name = "FlyingIngredient"
                clone.transform = Transform(matrix: source.transformMatrix(relativeTo: root))
                root.addChild(clone)
                flights.append(FoodFlight(entity: clone, start: clone.position, scale: clone.scale, born: elapsed + Float(index) * 0.035))
            }
            ensureAnimation()
        }

        private func ensureAnimation() {
            guard animation == nil, !owner.reduceMotion, let view else { return }
            animation = view.scene.subscribe(to: SceneEvents.Update.self) { [weak self] event in
                self?.animate(delta: Float(event.deltaTime))
            }
        }

        private func animate(delta: Float) {
            elapsed += min(delta, 0.05)
            for (name, motion) in selections {
                let t = min(1, max(0, (elapsed - motion.born) / 0.36))
                let pulse = sin(t * .pi) * (1 - t)
                var transform = motion.to
                transform.translation = motion.from.translation + (motion.to.translation - motion.from.translation) * t
                transform.translation.y += pulse * 0.2
                transform.scale *= SIMD3<Float>(1 + pulse * 0.12, 1 - pulse * 0.12, 1 + pulse * 0.12)
                transform.rotation *= simd_quatf(angle: pulse * 0.12, axis: [0, 0, 1])
                motion.entity.transform = transform
                if t >= 1 { selections.removeValue(forKey: name) }
            }
            for index in flights.indices.reversed() {
                let flight = flights[index]
                let t = min(1, max(0, (elapsed - flight.born) / 0.65))
                let target = SIMD3<Float>(1.28, 2.04, -1.62)
                flight.entity.position = flight.start + (target - flight.start) * t
                flight.entity.position.y += sin(t * .pi) * 1.35
                flight.entity.scale = flight.scale * (1 - t * 0.8)
                flight.entity.orientation = simd_quatf(angle: t * .pi * 1.5, axis: [0, 1, 0])
                if t >= 1 {
                    flight.entity.removeFromParent()
                    flights.remove(at: index)
                }
            }
            let cookingTime = elapsed - cookingStarted
            if owner.cooking {
                pot?.transform.rotation = potHome.rotation * simd_quatf(angle: sin(cookingTime * 18) * 0.035, axis: [0, 0, 1])
            }
            for index in 0..<6 where owner.cooking {
                let phase = (cookingTime * 0.6 + Float(index) / 6).truncatingRemainder(dividingBy: 1)
                guard let puff = root?.findEntity(named: "Steam_\(index)") else { continue }
                puff.position = [1.28 + sin(phase * 5 + Float(index)) * 0.12, 2.12 + phase * 1.1, -1.62]
                puff.scale = .init(repeating: sin(phase * .pi))
            }
            if !owner.cooking && selections.isEmpty && flights.isEmpty {
                animation?.cancel()
                animation = nil
                updateAccessibilityFrames()
            }
        }

        private func updateBackground() {
            view?.environment.background = .color(owner.dark
                ? UIColor(red: 0.10, green: 0.16, blue: 0.24, alpha: 1)
                : UIColor(red: 0.93, green: 0.97, blue: 1, alpha: 1))
        }

        private func fitCamera() {
            guard let view, root != nil, let framing = CloudKitchenAsset.bundled?.camera,
                  view.bounds.width > 0, view.bounds.height > 0 else { return }
            let direction = SIMD3<Float>(framing.direction)
            let center = SIMD3<Float>(framing.center)
            let verticalTangent = tan(Float.pi * 32 / 360)
            let horizontalTangent = verticalTangent * Float(view.bounds.width / view.bounds.height)
            var distance: Float = 1
            for point in framing.horizontalHull {
                distance = max(distance, abs(point[0]) / horizontalTangent + point[1])
            }
            for point in framing.verticalHull {
                distance = max(distance, abs(point[0]) / verticalTangent + point[1])
            }
            camera.look(at: center, from: center + direction * distance * 1.06, relativeTo: nil)
            updateAccessibilityFrames()
        }

        @objc private func tap(_ gesture: UITapGestureRecognizer) {
            guard let view, gesture.state == .ended else { return }
            let point = gesture.location(in: view)
            // Food is small on a phone. Resolve overlapping 44-point targets by
            // their closest projected center, rather than the frontmost sphere.
            let closest = accessibilityItems.compactMap { item -> (KitchenAccessibilityItem, CGFloat)? in
                let frame = item.accessibilityFrameInContainerSpace
                let distance = hypot(point.x - frame.midX, point.y - frame.midY)
                return distance <= (item.ingredient == nil ? 30 : 22) ? (item, distance) : nil
            }.min { $0.1 < $1.1 }
            if let closest {
                closest.0.activate?()
                return
            }
            for hit in view.hitTest(point) {
                var candidate: Entity? = hit.entity
                while let entity = candidate {
                    if entity === pot { owner.onCook(); return }
                    if let name = ingredients.first(where: { $0.value === entity })?.key {
                        owner.onSelect(name)
                        return
                    }
                    candidate = entity.parent
                }
            }
        }

        private func makeAccessibilityItems(asset: CloudKitchenAsset) {
            guard let view else { return }
            accessibilityItems = asset.ingredients.map { ingredient in
                let item = KitchenAccessibilityItem(accessibilityContainer: view)
                item.accessibilityLabel = "台面上的\(ingredient.name)"
                item.accessibilityIdentifier = "kitchen.scene.\(ingredient.id)"
                item.ingredient = ingredient.name
                item.activate = { [weak self] in self?.owner.onSelect(ingredient.name) }
                return item
            }
            let potItem = KitchenAccessibilityItem(accessibilityContainer: view)
            potItem.accessibilityLabel = "锅，开火推荐菜谱"
            potItem.accessibilityIdentifier = "kitchen.scene.pot"
            potItem.activate = { [weak self] in self?.owner.onCook() }
            accessibilityItems.append(potItem)
            view.accessibilityElements = accessibilityItems
        }

        private func updateAccessibilityFrames() {
            guard let view else { return }
            for item in accessibilityItems {
                let entity = item.ingredient.flatMap { ingredients[$0] } ?? pot
                guard let entity else { continue }
                let offset = SIMD3<Float>(0, item.ingredient == nil ? 0.3 : 0.15, 0)
                guard let projected = view.project(entity.convert(position: offset, to: nil)) else { continue }
                item.accessibilityFrameInContainerSpace = CGRect(x: projected.x - 22, y: projected.y - 22, width: 44, height: 44)
                item.accessibilityTraits = owner.selected.contains(item.ingredient ?? "") ? [.button, .selected] : .button
            }
        }

        func dispose() {
            disposed = true
            load?.cancel()
            animation?.cancel()
            load = nil
            animation = nil
            selections.removeAll()
            flights.forEach { $0.entity.removeFromParent() }
            flights.removeAll()
            root?.stopAllAnimations(recursive: true)
            root = nil
            pot = nil
            ingredients.removeAll()
            accessibilityItems.removeAll()
        }
    }
}

private final class KitchenAccessibilityItem: UIAccessibilityElement {
    var ingredient: String?
    var activate: (() -> Void)?

    override func accessibilityActivate() -> Bool {
        activate?()
        return true
    }
}
