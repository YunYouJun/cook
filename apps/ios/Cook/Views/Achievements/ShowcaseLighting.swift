import RealityKit
import UIKit

/// Attach to the stationary studio anchor, alongside the camera and outside the rotating dish.
@MainActor
func installShowcaseLighting(in view: ARView, on anchor: AnchorEntity) {
    view.environment.lighting.intensityExponent = -0.65
    let target = SIMD3<Float>(0, 0.017, 0)

    func addLight(from position: SIMD3<Float>, color: UIColor, lux: Float) {
        let light = DirectionalLight()
        light.light.color = color
        light.light.intensity = lux
        anchor.addChild(light)
        light.look(at: target, from: position, relativeTo: nil)
    }

    addLight(from: [-0.16, 0.28, 0.04], color: .init(red: 1, green: 0.965, blue: 0.91, alpha: 1),
             lux: 1250)
    addLight(from: [0.18, 0.15, 0.22], color: .init(red: 0.95, green: 0.975, blue: 1, alpha: 1),
             lux: 320)
    addLight(from: [0.05, 0.16, -0.17], color: .init(red: 1, green: 0.98, blue: 0.94, alpha: 1),
             lux: 280)
}
