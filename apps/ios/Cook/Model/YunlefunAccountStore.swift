import Foundation
import Observation

/// 云乐坊身份与 SwiftData / CloudKit 完全独立，不以登录、退出或切换账号迁移做菜数据。
@MainActor @Observable
final class YunlefunAccountStore {
    private(set) var account: YunlefunAccount?
    private(set) var isBusy = false
    private(set) var message: String?
    private(set) var canRetry = false
    @ObservationIgnored private let service: YunlefunAccountServing
    @ObservationIgnored private let vault: YunlefunAccountVault
    @ObservationIgnored private let authorization: YunlefunAuthorizing
    @ObservationIgnored private var credential: YunlefunCredential?
    @ObservationIgnored private var didRestore = false

    init(service: YunlefunAccountServing? = nil,
         vault: YunlefunAccountVault? = nil,
         authorization: YunlefunAuthorizing? = nil) {
        self.service = service ?? YunlefunAccountService()
        self.vault = vault ?? KeychainYunlefunAccountVault()
        self.authorization = authorization ?? SystemYunlefunAuthorization()
    }

    func restore() async {
        guard !didRestore else { return }
        didRestore = true
        await refreshSession()
    }

    func refreshIfNeeded() async {
        guard didRestore, let credential, credential.expiresAt.timeIntervalSinceNow < 60 else { return }
        await refreshSession()
    }

    func signIn() async {
        guard !isBusy else { return }
        isBusy = true
        message = nil
        let previousCanRetry = canRetry
        canRetry = false
        defer { isBusy = false }
        do {
            let transaction = try YunlefunSSO()
            let callback = try await authorization.authorize(transaction)
            try Task.checkCancellation()
            let fields = try transaction.exchangeFields(callback: callback)
            let next = try await service.signIn(fields: fields, deviceID: UUID().uuidString)
            let user = try await service.account(next)
            try Task.checkCancellation()
            try vault.save(next)
            credential = next
            account = user
        } catch is CancellationError {
            // 用户取消授权后回到原状态。
            canRetry = previousCanRetry
        } catch {
            canRetry = previousCanRetry
            message = errorMessage(error)
        }
    }

    func retry() async { await refreshSession() }

    func signOut() async {
        guard !isBusy else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            let previous = try vault.load()
            try vault.clear()
            credential = nil
            account = nil
            message = nil
            canRetry = false
            if let previous {
                do { try await service.signOut(previous) }
                catch { message = "已退出本机账号，服务器会话未能注销，将按有效期失效。" }
            }
        } catch {
            message = errorMessage(error)
        }
    }

    private func refreshSession() async {
        guard !isBusy else { return }
        isBusy = true
        message = nil
        canRetry = false
        defer { isBusy = false }
        do {
            guard var current = try vault.load() else { return }
            if current.expiresAt.timeIntervalSinceNow < 60 {
                current = try await service.refresh(current)
                // 刷新凭据会轮换：取资料前先原子保存，断网后仍可重试。
                try vault.save(current)
            }
            credential = current
            let user = try await service.account(current)
            try Task.checkCancellation()
            account = user
        } catch is CancellationError {
            canRetry = true
        } catch YunlefunAccountError.expiredSession {
            account = nil
            credential = nil
            do {
                try vault.clear()
                message = YunlefunAccountError.expiredSession.localizedDescription
            } catch { message = errorMessage(error) }
        } catch {
            // 网络失败保留钥匙串中的会话，以便恢复网络后重试。
            account = nil
            canRetry = true
            message = errorMessage(error)
        }
    }

    private func errorMessage(_ error: Error) -> String {
        (error as? YunlefunAccountError)?.localizedDescription ?? "暂时无法连接云乐坊，请检查网络后重试。"
    }
}
