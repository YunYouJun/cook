import CookCore
import Foundation
import Observation
import XCTest
@testable import Cook

@MainActor
final class AccountUserDataTests: XCTestCase {
    private func auth(_ uid: String, accessToken: String = "public-test-token", deviceID: String = "fixture") -> YunlefunCredential {
        YunlefunCredential(accessToken: accessToken, refreshToken: "unused", subject: uid,
                           expiresAt: Date().addingTimeInterval(3600), deviceID: deviceID)
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

    func testRemovingOneVisitPersistsAndSyncsOnlyThatVisit() async throws {
        let cache = MemoryAccountUserDataCache()
        let service = FakeUserDataService()
        let recipe = Recipe(id: 250, name: "番茄炒蛋", stuff: ["番茄", "鸡蛋"])
        let now = Date().timeIntervalSince1970 * 1000
        let earlier = UserDataDocument.Visit(recipe: .init(recipe: recipe), time: now - 10_000)
        let selected = UserDataDocument.Visit(recipe: .init(recipe: recipe), time: now + 10_000)
        var stalePeer = UserDataDocument()
        stalePeer.histories = [earlier, selected]
        stalePeer.favorites = [.init(id: recipe.id, time: earlier.time)]
        cache.values["a"] = .init(enabled: true, document: stalePeer)

        let store = AccountUserDataStore(cache: cache, service: service, credential: { self.auth("a") })
        store.activate("a")
        defer { store.activate(nil) }
        try store.removeVisit(selected, expectedRevision: store.libraryRevision)
        let removal = UserDataDocument.Removal(name: recipe.name, time: selected.time)
        XCTAssertEqual(cache.values["a"]?.document.histories, [earlier])
        XCTAssertEqual(cache.values["a"]?.document.historyRemovals, [removal])

        let received = expectation(description: "sync sent the exact history deletion")
        service.onSync = { document, _ in
            XCTAssertEqual(document.histories, [earlier])
            XCTAssertEqual(document.historyRemovals, [removal])
            XCTAssertEqual(document.favorites, stalePeer.favorites)
            received.fulfill()
            return document.merging(stalePeer)
        }
        store.requestSync()
        await fulfillment(of: [received], timeout: 2)
        XCTAssertEqual(store.snapshot.document.histories, [earlier])

        store.recordVisit(recipe, now: now)
        XCTAssertEqual(store.snapshot.document.histories.map(\.time), [earlier.time, selected.time + 1])
        XCTAssertEqual(store.snapshot.document.historyRemovals, [removal])
    }

    func testOldVisitRemovalCannotAffectTheAccountAfterSyncIsReenabled() async throws {
        let cache = MemoryAccountUserDataCache()
        let visit = UserDataDocument.Visit(recipe: .init(recipe: Recipe(id: 1, name: "鸡蛋", stuff: [])),
                                           time: Date().timeIntervalSince1970 * 1000)
        var document = UserDataDocument()
        document.histories = [visit]
        cache.values["a"] = .init(enabled: true, document: document)
        let store = AccountUserDataStore(cache: cache, service: FakeUserDataService(), credential: { self.auth("a") })
        store.activate("a")
        defer { store.activate(nil) }
        let old = store.libraryRevision
        await store.setEnabled(false)
        await store.setEnabled(true)
        XCTAssertThrowsError(try store.removeVisit(visit, expectedRevision: old)) { XCTAssertTrue($0 is CancellationError) }
        XCTAssertEqual(store.snapshot.document.histories, [visit])
        XCTAssertTrue(store.snapshot.document.historyRemovals.isEmpty)
    }

    func testClearHistorySyncPreservesAnUnseenVisitBeforeTheKnownFutureVisit() async throws {
        let now = Date().timeIntervalSince1970 * 1000
        let recipe = Recipe(id: 250, name: "番茄炒蛋", stuff: ["番茄", "鸡蛋"])
        let future = UserDataDocument.Visit(recipe: .init(recipe: recipe), time: now + 60_000)
        let concurrent = UserDataDocument.Visit(recipe: .init(recipe: recipe), time: now + 1_000)
        let removal = UserDataDocument.Removal(name: recipe.name, time: future.time)
        var local = UserDataDocument()
        local.histories = [future]
        var peer = UserDataDocument()
        peer.histories = [concurrent, future]
        let cache = MemoryAccountUserDataCache()
        cache.values["a"] = .init(enabled: true, document: local)
        let service = FakeUserDataService()
        let synced = expectation(description: "sync propagated the bounded clear")
        service.onSync = { document, _ in
            XCTAssertEqual(document.historyClearedAt, now)
            XCTAssertEqual(document.historyRemovals, [removal])
            XCTAssertTrue(document.histories.isEmpty)
            synced.fulfill()
            return document.merging(peer, now: now)
        }
        let store = AccountUserDataStore(cache: cache, service: service, credential: { self.auth("a") })
        defer { store.activate(nil) }
        store.activate("a")
        store.clearHistory(now: now)
        XCTAssertEqual(cache.values["a"]?.document.historyRemovals, [removal])
        store.requestSync()
        await fulfillment(of: [synced], timeout: 2)
        XCTAssertEqual(cache.values["a"]?.document.histories, [concurrent])
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

    func testVerifiedRenewalResumesSyncBeforeOrAfterTheOldDenial() async throws {
        for checkFirst in [false, true] {
            let cache = MemoryAccountUserDataCache()
            var local = UserDataDocument()
            local.favorites = [.init(id: 250, time: 2_000)]
            local.histories = [.init(recipe: .init(recipe: Recipe(id: 250, name: "番茄炒蛋", stuff: [])), time: 2_000)]
            var remote = UserDataDocument()
            remote.favorites = [.init(id: 588, time: 3_000)]
            remote.histories = [.init(recipe: .init(recipe: Recipe(id: 588, name: "蒸蛋羹", stuff: [])), time: 3_000)]
            cache.values["a"] = AccountUserDataSnapshot(enabled: true, document: local)
            let firstStarted = expectation(description: "original sync started")
            let resumed = expectation(description: "sync resumed for the same owner")
            let service = FakeUserDataService()
            var finishFirst: CheckedContinuation<Void, Never>?
            var uploads: [UserDataDocument] = []
            service.onSync = { document, credential in
                XCTAssertEqual(credential.subject, "a")
                uploads.append(document)
                if uploads.count == 1 {
                    await withCheckedContinuation { continuation in
                        finishFirst = continuation
                        firstStarted.fulfill()
                    }
                    throw UserDataSyncFailure.membershipRequired
                }
                resumed.fulfill()
                return document.merging(remote)
            }
            let store = AccountUserDataStore(cache: cache, service: service, credential: { self.auth("a") })
            defer { finishFirst?.resume(); store.activate(nil) }
            store.activate("a")
            await fulfillment(of: [firstStarted], timeout: 2)
            let finish = try XCTUnwrap(finishFirst)
            finishFirst = nil
            let renewed = MembershipEntitlement(userID: "a", expiresAt: .now.addingTimeInterval(3600))
            if checkFirst {
                store.membershipDidCheck(renewed)
                finish.resume()
            } else {
                let ended = expectation(description: "original sync stopped at the membership gate")
                withObservationTracking { _ = store.isSyncing } onChange: { ended.fulfill() }
                finish.resume()
                await fulfillment(of: [ended], timeout: 2)
                XCTAssertEqual(store.message, UserDataSyncFailure.membershipRequired.localizedDescription)
                XCTAssertEqual(cache.values["a"]?.document, local)
                store.membershipDidCheck(renewed)
            }

            await fulfillment(of: [resumed], timeout: 2)
            XCTAssertEqual(uploads, [local, local])
            XCTAssertEqual(cache.values["a"]?.document, local.merging(remote))
        }
    }

    func testMembershipCheckRespectsTheOwnerAndRevokedSyncConsent() async {
        let cache = MemoryAccountUserDataCache()
        var local = UserDataDocument()
        local.favorites = [.init(id: 250, time: 2_000)]
        cache.values["a"] = AccountUserDataSnapshot(enabled: true, document: local)
        let service = FakeUserDataService()
        let rejected = expectation(description: "original sync was rejected")
        let unexpected = expectation(description: "an ineligible membership check caused another upload")
        unexpected.isInverted = true
        var uploads = 0
        service.onSync = { _, _ in
            uploads += 1
            if uploads == 1 { rejected.fulfill() }
            else { unexpected.fulfill() }
            throw UserDataSyncFailure.membershipRequired
        }
        let store = AccountUserDataStore(cache: cache, service: service, credential: { self.auth("a") })
        defer { store.activate(nil) }
        store.activate("a")
        await fulfillment(of: [rejected], timeout: 2)
        XCTAssertEqual(store.message, UserDataSyncFailure.membershipRequired.localizedDescription)
        store.membershipDidCheck(.init(userID: "b", expiresAt: .now.addingTimeInterval(3600)))
        store.membershipDidCheck(.init(userID: "a", expiresAt: nil))
        await fulfillment(of: [unexpected], timeout: 0.25)

        let afterDisable = expectation(description: "sync resumed after consent was revoked")
        afterDisable.isInverted = true
        service.onSync = { _, _ in
            afterDisable.fulfill()
            throw UserDataSyncFailure.membershipRequired
        }
        await store.setEnabled(false)
        store.membershipDidCheck(.init(userID: "a", expiresAt: .now.addingTimeInterval(3600)))
        await fulfillment(of: [afterDisable], timeout: 0.25)
        XCTAssertFalse(store.usesAccountLibrary)
        XCTAssertEqual(cache.values["a"]?.document, local)
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

    func testSyncRenewsRejectedTokenAndRechecksAccessBeforeUploadingTheLatestChanges() async throws {
        let initial = auth("a")
        let renewed = auth("a", accessToken: "refreshed")
        let vault = MemoryYunlefunAccountVault()
        vault.credential = initial
        let accountService = RecoveringAccountService()
        accountService.offline = false
        let accounts = YunlefunAccountStore(service: accountService, vault: vault)
        await accounts.restore()

        let refreshing = expectation(description: "forced token refresh began")
        var renewal: CheckedContinuation<Void, Never>?
        accountService.onRefresh = { credential in
            XCTAssertEqual(credential, initial)
            await withCheckedContinuation { continuation in
                renewal = continuation
                refreshing.fulfill()
            }
            return renewed
        }
        let cache = MemoryAccountUserDataCache()
        cache.values["a"] = AccountUserDataSnapshot(enabled: true, document: UserDataDocument())
        let service = FakeUserDataService()
        let retried = expectation(description: "latest document retried with renewed token")
        var events: [String] = []
        service.onCheckAccess = { credential in
            XCTAssertEqual(credential, renewed)
            events.append("capabilities")
        }
        service.onSync = { document, credential in
            if credential == initial {
                XCTAssertTrue(document.favorites.isEmpty)
                events.append("rejected")
                throw UserDataSyncFailure.accessTokenRejected
            }
            XCTAssertEqual(credential, renewed)
            XCTAssertEqual(document.favorites.map(\.id), [101])
            if events.last == "capabilities" { retried.fulfill() }
            events.append("uploaded")
            return document
        }
        let store = AccountUserDataStore(cache: cache, service: service,
                                         credential: { try await accounts.credentialForRequest() },
                                         renewCredential: { try await accounts.credentialForRequest(rejecting: $0) })
        defer { renewal?.resume(); store.activate(nil) }
        store.activate("a")
        await fulfillment(of: [refreshing], timeout: 2)
        try store.setFavorite(101, enabled: true)
        let resume = try XCTUnwrap(renewal)
        renewal = nil
        resume.resume()
        await fulfillment(of: [retried], timeout: 3)
        XCTAssertEqual(Array(events.prefix(3)), ["rejected", "capabilities", "uploaded"])
        XCTAssertEqual(accountService.refreshes, 1)
        XCTAssertEqual(vault.credential, renewed)
        XCTAssertEqual(cache.values["a"]?.document.favorites.map(\.id), [101])
    }

    func testConcurrentRenewalRechecksAccessAndUploadsOfflineDeletionsWithTheLatestToken() async throws {
        for firstCheck in [nil, UserDataSyncFailure.accessTokenRejected] {
            let initial = auth("a")
            let renewed = auth("a", accessToken: "refreshed")
            let latest = auth("a", accessToken: "latest")
            let vault = MemoryYunlefunAccountVault()
            vault.credential = initial
            let accountService = RecoveringAccountService()
            accountService.offline = false
            accountService.onRefresh = { $0 == initial ? renewed : latest }
            let accounts = YunlefunAccountStore(service: accountService, vault: vault)
            await accounts.restore()

            let now = Date().timeIntervalSince1970 * 1000
            let visit = UserDataDocument.Visit(recipe: .init(recipe: Recipe(id: 101, name: "番茄炒蛋", stuff: [])), time: now - 1_000)
            var remote = UserDataDocument()
            remote.favorites = [.init(id: 101, time: visit.time)]
            remote.histories = [visit]
            let cache = MemoryAccountUserDataCache()
            cache.values["a"] = .init(enabled: true, document: remote)

            let checking = expectation(description: "first access check began")
            let uploaded = expectation(description: "latest credential uploaded the offline deletions")
            var finishFirstCheck: CheckedContinuation<Void, Never>?
            var checkedTokens: [String] = []
            var uploads: [UserDataDocument] = []
            let service = FakeUserDataService()
            service.onCheckAccess = { credential in
                checkedTokens.append(credential.accessToken)
                if credential == renewed {
                    await withCheckedContinuation { continuation in
                        finishFirstCheck = continuation
                        checking.fulfill()
                    }
                    if let firstCheck { throw firstCheck }
                } else {
                    XCTAssertEqual(credential, latest)
                }
            }
            service.onSync = { document, credential in
                uploads.append(document)
                if credential == initial { throw UserDataSyncFailure.accessTokenRejected }
                XCTAssertEqual(credential, latest)
                if uploads.count == 2 { uploaded.fulfill() }
                return document.merging(remote)
            }
            let store = AccountUserDataStore(cache: cache, service: service,
                                             credential: { try await accounts.credentialForRequest() },
                                             renewCredential: { try await accounts.credentialForRequest(rejecting: $0) })
            defer {
                finishFirstCheck?.resume()
                store.activate(nil)
            }
            store.activate("a")
            await fulfillment(of: [checking], timeout: 2)
            let active = try await accounts.credentialForRequest(rejecting: renewed)
            XCTAssertEqual(active, latest)
            try store.setFavorite(101, enabled: false, now: now)
            try store.removeVisit(visit, expectedRevision: store.libraryRevision)
            let edited = store.snapshot.document
            let resume = try XCTUnwrap(finishFirstCheck)
            finishFirstCheck = nil
            resume.resume()

            await fulfillment(of: [uploaded], timeout: 3)
            XCTAssertEqual(checkedTokens, [renewed.accessToken, latest.accessToken])
            XCTAssertEqual(Array(uploads.prefix(2)), [remote, edited])
            XCTAssertTrue(edited.favorites.isEmpty)
            XCTAssertTrue(edited.histories.isEmpty)
            XCTAssertEqual(edited.favoriteRemovals, [.init(id: 101, time: now)])
            XCTAssertEqual(edited.historyRemovals, [.init(name: visit.recipe.name, time: visit.time)])
            XCTAssertEqual(store.snapshot.document, edited)
            XCTAssertEqual(cache.values["a"]?.document, edited)
            XCTAssertEqual(vault.credential, latest)
            XCTAssertEqual(accountService.refreshes, 2)
        }
    }

    func testLatestCredentialMustHaveAccessBeforeSyncCanResendTheDocument() async {
        let initial = auth("a")
        let renewed = auth("a", accessToken: "refreshed")
        let latest = auth("a", accessToken: "latest")
        var active = initial
        let cache = MemoryAccountUserDataCache()
        var local = UserDataDocument()
        local.favorites = [.init(id: 101, time: 1_000)]
        cache.values["a"] = .init(enabled: true, document: local)
        let service = FakeUserDataService()
        service.onSync = { _, credential in
            XCTAssertEqual(credential, initial)
            throw UserDataSyncFailure.accessTokenRejected
        }
        var checked: [YunlefunCredential] = []
        service.onCheckAccess = { credential in
            checked.append(credential)
            if credential == latest { throw UserDataSyncFailure.membershipRequired }
            active = latest
        }
        let store = AccountUserDataStore(cache: cache, service: service, credential: { active }, renewCredential: { _ in
            active = renewed
            return renewed
        })
        defer { store.activate(nil) }
        store.activate("a")
        let denied = expectation(description: "latest access denial was shown")
        withObservationTracking { _ = store.message } onChange: { denied.fulfill() }
        await fulfillment(of: [denied], timeout: 2)
        XCTAssertEqual(store.message, UserDataSyncFailure.membershipRequired.localizedDescription)
        XCTAssertEqual(checked, [renewed, latest])
        XCTAssertEqual(service.calls, 1)
        XCTAssertEqual(cache.values["a"]?.document, local)
        XCTAssertNil(store.lastSyncedAt)
    }

    func testDisablingSyncDuringAccessRecheckPreventsRetryAndKeepsTheLocalRecord() async throws {
        let initial = auth("a")
        let renewed = auth("a", accessToken: "refreshed")
        var document = UserDataDocument()
        document.favorites = [.init(id: 101, time: 1000)]
        let cache = MemoryAccountUserDataCache()
        cache.values["a"] = AccountUserDataSnapshot(enabled: true, document: document)
        let service = FakeUserDataService()
        service.onSync = { _, credential in
            XCTAssertEqual(credential, initial)
            throw UserDataSyncFailure.accessTokenRejected
        }
        let checking = expectation(description: "membership check began")
        let checked = expectation(description: "membership check returned")
        var access: CheckedContinuation<Void, Never>?
        service.onCheckAccess = { credential in
            XCTAssertEqual(credential, renewed)
            await withCheckedContinuation { continuation in
                access = continuation
                checking.fulfill()
            }
            checked.fulfill()
        }
        let store = AccountUserDataStore(cache: cache, service: service, credential: { initial }, renewCredential: { _ in renewed })
        defer { access?.resume(); store.activate(nil) }
        store.activate("a")
        await fulfillment(of: [checking], timeout: 2)
        await store.setEnabled(false)
        let resume = try XCTUnwrap(access)
        access = nil
        resume.resume()
        await fulfillment(of: [checked], timeout: 2)
        await Task.yield()
        XCTAssertFalse(store.usesAccountLibrary)
        XCTAssertFalse(store.isSyncing)
        XCTAssertEqual(service.calls, 1)
        XCTAssertEqual(cache.values["a"]?.document, document)
    }

    func testForcedRenewalKeepsTheRejectedTokenMarkedWhenOffline() async throws {
        let initial = auth("a")
        let renewed = auth("a", accessToken: "refreshed")
        let vault = MemoryYunlefunAccountVault()
        vault.credential = initial
        let service = RecoveringAccountService()
        service.offline = false
        service.onRefresh = { _ in throw URLError(.notConnectedToInternet) }
        let accounts = YunlefunAccountStore(service: service, vault: vault)
        await accounts.restore()

        do {
            _ = try await accounts.credentialForRequest(rejecting: auth("a", deviceID: "previous-session"))
            XCTFail("A rejected token from another login must not refresh the active session")
        } catch YunlefunAccountError.expiredSession {}
        XCTAssertEqual(service.refreshes, 0)
        do {
            _ = try await accounts.credentialForRequest(rejecting: initial)
            XCTFail("An offline refresh must report that the token is unavailable")
        } catch YunlefunAccountError.unavailable {}
        XCTAssertEqual(vault.credential, initial)
        XCTAssertEqual(accounts.dataAccountID, "a")

        service.onRefresh = { _ in renewed }
        let recovered = try await accounts.credentialForRequest()
        XCTAssertEqual(recovered, renewed)
        XCTAssertEqual(service.refreshes, 2)
        XCTAssertEqual(vault.credential, renewed)
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

    func testTransportDistinguishesRejectedTokensFromSessionChangesAndServiceOutages() async throws {
        let credential = auth("a")
        let responses: [(Int, String, UserDataSyncFailure)] = [
            (401, "", .accessTokenRejected),
            (403, #"{"code":"LOGIN_REQUIRED"}"#, .accessTokenRejected),
            (401, #"{"code":"SESSION_CHANGED"}"#, .loginRequired),
            (403, #"{"code":"MEMBERSHIP_REQUIRED"}"#, .membershipRequired),
            (503, "", .unavailable)
        ]
        for (status, body, expected) in responses {
            let service = UserDataSyncService { request in
                (Data(body.utf8), HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
            }
            do {
                try await service.checkAccess(credential: credential)
                XCTFail("Expected sync failure for HTTP \(status)")
            } catch let failure as UserDataSyncFailure {
                XCTAssertEqual(failure, expected)
            }
        }
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
    var onRefresh: ((YunlefunCredential) async throws -> YunlefunCredential)?
    func signIn(fields: [String: String], deviceID: String) async throws -> YunlefunCredential { throw URLError(.unsupportedURL) }
    func refresh(_ credential: YunlefunCredential) async throws -> YunlefunCredential {
        refreshes += 1
        if let onRefresh { return try await onRefresh(credential) }
        return credential
    }
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
    var onCheckAccess: ((YunlefunCredential) async throws -> Void)?
    var onSync: ((UserDataDocument, YunlefunCredential) async throws -> UserDataDocument)?
    func checkAccess(credential: YunlefunCredential) async throws {
        if let accessError { throw accessError }
        try await onCheckAccess?(credential)
    }
    func sync(_ document: UserDataDocument, credential: YunlefunCredential) async throws -> UserDataDocument {
        calls += 1
        if let onSync { return try await onSync(document, credential) }
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
