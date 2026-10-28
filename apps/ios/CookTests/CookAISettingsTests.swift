import Foundation
import XCTest
@testable import Cook

@MainActor
private final class AIKeyVaultStub: CookAIKeyVault {
    var key: String?
    var failLoad = false
    var failSave = false
    var failClear = false
    var reads = 0

    func load() throws -> String? {
        reads += 1
        if failLoad { throw CocoaError(.fileReadNoPermission) }
        return key
    }

    func save(_ key: String) throws {
        if failSave { throw CocoaError(.fileWriteNoPermission) }
        self.key = key
    }

    func clear() throws {
        if failClear { throw CocoaError(.fileWriteNoPermission) }
        key = nil
    }
}

@MainActor
final class CookAISettingsTests: XCTestCase {
    private let testKey = "sk-test-only-never-a-real-key"

    private func withDefaults(_ action: (UserDefaults) throws -> Void) rethrows {
        let name = "cook.ai.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        try action(defaults)
    }

    func testOrdinaryUsersCanConfigureOwnKeyWithoutAccountAndDefaultIsLocal() throws {
        try withDefaults { defaults in
            let vault = AIKeyVaultStub()
            let settings = CookAISettings(defaults: defaults, vault: vault)
            XCTAssertEqual(settings.provider, .local)
            XCTAssertFalse(settings.canUseOwnDeepSeek)
            XCTAssertFalse(settings.selectProvider(.ownDeepSeek))
            XCTAssertTrue(settings.saveAPIKey("  \(testKey)\n"))
            XCTAssertEqual(vault.key, testKey)
            XCTAssertEqual(settings.provider, .local)
            XCTAssertTrue(settings.selectProvider(.ownDeepSeek))
            XCTAssertTrue(settings.canUseOwnDeepSeek)
            XCTAssertEqual(try settings.apiKeyForRequest(), testKey)
            XCTAssertEqual(defaults.string(forKey: CookAISettings.providerKey), CookAIProvider.ownDeepSeek.rawValue)
            XCTAssertFalse(defaults.dictionaryRepresentation().values.contains { String(describing: $0).contains(testKey) })
            let restarted = CookAISettings(defaults: defaults, vault: vault)
            XCTAssertTrue(restarted.canUseOwnDeepSeek)
        }
    }

    func testManagedProviderNeverUnlocksThroughLocalPreferenceOrKey() throws {
        try withDefaults { defaults in
            defaults.set(CookAIProvider.managedDeepSeek.rawValue, forKey: CookAISettings.providerKey)
            let vault = AIKeyVaultStub()
            vault.key = testKey
            let settings = CookAISettings(defaults: defaults, vault: vault)
            XCTAssertEqual(settings.provider, .local)
            XCTAssertFalse(settings.selectProvider(.managedDeepSeek))
            XCTAssertEqual(settings.provider, .local)
            XCTAssertThrowsError(try settings.apiKeyForRequest())
            XCTAssertNotNil(settings.message)
            XCTAssertTrue(settings.selectProvider(.ownDeepSeek))
            XCTAssertFalse(settings.selectProvider(.managedDeepSeek))
            XCTAssertEqual(settings.provider, .ownDeepSeek)
        }
    }

    func testDeleteFallsBackToLocalAndMissingKeyCannotBeUsedAfterRestart() {
        withDefaults { defaults in
            let vault = AIKeyVaultStub()
            let settings = CookAISettings(defaults: defaults, vault: vault)
            XCTAssertTrue(settings.saveAPIKey(testKey))
            XCTAssertTrue(settings.selectProvider(.ownDeepSeek))
            XCTAssertTrue(settings.deleteAPIKey())
            XCTAssertNil(vault.key)
            XCTAssertFalse(settings.hasAPIKey)
            XCTAssertEqual(settings.provider, .local)
            XCTAssertFalse(CookAISettings(defaults: defaults, vault: vault).canUseOwnDeepSeek)
            defaults.set(CookAIProvider.ownDeepSeek.rawValue, forKey: CookAISettings.providerKey)
            XCTAssertEqual(CookAISettings(defaults: defaults, vault: vault).provider, .local)
        }
    }

    func testKeychainWriteFailuresDoNotPretendToSaveOrDelete() throws {
        try withDefaults { defaults in
            let vault = AIKeyVaultStub()
            vault.failSave = true
            let settings = CookAISettings(defaults: defaults, vault: vault)
            XCTAssertFalse(settings.saveAPIKey(testKey))
            XCTAssertFalse(settings.hasAPIKey)
            XCTAssertNil(vault.key)
            vault.failSave = false
            XCTAssertTrue(settings.saveAPIKey(testKey))
            XCTAssertTrue(settings.selectProvider(.ownDeepSeek))
            vault.failClear = true
            XCTAssertFalse(settings.deleteAPIKey())
            XCTAssertTrue(settings.hasAPIKey)
            XCTAssertEqual(settings.provider, .ownDeepSeek)
            XCTAssertEqual(try settings.apiKeyForRequest(), testKey)
            XCTAssertFalse(settings.message?.contains(testKey) ?? true)
        }
    }

    func testRequestReadsCurrentVaultAndFailsClosedOnMissingOrUnreadableSecret() throws {
        try withDefaults { defaults in
            let vault = AIKeyVaultStub()
            let settings = CookAISettings(defaults: defaults, vault: vault)
            XCTAssertTrue(settings.saveAPIKey(testKey))
            XCTAssertTrue(settings.selectProvider(.ownDeepSeek))
            let priorReads = vault.reads
            XCTAssertEqual(try settings.apiKeyForRequest(), testKey)
            XCTAssertEqual(vault.reads, priorReads + 1)
            vault.failLoad = true
            XCTAssertThrowsError(try settings.apiKeyForRequest()) {
                XCTAssertEqual(($0 as? CookAISettingsError), .secureStorage)
            }
            vault.failLoad = false
            vault.key = nil
            XCTAssertThrowsError(try settings.apiKeyForRequest()) {
                XCTAssertEqual(($0 as? CookAISettingsError), .missingKey)
            }
            XCTAssertFalse(settings.canUseOwnDeepSeek)
        }
    }

    func testInvalidKeysAreRejectedAndReadFailureDoesNotExposeSecrets() {
        withDefaults { defaults in
            let vault = AIKeyVaultStub()
            let settings = CookAISettings(defaults: defaults, vault: vault)
            for key in ["", "   ", "has space", "has\nnewline", "含中文", String(repeating: "a", count: 513)] {
                XCTAssertFalse(settings.saveAPIKey(key))
                XCTAssertNil(vault.key)
            }
            vault.failLoad = true
            defaults.set(CookAIProvider.ownDeepSeek.rawValue, forKey: CookAISettings.providerKey)
            let unreadable = CookAISettings(defaults: defaults, vault: vault)
            XCTAssertEqual(unreadable.provider, .local)
            XCTAssertFalse(unreadable.hasAPIKey)
            XCTAssertNotNil(unreadable.message)
        }
    }
}
