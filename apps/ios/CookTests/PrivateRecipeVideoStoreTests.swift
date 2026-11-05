import AVFoundation
import CookCore
import SwiftData
import XCTest
@testable import Cook

final class PrivateRecipeVideoStoreTests: XCTestCase {
    private let device = "00000000-0000-0000-0000-000000000001"
    private func fixture() throws -> URL { try XCTUnwrap(Bundle(for: Self.self).url(forResource: "private-recipe-video", withExtension: "mp4")) }
    private func temporaryRoot() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("private-video-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    func testLocalVideoImportSurvivesReopeningAndNeverDeletesOriginal() async throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = PrivateRecipeVideoStore(localRoot: root, deviceID: device, cloudRoot: { nil })
        let source = try fixture()
        let original = try Data(contentsOf: source)
        let draft = try await store.stage(source)
        let saved = try await store.install(draft, inCloud: false)
        await store.discard(draft)
        XCTAssertFalse(FileManager.default.fileExists(atPath: draft.url.path))
        let reopened = PrivateRecipeVideoStore(localRoot: root, deviceID: device, cloudRoot: { nil })
        let url = try await reopened.playableURL(saved)
        XCTAssertEqual(try Data(contentsOf: url), original)
        try await reopened.remove(saved)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertEqual(try Data(contentsOf: source), original)
    }

    func testCloudReferenceResolvesFromDifferentDeviceWithoutAbsolutePath() async throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let cloud = root.appendingPathComponent("cloud")
        let store = PrivateRecipeVideoStore(localRoot: root.appendingPathComponent("a"), deviceID: device, cloudRoot: { cloud })
        let draft = try await store.stage(fixture())
        let saved = try await store.install(draft, inCloud: true)
        await store.discard(draft)
        XCTAssertEqual(saved.location, .iCloud)
        XCTAssertNil(saved.deviceID)
        let other = PrivateRecipeVideoStore(localRoot: root.appendingPathComponent("b"), deviceID: UUID().uuidString, cloudRoot: { cloud })
        let url = try await other.playableURL(saved)
        XCTAssertEqual(url.lastPathComponent, saved.fileName)
        try await other.remove(saved)
        do { _ = try await store.playableURL(saved); XCTFail("Deleted file must be reported missing") }
        catch PrivateVideoError.missing { }
    }

    func testUnavailableCloudAndOtherDeviceKeepDraftAndLocalVideo() async throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = PrivateRecipeVideoStore(localRoot: root, deviceID: device, cloudRoot: { nil })
        let draft = try await store.stage(fixture())
        do { _ = try await store.install(draft, inCloud: true); XCTFail("Unavailable cloud must not report success") }
        catch PrivateVideoError.unavailable { }
        XCTAssertTrue(FileManager.default.fileExists(atPath: draft.url.path))
        let saved = try await store.install(draft, inCloud: false)
        await store.discard(draft)
        let other = PrivateRecipeVideoStore(localRoot: root, deviceID: UUID().uuidString, cloudRoot: { nil })
        do { _ = try await other.playableURL(saved); XCTFail("Local reference belongs to its original installation") }
        catch PrivateVideoError.otherDevice { }
        _ = try await store.playableURL(saved)
    }

    func testCorruptOversizeAndCancelledImportsLeaveNoDrafts() async throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = PrivateRecipeVideoStore(localRoot: root.appendingPathComponent("store"), deviceID: device, cloudRoot: { nil })
        let bad = root.appendingPathComponent("bad.mp4")
        try Data("not a video".utf8).write(to: bad)
        do { _ = try await store.stage(bad); XCTFail("Renaming a file is not video validation") } catch { }
        let large = root.appendingPathComponent("large.mp4")
        FileManager.default.createFile(atPath: large.path, contents: nil)
        let handle = try FileHandle(forWritingTo: large)
        try handle.truncate(atOffset: UInt64(PrivateRecipeVideo.byteLimit + 1))
        try handle.close()
        do { _ = try await store.stage(large); XCTFail("Oversize video must be rejected before copying") }
        catch PrivateVideoError.tooLarge { }
        let source = try fixture()
        let task = Task { try await store.stage(source) }
        task.cancel()
        do { _ = try await task.value; XCTFail("Cancellation must not return an installed video") }
        catch is CancellationError { }
        let drafts = root.appendingPathComponent("store/Drafts")
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: drafts.path).isEmpty)
    }

    @MainActor
    func testRealVideoAdvancesPlaybackAndBackupRestoresCloudAssociation() async throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let cloud = root.appendingPathComponent("cloud")
        let files = PrivateRecipeVideoStore(localRoot: root.appendingPathComponent("a"), deviceID: device, cloudRoot: { cloud })
        let draft = try await files.stage(fixture())
        var recipe = PrivateRecipe(customId: "custom-backup-video", name: "蒸蛋", stuff: ["鸡蛋"], tools: [], createdAt: 1000, updatedAt: 1000)
        recipe.video = try await files.install(draft, inCloud: true)
        await files.discard(draft)
        let backup = try PrivateRecipeNativeFile.export(recipes: [recipe], backup: true)
        let restored = Persistence.makeContainer(inMemory: true)
        XCTAssertEqual(try PrivateRecipePersistence.importRecipes(PrivateRecipeNativeFile.parse(backup), keepCopies: false, in: restored), 1)
        let reference = try XCTUnwrap(PrivateRecipePersistence.snapshot(in: ModelContext(restored)).recipes.first?.video)
        let other = PrivateRecipeVideoStore(localRoot: root.appendingPathComponent("b"), deviceID: UUID().uuidString, cloudRoot: { cloud })
        let url = try await other.playableURL(reference)
        let player = AVPlayer(url: url)
        defer { player.pause() }
        player.play()
        for _ in 0..<30 {
            if player.currentTime().seconds > 0 { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertGreaterThan(player.currentTime().seconds, 0, "The real video must advance beyond its first frame")
    }

    func testAbandonedDraftCleanupKeepsActiveEditorAndCommittedFile() async throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = PrivateRecipeVideoStore(localRoot: root, deviceID: device, cloudRoot: { nil })
        let abandoned = root.appendingPathComponent("Drafts/" + UUID().uuidString.lowercased())
        try FileManager.default.createDirectory(at: abandoned, withIntermediateDirectories: true)
        let draft = try await store.stage(fixture())
        let saved = try await store.install(draft, inCloud: false)
        await store.removeAbandonedDrafts()
        XCTAssertFalse(FileManager.default.fileExists(atPath: abandoned.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: draft.url.path))
        _ = try await store.playableURL(saved)
        await store.discard(draft)
    }

    @MainActor
    func testSaveFailureKeepsOldRecordAndFileAndSuccessfulReplaceCleansIt() async throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = PrivateRecipeVideoStore(localRoot: root, deviceID: device, cloudRoot: { nil })
        let container = Persistence.makeContainer(inMemory: true)
        var recipe = PrivateRecipe(customId: "custom-video-atomic", name: "蒸蛋", stuff: ["鸡蛋"], tools: [], createdAt: 1000, updatedAt: 1000)
        let first = try await store.stage(fixture())
        recipe.video = try await store.install(first, inCloud: false)
        await store.discard(first)
        try PrivateRecipePersistence.save(recipe, replacing: nil, in: container)
        let replacement = try await store.stage(fixture())
        var edited = recipe
        edited.updatedAt += 1
        do {
            _ = try await PrivateRecipeVideoPersistence.save(edited, replacing: recipe, draft: replacement, inCloud: false, in: container, files: store, persist: { _, _, _ in throw CocoaError(.fileWriteOutOfSpace) })
            XCTFail("Disk failure must not report save success")
        } catch let error as CocoaError { XCTAssertEqual(error.code, .fileWriteOutOfSpace) }
        XCTAssertEqual(try PrivateRecipePersistence.snapshot(in: ModelContext(container)).recipes, [recipe])
        _ = try await store.playableURL(recipe.video!)
        XCTAssertTrue(FileManager.default.fileExists(atPath: replacement.url.path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("Files").path).count, 1)
        let clean = try await PrivateRecipeVideoPersistence.save(edited, replacing: recipe, draft: replacement, inCloud: false, in: container, files: store)
        XCTAssertTrue(clean)
        await store.discard(replacement)
        let saved = try XCTUnwrap(PrivateRecipePersistence.snapshot(in: ModelContext(container)).recipes.first)
        XCTAssertNotEqual(saved.video?.id, recipe.video?.id)
        XCTAssertEqual(saved.video?.byteCount, replacement.video.byteCount)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("Files").path).count, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("Files").appendingPathComponent(recipe.video!.fileName).path))
    }

    @MainActor
    func testSharedBackupReferencesRetainFileUntilFinalRecipeIsDeleted() async throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = PrivateRecipeVideoStore(localRoot: root, deviceID: device, cloudRoot: { nil })
        let container = Persistence.makeContainer(inMemory: true)
        let draft = try await store.stage(fixture())
        let video = try await store.install(draft, inCloud: false)
        await store.discard(draft)
        var first = PrivateRecipe(customId: "custom-first", name: "蒸蛋", stuff: ["鸡蛋"], tools: [], createdAt: 1000, updatedAt: 1000)
        first.video = video
        var second = first
        second.customId = "custom-second"
        try PrivateRecipePersistence.save(first, replacing: nil, in: container)
        try PrivateRecipePersistence.save(second, replacing: nil, in: container)
        try PrivateRecipePersistence.remove(first, in: container)
        try await PrivateRecipeVideoPersistence.removeIfUnreferenced(video, in: container, files: store)
        let path = try await store.playableURL(video)
        try PrivateRecipePersistence.remove(second, in: container)
        try await PrivateRecipeVideoPersistence.removeIfUnreferenced(video, in: container, files: store)
        XCTAssertFalse(FileManager.default.fileExists(atPath: path.path))
    }
}
