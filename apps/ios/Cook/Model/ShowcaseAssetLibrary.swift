import Foundation
import Observation

/// Holds one shared install state per dish, including when multiple windows are open.
@MainActor @Observable
final class ShowcaseAssetLibrary {
    let stores: [Int: ShowcaseAssetStore]

    private init(directory: URL, transfer: (ShowcaseManifest) -> ShowcaseAssetStore.Transfer? = { _ in nil }) {
        let entries = ShowcaseManifest.bundled().map { manifest in
            let store = ShowcaseAssetStore(manifest: manifest, directory: directory, transfer: transfer(manifest))
            return (manifest.recipeID, store)
        }
        stores = Dictionary(uniqueKeysWithValues: entries)
    }

    static func live() -> ShowcaseAssetLibrary {
        #if DEBUG
            let process = ProcessInfo.processInfo
            if process.arguments.contains("-ui-testing") {
                let env = process.environment
                let session = env["COOK_UI_TEST_SHOWCASE_SESSION"].flatMap(UUID.init(uuidString:)) ?? UUID()
                let directory = URL.applicationSupportDirectory
                    .appending(path: "CookShowcaseUITests", directoryHint: .isDirectory)
                    .appending(path: session.uuidString, directoryHint: .isDirectory)
                if env["COOK_UI_TEST_SHOWCASE_LIVE"] == "1" {
                    return ShowcaseAssetLibrary(directory: directory)
                }

                let fixtures = env["COOK_UI_TEST_SHOWCASE_FIXTURES"].map { URL(fileURLWithPath: $0, isDirectory: true) }
                return ShowcaseAssetLibrary(directory: directory) { manifest in
                    { asset, _ in
                        guard let fixtures else { throw URLError(.notConnectedToInternet) }
                        let url = fixtures.appending(path: manifest.pairID, directoryHint: .isDirectory)
                            .appending(path: "\(asset.kind.rawValue).\(asset.kind.fileExtension)", directoryHint: .notDirectory)
                        return try Data(contentsOf: url)
                    }
                }
            }
        #endif
        let directory = URL.applicationSupportDirectory.appending(path: "CookShowcaseAssets", directoryHint: .isDirectory)
        return ShowcaseAssetLibrary(directory: directory)
    }
}
