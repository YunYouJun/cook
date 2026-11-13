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

@MainActor
protocol YunlefunAccountServing {
    func signIn(fields: [String: String], deviceID: String) async throws -> YunlefunCredential
    func refresh(_ credential: YunlefunCredential) async throws -> YunlefunCredential
    func account(_ credential: YunlefunCredential) async throws -> YunlefunAccount
    func signOut(_ credential: YunlefunCredential) async throws
    func signInWithWeChat(code: String, deviceID: String) async throws -> YunlefunCredential
    func hasWeChatBinding(_ credential: YunlefunCredential) async throws -> Bool
    func bindWeChat(code: String, credential: YunlefunCredential) async throws
}

extension YunlefunAccountServing {
    func signInWithWeChat(code: String, deviceID: String) async throws -> YunlefunCredential {
        throw WeChatAccountError.unavailable
    }
    func hasWeChatBinding(_ credential: YunlefunCredential) async throws -> Bool {
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
    static let weChatProviderID = "cookwechatios"
    private static let accountAccessURL = URL(string: "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/v1/functions/account-api")!
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
        let data = try await request(URL(string: "https://api.yunle.fun/sso-ticket")!, fields: fields,
                                     origin: "https://cook.yunyoujun.cn")
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
        let providerToken = try await weChatProviderToken(code: code, deviceID: deviceID)
        let credential = try await token("signin/with/provider", fields: WeChatSignIn(
            providerID: Self.weChatProviderID, providerToken: providerToken
        ), deviceID: deviceID)
        do {
            try await requireActiveAccount(credential)
            return credential
        } catch {
            try? await signOut(credential)
            throw error
        }
    }

    func hasWeChatBinding(_ credential: YunlefunCredential) async throws -> Bool {
        let data = try await request(URL(string: Self.gateway + "user/provider")!, method: "GET", credential: credential)
        guard let response = try? JSONDecoder().decode(ProviderBindings.self, from: data),
              response.total == response.data.count else {
            throw WeChatAccountError.unavailable
        }
        let providers = response.data.filter { $0.id == Self.weChatProviderID }
        guard providers.count == 1 else { throw WeChatAccountError.unavailable }
        return providers[0].bind
    }

    func bindWeChat(code: String, credential: YunlefunCredential) async throws {
        try await requireActiveAccount(credential)
        let providerToken = try await weChatProviderToken(code: code, deviceID: credential.deviceID)
        _ = try await request(URL(string: Self.gateway + "user/provider/bind")!, fields: [
            "provider_token": providerToken,
        ], credential: credential)
    }

    private func requireActiveAccount(_ credential: YunlefunCredential) async throws {
        let data = try await request(Self.accountAccessURL, fields: ["action": "getAccountAccessStatus"], credential: credential)
        guard let access = try? JSONDecoder().decode(AccountAccess.self, from: data) else {
            throw YunlefunAccountError.invalidResponse
        }
        guard access.state == "active", !access.restricted else { throw WeChatAccountError.accountRestricted }
    }

    private func weChatProviderToken(code: String, deviceID: String) async throws -> String {
        let data = try await request(URL(string: Self.gateway + "provider/token")!, fields: [
            "provider_id": Self.weChatProviderID,
            "provider_code": code,
        ], deviceID: deviceID)
        guard let response = try? JSONDecoder().decode(WeChatProviderToken.self, from: data),
              response.profile.providerID == Self.weChatProviderID, !response.profile.subject.isEmpty,
              !response.token.isEmpty, response.token.utf8.count <= 32_768 else {
            throw YunlefunAccountError.invalidResponse
        }
        return response.token
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
        _ = try await request(URL(string: "\(Self.gateway)user/signout")!, fields: [String: String](), credential: credential)
    }

    private func token(_ path: String, fields: some Encodable, deviceID: String) async throws -> YunlefunCredential {
        let data = try await request(URL(string: Self.gateway + path)!, fields: fields, deviceID: deviceID)
        guard let token = try? JSONDecoder().decode(TokenResponse.self, from: data),
              !token.accessToken.isEmpty, !token.refreshToken.isEmpty, !token.subject.isEmpty,
              token.expiresIn > 0, token.expiresIn <= 31 * 86400 else { throw YunlefunAccountError.invalidResponse }
        return YunlefunCredential(accessToken: token.accessToken, refreshToken: token.refreshToken,
                                  subject: token.subject, expiresAt: Date().addingTimeInterval(token.expiresIn), deviceID: deviceID)
    }

    private func request(_ url: URL, method: String = "POST", fields: (any Encodable)? = nil,
                         credential: YunlefunCredential? = nil, deviceID: String? = nil, origin: String? = nil) async throws -> Data {
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
            if url.path.hasSuffix("/signin/with/provider"),
               ["not_found", "user_not_found", "account_not_found", "user_not_bound"].contains(failure?.error ?? "") {
                throw WeChatAccountError.bindingRequired
            }
            if url.path.hasSuffix("/user/provider/bind"), http.statusCode == 409 {
                throw WeChatAccountError.alreadyBound
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
    private struct ServiceFailure: Decodable { let error: String? }
    private struct AccountAccess: Decodable { let state: String; let restricted: Bool }
    private struct WeChatProviderToken: Decodable {
        struct Profile: Decodable {
            let providerID: String
            let subject: String
            enum CodingKeys: String, CodingKey { case providerID = "provider_id", subject = "sub" }
        }
        let token: String
        let profile: Profile
        enum CodingKeys: String, CodingKey { case token = "provider_token", profile = "provider_profile" }
    }
    private struct WeChatSignIn: Encodable {
        let providerID: String
        let providerToken: String
        let forceDisableSignUp = true
        let syncProfile = false
        enum CodingKeys: String, CodingKey {
            case providerID = "provider_id", providerToken = "provider_token"
            case forceDisableSignUp = "force_disable_sign_up", syncProfile = "sync_profile"
        }
    }
    private struct ProviderBindings: Decodable {
        struct Provider: Decodable { let id: String; let bind: Bool }
        let total: Int
        let data: [Provider]
    }
}

private final class AccountRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
