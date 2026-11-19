import CryptoKit
import Foundation
import Observation

enum ShowcaseAssetKind: String, CaseIterable, Codable, Sendable {
    case image, model

    var fileExtension: String { self == .image ? "webp" : "usdz" }
}

struct ShowcaseAsset: Decodable, Sendable {
    let kind: ShowcaseAssetKind
    let version: Int
    let url: URL
    let bytes: Int
    let sha256: String

    var filename: String { "v\(version)-\(sha256).\(kind.fileExtension)" }

    static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func isDigest(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }
}

struct ShowcaseManifest: Decodable {
    static let maximumBytes = 8 * 1024 * 1024
    let recipeID: Int
    let pairID: String
    let packs: [ShowcaseAsset]

    static func bundled() -> Self? {
        guard let url = Bundle.main.url(forResource: "tomato-egg-clay", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let manifest = try? JSONDecoder().decode(Self.self, from: data),
              manifest.recipeID == 250, manifest.pairID == "tomato-egg-clay",
              manifest.packs.count == ShowcaseAssetKind.allCases.count,
              Set(manifest.packs.map(\.kind)) == Set(ShowcaseAssetKind.allCases),
              manifest.packs.allSatisfy({ pack in
                  pack.version > 0 && (1...maximumBytes).contains(pack.bytes) && ShowcaseAsset.isDigest(pack.sha256)
                      && pack.url.absoluteString == "https://assets.yunle.fun/cook/showcase/\(manifest.recipeID)/\(manifest.pairID)/\(pack.kind.rawValue)/\(pack.filename)"
              })
        else { return nil }
        return manifest
    }
}

@MainActor @Observable
final class ShowcaseAssetStore {
    struct Installation {
        let url: URL
        let bytes: Int
    }

    typealias Transfer = @Sendable (ShowcaseAsset, @Sendable (Double) async -> Void) async throws -> Data

    let manifest: ShowcaseManifest?
    private(set) var installed: [ShowcaseAssetKind: Installation] = [:]
    private(set) var progress: [ShowcaseAssetKind: Double] = [:]
    private(set) var problems: [ShowcaseAssetKind: String] = [:]
    @ObservationIgnored private var tasks: [ShowcaseAssetKind: Task<Void, Never>] = [:]
    private let directory: URL
    private let transfer: Transfer
    private let fileManager = FileManager.default

    init(manifest: ShowcaseManifest?, directory: URL, transfer: Transfer? = nil) {
        self.manifest = manifest
        self.directory = directory
        self.transfer = transfer ?? { try await Self.fetch($0, progress: $1) }
    }

    static func live() -> ShowcaseAssetStore {
        let manifest = ShowcaseManifest.bundled()
        let directory = URL.applicationSupportDirectory.appending(path: "CookShowcaseAssets", directoryHint: .isDirectory)
        #if DEBUG
            let process = ProcessInfo.processInfo
            if process.arguments.contains("-ui-testing") {
                let env = process.environment
                let session = env["COOK_UI_TEST_SHOWCASE_SESSION"].flatMap(UUID.init(uuidString:)) ?? UUID()
                let testDirectory = URL.applicationSupportDirectory
                    .appending(path: "CookShowcaseUITests", directoryHint: .isDirectory)
                    .appending(path: session.uuidString, directoryHint: .isDirectory)
                if env["COOK_UI_TEST_SHOWCASE_LIVE"] != "1" {
                    let fixtures: [ShowcaseAssetKind: String] = [
                        .image: env["COOK_UI_TEST_SHOWCASE_IMAGE"] ?? "",
                        .model: env["COOK_UI_TEST_SHOWCASE_MODEL"] ?? "",
                    ]
                    return ShowcaseAssetStore(manifest: manifest, directory: testDirectory) { asset, _ in
                        guard let path = fixtures[asset.kind], !path.isEmpty else {
                            throw URLError(.notConnectedToInternet)
                        }
                        return try Data(contentsOf: URL(fileURLWithPath: path))
                    }
                }
                return ShowcaseAssetStore(manifest: manifest, directory: testDirectory)
            }
        #endif
        return ShowcaseAssetStore(manifest: manifest, directory: directory)
    }

    /// Find the most recent verified copy, including a previous version when an update is available.
    func reload() {
        guard let manifest else { return }
        for asset in manifest.packs {
            installed[asset.kind] = findInstallation(for: asset)
        }
    }

    @discardableResult
    func download(_ kind: ShowcaseAssetKind) -> Task<Void, Never>? {
        guard let asset = manifest?.packs.first(where: { $0.kind == kind }),
              tasks[kind] == nil, installed[kind]?.url.lastPathComponent != asset.filename
        else { return nil }
        progress[kind] = 0
        problems[kind] = nil
        let task = Task {
            defer {
                progress[kind] = nil
                tasks[kind] = nil
            }
            do {
                let data = try await transfer(asset) { [weak self] fraction in
                    await self?.reportProgress(fraction, for: kind)
                }
                try Task.checkCancellation()
                guard data.count == asset.bytes, ShowcaseAsset.digest(data) == asset.sha256 else {
                    throw AssetError.invalidContent
                }
                try install(data, for: asset)
            } catch is CancellationError {
                return
            } catch {
                if !Task.isCancelled {
                    problems[kind] = "下载失败，请重试。"
                }
            }
        }
        tasks[kind] = task
        return task
    }

    func cancel(_ kind: ShowcaseAssetKind) {
        tasks[kind]?.cancel()
    }

    func remove(_ kind: ShowcaseAssetKind) {
        guard tasks[kind] == nil, let folder = folder(for: kind) else { return }
        problems[kind] = nil
        do {
            try fileManager.removeItem(at: folder)
            installed[kind] = nil
        } catch let error as CocoaError where error.code == .fileNoSuchFile {
            installed[kind] = nil
        } catch {
            problems[kind] = "删除失败，请重试。"
        }
    }

    private func reportProgress(_ fraction: Double, for kind: ShowcaseAssetKind) {
        progress[kind] = fraction
    }

    private func folder(for kind: ShowcaseAssetKind) -> URL? {
        guard let manifest else { return nil }
        return directory
            .appending(path: "\(manifest.recipeID)-\(manifest.pairID)", directoryHint: .isDirectory)
            .appending(path: kind.rawValue, directoryHint: .isDirectory)
    }

    private func findInstallation(for asset: ShowcaseAsset) -> Installation? {
        guard let folder = folder(for: asset.kind) else { return nil }
        let urls = (try? fileManager.contentsOfDirectory(at: folder,
                                                        includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
                                                        options: [.skipsHiddenFiles])) ?? []
        let candidates = urls.compactMap { url -> (url: URL, version: Int, digest: String)? in
            let parts = url.deletingPathExtension().lastPathComponent.split(separator: "-", maxSplits: 1)
            guard url.pathExtension == asset.kind.fileExtension, parts.count == 2, parts[0].first == "v",
                  let version = Int(parts[0].dropFirst()), (1...asset.version).contains(version),
                  ShowcaseAsset.isDigest(String(parts[1])),
                  version < asset.version || url.lastPathComponent == asset.filename
            else { return nil }
            return (url, version, String(parts[1]))
        }
        for item in candidates.sorted(by: { $0.version > $1.version }) {
            guard let metadata = try? item.url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                  metadata.isRegularFile == true, let size = metadata.fileSize,
                  (1...ShowcaseManifest.maximumBytes).contains(size),
                  item.version < asset.version || size == asset.bytes,
                  let data = try? Data(contentsOf: item.url, options: .mappedIfSafe),
                  ShowcaseAsset.digest(data) == item.digest
            else { continue }
            return Installation(url: item.url, bytes: size)
        }
        return nil
    }

    private func install(_ data: Data, for asset: ShowcaseAsset) throws {
        var root = directory
        guard let folder = folder(for: asset.kind) else { throw AssetError.invalidContent }
        try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try root.setResourceValues(values)

        let destination = folder.appending(path: asset.filename, directoryHint: .notDirectory)
        try data.write(to: destination, options: .atomic)
        installed[asset.kind] = Installation(url: destination, bytes: data.count)

        // Only remove older copies after the replacement has been published locally.
        for url in (try? fileManager.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
            where url.lastPathComponent != destination.lastPathComponent {
            try? fileManager.removeItem(at: url)
        }
    }

    private enum AssetError: Error {
        case response
        case invalidContent
    }

    private nonisolated static func fetch(_ asset: ShowcaseAsset, progress: @Sendable (Double) async -> Void) async throws -> Data {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 120
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: asset.url)
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              http.url == asset.url,
              http.expectedContentLength == -1 || http.expectedContentLength == Int64(asset.bytes)
        else { throw AssetError.response }

        var data = Data()
        data.reserveCapacity(asset.bytes)
        let step = max(16 * 1024, asset.bytes / 50)
        for try await byte in bytes {
            guard data.count < asset.bytes else { throw AssetError.invalidContent }
            data.append(byte)
            if data.count.isMultiple(of: step) {
                await progress(Double(data.count) / Double(asset.bytes))
            }
        }
        return data
    }
}
