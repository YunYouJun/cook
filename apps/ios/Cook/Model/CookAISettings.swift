import CookCore
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

enum ManagedCookAIStatus: Equatable {
    case unconfigured, loginRequired, checking, ready, membershipRequired, expired, needsRefresh
    case failed(ManagedCookAIError)

    var message: String {
        switch self {
        case .unconfigured: return "此版本尚未配置会员 AI 服务。"
        case .loginRequired: return "登录云乐坊账号后，可检查会员 AI 使用资格。"
        case .checking: return "正在核验会员与服务状态…"
        case .ready: return "已核验有效会员，内置 DeepSeek 可用。"
        case .membershipRequired: return "当前账号不是有效云乐坊会员，可使用自己的 DeepSeek Key。"
        case .expired: return "云乐坊会员已到期，可使用自己的 DeepSeek Key。"
        case .needsRefresh: return "使用资格需要重新核验。"
        case let .failed(error): return error.localizedDescription
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
    private(set) var managedStatus: ManagedCookAIStatus = .unconfigured
    private(set) var managedValidUntil: Date?
    @ObservationIgnored private let defaults: UserDefaults?
    @ObservationIgnored private let vault: CookAIKeyVault
    @ObservationIgnored private let managedService: ManagedCookAIServing
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private var requestedProvider: CookAIProvider
    @ObservationIgnored private var managedSubject: String?
    @ObservationIgnored private weak var managedAccounts: YunlefunAccountStore?
    @ObservationIgnored private var managedRevision: UUID?
    @ObservationIgnored private var capabilityGeneration = UUID()
    @ObservationIgnored private var capabilityTask: Task<ManagedCookAICapability, Error>?
    @ObservationIgnored private var expiryTask: Task<Void, Never>?

    init(defaults: UserDefaults? = .standard, vault: CookAIKeyVault? = nil,
         managedService: ManagedCookAIServing = ManagedCookAIService(), now: @escaping () -> Date = Date.init) {
        self.defaults = defaults
        self.vault = vault ?? KeychainCookAIKeyVault()
        self.managedService = managedService
        self.now = now
        let saved = defaults?.string(forKey: Self.providerKey).flatMap(CookAIProvider.init(rawValue:)) ?? .local
        requestedProvider = saved
        managedStatus = managedService.isConfigured ? .loginRequired : .unconfigured
        do {
            hasAPIKey = try self.vault.load().map(Self.isValidKey) ?? false
            provider = saved == .ownDeepSeek && hasAPIKey ? .ownDeepSeek : .local
            if saved == .ownDeepSeek && !hasAPIKey { defaults?.set(provider.rawValue, forKey: Self.providerKey); requestedProvider = .local }
        } catch {
            message = CookAISettingsError.secureStorage.localizedDescription
        }
    }

    var canUseOwnDeepSeek: Bool { provider == .ownDeepSeek && hasAPIKey }
    var isManagedConfigured: Bool { managedService.isConfigured }
    var wantsManagedDeepSeek: Bool { requestedProvider == .managedDeepSeek }
    var isManagedAvailable: Bool {
        managedStatus == .ready && managedValidUntil.map { $0 > now() } == true
            && managedAccounts?.sessionRevision == managedRevision && managedAccounts?.account?.id == managedSubject
    }
    var canUseManagedDeepSeek: Bool { provider == .managedDeepSeek && isManagedAvailable }
    var canUseRemote: Bool { canUseOwnDeepSeek || canUseManagedDeepSeek }

    @discardableResult
    func selectProvider(_ next: CookAIProvider) -> Bool {
        guard next != .managedDeepSeek || isManagedAvailable else {
            message = managedStatus.message
            return false
        }
        guard next != .ownDeepSeek || hasAPIKey else {
            message = CookAISettingsError.missingKey.localizedDescription
            return false
        }
        if provider == .managedDeepSeek, next != provider { capabilityGeneration = UUID() }
        provider = next
        requestedProvider = next
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
                requestedProvider = .local
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

    /// A capability is short-lived and is discarded whenever the account lifecycle changes.
    func invalidateManagedCapability(accounts: YunlefunAccountStore) {
        clearManagedCapability(status: !managedService.isConfigured ? .unconfigured : (accounts.account == nil ? .loginRequired : .needsRefresh))
    }

    func refreshManagedCapability(accounts: YunlefunAccountStore) async {
        clearManagedCapability(status: .checking)
        guard managedService.isConfigured else { managedStatus = .unconfigured; return }
        guard let subject = accounts.account?.id else { managedStatus = .loginRequired; return }
        let revision = accounts.sessionRevision
        let generation = capabilityGeneration
        let service = managedService
        let task = Task {
            let credential = try await accounts.credentialForAIRequest()
            try Task.checkCancellation()
            guard accounts.sessionRevision == revision, credential.subject == subject else { throw CancellationError() }
            return try await service.capabilities(credential: credential)
        }
        capabilityTask = task
        do {
            let capability = try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
            try Task.checkCancellation()
            guard capabilityGeneration == generation else { return }
            guard accounts.sessionRevision == revision, accounts.account?.id == subject else {
                invalidateManagedCapability(accounts: accounts)
                return
            }
            capabilityTask = nil
            if let expiration = capability.membershipExpiresAt, expiration <= now() {
                managedStatus = .expired
                return
            }
            guard capability.membershipIsActive else { managedStatus = .membershipRequired; return }
            guard capability.enabled, let expiration = capability.membershipExpiresAt else {
                managedStatus = .failed(.unavailable)
                return
            }
            managedSubject = subject
            managedAccounts = accounts
            managedRevision = revision
            managedValidUntil = min(now().addingTimeInterval(60), expiration)
            managedStatus = .ready
            if requestedProvider == .managedDeepSeek { provider = .managedDeepSeek }
            let delay = max(0, managedValidUntil!.timeIntervalSince(now()))
            expiryTask = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(delay)) } catch { return }
                self?.expireManagedCapabilityIfNeeded()
            }
        } catch {
            guard capabilityGeneration == generation else { return }
            capabilityTask = nil
            if error is CancellationError { managedStatus = .needsRefresh }
            else if let accountError = error as? YunlefunAccountError, case .expiredSession = accountError { managedStatus = .loginRequired }
            else { managedStatus = .failed((error as? ManagedCookAIError) ?? .unavailable) }
        }
    }

    func expireManagedCapabilityIfNeeded() {
        if let managedValidUntil, managedValidUntil <= now() {
            // Expiry closes admission for new requests without revoking an already admitted call.
            self.managedValidUntil = nil
            managedStatus = .needsRefresh
            expiryTask?.cancel()
            expiryTask = nil
        }
    }

    func parseManaged(text: String, data: CookData, availableGoals: [DietaryGoal],
                      accounts: YunlefunAccountStore) async throws -> SearchIntent {
        expireManagedCapabilityIfNeeded()
        guard canUseManagedDeepSeek, let revision = managedRevision, let subject = managedSubject,
              accounts.sessionRevision == revision, accounts.account?.id == subject else {
            throw ManagedCookAIError.membershipRequired
        }
        let generation = capabilityGeneration
        func requestIsCurrent() -> Bool {
            capabilityGeneration == generation && provider == .managedDeepSeek
                && accounts.sessionRevision == revision && accounts.account?.id == subject
        }
        do {
            let credential = try await accounts.credentialForAIRequest()
            guard requestIsCurrent(), credential.subject == subject else { throw CancellationError() }
            let result = try await managedService.parse(text: text, credential: credential, data: data, availableGoals: availableGoals)
            try Task.checkCancellation()
            guard requestIsCurrent() else { throw CancellationError() }
            return result
        } catch let error as ManagedCookAIError {
            guard requestIsCurrent() else { throw CancellationError() }
            if error == .loginRequired { clearManagedCapability(status: .loginRequired) }
            else if error == .membershipRequired { clearManagedCapability(status: .membershipRequired) }
            else if error == .accountUnavailable || error == .unavailable { clearManagedCapability(status: .failed(error)) }
            throw error
        } catch let error as YunlefunAccountError {
            guard requestIsCurrent() else { throw CancellationError() }
            clearManagedCapability(status: .loginRequired)
            throw error
        }
    }

    private func clearManagedCapability(status: ManagedCookAIStatus) {
        capabilityGeneration = UUID()
        capabilityTask?.cancel()
        capabilityTask = nil
        expiryTask?.cancel()
        expiryTask = nil
        managedSubject = nil
        managedAccounts = nil
        managedRevision = nil
        managedValidUntil = nil
        managedStatus = status
        if provider == .managedDeepSeek { provider = .local }
    }

    private static func isValidKey(_ key: String) -> Bool {
        !key.isEmpty && key.utf8.count <= 512 && key.unicodeScalars.allSatisfy { (33...126).contains($0.value) }
    }
}
