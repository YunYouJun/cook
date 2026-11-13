import Foundation
import os

enum YunlefunAccountError: LocalizedError {
    case invalidCallback, authorizationFailed, invalidResponse, expiredSession, secureStorage, unavailable, requiresUpdate

    var errorDescription: String? {
        switch self {
        case .invalidCallback: return "授权结果无效或已过期，请重新登录。"
        case .authorizationFailed: return "云乐坊授权未完成，请重新登录。"
        case .invalidResponse: return "登录服务返回了无效结果，请稍后重试。"
        case .expiredSession: return "登录已过期，请重新登录。"
        case .secureStorage: return "无法访问安全存储，请解锁设备后重试。"
        case .unavailable: return "暂时无法连接登录服务，请稍后重试。"
        case .requiresUpdate: return "云乐坊登录需要 iOS 17.4 或更新版本。"
        }
    }
}

struct YunlefunAccount: Decodable, Equatable {
    let id: String
    let name: String?
    let username: String?
    let status: String?
    let isAnonymous: Bool?
    var picture: String? = nil

    enum CodingKeys: String, CodingKey {
        case id = "sub", name, username, status, picture, isAnonymous = "is_anonymous"
    }

    var displayName: String {
        [name, username].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty } ?? "云乐坊用户"
    }

    var avatarURL: URL? {
        guard let picture, picture.count <= 2048,
              let url = URL(string: picture), url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil else { return nil }
        return url
    }

    // CloudBase's authenticated profile may omit the optional status field.
    // The gateway validates the token; a supplied status must still be usable.
    var hasUsableStatus: Bool {
        guard let status else { return true }
        return ["DEFAULT", "ACTIVE"].contains(status.uppercased())
    }
}

struct YunlefunCredential: Codable, Equatable {
    let accessToken: String
    let refreshToken: String
    let subject: String
    let expiresAt: Date
    let deviceID: String
}

struct WeChatSignupCaptcha: Decodable {
    let image: String
    let challenge: String
}

enum WeChatSignupNext {
    case existingAccount
    case confirm(proof: String)
}

enum WeChatSignupError: LocalizedError {
    case captchaRequired, captchaInvalid, invalidOTP, restart, existingAccount, accountCreated

    var errorDescription: String? {
        switch self {
        case .captchaRequired: return "请先完成图片验证。"
        case .captchaInvalid: return "图片验证码错误或已过期，请重试。"
        case .invalidOTP: return "短信验证码错误或已过期，请检查后重试。"
        case .restart: return "本次验证已过期，请重新验证手机号。"
        case .existingAccount: return "此手机号已有云乐坊账号，请登录原账号再绑定微信。"
        case .accountCreated: return "云乐坊账号已创建，但未能完成登录。请用手机号登录，再到账号页检查微信绑定。"
        }
    }
}

@MainActor
protocol YunlefunAccountServing {
    func signIn(fields: [String: String], deviceID: String) async throws -> YunlefunCredential
    func refresh(_ credential: YunlefunCredential) async throws -> YunlefunCredential
    func account(_ credential: YunlefunCredential) async throws -> YunlefunAccount
    func signOut(_ credential: YunlefunCredential) async throws
    func signInWithWeChat(code: String, deviceID: String) async throws -> YunlefunCredential
    func bindWeChat(code: String, credential: YunlefunCredential) async throws
}

extension YunlefunAccountServing {
    func signInWithWeChat(code: String, deviceID: String) async throws -> YunlefunCredential {
        throw WeChatAccountError.unavailable
    }
    func bindWeChat(code: String, credential: YunlefunCredential) async throws {
        throw WeChatAccountError.unavailable
    }
}

/// 仅访问固定 HTTPS 端点，不接收重定向，不共享 Cookie，也不记录凭据。
struct YunlefunAccountService: YunlefunAccountServing {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    static let gateway = "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/auth/v1/"
    private static let ticketURL = URL(string: "https://api.yunle.fun/sso-ticket")!
    private static let cookOrigin = "https://cook.yunyoujun.cn"
    private static let logger = Logger(subsystem: "fun.yunle.cook", category: "account")
    private let transport: Transport

    init(transport: Transport? = nil) {
        if let transport { self.transport = transport }
        else {
            let config = URLSessionConfiguration.ephemeral
            config.httpShouldSetCookies = false
            config.httpCookieStorage = nil
            config.urlCredentialStorage = nil
            config.urlCache = nil
            config.timeoutIntervalForRequest = 20
            config.timeoutIntervalForResource = 30
            let session = URLSession(configuration: config, delegate: AccountRedirectPolicy(), delegateQueue: nil)
            self.transport = { try await session.data(for: $0) }
        }
    }

    func signIn(fields: [String: String], deviceID: String) async throws -> YunlefunCredential {
        let data = try await request(Self.ticketURL, fields: fields, origin: Self.cookOrigin)
        return try await exchangeWeChatTicket(data, deviceID: deviceID)
    }

    func weChatSignupCaptcha(deviceID: String) async throws -> WeChatSignupCaptcha {
        let data = try await signupRequest("cookSignupCaptcha", deviceID: deviceID)
        return try JSONDecoder().decode(WeChatSignupCaptcha.self, from: data)
    }

    func sendWeChatSignupSMS(phone: String, captcha: (challenge: String, answer: String)?,
                             deviceID: String) async throws -> String {
        var fields = ["phone": phone]
        if let captcha {
            fields["captchaChallenge"] = captcha.challenge
            fields["captchaAnswer"] = captcha.answer
        }
        let data = try await signupRequest("sendCookSignupSms", fields: fields, deviceID: deviceID)
        guard let challenge = try JSONDecoder().decode(SignupResponse.self, from: data).challenge,
              !challenge.isEmpty else { throw YunlefunAccountError.invalidResponse }
        return challenge
    }

    func verifyWeChatSignupSMS(challenge: String, otp: String, deviceID: String) async throws -> WeChatSignupNext {
        let data = try await signupRequest("verifyCookSignupSms", fields: ["challenge": challenge, "otp": otp], deviceID: deviceID)
        let result = try JSONDecoder().decode(SignupResponse.self, from: data)
        if result.next == "existing", result.proof == nil { return .existingAccount }
        if result.next == "confirm", let proof = result.proof, !proof.isEmpty { return .confirm(proof: proof) }
        throw YunlefunAccountError.invalidResponse
    }

    func registerWithWeChat(proof: String, code: String, deviceID: String) async throws -> YunlefunCredential {
        let data = try await signupRequest("registerCookWeChat", fields: ["proof": proof, "code": code], deviceID: deviceID)
        do { return try await exchangeWeChatTicket(data, deviceID: deviceID) }
        catch { throw WeChatSignupError.accountCreated }
    }

    private func signupRequest(_ action: String, fields: [String: String] = [:], deviceID: String) async throws -> Data {
        var body = fields
        body["action"] = action
        body["clientId"] = YunlefunSSO.clientID
        return try await request(Self.ticketURL, fields: body, deviceID: deviceID, origin: Self.cookOrigin, weChat: true)
    }

    private func exchangeWeChatTicket(_ data: Data, deviceID: String) async throws -> YunlefunCredential {
        guard let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              payload["ok"] as? Bool == true, let ticket = payload["ticket"] as? String, !ticket.isEmpty,
              ["access_token", "refresh_token", "session"].allSatisfy({ payload[$0] == nil }) else {
            throw YunlefunAccountError.invalidResponse
        }
        return try await token("signin/custom", fields: ["provider_id": "custom", "ticket": ticket], deviceID: deviceID)
    }

    func refresh(_ credential: YunlefunCredential) async throws -> YunlefunCredential {
        let next = try await token("token", fields: ["grant_type": "refresh_token", "refresh_token": credential.refreshToken],
                                   deviceID: credential.deviceID)
        guard next.subject == credential.subject else { throw YunlefunAccountError.expiredSession }
        return next
    }

    func signInWithWeChat(code: String, deviceID: String) async throws -> YunlefunCredential {
        let data = try await request(Self.ticketURL, fields: [
            "action": "exchangeCookWeChatCode", "clientId": YunlefunSSO.clientID, "code": code,
        ], origin: Self.cookOrigin, weChat: true)
        guard let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(payload.keys) == ["ok", "ticket", "subject"],
              payload["ok"] as? Bool == true, let ticket = payload["ticket"] as? String,
              !ticket.isEmpty, ticket.utf8.count <= 32_768,
              let subject = payload["subject"] as? String, !subject.isEmpty else {
            throw YunlefunAccountError.invalidResponse
        }
        let credential = try await token("signin/custom", fields: ["provider_id": "custom", "ticket": ticket], deviceID: deviceID)
        guard credential.subject == subject else {
            try? await signOut(credential)
            throw YunlefunAccountError.invalidResponse
        }
        return credential
    }

    func bindWeChat(code: String, credential: YunlefunCredential) async throws {
        let data = try await request(Self.ticketURL, fields: [
            "action": "bindCookWeChat", "clientId": YunlefunSSO.clientID, "code": code,
        ], credential: credential, origin: Self.cookOrigin, weChat: true)
        guard let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(payload.keys) == ["ok"], payload["ok"] as? Bool == true else {
            throw YunlefunAccountError.invalidResponse
        }
    }

    func account(_ credential: YunlefunCredential) async throws -> YunlefunAccount {
        let data = try await request(URL(string: "\(Self.gateway)user/me")!, method: "GET", credential: credential)
        guard let user = try? JSONDecoder().decode(YunlefunAccount.self, from: data) else {
            Self.logger.error("Profile response could not be decoded")
            throw YunlefunAccountError.invalidResponse
        }
        guard !user.id.isEmpty, user.id == credential.subject, user.isAnonymous != true,
              user.hasUsableStatus else {
            let normalizedStatus = user.status?.uppercased() ?? "missing"
            let knownStatus = ["DEFAULT", "ACTIVE", "PENDING", "BLOCKED", "missing"].contains(normalizedStatus) ? normalizedStatus : "unknown"
            Self.logger.error("Profile rejected: subjectMatches=\(user.id == credential.subject), anonymous=\(user.isAnonymous == true), status=\(knownStatus, privacy: .public)")
            throw YunlefunAccountError.expiredSession
        }
        Self.logger.debug("Profile validated; statusFieldPresent=\(user.status != nil)")
        return user
    }

    func signOut(_ credential: YunlefunCredential) async throws {
        _ = try await request(URL(string: "\(Self.gateway)user/signout")!, fields: [:], credential: credential)
    }

    private func token(_ path: String, fields: [String: String], deviceID: String) async throws -> YunlefunCredential {
        let data = try await request(URL(string: Self.gateway + path)!, fields: fields, deviceID: deviceID)
        guard let token = try? JSONDecoder().decode(TokenResponse.self, from: data),
              !token.accessToken.isEmpty, !token.refreshToken.isEmpty, !token.subject.isEmpty,
              token.expiresIn > 0, token.expiresIn <= 31 * 86400 else { throw YunlefunAccountError.invalidResponse }
        return YunlefunCredential(accessToken: token.accessToken, refreshToken: token.refreshToken,
                                  subject: token.subject, expiresAt: Date().addingTimeInterval(token.expiresIn), deviceID: deviceID)
    }

    private func request(_ url: URL, method: String = "POST", fields: [String: String]? = nil,
                         credential: YunlefunCredential? = nil, deviceID: String? = nil, origin: String? = nil,
                         weChat: Bool = false) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue(deviceID ?? credential?.deviceID, forHTTPHeaderField: "x-device-id")
        request.setValue(origin, forHTTPHeaderField: "Origin")
        if let credential { request.setValue("Bearer \(credential.accessToken)", forHTTPHeaderField: "Authorization") }
        if let fields {
            request.httpBody = try JSONEncoder().encode(fields)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await transport(request)
        guard let http = response as? HTTPURLResponse, http.url == url,
              data.count < 256 * 1024 else { throw YunlefunAccountError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let failure = try? JSONDecoder().decode(ServiceFailure.self, from: data)
            // Only a fixed endpoint path and HTTP code; never log response bodies, tokens or user identifiers.
            Self.logger.error("Account endpoint \(url.path, privacy: .public) returned HTTP \(http.statusCode)")
            if weChat {
                switch failure?.reason {
                case "signup_captcha_required": throw WeChatSignupError.captchaRequired
                case "signup_captcha_invalid": throw WeChatSignupError.captchaInvalid
                case "signup_otp_invalid": throw WeChatSignupError.invalidOTP
                case "signup_restart_required": throw WeChatSignupError.restart
                case "signup_existing_account": throw WeChatSignupError.existingAccount
                case "signup_account_created": throw WeChatSignupError.accountCreated
                case "wechat_binding_required": throw WeChatAccountError.bindingRequired
                case "binding_conflict": throw WeChatAccountError.alreadyBound
                case "account_restricted": throw WeChatAccountError.accountRestricted
                case "phone_verification_required": throw WeChatAccountError.phoneVerificationRequired
                case "wechat_identity_unverified": throw WeChatAccountError.identityUnverified
                case "wechat_code_invalid": throw WeChatAccountError.authorizationFailed
                case "not_authenticated": throw YunlefunAccountError.expiredSession
                case "rate_limited": throw WeChatAccountError.rateLimited
                default: throw WeChatAccountError.unavailable
                }
            }
            if http.statusCode == 401 || ["invalid_grant", "invalid_token", "unauthenticated", "user_not_found", "user_disabled"].contains(failure?.error ?? "") {
                throw YunlefunAccountError.expiredSession
            }
            throw YunlefunAccountError.unavailable
        }
        return data
    }

    private struct TokenResponse: Decodable {
        let accessToken: String
        let refreshToken: String
        let subject: String
        let expiresIn: TimeInterval
        enum CodingKeys: String, CodingKey {
            case accessToken = "access_token", refreshToken = "refresh_token", subject = "sub", expiresIn = "expires_in"
        }
    }
    private struct ServiceFailure: Decodable { let error: String?; let reason: String? }
    private struct SignupResponse: Decodable { let next: String?; let proof: String?; let challenge: String? }
}

private final class AccountRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
