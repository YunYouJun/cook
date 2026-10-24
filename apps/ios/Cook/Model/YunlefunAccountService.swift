import Foundation

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

    enum CodingKeys: String, CodingKey {
        case id = "sub", name, username, status, isAnonymous = "is_anonymous"
    }

    var displayName: String {
        [name, username].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty } ?? "云乐坊用户"
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
}

/// 仅访问固定 HTTPS 端点，不接收重定向，不共享 Cookie，也不记录凭据。
struct YunlefunAccountService: YunlefunAccountServing {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    static let gateway = "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/auth/v1/"
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

    func account(_ credential: YunlefunCredential) async throws -> YunlefunAccount {
        let data = try await request(URL(string: "\(Self.gateway)user/me")!, method: "GET", credential: credential)
        guard let user = try? JSONDecoder().decode(YunlefunAccount.self, from: data),
              !user.id.isEmpty, user.id == credential.subject, user.isAnonymous != true,
              user.status == "ACTIVE" else { throw YunlefunAccountError.expiredSession }
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
        guard let http = response as? HTTPURLResponse, data.count < 256 * 1024 else { throw YunlefunAccountError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let failure = try? JSONDecoder().decode(ServiceFailure.self, from: data)
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
}

private final class AccountRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
