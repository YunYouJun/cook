import Foundation
import simd

/// Camera-only interaction state. The model stays fixed so ingredient hit tests,
/// selection effects and the cooking animation share the same coordinates.
struct CloudKitchenCameraOrbit {
    static let fieldOfView: Float = 32
    static let yawLimit: Float = 35 * .pi / 180
    static let pitchLimit: Float = 12 * .pi / 180

    struct Pose {
        let center: SIMD3<Float>
        let position: SIMD3<Float>
    }

    private(set) var yaw: Float = 0
    private(set) var pitch: Float = 0
    private var dragYaw: Float = 0
    private var dragPitch: Float = 0
    private let center: SIMD3<Float>
    private let originalDirection: SIMD3<Float>
    private let framingPoints: [SIMD3<Float>]

    init(framing: CloudKitchenAsset.Camera) {
        center = SIMD3<Float>(framing.center)
        originalDirection = simd_normalize(SIMD3<Float>(framing.direction))
        framingPoints = Self.enclosingPoints(framing: framing, direction: originalDirection)
    }

    mutating func beginDrag() {
        dragYaw = yaw
        dragPitch = pitch
    }

    /// Translation is cumulative from the start of the current pan, just like
    /// UIPanGestureRecognizer.translation(in:). Repeated updates do not drift.
    mutating func updateDrag(translation: CGSize, viewport: CGSize) {
        guard viewport.width > 0, viewport.height > 0,
              translation.width.isFinite, translation.height.isFinite else { return }
        yaw = min(Self.yawLimit, max(-Self.yawLimit,
            dragYaw - Float(translation.width / viewport.width) * Self.yawLimit * 2))
        pitch = min(Self.pitchLimit, max(-Self.pitchLimit,
            dragPitch + Float(translation.height / viewport.height) * Self.pitchLimit * 2))
    }

    mutating func reset() {
        yaw = 0
        pitch = 0
        dragYaw = 0
        dragPitch = 0
    }

    func pose(viewport: CGSize) -> Pose? {
        guard viewport.width > 0, viewport.height > 0,
              viewport.width.isFinite, viewport.height.isFinite,
              !framingPoints.isEmpty else { return nil }
        let azimuth = atan2(originalDirection.x, originalDirection.z) + yaw
        let elevation = asin(originalDirection.y) + pitch
        let direction = SIMD3<Float>(sin(azimuth) * cos(elevation), sin(elevation), cos(azimuth) * cos(elevation))
        let right = simd_normalize(simd_cross(SIMD3<Float>(0, 1, 0), direction))
        let up = simd_cross(direction, right)
        let verticalTangent = tan(Self.fieldOfView * .pi / 360)
        let horizontalTangent = verticalTangent * Float(viewport.width / viewport.height)
        var distance: Float = 1
        for point in framingPoints {
            let depth = simd_dot(point, direction)
            distance = max(distance, abs(simd_dot(point, right)) / horizontalTangent + depth)
            distance = max(distance, abs(simd_dot(point, up)) / verticalTangent + depth)
        }
        return Pose(center: center, position: center + direction * distance * 1.06)
    }

    /// The manifest stores horizontal/depth and vertical/depth convex hulls.
    /// Their extruded intersection encloses every exported vertex. At each hull
    /// depth, its four section corners are enough to frame that enclosure from
    /// any permitted direction, without reading all 38,000 model triangles.
    private static func enclosingPoints(framing: CloudKitchenAsset.Camera, direction: SIMD3<Float>) -> [SIMD3<Float>] {
        let right = simd_normalize(simd_cross(SIMD3<Float>(0, 1, 0), direction))
        let up = simd_cross(direction, right)
        let depths = Set((framing.horizontalHull + framing.verticalHull).map { $0[1] }).sorted()
        return depths.flatMap { depth -> [SIMD3<Float>] in
            guard let horizontal = section(of: framing.horizontalHull, at: depth),
                  let vertical = section(of: framing.verticalHull, at: depth) else { return [] }
            return [horizontal.lowerBound, horizontal.upperBound].flatMap { x in
                [vertical.lowerBound, vertical.upperBound].map { y in
                    right * x + up * y + direction * depth
                }
            }
        }
    }

    private static func section(of hull: [[Float]], at depth: Float) -> ClosedRange<Float>? {
        guard hull.count >= 3 else { return nil }
        var coordinates: [Float] = []
        for index in hull.indices {
            let start = hull[index]
            let end = hull[(index + 1) % hull.count]
            guard depth >= min(start[1], end[1]), depth <= max(start[1], end[1]) else { continue }
            let delta = end[1] - start[1]
            if delta == 0 {
                coordinates.append(contentsOf: [start[0], end[0]])
            } else {
                coordinates.append(start[0] + (end[0] - start[0]) * ((depth - start[1]) / delta))
            }
        }
        guard let low = coordinates.min(), let high = coordinates.max() else { return nil }
        return low...high
    }
}
