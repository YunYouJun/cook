import CookCore
import Foundation
import XCTest
@testable import Cook

@MainActor
private final class ManagedAIAccountServiceStub: YunlefunAccountServing {
    var refreshes = 0
    func signIn(fields: [String: String], deviceID: String) async throws -> YunlefunCredential { throw YunlefunAccountError.unavailable }
    func refresh(_ credential: YunlefunCredential) async throws -> YunlefunCredential {
        refreshes += 1
        return YunlefunCredential(accessToken: "rotated-test-token", refreshToken: "rotated-test-refresh", subject: credential.subject,
                                 expiresAt: Date().addingTimeInterval(3_600), deviceID: credential.deviceID)
    }
    func account(_ credential: YunlefunCredential) async throws -> YunlefunAccount {
        YunlefunAccount(id: credential.subject, name: "测试账号", username: nil, status: "ACTIVE", isAnonymous: false)
    }
    func signOut(_ credential: YunlefunCredential) async throws {}
}

private actor ManagedAISettingsServiceStub: ManagedCookAIServing {
    nonisolated let isConfigured: Bool
    let capability: ManagedCookAICapability
    let holdCapabilities: Bool
    let holdParse: Bool
    let parseIntent: SearchIntent?
    private(set) var capabilityCalls = 0
    private(set) var parseCalls = 0
    private var capabilityContinuation: CheckedContinuation<ManagedCookAICapability, Never>?
    private var parseContinuation: CheckedContinuation<SearchIntent, Error>?

    init(configured: Bool = true, enabled: Bool = true, active: Bool = true, expiration: Date? = Date().addingTimeInterval(3_600),
         holdCapabilities: Bool = false, holdParse: Bool = false, parseIntent: SearchIntent? = nil) {
        isConfigured = configured
        capability = ManagedCookAICapability(enabled: enabled, membershipIsActive: active, membershipExpiresAt: expiration)
        self.holdCapabilities = holdCapabilities
        self.holdParse = holdParse
        self.parseIntent = parseIntent
    }

    func capabilities(credential: YunlefunCredential) async throws -> ManagedCookAICapability {
        capabilityCalls += 1
        if holdCapabilities { return await withCheckedContinuation { capabilityContinuation = $0 } }
        return capability
    }

    func parse(text: String, credential: YunlefunCredential, data: CookData,
               availableGoals: [DietaryGoal]) async throws -> SearchIntent {
        parseCalls += 1
        if holdParse { return try await withCheckedThrowingContinuation { parseContinuation = $0 } }
        guard let parseIntent else { throw ManagedCookAIError.unavailable }
        return parseIntent
    }

    func completeCapabilities() { capabilityContinuation?.resume(returning: capability); capabilityContinuation = nil }
    func completeParse() {
        if let parseIntent { parseContinuation?.resume(returning: parseIntent) }
        else { parseContinuation?.resume(throwing: ManagedCookAIError.unavailable) }
        parseContinuation = nil
    }
}

@MainActor
final class ManagedCookAISettingsTests: XCTestCase {
    private func credential(_ subject: String = "member-test-user", expiration: Date = Date().addingTimeInterval(3_600)) -> YunlefunCredential {
        YunlefunCredential(accessToken: "test-access", refreshToken: "test-refresh", subject: subject,
                           expiresAt: expiration, deviceID: "test-installation")
    }

    private func account() async -> (YunlefunAccountStore, MemoryYunlefunAccountVault, ManagedAIAccountServiceStub) {
        let vault = MemoryYunlefunAccountVault()
        vault.credential = credential()
        let service = ManagedAIAccountServiceStub()
        let account = YunlefunAccountStore(service: service, vault: vault)
        await account.restore()
        return (account, vault, service)
    }

    func testLoginAndConfigurationDoNotImplyMembershipOrSendCapabilitiesWithoutIdentity() async {
        let signedOut = YunlefunAccountStore(vault: MemoryYunlefunAccountVault())
        let service = ManagedAISettingsServiceStub()
        let settings = CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: service)
        await settings.refreshManagedCapability(accounts: signedOut)
        XCTAssertEqual(settings.managedStatus, .loginRequired)
        XCTAssertFalse(settings.selectProvider(.managedDeepSeek))
        let calls = await service.capabilityCalls
        XCTAssertEqual(calls, 0)
        let (accounts, _, _) = await account()
        let unconfigured = ManagedAISettingsServiceStub(configured: false)
        let absent = CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: unconfigured)
        await absent.refreshManagedCapability(accounts: accounts)
        XCTAssertEqual(absent.managedStatus, .unconfigured)
        XCTAssertFalse(absent.isManagedAvailable)
        let absentCalls = await unconfigured.capabilityCalls
        XCTAssertEqual(absentCalls, 0)
    }

    func testNonmemberExpiredAndServiceUnavailableHaveDistinctRecoverableStates() async {
        let (accounts, _, _) = await account()
        let cases: [(ManagedAISettingsServiceStub, ManagedCookAIStatus)] = [
            (ManagedAISettingsServiceStub(enabled: false, active: false, expiration: nil), .membershipRequired),
            (ManagedAISettingsServiceStub(enabled: false, active: false, expiration: Date().addingTimeInterval(-60)), .expired),
            (ManagedAISettingsServiceStub(enabled: false), .failed(.unavailable)),
        ]
        for (service, expected) in cases {
            let settings = CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: service)
            await settings.refreshManagedCapability(accounts: accounts)
            XCTAssertEqual(settings.managedStatus, expected)
            XCTAssertFalse(settings.selectProvider(.managedDeepSeek))
            XCTAssertTrue(settings.saveAPIKey("test-own-key"))
            XCTAssertTrue(settings.selectProvider(.ownDeepSeek))
            XCTAssertTrue(settings.canUseOwnDeepSeek)
        }
    }

    func testSavedManagedPreferenceOnlyRestoresAfterFreshMemberCapabilityAndExpiresWithinTTL() async {
        let name = "cook.ai.managed.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set(CookAIProvider.managedDeepSeek.rawValue, forKey: CookAISettings.providerKey)
        let (accounts, _, _) = await account()
        var time = Date()
        let expiration = time.addingTimeInterval(30)
        let service = ManagedAISettingsServiceStub(expiration: expiration)
        let settings = CookAISettings(defaults: defaults, vault: MemoryCookAIKeyVault(), managedService: service, now: { time })
        XCTAssertEqual(settings.provider, .local)
        XCTAssertFalse(settings.canUseRemote)
        await settings.refreshManagedCapability(accounts: accounts)
        XCTAssertTrue(settings.canUseManagedDeepSeek)
        XCTAssertEqual(settings.managedValidUntil, expiration)
        time = expiration.addingTimeInterval(1)
        XCTAssertFalse(settings.canUseManagedDeepSeek)
        settings.expireManagedCapabilityIfNeeded()
        XCTAssertEqual(settings.provider, .managedDeepSeek)
        XCTAssertNil(settings.managedValidUntil)
        XCTAssertEqual(settings.managedStatus, .needsRefresh)
        XCTAssertFalse(settings.selectProvider(.managedDeepSeek))
    }

    func testTTLExpiryAcceptsAdmittedResultButRejectsNewRequestsUntilRechecked() async throws {
        let (accounts, _, _) = await account()
        let data = try RecipeStore.loadBundled(inMemory: true).data
        var intent = LocalSearchIntentParser(data: data, availableGoals: []).parse("鸡蛋")
        intent.source = .deepseek
        var time = Date()
        let service = ManagedAISettingsServiceStub(expiration: time.addingTimeInterval(3_600), holdParse: true, parseIntent: intent)
        let settings = CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: service, now: { time })
        await settings.refreshManagedCapability(accounts: accounts)
        XCTAssertTrue(settings.selectProvider(.managedDeepSeek))
        let parse = Task { try await settings.parseManaged(text: "鸡蛋", data: data, availableGoals: [], accounts: accounts) }
        for _ in 0..<1_000 { if await service.parseCalls > 0 { break }; await Task.yield() }
        time = time.addingTimeInterval(61)
        settings.expireManagedCapabilityIfNeeded()
        XCTAssertEqual(settings.provider, .managedDeepSeek)
        XCTAssertEqual(settings.managedStatus, .needsRefresh)
        XCTAssertFalse(settings.canUseManagedDeepSeek)
        do {
            _ = try await settings.parseManaged(text: "土豆", data: data, availableGoals: [], accounts: accounts)
            XCTFail("Expired capability admitted a new request")
        } catch { XCTAssertEqual(error as? ManagedCookAIError, .membershipRequired) }
        let calls = await service.parseCalls
        XCTAssertEqual(calls, 1)
        await service.completeParse()
        let result = try await parse.value
        XCTAssertEqual(result, intent)
    }

    func testTTLExpiryDoesNotPreserveLateResultsAfterLogoutProviderChangeOrManualInvalidation() async throws {
        let data = try RecipeStore.loadBundled(inMemory: true).data
        var intent = LocalSearchIntentParser(data: data, availableGoals: []).parse("鸡蛋")
        intent.source = .deepseek
        for invalidation in ["logout", "provider", "manual"] {
            let (accounts, _, _) = await account()
            var time = Date()
            let service = ManagedAISettingsServiceStub(expiration: time.addingTimeInterval(3_600), holdParse: true, parseIntent: intent)
            let settings = CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: service, now: { time })
            await settings.refreshManagedCapability(accounts: accounts)
            XCTAssertTrue(settings.selectProvider(.managedDeepSeek))
            let parse = Task { try await settings.parseManaged(text: "鸡蛋", data: data, availableGoals: [], accounts: accounts) }
            for _ in 0..<1_000 { if await service.parseCalls > 0 { break }; await Task.yield() }
            time = time.addingTimeInterval(61)
            settings.expireManagedCapabilityIfNeeded()
            switch invalidation {
            case "logout": await accounts.signOut()
            case "provider": XCTAssertTrue(settings.selectProvider(.local))
            default: settings.invalidateManagedCapability(accounts: accounts)
            }
            await service.completeParse()
            do { _ = try await parse.value; XCTFail("Revoked request published after \(invalidation)") }
            catch { XCTAssertTrue(error is CancellationError, invalidation) }
        }
    }

    func testLogoutInvalidatesAvailablePermissionAndNeverDeletesPersonalKey() async {
        let (accounts, _, _) = await account()
        let service = ManagedAISettingsServiceStub()
        let settings = CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: service)
        XCTAssertTrue(settings.saveAPIKey("test-personal-key"))
        await settings.refreshManagedCapability(accounts: accounts)
        XCTAssertTrue(settings.selectProvider(.managedDeepSeek))
        let revision = accounts.sessionRevision
        await accounts.signOut()
        XCTAssertNotEqual(accounts.sessionRevision, revision)
        XCTAssertFalse(settings.isManagedAvailable)
        settings.invalidateManagedCapability(accounts: accounts)
        XCTAssertEqual(settings.provider, .local)
        XCTAssertEqual(settings.managedStatus, .loginRequired)
        XCTAssertTrue(settings.selectProvider(.ownDeepSeek))
        XCTAssertEqual(try? settings.apiKeyForRequest(), "test-personal-key")
    }

    func testLateCapabilityAfterLogoutCannotEnableManagedProvider() async {
        let (accounts, _, _) = await account()
        let service = ManagedAISettingsServiceStub(holdCapabilities: true)
        let settings = CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: service)
        let refresh = Task { await settings.refreshManagedCapability(accounts: accounts) }
        for _ in 0..<1_000 { if await service.capabilityCalls > 0 { break }; await Task.yield() }
        await accounts.signOut()
        await service.completeCapabilities()
        await refresh.value
        XCTAssertFalse(settings.isManagedAvailable)
        XCTAssertFalse(settings.selectProvider(.managedDeepSeek))
        XCTAssertEqual(settings.managedStatus, .loginRequired)
    }

    func testLateParseAfterAccountSwitchCannotPublishAndServerStillReceivesEveryParse() async throws {
        let (accounts, vault, _) = await account()
        let data = try RecipeStore.loadBundled(inMemory: true).data
        var intent = LocalSearchIntentParser(data: data, availableGoals: []).parse("鸡蛋")
        intent.source = .deepseek
        let service = ManagedAISettingsServiceStub(holdParse: true, parseIntent: intent)
        let settings = CookAISettings(defaults: nil, vault: MemoryCookAIKeyVault(), managedService: service)
        await settings.refreshManagedCapability(accounts: accounts)
        XCTAssertTrue(settings.selectProvider(.managedDeepSeek))
        let parse = Task { try await settings.parseManaged(text: "鸡蛋", data: data, availableGoals: [], accounts: accounts) }
        for _ in 0..<1_000 { if await service.parseCalls > 0 { break }; await Task.yield() }
        await accounts.signOut()
        vault.credential = credential("another-test-user")
        await accounts.retry()
        await service.completeParse()
        do { _ = try await parse.value; XCTFail("Stale account response accepted") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertFalse(settings.isManagedAvailable)
        let calls = await service.parseCalls
        XCTAssertEqual(calls, 1)
    }

    func testAIRequestUsesRotatedAccountCredentialAndDoesNotTreatLoggedOutIdentityAsUsable() async throws {
        let (accounts, vault, service) = await account()
        vault.credential = credential(expiration: Date().addingTimeInterval(-60))
        await accounts.retry()
        XCTAssertEqual(service.refreshes, 1)
        let credential = try await accounts.credentialForAIRequest()
        XCTAssertEqual(credential.accessToken, "rotated-test-token")
        await accounts.signOut()
        do { _ = try await accounts.credentialForAIRequest(); XCTFail("Signed-out credential accepted") }
        catch { XCTAssertTrue(error is YunlefunAccountError) }
    }
}
