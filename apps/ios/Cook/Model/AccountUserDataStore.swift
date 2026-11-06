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

    @ObservationIgnored private let cache: AccountUserDataCaching
    @ObservationIgnored private let service: UserDataSyncServing
    @ObservationIgnored private let credential: () async throws -> YunlefunCredential
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()
    @ObservationIgnored private var pending = false
    @ObservationIgnored private var retry = 0

    init(cache: AccountUserDataCaching, service: UserDataSyncServing? = nil,
         credential: @escaping () async throws -> YunlefunCredential) {
        self.cache = cache; self.service = service ?? UserDataSyncService(); self.credential = credential
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
                let auth = try await credential()
                guard current == generation, auth.subject == uid else { return }
                try await service.checkAccess(credential: auth)
                guard current == generation, ownerID == uid else { return }
            }
            var next = snapshot
            next.enabled = enabled
            try persist(next)
            invalidate()
            message = nil
            if enabled { requestSync() }
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
        value.histories.append(.init(recipe: .init(recipe: recipe), time: max(now, latest + 1, value.historyClearedAt + 1)))
        do { try update(value) }
        catch { message = UserDataSyncFailure.localStorage.localizedDescription }
    }

    func clearHistory(now: Double = Date().timeIntervalSince1970 * 1000) {
        var value = snapshot.document
        value.historyClearedAt = max(now, value.historyClearedAt, value.histories.map(\.time).max() ?? 0)
        do { try update(value) }
        catch { message = UserDataSyncFailure.localStorage.localizedDescription }
    }

    func importDocument(_ document: UserDataDocument) throws {
        try document.validate()
        try update(snapshot.document.merging(document))
    }

    func visitIDs(recipeIdByName: [String: Int]) -> [Int] {
        uniqueRecipeIds(snapshot.document.histories.reversed().compactMap { recipeIdByName[$0.recipe.name] })
    }

    func exportText() -> String {
        guard let data = try? JSONEncoder().encode(snapshot.document),
              var object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        object["app"] = "cook"
        object["exportedAt"] = Date().timeIntervalSince1970 * 1000
        guard let result = try? JSONSerialization.data(withJSONObject: object, options: .sortedKeys) else { return "" }
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

    private func sync(_ current: UUID) async {
        guard let uid = ownerID, usesAccountLibrary else { return }
        isSyncing = true
        pending = false
        var retryDelay: Double?
        do {
            let auth = try await credential()
            guard generation == current else { return }
            guard auth.subject == uid else { throw UserDataSyncFailure.loginRequired }
            let accepted = try await service.sync(snapshot.document, credential: auth)
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
