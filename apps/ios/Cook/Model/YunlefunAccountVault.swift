import Foundation
import Security

@MainActor
protocol YunlefunAccountVault {
    func load() throws -> YunlefunCredential?
    func save(_ credential: YunlefunCredential) throws
    func clear() throws
    func loadApprovedWebsite() throws -> YunlefunAccountStore.ApprovedWebsite?
    func saveApprovedWebsite(_ website: YunlefunAccountStore.ApprovedWebsite?) throws
}

struct KeychainYunlefunAccountVault: YunlefunAccountVault {
    private enum Slot: String {
        case credential = "yunlefun"
        case approvedWebsite = "approved-account-website"
    }

    private func query(for slot: Slot) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "fun.yunle.cook.account",
         kSecAttrAccount as String: slot.rawValue,
         kSecAttrSynchronizable as String: false]
    }

    func load() throws -> YunlefunCredential? {
        guard let data = try loadData(from: .credential) else { return nil }
        guard let credential = try? JSONDecoder().decode(YunlefunCredential.self, from: data),
              !credential.accessToken.isEmpty, !credential.refreshToken.isEmpty,
              !credential.subject.isEmpty, !credential.deviceID.isEmpty else {
            throw YunlefunAccountError.expiredSession
        }
        return credential
    }

    func save(_ credential: YunlefunCredential) throws {
        try saveData(JSONEncoder().encode(credential), to: .credential)
    }

    func clear() throws { try clear(.credential) }

    func loadApprovedWebsite() throws -> YunlefunAccountStore.ApprovedWebsite? {
        guard let data = try loadData(from: .approvedWebsite) else { return nil }
        return try JSONDecoder().decode(YunlefunAccountStore.ApprovedWebsite.self, from: data)
    }

    func saveApprovedWebsite(_ website: YunlefunAccountStore.ApprovedWebsite?) throws {
        if let website {
            try saveData(JSONEncoder().encode(website), to: .approvedWebsite)
        } else {
            try clear(.approvedWebsite)
        }
    }

    private func loadData(from slot: Slot) throws -> Data? {
        var query = query(for: slot)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw YunlefunAccountError.secureStorage }
        return data
    }

    private func saveData(_ data: Data, to slot: Slot) throws {
        let query = query(for: slot)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(query.merging(attributes) { _, value in value } as CFDictionary, nil) == errSecSuccess else {
                throw YunlefunAccountError.secureStorage
            }
        } else if status != errSecSuccess { throw YunlefunAccountError.secureStorage }
    }

    private func clear(_ slot: Slot) throws {
        let status = SecItemDelete(query(for: slot) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw YunlefunAccountError.secureStorage }
    }
}

/// 测试时不触碰真机钥匙串或网络。
@MainActor
final class MemoryYunlefunAccountVault: YunlefunAccountVault {
    var credential: YunlefunCredential?
    var approvedWebsite: YunlefunAccountStore.ApprovedWebsite?
    func load() throws -> YunlefunCredential? { credential }
    func save(_ credential: YunlefunCredential) throws { self.credential = credential }
    func clear() throws { credential = nil }
    func loadApprovedWebsite() throws -> YunlefunAccountStore.ApprovedWebsite? { approvedWebsite }
    func saveApprovedWebsite(_ website: YunlefunAccountStore.ApprovedWebsite?) throws { approvedWebsite = website }
}
