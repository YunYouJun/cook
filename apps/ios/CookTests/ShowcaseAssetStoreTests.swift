import XCTest
@testable import Cook

@MainActor
final class ShowcaseAssetStoreTests: XCTestCase {
    nonisolated private static let image = Data("tomato image".utf8)
    nonisolated private static let model = Data("tomato model".utf8)
    nonisolated private static let updatedModel = Data("updated tomato model".utf8)

    func testBundleManifestsContainBothPacksAndKeepPayloadsOutOfApp() throws {
        let manifest = try XCTUnwrap(ShowcaseManifest.bundled())
        XCTAssertEqual(manifest.recipeID, 250)
        XCTAssertEqual(manifest.pairID, "tomato-egg-clay")
        XCTAssertEqual(manifest.packs.map(\.kind), [.image, .model])
        XCTAssertNil(Bundle.main.url(forResource: "TomatoEggAchievement", withExtension: "usdz"))
        XCTAssertNil(Bundle.main.url(forResource: "tomato-egg", withExtension: "webp"))
    }

    func testIndependentDownloadsReopenOfflineDeleteAndRedownload() async throws {
        let directory = temporaryDirectory()
        let manifest = Self.manifest()
        let store = ShowcaseAssetStore(manifest: manifest, directory: directory) { asset, progress in
            await progress(0.5)
            return asset.kind == .image ? Self.image : Self.model
        }
        store.reload()
        XCTAssertTrue(store.installed.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))

        await store.download(.image)?.value
        XCTAssertNotNil(store.installed[.image])
        XCTAssertNil(store.installed[.model])
        await store.download(.model)?.value
        let modelURL = try XCTUnwrap(store.installed[.model]?.url)
        XCTAssertTrue(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)

        let reopened = ShowcaseAssetStore(manifest: manifest, directory: directory) { _, _ in
            throw URLError(.notConnectedToInternet)
        }
        reopened.reload()
        let imageURL = try XCTUnwrap(reopened.installed[.image]?.url)
        XCTAssertEqual(try Data(contentsOf: imageURL), Self.image)
        XCTAssertEqual(try Data(contentsOf: modelURL), Self.model)
        reopened.remove(.image)
        XCTAssertNil(reopened.installed[.image])
        XCTAssertEqual(reopened.installed[.model]?.url, modelURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: imageURL.path))
        await reopened.download(.image)?.value
        XCTAssertNotNil(reopened.problems[.image])
        XCTAssertNil(reopened.installed[.image])
        reopened.remove(.model)
        XCTAssertNil(reopened.installed[.model])
        XCTAssertFalse(FileManager.default.fileExists(atPath: modelURL.path))

        store.reload()
        XCTAssertTrue(store.installed.isEmpty)
        await store.download(.model)?.value
        XCTAssertNil(store.installed[.image])
        XCTAssertNotNil(store.installed[.model])
    }

    func testInvalidContentKeepsInstalledVersionUntilVerifiedReplacement() async throws {
        let directory = temporaryDirectory()
        let original = ShowcaseAssetStore(manifest: Self.manifest(), directory: directory) { _, _ in Self.model }
        await original.download(.model)?.value
        let previousURL = try XCTUnwrap(original.installed[.model]?.url)
        let manifest = Self.manifest(modelVersion: 2)
        for invalid in [Self.model, Data(repeating: 0, count: Self.updatedModel.count)] {
            let updating = ShowcaseAssetStore(manifest: manifest, directory: directory) { _, _ in invalid }
            updating.reload()
            XCTAssertEqual(updating.installed[.model]?.url, previousURL)
            await updating.download(.model)?.value
            XCTAssertNotNil(updating.problems[.model])
            XCTAssertEqual(updating.installed[.model]?.url, previousURL)
        }

        let updating = ShowcaseAssetStore(manifest: manifest, directory: directory) { _, _ in Self.updatedModel }
        updating.reload()
        await updating.download(.model)?.value
        let replacementURL = try XCTUnwrap(updating.installed[.model]?.url)
        XCTAssertNotEqual(replacementURL, previousURL)
        XCTAssertEqual(try Data(contentsOf: replacementURL), Self.updatedModel)
        XCTAssertFalse(FileManager.default.fileExists(atPath: previousURL.path))
    }

    func testCancelDiscardsLateTransferAndKeepsPreviousVersion() async throws {
        let directory = temporaryDirectory()
        let original = ShowcaseAssetStore(manifest: Self.manifest(), directory: directory) { _, _ in Self.model }
        await original.download(.model)?.value
        let previousURL = try XCTUnwrap(original.installed[.model]?.url)
        let updating = ShowcaseAssetStore(manifest: Self.manifest(modelVersion: 2), directory: directory) { _, progress in
            await progress(0.5)
            try? await Task.sleep(for: .seconds(1))
            return Self.updatedModel
        }
        updating.reload()
        let task = try XCTUnwrap(updating.download(.model))
        XCTAssertNil(updating.download(.model))
        updating.cancel(.model)
        await task.value
        XCTAssertNil(updating.progress[.model])
        XCTAssertNil(updating.problems[.model])
        XCTAssertEqual(updating.installed[.model]?.url, previousURL)
        updating.reload()
        XCTAssertEqual(updating.installed[.model]?.url, previousURL)
    }

    private static func manifest(modelVersion: Int = 1) -> ShowcaseManifest {
        func asset(_ kind: ShowcaseAssetKind, version: Int, data: Data) -> ShowcaseAsset {
            ShowcaseAsset(kind: kind, version: version, url: URL(string: "https://example.test")!,
                          bytes: data.count, sha256: ShowcaseAsset.digest(data))
        }
        return ShowcaseManifest(recipeID: 250, pairID: "tomato-egg-clay", packs: [
            asset(.image, version: 1, data: image),
            asset(.model, version: modelVersion, data: modelVersion == 1 ? model : updatedModel),
        ])
    }

    private func temporaryDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }
}
