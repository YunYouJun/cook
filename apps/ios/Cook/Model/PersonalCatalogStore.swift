import CookCore
import Foundation
import Observation

/// Device-local configuration, independent of the CloudKit schema.
@Observable
final class PersonalCatalogStore {
    private(set) var value = PersonalCatalog()
    private(set) var loadError: String?
    private let url: URL?
    private let write: (Data, URL) throws -> Void

    init(url: URL? = nil, write: @escaping (Data, URL) throws -> Void = { try $0.write(to: $1, options: .atomic) }) {
        self.url = url
        self.write = write
        reload()
    }
    static var defaultURL: URL {
        URL.applicationSupportDirectory.appendingPathComponent("Cook/PersonalCatalog.json")
    }
    func reload() {
        do {
            if let url, FileManager.default.fileExists(atPath: url.path) {
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= PersonalCatalog.fileLimit else { throw PersonalCatalogError.tooLarge }
                value = try PersonalCatalog.parse(String(contentsOf: url, encoding: .utf8))
            } else if url != nil { value = PersonalCatalog() }
            loadError = nil
        } catch { loadError = "本机配置读取失败，原始文件已保留。\(error.localizedDescription)" }
    }
    func update(_ change: (PersonalCatalog) throws -> PersonalCatalog) throws {
        // Re-read before writing; preserve corrupt data rather than replacing it with defaults.
        reload()
        guard loadError == nil else { throw PersonalCatalogError.invalid }
        let next = try change(value)
        let data = Data(try next.export().utf8)
        if let url {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try write(data, url)
        }
        value = next
    }
}
