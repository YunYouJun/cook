import AVFoundation
import CryptoKit
import Foundation

struct LocalRecipeVideo: Codable, Identifiable, Sendable {
    var customId: String
    var recipeName: String
    var filename: String
    var storageFilename: String
    var id: String { customId }
}

struct StoredRecipeVideo: Sendable {
    var metadata: LocalRecipeVideo
    var url: URL
}

enum LocalVideoError: LocalizedError {
    case invalid, unreadable
    var errorDescription: String? {
        switch self {
        case .invalid: return "请选择不超过 200 MB、可播放的 MP4 或 MOV 视频。"
        case .unreadable: return "本机视频文件无法读取，原有数据已保留。"
        }
    }
}

/// Each recipe has a hashed directory. A small atomic manifest commits a new copied file;
/// a failed copy/write leaves the previous attachment intact. No media enters CloudKit.
actor LocalRecipeVideoStore {
    static let shared = LocalRecipeVideoStore(root: URL.applicationSupportDirectory.appendingPathComponent("Cook/LocalVideos"))
    static let limit = 200 * 1024 * 1024
    let root: URL
    private let writeManifest: @Sendable (Data, URL) throws -> Void
    init(root: URL, writeManifest: @escaping @Sendable (Data, URL) throws -> Void = { try $0.write(to: $1, options: .atomic) }) {
        self.root = root
        self.writeManifest = writeManifest
    }
    private func directory(for id: String) -> URL {
        let hash = SHA256.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent(hash, isDirectory: true)
    }
    private func read(directory: URL) throws -> StoredRecipeVideo? {
        let manifest = directory.appendingPathComponent("manifest.json")
        guard FileManager.default.fileExists(atPath: manifest.path) else { return nil }
        guard (try manifest.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 16384 else { throw LocalVideoError.unreadable }
        let metadata = try JSONDecoder().decode(LocalRecipeVideo.self, from: Data(contentsOf: manifest))
        let name = metadata.storageFilename
        guard name.range(of: #"^[A-Fa-f0-9-]{36}\.(mp4|mov)$"#, options: .regularExpression) != nil,
              self.directory(for: metadata.customId).lastPathComponent == directory.lastPathComponent else { throw LocalVideoError.unreadable }
        let url = directory.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else { throw LocalVideoError.unreadable }
        return StoredRecipeVideo(metadata: metadata, url: url)
    }
    func video(for id: String) throws -> StoredRecipeVideo? { try read(directory: directory(for: id)) }
    func all() throws -> [StoredRecipeVideo] {
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])
            .compactMap { try read(directory: $0) }.sorted { $0.metadata.recipeName < $1.metadata.recipeName }
    }
    func save(id: String, recipeName: String, source: URL) async throws -> StoredRecipeVideo {
        let ext = source.pathExtension.lowercased()
        let size = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard !id.isEmpty, ["mp4", "mov"].contains(ext), size > 0, size <= Self.limit else { throw LocalVideoError.invalid }
        let asset = AVURLAsset(url: source)
        guard try await asset.load(.isPlayable), !(try await asset.loadTracks(withMediaType: .video)).isEmpty else { throw LocalVideoError.invalid }
        // No await between reading the old manifest and committing the replacement.
        return try copyValidated(id: id, recipeName: recipeName, source: source)
    }
    func copyValidated(id: String, recipeName: String, source: URL) throws -> StoredRecipeVideo {
        let ext = source.pathExtension.lowercased()
        guard !id.isEmpty, ["mp4", "mov"].contains(ext) else { throw LocalVideoError.invalid }
        let old = try video(for: id)
        let directory = directory(for: id)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let metadata = LocalRecipeVideo(customId: id, recipeName: recipeName, filename: source.lastPathComponent, storageFilename: UUID().uuidString + "." + ext)
        let destination = directory.appendingPathComponent(metadata.storageFilename)
        var committed = false
        defer { if !committed { try? FileManager.default.removeItem(at: destination) } }
        try FileManager.default.copyItem(at: source, to: destination)
        let size = try destination.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= Self.limit else { throw LocalVideoError.invalid }
        try writeManifest(JSONEncoder().encode(metadata), directory.appendingPathComponent("manifest.json"))
        committed = true
        if let old { try? FileManager.default.removeItem(at: old.url) }
        return StoredRecipeVideo(metadata: metadata, url: destination)
    }
    func remove(id: String) throws {
        let directory = directory(for: id)
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }
}
