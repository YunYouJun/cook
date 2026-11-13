import Foundation
import UIKit

enum WeChatAccountError: LocalizedError {
    case unavailable, authorizationFailed, bindingRequired, alreadyBound, accountRestricted
    case phoneVerificationRequired, identityUnverified, rateLimited

    var errorDescription: String? {
        switch self {
        case .unavailable: return "微信登录暂不可用，请使用云乐坊账号登录。"
        case .authorizationFailed: return "微信授权未完成，请重试。"
        case .bindingRequired: return "此微信尚未关联云食册。请先登录原来的云乐坊账号，再到账号详情绑定。"
        case .alreadyBound: return "当前账号已绑定微信，或此微信已被绑定。请检查账号绑定状态。"
        case .accountRestricted: return "此云乐坊账号暂不可用，请在云乐坊网站检查账号状态。"
        case .phoneVerificationRequired: return "请先在云乐坊网站验证手机号，再使用微信登录。"
        case .identityUnverified: return "无法确认此微信与云乐坊账号的关联，请使用原来的登录方式。"
        case .rateLimited: return "操作过于频繁，请稍后重新发起微信授权。"
        }
    }
}

@MainActor
protocol WeChatAuthorizing {
    var isAvailable: Bool { get }
    func authorize() async throws -> String
    func handle(_ url: URL) -> Bool
    func handle(_ activity: NSUserActivity) -> Bool
}

/// WeChat sends results through OpenSDK. A URL alone never supplies a code to CloudBase.
@MainActor
final class WeChatAuthorization: WeChatAuthorizing {
    struct Driver {
        // Checking the URL scheme does not initialize OpenSDK before consent.
        var isInstalled: @MainActor () -> Bool = {
            CookWeChatBridge.isLinked() && UIApplication.shared.canOpenURL(URL(string: "weixin://")!)
        }
        var register: (String, String) -> Bool = { CookWeChatBridge.prepare(appID: $0, universalLink: $1) }
        var send: (String, @escaping @Sendable (Bool) -> Void) -> Void = { CookWeChatBridge.sendAuth(state: $0, completion: $1) }
        var handleURL: (URL, @escaping CookWeChatResponse) -> Bool = { CookWeChatBridge.handle(url: $0, response: $1) }
        var handleActivity: (NSUserActivity, @escaping CookWeChatResponse) -> Bool = { CookWeChatBridge.handle(activity: $0, response: $1) }
    }

    static let appID = "wxce6c7405dd756b9d"
    static let universalLink = "https://cook.yunyoujun.cn/auth/wechat/"
    private let enabled: Bool
    private let driver: Driver
    private var registered = false
    private var pending: (state: String, deadline: ContinuousClock.Instant, reply: CheckedContinuation<String, Error>)?
    private var timeout: Task<Void, Never>?

    init(enabled: Bool, driver: Driver? = nil) {
        self.enabled = enabled
        self.driver = driver ?? Driver()
    }

    var isAvailable: Bool {
        enabled && driver.isInstalled()
    }

    private func prepare() -> Bool {
        guard isAvailable else { return false }
        if !registered {
            registered = driver.register(Self.appID, Self.universalLink)
        }
        return registered
    }

    func authorize() async throws -> String {
        guard pending == nil, prepare() else { throw WeChatAccountError.unavailable }
        let state = try YunlefunSSO.randomToken()
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { reply in
                pending = (state, .now.advanced(by: .seconds(180)), reply)
                timeout = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(180)) } catch { return }
                    self?.finish(.failure(WeChatAccountError.authorizationFailed), state: state)
                }
                driver.send(state) { [weak self] sent in
                    guard !sent else { return }
                    Task { @MainActor in self?.finish(.failure(WeChatAccountError.unavailable), state: state) }
                }
            }
        } onCancel: {
            Task { @MainActor in self.finish(.failure(CancellationError()), state: state) }
        }
    }

    func handle(_ url: URL) -> Bool {
        guard pending != nil else { return false }
        if CookUniversalLink.isWeChatLink(url) {
            let activity = NSUserActivity(activityType: NSUserActivityTypeBrowsingWeb)
            activity.webpageURL = url
            return handle(activity)
        }
        guard url.scheme?.lowercased() == Self.appID else { return false }
        return driver.handleURL(url, response)
    }

    func handle(_ activity: NSUserActivity) -> Bool {
        guard pending != nil, activity.activityType == NSUserActivityTypeBrowsingWeb,
              let url = activity.webpageURL, CookUniversalLink.isWeChatLink(url) else { return false }
        return driver.handleActivity(activity, response)
    }

    private var response: CookWeChatResponse {
        { [weak self] status, state, code in
            guard let state, !state.isEmpty else { return }
            Task { @MainActor in
                self?.finish(Result { try Self.validatedCode(status: status, code: code) }, state: state)
            }
        }
    }

    nonisolated static func validatedCode(status: Int, code: String?) throws -> String {
        if status == -2 || status == -4 { throw CancellationError() }
        guard status == 0, let code, (1...512).contains(code.utf8.count),
              code.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0)
                  || (97...122).contains($0) || $0 == 45 || $0 == 95 })
        else { throw WeChatAccountError.authorizationFailed }
        return code
    }

    private func finish(_ result: Result<String, Error>, state: String) {
        guard let pending, pending.state == state else { return }
        self.pending = nil
        timeout?.cancel()
        timeout = nil
        pending.reply.resume(with: .now < pending.deadline ? result : .failure(WeChatAccountError.authorizationFailed))
    }
}
