import CookCore
import Foundation
import Observation
import SwiftUI

private struct AccountUserDataKey: EnvironmentKey {
    static let defaultValue: AccountUserDataStore? = nil
}

extension EnvironmentValues {
    var accountUserData: AccountUserDataStore? {
        get { self[AccountUserDataKey.self] }
        set { self[AccountUserDataKey.self] = newValue }
    }
}

/// Owns the active account library, durable offline changes and serial retries.
@MainActor @Observable
final class AccountUserDataStore {
    private(set) var ownerID: String?
    private(set) var snapshot = AccountUserDataSnapshot()
    private(set) var isSyncing = false
    private(set) var message: String?
    private(set) var lastSyncedAt: Date?
    private(set) var cacheFailed = false
    var isEnabled: Bool { snapshot.enabled }
    var usesAccountLibrary: Bool { ownerID != nil && snapshot.enabled }
    var favoriteIDs: [Int] { snapshot.document.favorites.reversed().map(\.id) }
    var libraryRevision: UUID { generation }

    @ObservationIgnored private let cache: AccountUserDataCaching
    @ObservationIgnored private let service: UserDataSyncServing
    @ObservationIgnored private let credential: () async throws -> YunlefunCredential
    @ObservationIgnored private let renewCredential: (YunlefunCredential) async throws -> YunlefunCredential
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()
    @ObservationIgnored private var pending = false
    @ObservationIgnored private var retry = 0
    @ObservationIgnored private var membershipCheckRevision = UUID()
    @ObservationIgnored private var checkedMembershipExpiry: Date?
    @ObservationIgnored private var waitingForMembership = false

    init(cache: AccountUserDataCaching, service: UserDataSyncServing? = nil,
         credential: @escaping () async throws -> YunlefunCredential,
         renewCredential: @escaping (YunlefunCredential) async throws -> YunlefunCredential = { _ in throw UserDataSyncFailure.loginRequired }) {
        self.cache = cache
        self.service = service ?? UserDataSyncService()
        self.credential = credential
        self.renewCredential = renewCredential
    }

    func activate(_ uid: String?) {
        guard uid != ownerID else { return }
        invalidate()
        ownerID = uid
        snapshot = AccountUserDataSnapshot()
        cacheFailed = false
        message = nil
        lastSyncedAt = nil
        guard let uid else { return }
        do {
            snapshot = try cache.load(uid)
            if snapshot.enabled { requestSync() }
        } catch {
            cacheFailed = true
            message = "账号缓存无法读取，已停止同步，原文件已保留。"
        }
    }

    func setEnabled(_ enabled: Bool) async {
        guard let uid = ownerID, !cacheFailed else { return }
        let current = generation
        do {
            if enabled {
                try await withSyncCredential(uid: uid, generation: current, request: service.checkAccess)
                try Task.checkCancellation()
                guard current == generation, ownerID == uid else { return }
            }
            var next = snapshot
            next.enabled = enabled
            try persist(next)
            invalidate()
            message = nil
            if enabled { requestSync() }
        } catch is CancellationError {
            return
        } catch {
            guard generation == current else { return }
            message = error.localizedDescription
        }
    }

    func setFavorite(_ id: Int, enabled: Bool, now: Double = Date().timeIntervalSince1970 * 1000) throws {
        var value = snapshot.document
        let latest = (value.favorites + value.favoriteRemovals).filter { $0.id == id }.map(\.time).max() ?? 0
        let time = max(now, latest + 1)
        value.favorites.removeAll { $0.id == id }
        value.favoriteRemovals.removeAll { $0.id == id }
        if enabled { value.favorites.append(.init(id: id, time: time)) }
        else { value.favoriteRemovals.append(.init(id: id, time: time)) }
        try update(value)
    }

    func recordVisit(_ recipe: Recipe, now: Double = Date().timeIntervalSince1970 * 1000) {
        var value = snapshot.document
        let latest = value.histories.filter { $0.recipe.name == recipe.name }.map(\.time).max() ?? 0
        let lastRemoval = value.historyRemovals.filter { $0.name == recipe.name }.map(\.time).max() ?? 0
        let time = max(now, latest + 1, lastRemoval + 1, value.historyClearedAt + 1)
        value.histories.append(.init(recipe: .init(recipe: recipe), time: time))
        do { try update(value) }
        catch { message = UserDataSyncFailure.localStorage.localizedDescription }
    }

    func removeVisit(_ visit: UserDataDocument.Visit, expectedRevision: UUID) throws {
        guard expectedRevision == generation, usesAccountLibrary else { throw CancellationError() }
        var value = snapshot.document
        let name = visit.recipe.name
        func matches(_ item: UserDataDocument.Visit) -> Bool { item.recipe.name == name && item.time == visit.time }
        guard value.histories.contains(where: matches) else { return }
        value.histories.removeAll(where: matches)
        value.historyRemovals.append(.init(name: name, time: visit.time))
        try update(value)
    }

    func clearHistory(now: Double = Date().timeIntervalSince1970 * 1000) {
        do { try update(snapshot.document.clearingHistory(now: now)) }
        catch { message = UserDataSyncFailure.localStorage.localizedDescription }
    }

    func importDocument(_ document: UserDataDocument) throws {
        try document.validate()
        try update(snapshot.document.merging(document))
    }

    func exportText() -> String {
        guard let data = try? JSONEncoder().encode(snapshot.document),
              var object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        object["app"] = "cook"
        object["exportedAt"] = WebUserData.milliseconds(Date())
        guard let result = try? JSONSerialization.data(
            withJSONObject: object,
            options: [.sortedKeys, .withoutEscapingSlashes],
        ) else { return "" }
        return String(decoding: result, as: UTF8.self)
    }

    private func update(_ document: UserDataDocument) throws {
        guard usesAccountLibrary, !cacheFailed else { throw UserDataSyncFailure.localStorage }
        var next = snapshot
        next.document = document.merging(UserDataDocument())
        try persist(next)
        requestSync(delay: 2)
    }

    private func persist(_ next: AccountUserDataSnapshot) throws {
        guard let uid = ownerID else { throw UserDataSyncFailure.localStorage }
        do { try cache.save(next, uid: uid) }
        catch { throw UserDataSyncFailure.localStorage }
        snapshot = next
    }

    private func invalidate() {
        generation = UUID()
        task?.cancel()
        task = nil
        isSyncing = false
        pending = false
        retry = 0
        checkedMembershipExpiry = nil
        waitingForMembership = false
    }

    func membershipDidCheck(_ entitlement: MembershipEntitlement) {
        guard usesAccountLibrary, !cacheFailed, entitlement.userID == ownerID else { return }
        membershipCheckRevision = UUID()
        checkedMembershipExpiry = entitlement.expiresAt
        guard waitingForMembership, entitlement.isActive(at: .now) else { return }
        waitingForMembership = false
        requestSync()
    }

    func requestSync(delay: Double = 0) {
        guard usesAccountLibrary, !cacheFailed else { return }
        if isSyncing { pending = true; return }
        task?.cancel()
        let current = generation
        task = Task { [weak self] in
            do { if delay > 0 { try await Task.sleep(for: .seconds(delay)) } }
            catch { return }
            guard let self, self.generation == current, !Task.isCancelled else { return }
            await self.sync(current)
        }
    }

    private func withSyncCredential<T>(uid: String, generation current: UUID, recheckAccess: Bool = false,
                                       request: (YunlefunCredential) async throws -> T) async throws -> T {
        func validate(_ auth: YunlefunCredential) throws {
            try Task.checkCancellation()
            guard generation == current, ownerID == uid else { throw CancellationError() }
            guard auth.subject == uid else { throw UserDataSyncFailure.loginRequired }
        }

        do {
            let auth = try await credential()
            try validate(auth)
            do { return try await request(auth) }
            catch UserDataSyncFailure.accessTokenRejected {
                try validate(auth)
                let renewed = try await renewCredential(auth)
                try validate(renewed)
                guard renewed.deviceID == auth.deviceID, renewed.accessToken != auth.accessToken else {
                    throw UserDataSyncFailure.loginRequired
                }
                let ready = recheckAccess ? try await confirmSyncAccess(renewed, validate: validate) : renewed
                return try await request(ready)
            }
        } catch YunlefunAccountError.expiredSession {
            throw UserDataSyncFailure.loginRequired
        }
    }

    // Confirm access without sending the document. Another request can rotate the token while we wait.
    private func confirmSyncAccess(_ renewed: YunlefunCredential, validate: (YunlefunCredential) throws -> Void)
        async throws -> YunlefunCredential {
        var candidate = renewed
        for _ in 0..<2 {
            let rejected: Bool
            do {
                try await service.checkAccess(credential: candidate)
                rejected = false
            } catch UserDataSyncFailure.accessTokenRejected {
                rejected = true
            }
            try validate(candidate)
            let active = try await credential()
            try validate(active)
            guard active.deviceID == renewed.deviceID else { throw UserDataSyncFailure.loginRequired }
            if active.accessToken == candidate.accessToken {
                if rejected { throw UserDataSyncFailure.accessTokenRejected }
                return active
            }
            candidate = active
        }
        throw UserDataSyncFailure.unavailable
    }

    private func sync(_ current: UUID) async {
        guard let uid = ownerID, usesAccountLibrary else { return }
        let membershipCheckAtStart = membershipCheckRevision
        isSyncing = true
        pending = false
        waitingForMembership = false
        var retryDelay: Double?
        do {
            let accepted = try await withSyncCredential(uid: uid, generation: current, recheckAccess: true) { auth in
                try await service.sync(snapshot.document, credential: auth)
            }
            try Task.checkCancellation()
            guard generation == current, ownerID == uid else { return }
            var next = snapshot
            next.document = snapshot.document.merging(accepted)
            try persist(next)
            // An edit during transport stays queued even after a successful round.
            pending = pending || next.document != accepted
            lastSyncedAt = Date()
            message = nil
            retry = 0
        } catch is CancellationError {
            // A changed generation owns all following state.
        } catch {
            guard generation == current else { return }
            message = (error as? UserDataSyncFailure)?.localizedDescription ?? UserDataSyncFailure.unavailable.localizedDescription
            switch error as? UserDataSyncFailure {
            case .loginRequired, .accessTokenRejected: pending = false
            case .membershipRequired:
                // A successful check may have arrived while this request was still in flight.
                pending = membershipCheckRevision != membershipCheckAtStart && checkedMembershipExpiry.map { $0 > .now } == true
                waitingForMembership = !pending
            default: break
            }
            if case UserDataSyncFailure.unavailable = error { retryDelay = min(30, pow(2, Double(retry))); retry += 1 }
            else if case YunlefunAccountError.unavailable = error { retryDelay = min(30, pow(2, Double(retry))); retry += 1 }
            else if error is URLError { retryDelay = min(30, pow(2, Double(retry))); retry += 1 }
        }
        guard generation == current else { return }
        isSyncing = false
        task = nil
        if pending { requestSync() }
        else if let retryDelay { requestSync(delay: retryDelay) }
    }
}
