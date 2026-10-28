import CookCore
import Foundation

enum ManagedCookAIError: Error, LocalizedError, Equatable {
    case unconfigured, loginRequired, membershipRequired, accountUnavailable, rateLimited, budgetExhausted
    case versionMismatch, unavailable, invalidResponse, timedOut, offline, invalidInput

    var errorDescription: String? {
        switch self {
        case .unconfigured: return "此版本尚未配置会员 AI 服务。"
        case .loginRequired: return "请登录云乐坊账号后检查会员资格。"
        case .membershipRequired: return "会员内置 DeepSeek 仅供有效云乐坊会员使用。"
        case .accountUnavailable: return "当前云乐坊账号暂时无法使用会员 AI 服务。"
        case .rateLimited: return "会员 AI 请求较多，请稍后重试。"
        case .budgetExhausted: return "会员 AI 本期额度已用完，请稍后再试或使用自己的 Key。"
        case .versionMismatch: return "会员 AI 服务与当前菜谱版本不一致，请更新后重试。"
        case .unavailable: return "会员 AI 服务尚未就绪或暂时不可用，可以继续使用本地识别。"
        case .invalidResponse: return "会员 AI 未返回可用的条件，请重试或使用本地识别。"
        case .timedOut: return "会员 AI 识别超时，可以重试或使用本地识别。"
        case .offline: return "暂时无法连接会员 AI 服务，可以继续使用本地识别。"
        case .invalidInput: return "请输入 1–1000 字的做菜想法。"
        }
    }
}

struct ManagedCookAICapability: Sendable {
    let enabled: Bool
    let membershipIsActive: Bool
    let membershipExpiresAt: Date?
}

protocol ManagedCookAIServing: Sendable {
    var isConfigured: Bool { get }
    func capabilities(credential: YunlefunCredential) async throws -> ManagedCookAICapability
    func parse(text: String, credential: YunlefunCredential, data: CookData,
               availableGoals: [DietaryGoal]) async throws -> SearchIntent
}

/// The server validates the current bearer identity, membership and quotas on every parse.
struct ManagedCookAIService: ManagedCookAIServing {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    private let endpoint: URL?
    private let transport: Transport
    var isConfigured: Bool { endpoint != nil }

    init(endpoint: URL? = ManagedCookAIService.configuredEndpoint, transport: Transport? = nil) {
        self.endpoint = endpoint.flatMap(Self.approvedEndpoint)
        self.transport = transport ?? { try await Self.sendLive($0) }
    }

    static var configuredEndpoint: URL? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "CookAIServiceURL") as? String,
              !value.isEmpty else { return nil }
        return URL(string: value).flatMap(approvedEndpoint)
    }

    func capabilities(credential: YunlefunCredential) async throws -> ManagedCookAICapability {
        let body = try await request(["action": "capabilities"], credential: credential)
        guard let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              Set(object.keys).isSubset(of: ["ok", "serviceVersion", "enabled", "membership", "code"]),
              let membership = object["membership"] as? [String: Any],
              Set(membership.keys).isSubset(of: ["isActive", "expireAt"]),
              let wire = try? JSONDecoder().decode(CapabilityWire.self, from: body), wire.serviceVersion == 1 else {
            throw ManagedCookAIError.invalidResponse
        }
        if let milliseconds = wire.membership.expireAt, !milliseconds.isFinite || milliseconds <= 0 {
            throw ManagedCookAIError.invalidResponse
        }
        let expiresAt = wire.membership.expireAt.map { Date(timeIntervalSince1970: $0 / 1_000) }
        if wire.enabled {
            guard wire.code == nil, wire.membership.isActive, let expiresAt, expiresAt > Date() else { throw ManagedCookAIError.invalidResponse }
        }
        return ManagedCookAICapability(enabled: wire.enabled, membershipIsActive: wire.membership.isActive, membershipExpiresAt: expiresAt)
    }

    func parse(text: String, credential: YunlefunCredential, data: CookData,
               availableGoals: [DietaryGoal]) async throws -> SearchIntent {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.count <= 1_000 else {
            throw ManagedCookAIError.invalidInput
        }
        let body = try await request(["action": "parse", "schemaVersion": SearchIntent.currentSchemaVersion,
                                      "policyVersion": SearchIntent.currentPolicyVersion,
                                      "dataVersion": data.meta.dataVersion, "text": text], credential: credential)
        guard let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              Set(object.keys) == ["ok", "intent"], let rawIntent = object["intent"] as? [String: Any] else {
            throw ManagedCookAIError.invalidResponse
        }
        do {
            let validator = SearchIntentValidator(data: data, availableGoals: availableGoals)
            let intent = try validator.decode(JSONSerialization.data(withJSONObject: rawIntent), expectedSource: .deepseek)
            return try CookAIIntentValidation.preserveLocalConditions(intent, text: text, data: data, availableGoals: availableGoals)
        } catch is CookAIIntentValidationFailure { throw ManagedCookAIError.invalidResponse }
    }

    private func request(_ fields: [String: Any], credential: YunlefunCredential) async throws -> Data {
        try Task.checkCancellation()
        guard let endpoint else { throw ManagedCookAIError.unconfigured }
        guard credential.expiresAt > Date(), !credential.subject.isEmpty, !credential.accessToken.isEmpty,
              credential.accessToken.utf8.count <= 16_384,
              credential.accessToken.unicodeScalars.allSatisfy({ (33...126).contains($0.value) }) else {
            throw ManagedCookAIError.loginRequired
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 45
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("Bearer \(credential.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.httpBody = try JSONSerialization.data(withJSONObject: fields, options: .sortedKeys)
        do {
            let (body, response) = try await transport(request)
            try Task.checkCancellation()
            guard let http = response as? HTTPURLResponse, http.url == endpoint, body.count <= 65_536 else {
                throw ManagedCookAIError.invalidResponse
            }
            let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any]
            if http.statusCode != 200 {
                throw Self.serviceError(status: http.statusCode, code: object?["code"] as? String)
            }
            guard let envelope = try? JSONDecoder().decode(ServiceEnvelope.self, from: body), envelope.ok else {
                throw ManagedCookAIError.invalidResponse
            }
            return body
        } catch is CancellationError { throw CancellationError() }
        catch let error as URLError {
            if Task.isCancelled || error.code == .cancelled { throw CancellationError() }
            switch error.code {
            case .timedOut: throw ManagedCookAIError.timedOut
            case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed:
                throw ManagedCookAIError.offline
            default: throw ManagedCookAIError.unavailable
            }
        } catch let error as ManagedCookAIError { throw error }
        catch { throw ManagedCookAIError.unavailable }
    }

    private static func approvedEndpoint(_ url: URL) -> URL? {
        guard url.scheme?.lowercased() == "https", url.port == nil || url.port == 443,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              !url.path.isEmpty, url.path != "/" else { return nil }
        let host = url.host?.lowercased() ?? ""
        guard host == "api.yunle.fun", url.path == "/cook-ai-intent" else { return nil }
        return url
    }

    private static func serviceError(status: Int, code: String?) -> ManagedCookAIError {
        switch (status, code) {
        case (401, "LOGIN_REQUIRED"): return .loginRequired
        case (403, "MEMBERSHIP_REQUIRED"): return .membershipRequired
        case (403, "ACCOUNT_UNAVAILABLE"): return .accountUnavailable
        case (409, "VERSION_MISMATCH"): return .versionMismatch
        case (429, "RATE_LIMITED"): return .rateLimited
        case (429, "BUDGET_EXHAUSTED"): return .budgetExhausted
        case (503, "SERVICE_UNAVAILABLE"): return .unavailable
        case (502, "INVALID_MODEL_RESPONSE"): return .invalidResponse
        case (504, "MODEL_TIMEOUT"): return .timedOut
        case (500...599, nil): return .unavailable
        default: return .invalidResponse
        }
    }

    private static func sendLive(_ request: URLRequest) async throws -> (Data, URLResponse) {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieStorage = nil
        config.urlCredentialStorage = nil
        config.urlCache = nil
        config.timeoutIntervalForRequest = 45
        config.timeoutIntervalForResource = 50
        let session = URLSession(configuration: config, delegate: ManagedCookAIRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (bytes, response) = try await session.bytes(for: request)
        var body = Data()
        for try await byte in bytes {
            try Task.checkCancellation()
            guard body.count < 65_536 else { throw ManagedCookAIError.invalidResponse }
            body.append(byte)
        }
        return (body, response)
    }

    private struct ServiceEnvelope: Decodable { let ok: Bool }
    private struct CapabilityWire: Decodable {
        struct Membership: Decodable { let isActive: Bool; let expireAt: Double? }
        let serviceVersion: Int
        let enabled: Bool
        let membership: Membership
        let code: String?
    }
}

private final class ManagedCookAIRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
