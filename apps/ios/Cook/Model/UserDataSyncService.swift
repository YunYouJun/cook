import CookCore
import CryptoKit
import Foundation

enum UserDataSyncFailure: LocalizedError {
    case loginRequired, membershipRequired, unavailable, invalidResponse, localStorage
    var errorDescription: String? {
        switch self {
        case .loginRequired: return "请重新登录后同步，离线记录已保留。"
        case .membershipRequired: return "跨平台同步需要有效的云乐坊会员，已有记录仍可在本机使用。"
        case .unavailable: return "暂时无法同步，恢复联网后会重试。"
        case .invalidResponse: return "同步服务或数据版本暂不兼容，已停止覆盖，请稍后重试。"
        case .localStorage: return "账号记录未能保存，原有数据已保留。请检查设备储存空间。"
        }
    }
}

@MainActor
protocol UserDataSyncServing {
    func checkAccess(credential: YunlefunCredential) async throws
    func sync(_ document: UserDataDocument, credential: YunlefunCredential) async throws -> UserDataDocument
}

/// Official authenticated function invocation; no public route or platform key.
struct UserDataSyncService: UserDataSyncServing {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    nonisolated static let endpoint = URL(string: "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/v1/functions/cook-user-data")!
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
            let session = URLSession(configuration: config, delegate: UserDataRedirectPolicy(), delegateQueue: nil)
            self.transport = { try await session.data(for: $0) }
        }
    }

    func checkAccess(credential: YunlefunCredential) async throws {
        _ = try await request(["action": "capabilities"], credential: credential)
    }

    func sync(_ document: UserDataDocument, credential: YunlefunCredential) async throws -> UserDataDocument {
        try document.validate()
        let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(document))
        let body = try await request(["action": "sync", "document": object], credential: credential)
        guard let value = body["document"] else { throw UserDataSyncFailure.invalidResponse }
        do { return try UserDataDocument.decode(JSONSerialization.data(withJSONObject: value)) }
        catch { throw UserDataSyncFailure.invalidResponse }
    }

    private func request(_ fields: [String: Any], credential: YunlefunCredential) async throws -> [String: Any] {
        try Task.checkCancellation()
        guard credential.expiresAt > Date(), !credential.subject.isEmpty, !credential.accessToken.isEmpty,
              credential.accessToken.utf8.count <= 16_384,
              credential.accessToken.unicodeScalars.allSatisfy({ (33...126).contains($0.value) }) else { throw UserDataSyncFailure.loginRequired }
        var request = URLRequest(url: Self.endpoint)
        request.httpMethod = "POST"
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("Bearer \(credential.accessToken)", forHTTPHeaderField: "Authorization")
        var bodyFields = fields
        bodyFields["ownerDigest"] = SHA256.hash(data: Data(credential.subject.utf8)).map { String(format: "%02x", $0) }.joined()
        request.httpBody = try JSONSerialization.data(withJSONObject: bodyFields)
        let (data, response) = try await transport(request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse, http.url == Self.endpoint, data.count <= 300 * 1024,
              !(300..<400).contains(http.statusCode),
              let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw UserDataSyncFailure.invalidResponse }
        if http.statusCode == 401 || ["LOGIN_REQUIRED", "SESSION_CHANGED"].contains(body["code"] as? String ?? "") { throw UserDataSyncFailure.loginRequired }
        if body["code"] as? String == "MEMBERSHIP_REQUIRED" { throw UserDataSyncFailure.membershipRequired }
        if ["INVALID_REMOTE", "INVALID_DOCUMENT", "DOCUMENT_LIMIT", "UNKNOWN_ACTION"].contains(body["code"] as? String ?? "") {
            throw UserDataSyncFailure.invalidResponse
        }
        guard (200..<300).contains(http.statusCode), body["ok"] as? Bool == true else { throw UserDataSyncFailure.unavailable }
        guard body["protocolVersion"] as? Int == 2 else { throw UserDataSyncFailure.invalidResponse }
        return body
    }
}

private final class UserDataRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
