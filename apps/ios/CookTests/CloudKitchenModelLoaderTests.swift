import RealityKit
import UIKit
import XCTest
@testable import Cook

final class CloudKitchenModelLoaderTests: XCTestCase {
    @MainActor
    func testCachedClonesKeepSelectionAndTransformsIndependent() async throws {
        var loads = 0
        let prototype = Entity()
        let ingredient = Entity()
        ingredient.name = "Tomato"
        prototype.addChild(ingredient)
        let loader = CloudKitchenModelLoader(source: {
            loads += 1
            return prototype
        }, notificationCenter: NotificationCenter())

        let first = try await loader.modelClone()
        let tomato = try XCTUnwrap(first.findEntity(named: "Tomato"))
        tomato.isEnabled = false
        tomato.position.y = 2
        let second = try await loader.modelClone()
        let secondTomato = try XCTUnwrap(second.findEntity(named: "Tomato"))

        XCTAssertEqual(loads, 1)
        XCTAssertFalse(first === second)
        XCTAssertFalse(tomato === secondTomato)
        XCTAssertTrue(secondTomato.isEnabled)
        XCTAssertEqual(secondTomato.position.y, 0)
        XCTAssertTrue(ingredient.isEnabled)
        XCTAssertEqual(ingredient.position.y, 0)
        XCTAssertNil(prototype.parent)
    }

    @MainActor
    func testConcurrentScenesAndPreloadShareOneRequest() async throws {
        var loads = 0
        var continuation: CheckedContinuation<Entity, Never>?
        let loader = CloudKitchenModelLoader(source: {
            loads += 1
            return await withCheckedContinuation { continuation = $0 }
        }, notificationCenter: NotificationCenter())

        let warming = Task { try await loader.preload() }
        let first = Task { try await loader.modelClone() }
        let second = Task { try await loader.modelClone() }
        while continuation == nil { await Task.yield() }
        continuation?.resume(returning: Entity())

        try await warming.value
        let firstModel = try await first.value
        let secondModel = try await second.value
        XCTAssertEqual(loads, 1)
        XCTAssertFalse(firstModel === secondModel)
    }

    @MainActor
    func testFailedLoadCanRetry() async throws {
        enum Failure: Error { case expected }
        var loads = 0
        let loader = CloudKitchenModelLoader(source: {
            loads += 1
            if loads == 1 { throw Failure.expected }
            return Entity()
        }, notificationCenter: NotificationCenter())

        do {
            _ = try await loader.modelClone()
            XCTFail("The first load should fail")
        } catch Failure.expected { }
        _ = try await loader.modelClone()
        _ = try await loader.modelClone()
        XCTAssertEqual(loads, 2)
    }

    @MainActor
    func testEvictionPreservesAnActiveSceneAndReloadsNextVisit() async throws {
        var loads = 0
        let loader = CloudKitchenModelLoader(source: {
            loads += 1
            let model = Entity()
            model.name = "Loaded \(loads)"
            return model
        }, notificationCenter: NotificationCenter())

        let active = try await loader.modelClone()
        let anchor = AnchorEntity(world: .zero)
        anchor.addChild(active)
        loader.evictPrototype()
        let next = try await loader.modelClone()

        XCTAssertTrue(active.parent === anchor)
        XCTAssertEqual(active.name, "Loaded 1")
        XCTAssertEqual(next.name, "Loaded 2")
        XCTAssertEqual(loads, 2)
    }

    @MainActor
    func testMemoryWarningDuringLoadDoesNotRepopulateCache() async throws {
        var loads = 0
        var continuation: CheckedContinuation<Entity, Never>?
        let center = NotificationCenter()
        let loader = CloudKitchenModelLoader(source: {
            loads += 1
            if loads == 1 {
                return await withCheckedContinuation { continuation = $0 }
            }
            return Entity()
        }, notificationCenter: center)
        let pending = Task { try await loader.modelClone() }
        while continuation == nil { await Task.yield() }
        center.post(name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
        continuation?.resume(returning: Entity())
        _ = try await pending.value
        _ = try await loader.modelClone()

        XCTAssertEqual(loads, 2)
    }

    @MainActor
    func testCancelingOneSceneDoesNotCancelTheSharedLoad() async throws {
        var loads = 0
        var continuation: CheckedContinuation<Entity, Never>?
        let loader = CloudKitchenModelLoader(source: {
            loads += 1
            return await withCheckedContinuation { continuation = $0 }
        }, notificationCenter: NotificationCenter())
        let leaving = Task { try await loader.modelClone() }
        let remaining = Task { try await loader.modelClone() }
        while continuation == nil { await Task.yield() }
        leaving.cancel()
        continuation?.resume(returning: Entity())

        do {
            _ = try await leaving.value
            XCTFail("A canceled scene should not receive a clone")
        } catch is CancellationError { }
        _ = try await remaining.value
        _ = try await loader.modelClone()
        XCTAssertEqual(loads, 1)
    }

    /// Measures an application cache miss, not a process-cold load: RealityKit
    /// may have internal resource caches. Excludes ARView creation and the first GPU
    /// frame. No timing threshold depends on a simulator's current workload.
    @MainActor
    func testBundledUncachedLoadAndWarmCloneTimings() async throws {
        let loader = CloudKitchenModelLoader(notificationCenter: NotificationCenter())
        let clock = ContinuousClock()
        let coldStart = clock.now
        let cold = try await loader.modelClone()
        let coldTime = coldStart.duration(to: clock.now)
        let warmStart = clock.now
        let warm = try await loader.modelClone()
        let warmTime = warmStart.duration(to: clock.now)

        XCTAssertNotNil(cold.findEntity(named: "CookPot"))
        XCTAssertNotNil(warm.findEntity(named: "CookPot"))
        XCTAssertFalse(cold === warm)
        print("CloudKitchen model path: uncached load + clone = \(coldTime); cached clone = \(warmTime). Excludes ARView/GPU first frame.")
    }
}
