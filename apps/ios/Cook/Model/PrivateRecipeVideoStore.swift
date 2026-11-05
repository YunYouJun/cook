import AVFoundation
import CookCore
import Foundation

struct PrivateVideoDraft: Sendable {
    let video: PrivateRecipeVideo
    let url: URL
}

enum PrivateVideoError: LocalizedError {
    case format, tooLarge, empty, unavailable, missing, otherDevice, downloadPending, corrupt
    var errorDescription: String? {
        switch self {
        case .format: return "请选择可播放的 MP4、MOV 或 M4V 视频。"
        case .tooLarge: return "视频超过 500 MB，请先剪短或压缩。"
        case .empty: return "视频文件为空，未改变原有附件。"
        case .unavailable: return "iCloud Drive 暂不可用。可重试，或关闭“存入 iCloud Drive”保存在本机。"
        case .missing: return "视频尚未同步到这台设备，或已被移动、删除。请稍后重试，或在编辑中重新选择视频。"
        case .otherDevice: return "这个视频只保存在原来的设备上。请在原设备存入 iCloud Drive，或在这里重新选择视频。"
        case .downloadPending: return "视频仍在下载。请检查网络和 iCloud 空间后重试。"
        case .corrupt: return "视频无法播放，文件可能已损坏或编码不受支持。请重新选择视频。"
        }
    }
}

/// All file coordination and bounded copying run off the main actor. Original files are read-only.
actor PrivateRecipeVideoStore {
    static let shared = PrivateRecipeVideoStore()
    private let localRoot: URL
    private let cloudRoot: @Sendable () -> URL?
    private let deviceID: String
    private let manager = FileManager.default
    private var activeDrafts: Set<String> = []
    private var cleanedDrafts = false

    init(localRoot: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("PrivateRecipeVideos", isDirectory: true),
         deviceID: String? = nil,
         cloudRoot: @escaping @Sendable () -> URL? = { FileManager.default.url(forUbiquityContainerIdentifier: "iCloud.fun.yunle.cook")?.appendingPathComponent("Documents/Videos", isDirectory: true) }) {
        self.localRoot = localRoot
        self.cloudRoot = cloudRoot
        // A local identifier is not a portable attachment URL and never grants file access.
        self.deviceID = deviceID ?? Self.installationID()
    }

    private static func installationID() -> String {
        let key = "privateRecipeVideos.deviceID"
        if let saved = UserDefaults.standard.string(forKey: key), UUID(uuidString: saved) != nil { return saved }
        let value = UUID().uuidString.lowercased()
        UserDefaults.standard.set(value, forKey: key)
        return value
    }

    func cloudAvailable() -> Bool { cloudRoot() != nil }

    func removeAbandonedDrafts() {
        guard !cleanedDrafts else { return }
        cleanedDrafts = true
        let root = localRoot.appendingPathComponent("Drafts", isDirectory: true)
        for directory in (try? manager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
            guard UUID(uuidString: directory.lastPathComponent) != nil, !activeDrafts.contains(directory.lastPathComponent) else { continue }
            try? manager.removeItem(at: directory)
        }
    }

    func stage(_ source: URL) async throws -> PrivateVideoDraft {
        let ext = source.pathExtension.lowercased()
        guard source.isFileURL, PrivateRecipeVideo.extensions.contains(ext) else { throw PrivateVideoError.format }
        let id = UUID().uuidString.lowercased()
        let directory = localRoot.appendingPathComponent("Drafts/\(id)", isDirectory: true)
        try manager.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent("\(id).\(ext)")
        activeDrafts.insert(id)
        var keep = false
        defer { if !keep { activeDrafts.remove(id); try? manager.removeItem(at: directory) } }
        let access = source.startAccessingSecurityScopedResource()
        defer { if access { source.stopAccessingSecurityScopedResource() } }
        try coordinatedRead(source) { url in try copyBounded(url, to: destination) }
        try Task.checkCancellation()
        let asset = AVURLAsset(url: destination)
        guard try await asset.load(.isPlayable), !(try await asset.loadTracks(withMediaType: .video)).isEmpty else { throw PrivateVideoError.format }
        try Task.checkCancellation()
        let size = try destination.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        let video = PrivateRecipeVideo(id: id, fileExtension: ext, originalName: String(source.lastPathComponent.prefix(200)), byteCount: Int64(size), location: .local, deviceID: deviceID)
        _ = try video.validated()
        keep = true
        return PrivateVideoDraft(video: video, url: destination)
    }

    func discard(_ draft: PrivateVideoDraft) {
        // Only this store's own draft directory can be removed.
        let expected = localRoot.appendingPathComponent("Drafts/\(draft.video.id)", isDirectory: true)
        guard draft.url.deletingLastPathComponent().standardizedFileURL == expected.standardizedFileURL else { return }
        activeDrafts.remove(draft.video.id)
        try? manager.removeItem(at: expected)
    }

    func install(_ draft: PrivateVideoDraft, inCloud: Bool) throws -> PrivateRecipeVideo {
        try Task.checkCancellation()
        var video = try draft.video.validated()
        // A retry gets a fresh destination even if a previous failed commit left an orphan.
        video.id = UUID().uuidString.lowercased()
        let root: URL
        if inCloud {
            guard let cloud = cloudRoot() else { throw PrivateVideoError.unavailable }
            root = cloud
            video.location = .iCloud
            video.deviceID = nil
        } else {
            root = localRoot.appendingPathComponent("Files", isDirectory: true)
            video.location = .local
            video.deviceID = deviceID
        }
        try manager.createDirectory(at: root, withIntermediateDirectories: true)
        let destination = root.appendingPathComponent(video.fileName)
        // Never overwrite another file, including an attachment staged by a concurrent editor.
        var failure: Error?
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(writingItemAt: destination, options: .forReplacing, error: &coordinationError) { url in
            let existed = manager.fileExists(atPath: url.path)
            do { try manager.copyItem(at: draft.url, to: url) }
            catch {
                if !existed { try? manager.removeItem(at: url) }
                failure = error
            }
        }
        if let error = coordinationError ?? failure { throw error }
        return video
    }

    /// Make an existing local attachment a new draft before explicit promotion to iCloud.
    func stageExisting(_ video: PrivateRecipeVideo) async throws -> PrivateVideoDraft {
        let url = try await playableURL(video)
        let draft = try await stage(url)
        var metadata = draft.video
        metadata.originalName = video.originalName
        return PrivateVideoDraft(video: metadata, url: draft.url)
    }

    func playableURL(_ video: PrivateRecipeVideo) async throws -> URL {
        let url = try fileURL(video)
        if video.location == .iCloud {
            // File metadata can arrive after the CloudKit recipe. Retry also starts a pending download.
            do { try manager.startDownloadingUbiquitousItem(at: url) }
            catch {
                if !manager.fileExists(atPath: url.path) { throw PrivateVideoError.missing }
            }
            for _ in 0..<120 {
                try Task.checkCancellation()
                let values = try? url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey, .ubiquitousItemDownloadingErrorKey, .isUbiquitousItemKey])
                if let error = values?.ubiquitousItemDownloadingError { throw error }
                if values?.ubiquitousItemDownloadingStatus == .current || values?.ubiquitousItemDownloadingStatus == .downloaded { break }
                // Injected directories used by unit tests are ordinary local files.
                if values?.isUbiquitousItem != true, manager.fileExists(atPath: url.path) { break }
                try await Task.sleep(for: .milliseconds(500))
            }
        }
        try Task.checkCancellation()
        guard manager.fileExists(atPath: url.path) else { throw PrivateVideoError.missing }
        let values = try url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey, .isUbiquitousItemKey])
        if values.isUbiquitousItem == true, values.ubiquitousItemDownloadingStatus == .notDownloaded { throw PrivateVideoError.downloadPending }
        let asset = AVURLAsset(url: url)
        guard try await asset.load(.isPlayable), !(try await asset.loadTracks(withMediaType: .video)).isEmpty else { throw PrivateVideoError.corrupt }
        return url
    }

    func remove(_ video: PrivateRecipeVideo) throws {
        let url = try fileURL(video)
        var failure: Error?
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(writingItemAt: url, options: .forDeleting, error: &coordinationError) { coordinated in
            do { try manager.removeItem(at: coordinated) }
            catch let error as CocoaError where error.code == .fileNoSuchFile { /* already removed */ }
            catch { failure = error }
        }
        if let error = coordinationError ?? failure { throw error }
    }

    private func fileURL(_ video: PrivateRecipeVideo) throws -> URL {
        _ = try video.validated()
        if video.location == .iCloud {
            guard let root = cloudRoot() else { throw PrivateVideoError.unavailable }
            return root.appendingPathComponent(video.fileName)
        }
        guard video.deviceID == deviceID else { throw PrivateVideoError.otherDevice }
        return localRoot.appendingPathComponent("Files", isDirectory: true).appendingPathComponent(video.fileName)
    }

    private func coordinatedRead(_ source: URL, action: (URL) throws -> Void) throws {
        var failure: Error?
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: source, options: .withoutChanges, error: &coordinationError) { url in
            do { try action(url) } catch { failure = error }
        }
        if let error = coordinationError ?? failure { throw error }
    }

    private func copyBounded(_ source: URL, to destination: URL) throws {
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true else { throw PrivateVideoError.format }
        guard Int64(values.fileSize ?? 0) <= PrivateRecipeVideo.byteLimit else { throw PrivateVideoError.tooLarge }
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        guard manager.createFile(atPath: destination.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        var count: Int64 = 0
        while let data = try input.read(upToCount: 1024 * 1024), !data.isEmpty {
            try Task.checkCancellation()
            count += Int64(data.count)
            guard count <= PrivateRecipeVideo.byteLimit else { throw PrivateVideoError.tooLarge }
            try output.write(contentsOf: data)
        }
        guard count > 0 else { throw PrivateVideoError.empty }
    }
}
