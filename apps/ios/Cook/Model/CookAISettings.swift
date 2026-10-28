import Foundation
import Observation
import Security

/// Model use is opt-in; ordinary sentence search remains local.
enum CookAIProvider: String, CaseIterable, Identifiable {
    case local
    case ownDeepSeek = "own-deepseek"
    case managedDeepSeek = "managed-deepseek"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .local: return "仅本地识别"
        case .ownDeepSeek: return "使用自己的 DeepSeek Key"
        case .managedDeepSeek: return "云乐坊会员内置 DeepSeek"
        }
    }
}

enum CookAISettingsError: Error, LocalizedError, Equatable {
    case invalidKey, secureStorage, missingKey, notEnabled, managedUnavailable

    var errorDescription: String? {
        switch self {
        case .invalidKey: return "请输入有效的 API Key，不要包含空格或换行。"
        case .secureStorage: return "暂时无法读写本机钥匙串，请解锁设备后重试。"
        case .missingKey: return "请先在设置中保存自己的 DeepSeek API Key。"
        case .notEnabled: return "当前使用本地识别，请先在设置中选择自己的 DeepSeek Key。"
        case .managedUnavailable: return "会员内置服务尚未接通，暂时无法使用。"
        }
    }
}

@MainActor
protocol CookAIKeyVault {
    func load() throws -> String?
    func save(_ key: String) throws
    func clear() throws
}

struct KeychainCookAIKeyVault: CookAIKeyVault {
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "fun.yunle.cook.ai.deepseek",
         kSecAttrAccount as String: "personal-api-key",
         kSecAttrSynchronizable as String: false]
    }

    func load() throws -> String? {
        var query = query
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data,
              let key = String(data: data, encoding: .utf8) else { throw CookAISettingsError.secureStorage }
        return key
    }

    func save(_ key: String) throws {
        let attributes: [String: Any] = [
            kSecValueData as String: Data(key.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(query.merging(attributes) { _, value in value } as CFDictionary, nil) == errSecSuccess else {
                throw CookAISettingsError.secureStorage
            }
        } else if status != errSecSuccess { throw CookAISettingsError.secureStorage }
    }

    func clear() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw CookAISettingsError.secureStorage }
    }
}

/// Test and preview storage never accesses the device keychain.
@MainActor
final class MemoryCookAIKeyVault: CookAIKeyVault {
    private var key: String?
    func load() throws -> String? { key }
    func save(_ key: String) throws { self.key = key }
    func clear() throws { key = nil }
}

/// Only the provider preference is persisted in defaults; secrets stay in the vault.
@MainActor @Observable
final class CookAISettings {
    static let providerKey = "cook.ai.provider"
    private(set) var provider: CookAIProvider = .local
    private(set) var hasAPIKey = false
    private(set) var message: String?
    @ObservationIgnored private let defaults: UserDefaults?
    @ObservationIgnored private let vault: CookAIKeyVault

    init(defaults: UserDefaults? = .standard, vault: CookAIKeyVault? = nil) {
        self.defaults = defaults
        self.vault = vault ?? KeychainCookAIKeyVault()
        let saved = defaults?.string(forKey: Self.providerKey).flatMap(CookAIProvider.init(rawValue:)) ?? .local
        do {
            hasAPIKey = try self.vault.load().map(Self.isValidKey) ?? false
            provider = saved == .ownDeepSeek && hasAPIKey ? .ownDeepSeek : .local
            if saved != provider { defaults?.set(provider.rawValue, forKey: Self.providerKey) }
        } catch {
            message = CookAISettingsError.secureStorage.localizedDescription
        }
    }

    var canUseOwnDeepSeek: Bool { provider == .ownDeepSeek && hasAPIKey }

    @discardableResult
    func selectProvider(_ next: CookAIProvider) -> Bool {
        guard next != .managedDeepSeek else {
            message = CookAISettingsError.managedUnavailable.localizedDescription
            return false
        }
        guard next != .ownDeepSeek || hasAPIKey else {
            message = CookAISettingsError.missingKey.localizedDescription
            return false
        }
        provider = next
        defaults?.set(next.rawValue, forKey: Self.providerKey)
        message = nil
        return true
    }

    @discardableResult
    func saveAPIKey(_ value: String) -> Bool {
        let key = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isValidKey(key) else {
            message = CookAISettingsError.invalidKey.localizedDescription
            return false
        }
        do {
            try vault.save(key)
            hasAPIKey = true
            message = "API Key 已保存在本机钥匙串。"
            return true
        } catch {
            message = CookAISettingsError.secureStorage.localizedDescription
            return false
        }
    }

    @discardableResult
    func deleteAPIKey() -> Bool {
        do {
            try vault.clear()
            hasAPIKey = false
            if provider == .ownDeepSeek {
                provider = .local
                defaults?.set(provider.rawValue, forKey: Self.providerKey)
            }
            message = "已删除本机 API Key。"
            return true
        } catch {
            message = CookAISettingsError.secureStorage.localizedDescription
            return false
        }
    }

    /// Read the key only when preparing an explicitly requested model call.
    func apiKeyForRequest() throws -> String {
        guard provider == .ownDeepSeek else { throw CookAISettingsError.notEnabled }
        do {
            guard let key = try vault.load(), Self.isValidKey(key) else {
                hasAPIKey = false
                throw CookAISettingsError.missingKey
            }
            return key
        } catch let error as CookAISettingsError {
            throw error
        } catch {
            throw CookAISettingsError.secureStorage
        }
    }

    private static func isValidKey(_ key: String) -> Bool {
        !key.isEmpty && key.utf8.count <= 512 && key.unicodeScalars.allSatisfy { (33...126).contains($0.value) }
    }
}
