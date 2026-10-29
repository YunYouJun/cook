import simd
import XCTest
@testable import Cook

final class CloudKitchenCameraOrbitTests: XCTestCase {
    func testDefaultCameraKeepsExistingManifestFraming() throws {
        let framing = try XCTUnwrap(CloudKitchenAsset.bundled?.camera)
        let orbit = CloudKitchenCameraOrbit(framing: framing)
        for viewport in [CGSize(width: 320, height: 430), CGSize(width: 720, height: 430)] {
            let pose = try XCTUnwrap(orbit.pose(viewport: viewport))
            let verticalTangent = tan(Float.pi * 32 / 360)
            let horizontalTangent = verticalTangent * Float(viewport.width / viewport.height)
            let horizontalDistance = framing.horizontalHull.map { abs($0[0]) / horizontalTangent + $0[1] }.max()!
            let verticalDistance = framing.verticalHull.map { abs($0[0]) / verticalTangent + $0[1] }.max()!
            let expected = max(1, horizontalDistance, verticalDistance) * 1.06
            XCTAssertEqual(simd_length(pose.position - pose.center), expected, accuracy: 0.0001)
            let expectedDirection = simd_normalize(SIMD3<Float>(framing.direction))
            XCTAssertLessThan(simd_length(simd_normalize(pose.position - pose.center) - expectedDirection), 0.00001)
        }
    }

    func testCumulativePanClampsAndDoesNotDrift() {
        var orbit = CloudKitchenCameraOrbit(framing: boxFraming)
        let viewport = CGSize(width: 400, height: 300)
        orbit.beginDrag()
        for _ in 0..<20 { orbit.updateDrag(translation: CGSize(width: 100, height: 50), viewport: viewport) }
        XCTAssertEqual(orbit.yaw, -CloudKitchenCameraOrbit.yawLimit / 2, accuracy: 0.00001)
        XCTAssertEqual(orbit.pitch, CloudKitchenCameraOrbit.pitchLimit / 3, accuracy: 0.00001)
        orbit.updateDrag(translation: CGSize(width: 10_000, height: -10_000), viewport: viewport)
        XCTAssertEqual(orbit.yaw, -CloudKitchenCameraOrbit.yawLimit)
        XCTAssertEqual(orbit.pitch, -CloudKitchenCameraOrbit.pitchLimit)
        orbit.beginDrag()
        orbit.updateDrag(translation: CGSize(width: -100, height: 50), viewport: viewport)
        XCTAssertEqual(orbit.yaw, -CloudKitchenCameraOrbit.yawLimit / 2, accuracy: 0.00001)
        XCTAssertEqual(orbit.pitch, -CloudKitchenCameraOrbit.pitchLimit * 2 / 3, accuracy: 0.00001)
    }

    func testRotatedCameraFramesEveryBoxCornerOnNarrowAndWideScreens() throws {
        let framing = boxFraming
        let center = SIMD3<Float>(framing.center)
        let direction = simd_normalize(SIMD3<Float>(framing.direction))
        let right = simd_normalize(simd_cross(SIMD3<Float>(0, 1, 0), direction))
        let up = simd_cross(direction, right)
        let corners = [-4.0 as Float, 4].flatMap { x in
            [-2.0 as Float, 3].flatMap { y in
                [-3.0 as Float, 2].map { z in center + right * x + up * y + direction * z }
            }
        }
        for viewport in [CGSize(width: 280, height: 430), CGSize(width: 720, height: 250)] {
            for x in [-1000.0, 0, 1000] {
                for y in [-1000.0, 0, 1000] {
                    var orbit = CloudKitchenCameraOrbit(framing: framing)
                    orbit.beginDrag()
                    orbit.updateDrag(translation: CGSize(width: x, height: y), viewport: viewport)
                    let pose = try XCTUnwrap(orbit.pose(viewport: viewport))
                    let backward = simd_normalize(pose.position - pose.center)
                    let cameraRight = simd_normalize(simd_cross(SIMD3<Float>(0, 1, 0), backward))
                    let cameraUp = simd_cross(backward, cameraRight)
                    let verticalTangent = tan(CloudKitchenCameraOrbit.fieldOfView * .pi / 360)
                    let horizontalTangent = verticalTangent * Float(viewport.width / viewport.height)
                    for corner in corners {
                        let relative = corner - pose.position
                        let depth = -simd_dot(relative, backward)
                        XCTAssertGreaterThan(depth, 0)
                        XCTAssertLessThan(abs(simd_dot(relative, cameraRight)) / depth, horizontalTangent)
                        XCTAssertLessThan(abs(simd_dot(relative, cameraUp)) / depth, verticalTangent)
                    }
                }
            }
        }
    }

    func testResizeDoesNotMutateAnglesAndResetRestoresDefaultPose() throws {
        let framing = boxFraming
        var orbit = CloudKitchenCameraOrbit(framing: framing)
        let viewport = CGSize(width: 400, height: 300)
        let original = try XCTUnwrap(orbit.pose(viewport: viewport))
        orbit.beginDrag()
        orbit.updateDrag(translation: CGSize(width: 80, height: 60), viewport: viewport)
        let angles = (orbit.yaw, orbit.pitch)
        _ = orbit.pose(viewport: CGSize(width: 800, height: 300))
        XCTAssertEqual(orbit.yaw, angles.0)
        XCTAssertEqual(orbit.pitch, angles.1)
        orbit.reset()
        let reset = try XCTUnwrap(orbit.pose(viewport: viewport))
        XCTAssertEqual(reset.center, original.center)
        XCTAssertEqual(reset.position, original.position)
        orbit.updateDrag(translation: .zero, viewport: viewport)
        XCTAssertEqual(orbit.yaw, 0)
        XCTAssertEqual(orbit.pitch, 0)
    }

    func testZeroSizeHasNoPoseAndIgnoresDrag() {
        var orbit = CloudKitchenCameraOrbit(framing: boxFraming)
        orbit.beginDrag()
        orbit.updateDrag(translation: CGSize(width: 100, height: 100), viewport: .zero)
        XCTAssertEqual(orbit.yaw, 0)
        XCTAssertEqual(orbit.pitch, 0)
        XCTAssertNil(orbit.pose(viewport: .zero))
    }

    private var boxFraming: CloudKitchenAsset.Camera {
        CloudKitchenAsset.Camera(
            center: [0.5, 1.5, -0.5],
            direction: [0.5793216, 0.3806008, 0.7207839],
            horizontalHull: [[-4, -3], [4, -3], [4, 2], [-4, 2]],
            verticalHull: [[-2, -3], [3, -3], [3, 2], [-2, 2]]
        )
    }
}
