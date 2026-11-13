import Foundation
import Observation

/// 云乐坊身份与 SwiftData / CloudKit 完全独立，不以登录、退出或切换账号迁移做菜数据。
@MainActor @Observable
final class YunlefunAccountStore {
    private(set) var account: YunlefunAccount?
    private(set) var isBusy = false
    private(set) var message: String?
    private(set) var canRetry = false
    private(set) var sessionRevision = UUID()
    private(set) var offlineSubject: String?
    private(set) var weChatBindingMessage: String?
    @ObservationIgnored private let service: YunlefunAccountServing
    @ObservationIgnored private let vault: YunlefunAccountVault
    @ObservationIgnored private let authorization: YunlefunAuthorizing
    @ObservationIgnored private let weChat: WeChatAuthorizing
    @ObservationIgnored private let appAuthorization: YunlefunAppAuthorization
    @ObservationIgnored private var credential: YunlefunCredential?
    @ObservationIgnored private var didRestore = false

    /// A previously verified session may keep its own offline library visible.
    /// Sign-out/invalid credentials clear this identity before any async work.
    var dataAccountID: String? { account?.id ?? offlineSubject }
    var canUseWeChat: Bool { weChat.isAvailable }
    var isAwaitingYunlefunApp: Bool { appAuthorization.isWaiting }

    init(service: YunlefunAccountServing? = nil,
         vault: YunlefunAccountVault? = nil,
         authorization: YunlefunAuthorizing? = nil,
         weChat: WeChatAuthorizing? = nil,
         appAuthorization: YunlefunAppAuthorization? = nil) {
        self.service = service ?? YunlefunAccountService()
        self.vault = vault ?? KeychainYunlefunAccountVault()
        self.authorization = authorization ?? SystemYunlefunAuthorization()
        self.weChat = weChat ?? WeChatAuthorization(enabled: CookFeatures.weChatLoginEnabled)
        // Tests that provide their own browser must never launch another app.
        self.appAuthorization = appAuthorization ?? (authorization == nil
            ? YunlefunAppAuthorization()
            : YunlefunAppAuthorization(openApp: { _ in false }))
    }

    func restore() async {
        guard !didRestore else { return }
        didRestore = true
        await refreshSession()
    }

    func refreshIfNeeded() async {
        guard didRestore, let credential,
              account == nil || canRetry || credential.expiresAt.timeIntervalSinceNow < 60 else { return }
        await refreshSession()
    }

    /// Never exposes the refresh token to a request body or assumes that signing in grants membership.
    func credentialForAIRequest() async throws -> YunlefunCredential {
        try await credentialForRequest()
    }

    func credentialForRequest() async throws -> YunlefunCredential {
        guard !isBusy else { throw YunlefunAccountError.unavailable }
        guard let current = credential else {
            throw YunlefunAccountError.expiredSession
        }
        let subject = current.subject
        if account?.id != subject || canRetry || current.expiresAt.timeIntervalSinceNow < 60 { await refreshSession() }
        try Task.checkCancellation()
        guard let next = credential, next.subject == subject else { throw YunlefunAccountError.expiredSession }
        guard account?.id == subject, next.expiresAt > Date() else {
            if canRetry { throw YunlefunAccountError.unavailable }
            throw YunlefunAccountError.expiredSession
        }
        return next
    }

    func signIn() async {
        await completeSignIn {
            let fields: [String: String]
            switch try await appAuthorization.authorize(YunlefunSSO()) {
            case .authorized(let approvedFields):
                fields = approvedFields
            case .useWeb:
                // Start a new transaction after the app attempt has been invalidated.
                try Task.checkCancellation()
                let transaction = try YunlefunSSO()
                let callback = try await authorization.authorize(transaction)
                fields = try transaction.exchangeFields(callback: callback)
            }
            try Task.checkCancellation()
            return try await self.service.signIn(fields: fields, deviceID: UUID().uuidString)
        }
    }

    func signInWithWeChat() async {
        await completeSignIn {
            let code = try await self.weChat.authorize()
            try Task.checkCancellation()
            return try await self.service.signInWithWeChat(code: code, deviceID: UUID().uuidString)
        }
    }

    func registerWithWeChat(proof: String, deviceID: String) async throws {
        guard account == nil, credential == nil, !isBusy, canUseWeChat, CookFeatures.weChatSignupEnabled else {
            throw WeChatAccountError.unavailable
        }
        isBusy = true
        defer { isBusy = false }
        message = nil
        let code = try await weChat.authorize()
        try Task.checkCancellation()
        let next = try await YunlefunAccountService().registerWithWeChat(proof: proof, code: code, deviceID: deviceID)
        do { try await establishSession(next) }
        catch {
            try? await service.signOut(next)
            throw WeChatSignupError.accountCreated
        }
    }

    func handleWeChat(_ url: URL) -> Bool { weChat.handle(url) }
    func handleWeChat(_ activity: NSUserActivity) -> Bool { weChat.handle(activity) }

    func bindWeChat() async {
        guard !isBusy, let current = credential, account?.id == current.subject else { return }
        isBusy = true
        message = nil
        weChatBindingMessage = nil
        defer { isBusy = false }
        do {
            guard current.expiresAt.timeIntervalSinceNow > 60 else {
                throw YunlefunAccountError.expiredSession
            }
            let code = try await weChat.authorize()
            try Task.checkCancellation()
            guard current.expiresAt > Date() else { throw YunlefunAccountError.expiredSession }
            // Yunlefun atomically checks both sides of the binding against the current session.
            try await service.bindWeChat(code: code, credential: current)
            weChatBindingMessage = "已将微信绑定到当前云乐坊账号。"
        } catch is CancellationError {
            // Closing WeChat leaves the active account intact.
        } catch {
            message = errorMessage(error)
        }
    }

    private func completeSignIn(_ nextCredential: () async throws -> YunlefunCredential) async {
        guard !isBusy else { return }
        isBusy = true
        message = nil
        weChatBindingMessage = nil
        let previousCanRetry = canRetry
        canRetry = false
        defer { isBusy = false }
        do {
            let next = try await nextCredential()
            try await establishSession(next)
        } catch is CancellationError {
            // 用户取消授权后回到原状态。
            canRetry = previousCanRetry
        } catch {
            canRetry = previousCanRetry
            message = errorMessage(error)
        }
    }

    private func establishSession(_ next: YunlefunCredential) async throws {
        let user = try await service.account(next)
        try Task.checkCancellation()
        try vault.save(next)
        credential = next
        offlineSubject = next.subject
        account = user
        sessionRevision = UUID()
        canRetry = false
    }

    func handleYunlefunCallback(_ url: URL) -> Bool { appAuthorization.handle(url) }
    func continueYunlefunOnWeb() { appAuthorization.useWeb() }
    func cancelYunlefunAppAuthorization() { appAuthorization.cancel() }

    func retry() async { await refreshSession() }

    func signOut() async {
        guard !isBusy else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            let previous = try vault.load()
            try vault.clear()
            credential = nil
            offlineSubject = nil
            account = nil
            sessionRevision = UUID()
            message = nil
            weChatBindingMessage = nil
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
            guard var current = try vault.load() else {
                if account != nil || credential != nil { sessionRevision = UUID() }
                credential = nil
                offlineSubject = nil
                account = nil
                return
            }
            credential = current
            offlineSubject = current.subject
            if current.expiresAt.timeIntervalSinceNow < 60 {
                current = try await service.refresh(current)
                // 刷新凭据会轮换：取资料前先原子保存，断网后仍可重试。
                try vault.save(current)
            }
            credential = current
            offlineSubject = current.subject
            let user = try await service.account(current)
            try Task.checkCancellation()
            if account?.id != user.id { sessionRevision = UUID() }
            account = user
        } catch is CancellationError {
            canRetry = true
        } catch YunlefunAccountError.expiredSession {
            if account != nil || credential != nil { sessionRevision = UUID() }
            account = nil
            credential = nil
            offlineSubject = nil
            do {
                try vault.clear()
                message = YunlefunAccountError.expiredSession.localizedDescription
            } catch { message = errorMessage(error) }
        } catch {
            // 网络失败保留钥匙串中的会话，以便恢复网络后重试。
            if account != nil { sessionRevision = UUID() }
            account = nil
            canRetry = true
            message = errorMessage(error)
        }
    }

    private func errorMessage(_ error: Error) -> String {
        (error as? WeChatAccountError)?.localizedDescription
            ?? (error as? YunlefunAccountError)?.localizedDescription
            ?? "暂时无法连接云乐坊，请检查网络后重试。"
    }
}
