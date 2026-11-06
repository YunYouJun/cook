import CookCore
import CryptoKit
import Foundation

struct AccountUserDataSnapshot: Codable {
    var enabled = false
    var document = UserDataDocument()
}

@MainActor
protocol AccountUserDataCaching {
    func load(_ uid: String) throws -> AccountUserDataSnapshot
    func save(_ value: AccountUserDataSnapshot, uid: String) throws
}

/// Account records never enter the Apple account's SwiftData/CloudKit store.
@MainActor
final class AccountUserDataCache: AccountUserDataCaching {
    private let root: URL
    init(root: URL = URL.applicationSupportDirectory.appendingPathComponent("CookAccountLibraries", isDirectory: true)) { self.root = root }

    private func file(_ uid: String) -> URL {
        let hash = SHA256.hash(data: Data(uid.utf8)).map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent("\(hash).json")
    }

    func load(_ uid: String) throws -> AccountUserDataSnapshot {
        let url = file(uid)
        guard FileManager.default.fileExists(atPath: url.path) else { return AccountUserDataSnapshot() }
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        guard data.count <= 300 * 1024 else { throw UserDataSyncFailure.localStorage }
        let value = try JSONDecoder().decode(AccountUserDataSnapshot.self, from: data)
        try value.document.validate()
        return value
    }

    func save(_ value: AccountUserDataSnapshot, uid: String) throws {
        try value.document.validate()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        var directory = root
        var resources = URLResourceValues()
        resources.isExcludedFromBackup = true
        try directory.setResourceValues(resources)
        let url = file(uid)
        try JSONEncoder().encode(value).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}

@MainActor
final class MemoryAccountUserDataCache: AccountUserDataCaching {
    var values: [String: AccountUserDataSnapshot] = [:]
    func load(_ uid: String) throws -> AccountUserDataSnapshot { values[uid] ?? AccountUserDataSnapshot() }
    func save(_ value: AccountUserDataSnapshot, uid: String) throws { values[uid] = value }
}
