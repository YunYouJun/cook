import CookCore
import Foundation
import SwiftData

@MainActor
enum PrivateRecipeVideoPersistence {
    /// Install first, commit the record, then release the old file. A failed commit leaves the old
    /// association untouched and removes only the newly installed file; the editor keeps its draft.
    static func save(_ recipe: PrivateRecipe, replacing original: PrivateRecipe?, draft: PrivateVideoDraft?, inCloud: Bool,
                     in container: ModelContainer, files: PrivateRecipeVideoStore,
                     persist: @MainActor (PrivateRecipe, PrivateRecipe?, ModelContainer) throws -> Void = { try PrivateRecipePersistence.save($0, replacing: $1, in: $2) }) async throws -> Bool {
        var saved = recipe
        var installed: PrivateRecipeVideo?
        do {
            if let draft {
                installed = try await files.install(draft, inCloud: inCloud)
                saved.video = installed
            }
            try Task.checkCancellation()
            try persist(saved, original, container)
        } catch let saveError {
            if let installed {
                do { try await files.remove(installed) }
                catch { throw RollbackError(cause: saveError) }
            }
            throw saveError
        }
        if let old = original?.video, old != saved.video {
            do { try await removeIfUnreferenced(old, in: container, files: files) }
            catch { return false }
        }
        return true
    }

    private struct RollbackError: LocalizedError {
        let cause: Error
        var errorDescription: String? {
            cause.localizedDescription + "\n新视频副本未能清理，可能继续占用本机或 iCloud 空间。原有菜谱和视频未改变，可以重试保存。"
        }
    }

    static func removeIfUnreferenced(_ video: PrivateRecipeVideo, in container: ModelContainer, files: PrivateRecipeVideoStore) async throws {
        let current = try PrivateRecipePersistence.snapshot(in: ModelContext(container))
        guard !current.recipes.contains(where: { $0.video?.id == video.id }) else { return }
        // A reference imported from another device never grants access to its local storage.
        do { try await files.remove(video) }
        catch PrivateVideoError.otherDevice { }
    }
}

extension Notification.Name {
    static let privateVideoCleanupFailed = Notification.Name("cook.privateVideoCleanupFailed")
}
