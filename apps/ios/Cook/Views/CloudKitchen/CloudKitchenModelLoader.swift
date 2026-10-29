import Combine
import RealityKit
import UIKit

/// Keeps one unmodified model hierarchy. Scene instances only receive clones,
/// so selecting food or running an animation never changes a later visit.
@MainActor
final class CloudKitchenModelLoader {
    typealias Source = @MainActor () async throws -> Entity

    static let shared = CloudKitchenModelLoader()

    private struct Request {
        let id: UUID
        let generation: UInt
        let task: Task<Entity, Error>
    }

    private let source: Source
    private let notificationCenter: NotificationCenter
    private var memoryWarningObserver: NSObjectProtocol?
    private var prototype: Entity?
    private var request: Request?
    private var generation: UInt = 0

    init(source: Source? = nil, notificationCenter: NotificationCenter = .default) {
        self.source = source ?? { try await Self.loadBundledPrototype() }
        self.notificationCenter = notificationCenter
        memoryWarningObserver = notificationCenter.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            // NotificationCenter guarantees this observer runs on .main.
            MainActor.assumeIsolated { self?.evictPrototype() }
        }
    }

    deinit {
        if let memoryWarningObserver {
            notificationCenter.removeObserver(memoryWarningObserver)
        }
    }

    func modelClone() async throws -> Entity {
        let model = try await cachedPrototype()
        try Task.checkCancellation()
        return model.clone(recursive: true)
    }

    /// Optional warming shares the same request as a visible scene and avoids
    /// creating a hierarchy that no view will use.
    func preload() async throws {
        _ = try await cachedPrototype()
        try Task.checkCancellation()
    }

    /// Existing scene clones remain valid. A pending load may finish for its
    /// callers, but memory pressure must not let it repopulate this cache.
    func evictPrototype() {
        prototype = nil
        generation &+= 1
    }

    private func cachedPrototype() async throws -> Entity {
        if let prototype { return prototype }

        let pending: Request
        if let request {
            pending = request
        } else {
            let source = source
            pending = Request(id: UUID(), generation: generation, task: Task { try await source() })
            request = pending
        }

        do {
            // An individual view leaving must not cancel another view's load.
            let model = try await pending.task.value
            if request?.id == pending.id {
                request = nil
                if pending.generation == generation { prototype = model }
            }
            return model
        } catch {
            // Match the request so an older waiter cannot clear a new retry.
            if request?.id == pending.id { request = nil }
            throw error
        }
    }

    private static func loadBundledPrototype() async throws -> Entity {
        guard let asset = CloudKitchenAsset.bundled,
              let url = Bundle.main.url(forResource: asset.model, withExtension: "usdz") else {
            throw LoadError.missingResource
        }
        // Publisher.values is available from iOS 15; retain the asynchronous
        // RealityKit loader used by our iOS 17 deployment target.
        for try await model in Entity.loadAsync(contentsOf: url).values { return model }
        throw LoadError.emptyModel
    }

    private enum LoadError: Error {
        case missingResource
        case emptyModel
    }
}
