import CookCore
import Foundation
import XCTest
@testable import Cook

@MainActor
final class AccountUserDataTests: XCTestCase {
    private func auth(_ uid: String) -> YunlefunCredential {
        YunlefunCredential(accessToken: "public-test-token", refreshToken: "unused", subject: uid,
                           expiresAt: Date().addingTimeInterval(3600), deviceID: "fixture")
    }

    func testOfflineAccountChangesSurviveRelaunchAndDoNotEnterAnotherAccount() async throws {
        let cache = MemoryAccountUserDataCache()
        var identity = "a"
        let store = AccountUserDataStore(cache: cache, service: FakeUserDataService(), credential: { self.auth(identity) })
        store.activate("a")
        await store.setEnabled(true)
        try store.setFavorite(101, enabled: true)
        store.activate("b")
        identity = "b"
        XCTAssertFalse(store.isEnabled)
        XCTAssertTrue(store.favoriteIDs.isEmpty)
        await store.setEnabled(true)
        try store.setFavorite(202, enabled: true)
        store.activate(nil)
        XCTAssertFalse(store.usesAccountLibrary)
        identity = "a"
        let relaunched = AccountUserDataStore(cache: cache, service: FakeUserDataService(), credential: { self.auth(identity) })
        relaunched.activate("a")
        XCTAssertEqual(relaunched.favoriteIDs, [101])
        relaunched.activate("b")
        XCTAssertEqual(relaunched.favoriteIDs, [202])
        store.activate(nil)
        relaunched.activate(nil)
    }

    func testLateReplyCannotChangeTheSelectedAccount() async throws {
        let cache = MemoryAccountUserDataCache()
        cache.values["a"] = AccountUserDataSnapshot(enabled: true, document: UserDataDocument())
        let service = FakeUserDataService()
        service.pause = true
        let started = expectation(description: "sync started")
        service.started = { started.fulfill() }
        let store = AccountUserDataStore(cache: cache, service: service, credential: { self.auth("a") })
        store.activate("a")
        await fulfillment(of: [started], timeout: 2)
        store.activate("b")
        var remote = UserDataDocument()
        remote.favorites = [.init(id: 999, time: Date().timeIntervalSince1970 * 1000)]
        service.finish?(remote)
        await Task.yield()
        await Task.yield()
        XCTAssertEqual(store.ownerID, "b")
        XCTAssertTrue(store.favoriteIDs.isEmpty)
        XCTAssertTrue(cache.values["b"] == nil)
    }

    func testDiskFailurePreservesThePreviousFavoriteState() async throws {
        let cache = FailingUserDataCache()
        let store = AccountUserDataStore(cache: cache, service: FakeUserDataService(), credential: { self.auth("a") })
        store.activate("a")
        await store.setEnabled(true)
        cache.failSave = true
        XCTAssertThrowsError(try store.setFavorite(1, enabled: true))
        XCTAssertTrue(store.favoriteIDs.isEmpty)
        XCTAssertTrue(cache.value.document.favorites.isEmpty)
        store.activate(nil)
    }

    func testCorruptCacheDoesNotGetReplacedOrEnabled() async {
        let cache = FailingUserDataCache()
        cache.failLoad = true
        let service = FakeUserDataService()
        let store = AccountUserDataStore(cache: cache, service: service, credential: { self.auth("a") })
        store.activate("a")
        await store.setEnabled(true)
        XCTAssertTrue(store.cacheFailed)
        XCTAssertFalse(store.isEnabled)
        XCTAssertEqual(service.calls, 0)
        XCTAssertEqual(cache.saves, 0)
    }

    func testMemberFailureDoesNotEnableOrEraseTheLibrary() async {
        let cache = MemoryAccountUserDataCache()
        let service = FakeUserDataService()
        service.accessError = UserDataSyncFailure.membershipRequired
        let store = AccountUserDataStore(cache: cache, service: service, credential: { self.auth("a") })
        store.activate("a")
        await store.setEnabled(true)
        XCTAssertFalse(store.isEnabled)
        XCTAssertTrue(store.message?.contains("会员") == true)
        XCTAssertTrue(cache.values.isEmpty)
    }

    func testColdStartKeepsTheVerifiedOwnerAvailableWhenTokenRefreshIsOffline() async throws {
        let vault = MemoryYunlefunAccountVault()
        let previous = auth("a")
        vault.credential = YunlefunCredential(accessToken: previous.accessToken, refreshToken: previous.refreshToken,
                                             subject: previous.subject, expiresAt: .distantPast, deviceID: previous.deviceID)
        let accounts = YunlefunAccountStore(service: OfflineAccountService(), vault: vault)
        await accounts.restore()
        XCTAssertNil(accounts.account)
        XCTAssertEqual(accounts.dataAccountID, "a")
        XCTAssertTrue(accounts.canRetry)
        await accounts.signOut()
        XCTAssertNil(accounts.dataAccountID)
    }

    func testStillValidOfflineSessionRecoversThroughForegroundAndManualRequests() async throws {
        let vault = MemoryYunlefunAccountVault()
        vault.credential = auth("a")
        let service = RecoveringAccountService()
        let accounts = YunlefunAccountStore(service: service, vault: vault)
        await accounts.restore()
        XCTAssertNil(accounts.account)
        do {
            _ = try await accounts.credentialForRequest()
            XCTFail("Offline profile must not be treated as verified")
        } catch YunlefunAccountError.unavailable {} // Retryable network failure.
        service.offline = false
        let restored = try await accounts.credentialForRequest()
        XCTAssertEqual(restored.subject, "a")
        XCTAssertEqual(accounts.account?.id, "a")
        XCTAssertEqual(service.refreshes, 0, "A valid token only needs profile recovery")

        service.offline = true
        await accounts.retry()
        XCTAssertNil(accounts.account)
        service.offline = false
        await accounts.refreshIfNeeded()
        XCTAssertEqual(accounts.account?.id, "a")
    }

    func testSyncAutomaticallyRecoversAStillValidSessionAfterNetworkReturns() async throws {
        let vault = MemoryYunlefunAccountVault()
        vault.credential = auth("a")
        let accountService = RecoveringAccountService()
        let accounts = YunlefunAccountStore(service: accountService, vault: vault)
        await accounts.restore()
        let cache = MemoryAccountUserDataCache()
        cache.values["a"] = AccountUserDataSnapshot(enabled: true, document: UserDataDocument())
        let syncService = FakeUserDataService()
        syncService.succeeds = true
        let restored = expectation(description: "sync resumed without another login")
        syncService.started = { restored.fulfill() }
        let offline = expectation(description: "profile recovery failed offline")
        accountService.onOffline = { offline.fulfill() }
        let store = AccountUserDataStore(cache: cache, service: syncService, credential: { try await accounts.credentialForRequest() })
        store.activate("a")
        await fulfillment(of: [offline], timeout: 2)
        accountService.offline = false
        accountService.onOffline = nil
        await fulfillment(of: [restored], timeout: 5)
        XCTAssertEqual(accounts.account?.id, "a")
        XCTAssertEqual(syncService.calls, 1)
        store.activate(nil)
    }

    func testFileCacheRoundTripAndCorruptionProtection() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = AccountUserDataCache(root: root)
        var document = UserDataDocument()
        document.favorites = [.init(id: 12, time: 1000)]
        try cache.save(AccountUserDataSnapshot(enabled: true, document: document), uid: "a")
        XCTAssertEqual(try cache.load("a").document, document)
        XCTAssertFalse(try cache.load("b").enabled)
        let file = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil).first)
        try Data("broken".utf8).write(to: file)
        XCTAssertThrowsError(try cache.load("a"))
        XCTAssertEqual(try String(contentsOf: file), "broken")
    }

    func testTransportUsesFixedEndpointAndHeaderOnlyCredentials() async throws {
        let credential = auth("a")
        let service = UserDataSyncService { request in
            XCTAssertEqual(request.url, UserDataSyncService.endpoint)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer public-test-token")
            let body = try XCTUnwrap(request.httpBody)
            XCTAssertFalse(String(decoding: body, as: UTF8.self).contains("public-test-token"))
            let document = try JSONEncoder().encode(UserDataDocument())
            let response = Data("{\"ok\":true,\"protocolVersion\":2,\"document\":\(String(decoding: document, as: UTF8.self))}".utf8)
            return (response, HTTPURLResponse(url: UserDataSyncService.endpoint, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let result = try await service.sync(UserDataDocument(), credential: credential)
        XCTAssertEqual(result, UserDataDocument())
    }
}

@MainActor
private struct OfflineAccountService: YunlefunAccountServing {
    func signIn(fields: [String: String], deviceID: String) async throws -> YunlefunCredential { throw URLError(.notConnectedToInternet) }
    func refresh(_ credential: YunlefunCredential) async throws -> YunlefunCredential { throw URLError(.notConnectedToInternet) }
    func account(_ credential: YunlefunCredential) async throws -> YunlefunAccount { throw URLError(.notConnectedToInternet) }
    func signOut(_ credential: YunlefunCredential) async throws {}
}

@MainActor
private final class RecoveringAccountService: YunlefunAccountServing {
    var offline = true
    var refreshes = 0
    var onOffline: (() -> Void)?
    func signIn(fields: [String: String], deviceID: String) async throws -> YunlefunCredential { throw URLError(.unsupportedURL) }
    func refresh(_ credential: YunlefunCredential) async throws -> YunlefunCredential { refreshes += 1; return credential }
    func account(_ credential: YunlefunCredential) async throws -> YunlefunAccount {
        if offline { onOffline?(); throw URLError(.notConnectedToInternet) }
        return YunlefunAccount(id: credential.subject, name: "Fixture", username: nil, status: "ACTIVE", isAnonymous: false)
    }
    func signOut(_ credential: YunlefunCredential) async throws {}
}

@MainActor
private final class FakeUserDataService: UserDataSyncServing {
    var accessError: Error?
    var calls = 0
    var pause = false
    var succeeds = false
    var started: (() -> Void)?
    var finish: ((UserDataDocument) -> Void)?
    func checkAccess(credential: YunlefunCredential) async throws { if let accessError { throw accessError } }
    func sync(_ document: UserDataDocument, credential: YunlefunCredential) async throws -> UserDataDocument {
        calls += 1
        if succeeds { started?(); return document }
        if pause {
            return await withCheckedContinuation { continuation in
                finish = { continuation.resume(returning: $0) }
                started?()
            }
        }
        throw URLError(.notConnectedToInternet)
    }
}

@MainActor
private final class FailingUserDataCache: AccountUserDataCaching {
    var value = AccountUserDataSnapshot()
    var failLoad = false
    var failSave = false
    var saves = 0
    func load(_ uid: String) throws -> AccountUserDataSnapshot {
        if failLoad { throw CocoaError(.fileReadCorruptFile) }
        return value
    }
    func save(_ value: AccountUserDataSnapshot, uid: String) throws {
        if failSave { throw CocoaError(.fileWriteOutOfSpace) }
        saves += 1
        self.value = value
    }
}
