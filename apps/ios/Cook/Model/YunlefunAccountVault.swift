import Foundation
import Security

@MainActor
protocol YunlefunAccountVault {
    func load() throws -> YunlefunCredential?
    func save(_ credential: YunlefunCredential) throws
    func clear() throws
}

struct KeychainYunlefunAccountVault: YunlefunAccountVault {
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "fun.yunle.cook.account",
         kSecAttrAccount as String: "yunlefun",
         kSecAttrSynchronizable as String: false]
    }

    func load() throws -> YunlefunCredential? {
        var query = query
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw YunlefunAccountError.secureStorage }
        guard let credential = try? JSONDecoder().decode(YunlefunCredential.self, from: data),
              !credential.accessToken.isEmpty, !credential.refreshToken.isEmpty,
              !credential.subject.isEmpty, !credential.deviceID.isEmpty else {
            throw YunlefunAccountError.expiredSession
        }
        return credential
    }

    func save(_ credential: YunlefunCredential) throws {
        let attributes: [String: Any] = [
            kSecValueData as String: try JSONEncoder().encode(credential),
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(query.merging(attributes) { _, value in value } as CFDictionary, nil) == errSecSuccess else {
                throw YunlefunAccountError.secureStorage
            }
        } else if status != errSecSuccess { throw YunlefunAccountError.secureStorage }
    }

    func clear() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw YunlefunAccountError.secureStorage }
    }
}

/// 测试时不触碰真机钥匙串或网络。
@MainActor
final class MemoryYunlefunAccountVault: YunlefunAccountVault {
    var credential: YunlefunCredential?
    func load() throws -> YunlefunCredential? { credential }
    func save(_ credential: YunlefunCredential) throws { self.credential = credential }
    func clear() throws { credential = nil }
}
