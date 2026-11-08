import CookCore
import Foundation

/// BYOK guidance shares the existing account, endpoint and ephemeral-session policy,
/// but never reuses the SearchIntent response contract.
struct DeepSeekStepGuidanceService: Sendable {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    static let endpoint = URL(string: "https://api.deepseek.com/chat/completions")!
    private static let maximumResponseBytes = 65_536
    private let transport: Transport

    init(transport: Transport? = nil) {
        self.transport = transport ?? { try await Self.sendLive($0) }
    }

    func complete(_ prompt: StepGuidancePrompt, key: String) async throws -> StepGuidanceResponse {
        try Task.checkCancellation()
        guard !key.isEmpty, key.utf8.count <= 512,
              key.unicodeScalars.allSatisfy({ (33 ... 126).contains($0.value) }) else {
            throw StepGuidanceError.authentication
        }
        var request = URLRequest(url: Self.endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.httpBody = try JSONEncoder().encode(ChatRequest(messages: [
            Message(role: "system", content: prompt.systemPrompt),
            Message(role: "user", content: prompt.userMessage),
        ]))

        do {
            let (body, response) = try await transport(request)
            try Task.checkCancellation()
            guard let http = response as? HTTPURLResponse, http.url == Self.endpoint,
                  body.count <= Self.maximumResponseBytes else {
                throw StepGuidanceError.invalidResponse
            }
            switch http.statusCode {
            case 200: break
            case 401, 403: throw StepGuidanceError.authentication
            case 402: throw StepGuidanceError.insufficientBalance
            case 429: throw StepGuidanceError.rateLimited
            default: throw StepGuidanceError.unavailable
            }
            guard let completion = try? JSONDecoder().decode(ChatResponse.self, from: body),
                  completion.choices.count == 1, let choice = completion.choices.first,
                  choice.finishReason == "stop", choice.message.role == "assistant",
                  let content = choice.message.content,
                  !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw StepGuidanceError.invalidResponse
            }
            let draft = try StepGuidanceValidator.decodeDraft(Data(content.utf8), for: prompt)
            return StepGuidanceResponse(draft: draft, for: prompt)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError {
            if Task.isCancelled || error.code == .cancelled { throw CancellationError() }
            switch error.code {
            case .timedOut: throw StepGuidanceError.timedOut
            case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost,
                 .cannotConnectToHost, .dnsLookupFailed:
                throw StepGuidanceError.offline
            default: throw StepGuidanceError.unavailable
            }
        } catch let error as StepGuidanceError {
            throw error
        } catch {
            throw StepGuidanceError.unavailable
        }
    }

    private static func sendLive(_ request: URLRequest) async throws -> (Data, URLResponse) {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieStorage = nil
        config.urlCredentialStorage = nil
        config.urlCache = nil
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 35
        let session = URLSession(configuration: config, delegate: StepGuidanceRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (bytes, response) = try await session.bytes(for: request)
        var body = Data()
        for try await byte in bytes {
            try Task.checkCancellation()
            guard body.count < maximumResponseBytes else { throw StepGuidanceError.invalidResponse }
            body.append(byte)
        }
        return (body, response)
    }

    private struct ChatRequest: Encodable {
        let model = "deepseek-flash"
        let thinking = ["type": "disabled"]
        let responseFormat = ["type": "json_object"]
        let maxTokens = 1_400
        let stream = false
        let messages: [Message]

        enum CodingKeys: String, CodingKey {
            case model, thinking, stream, messages
            case responseFormat = "response_format", maxTokens = "max_tokens"
        }
    }

    private struct Message: Encodable {
        let role: String
        let content: String
    }

    private struct ChatResponse: Decodable {
        struct Choice: Decodable {
            struct ResponseMessage: Decodable {
                let role: String
                let content: String?
            }
            let message: ResponseMessage
            let finishReason: String
            enum CodingKeys: String, CodingKey { case message; case finishReason = "finish_reason" }
        }
        let choices: [Choice]
    }
}

struct ManagedStepGuidanceCapability: Sendable {
    let enabled: Bool
    let membershipIsActive: Bool
    let membershipExpiresAt: Date?
}

protocol ManagedStepGuidanceServing: Sendable {
    var isConfigured: Bool { get }
    func capabilities(credential: YunlefunCredential) async throws -> ManagedStepGuidanceCapability
    func complete(_ prompt: StepGuidancePrompt, credential: YunlefunCredential) async throws -> StepGuidanceResponse
}

/// The managed path probes its own capability and validates its own response envelope.
/// A ready SearchIntent service cannot make this channel ready.
struct ManagedStepGuidanceService: ManagedStepGuidanceServing {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    private let endpoint: URL?
    private let transport: Transport

    var isConfigured: Bool { endpoint != nil }

    init(endpoint: URL? = ManagedStepGuidanceService.configuredEndpoint, transport: Transport? = nil) {
        self.endpoint = endpoint.flatMap(Self.approvedEndpoint)
        self.transport = transport ?? { try await Self.sendLive($0) }
    }

    static var configuredEndpoint: URL? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "CookAIGuidanceServiceURL") as? String,
              !value.isEmpty else { return nil }
        return URL(string: value).flatMap(approvedEndpoint)
    }

    func capabilities(credential: YunlefunCredential) async throws -> ManagedStepGuidanceCapability {
        let body = try await request(["action": "capabilities"], credential: credential)
        guard let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              Set(object.keys) == ["ok", "serviceVersion", "enabled", "membership"],
              let membership = object["membership"] as? [String: Any],
              Set(membership.keys) == ["isActive", "expireAt"],
              let wire = try? JSONDecoder().decode(CapabilityWire.self, from: body),
              wire.serviceVersion == 1, wire.ok else {
            throw StepGuidanceError.invalidResponse
        }
        if let milliseconds = wire.membership.expireAt, !milliseconds.isFinite || milliseconds <= 0 {
            throw StepGuidanceError.invalidResponse
        }
        let expiresAt = wire.membership.expireAt.map { Date(timeIntervalSince1970: $0 / 1_000) }
        if wire.enabled {
            guard wire.membership.isActive, let expiresAt, expiresAt > Date() else {
                throw StepGuidanceError.invalidResponse
            }
        }
        return ManagedStepGuidanceCapability(
            enabled: wire.enabled,
            membershipIsActive: wire.membership.isActive,
            membershipExpiresAt: expiresAt
        )
    }

    func complete(_ prompt: StepGuidancePrompt, credential: YunlefunCredential) async throws -> StepGuidanceResponse {
        var fields: [String: Any] = [
            "action": "guidance",
            "schemaVersion": prompt.request.schemaVersion,
            "policyVersion": prompt.request.policyVersion,
            "dataVersion": prompt.request.dataVersion,
            "sourceFingerprint": prompt.request.sourceFingerprint,
            "recipeID": prompt.request.recipeID,
            "stepID": prompt.request.stepID,
            "servings": prompt.request.servings,
            "kind": prompt.request.kind.rawValue,
            "requestID": prompt.request.requestID,
            "contextRevision": prompt.request.contextRevision,
        ]
        if let question = prompt.request.question { fields["question"] = question }
        let body = try await request(fields, credential: credential)
        guard let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              Set(object.keys) == ["ok", "guidance"], object["ok"] as? Bool == true,
              let guidance = object["guidance"] as? [String: Any],
              Set(guidance.keys) == [
                  "schemaVersion", "policyVersion", "dataVersion", "sourceFingerprint",
                  "recipeID", "stepID", "requestID", "contextRevision",
                  "status", "answer", "sourceIDs",
              ] else {
            throw StepGuidanceError.invalidResponse
        }
        do {
            let response = try JSONDecoder().decode(
                StepGuidanceResponse.self,
                from: JSONSerialization.data(withJSONObject: guidance)
            )
            return try StepGuidanceValidator.validate(response, for: prompt)
        } catch let error as StepGuidanceError {
            throw error
        } catch {
            throw StepGuidanceError.invalidResponse
        }
    }

    private func request(_ fields: [String: Any], credential: YunlefunCredential) async throws -> Data {
        try Task.checkCancellation()
        guard let endpoint else { throw StepGuidanceError.notDeployed }
        guard credential.expiresAt > Date(), !credential.subject.isEmpty, !credential.accessToken.isEmpty,
              credential.accessToken.utf8.count <= 16_384,
              credential.accessToken.unicodeScalars.allSatisfy({ (33 ... 126).contains($0.value) }) else {
            throw StepGuidanceError.loginRequired
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 35
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
                throw StepGuidanceError.invalidResponse
            }
            let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any]
            if http.statusCode != 200 {
                throw Self.serviceError(status: http.statusCode, code: object?["code"] as? String)
            }
            guard let envelope = try? JSONDecoder().decode(ServiceEnvelope.self, from: body), envelope.ok else {
                throw StepGuidanceError.invalidResponse
            }
            return body
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError {
            if Task.isCancelled || error.code == .cancelled { throw CancellationError() }
            switch error.code {
            case .timedOut: throw StepGuidanceError.timedOut
            case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost,
                 .cannotConnectToHost, .dnsLookupFailed:
                throw StepGuidanceError.offline
            default: throw StepGuidanceError.unavailable
            }
        } catch let error as StepGuidanceError {
            throw error
        } catch {
            throw StepGuidanceError.unavailable
        }
    }

    private static func approvedEndpoint(_ url: URL) -> URL? {
        guard url.scheme?.lowercased() == "https", url.port == nil || url.port == 443,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.host?.lowercased() == "api.yunle.fun",
              url.path == "/cook-ai-guidance" else { return nil }
        return url
    }

    private static func serviceError(status: Int, code: String?) -> StepGuidanceError {
        switch (status, code) {
        case (401, "LOGIN_REQUIRED"): return .loginRequired
        case (403, "MEMBERSHIP_REQUIRED"): return .membershipRequired
        case (403, "ACCOUNT_UNAVAILABLE"): return .unavailable
        case (404, _), (503, "GUIDANCE_NOT_DEPLOYED"): return .notDeployed
        case (409, "VERSION_MISMATCH"), (409, "SOURCE_MISMATCH"), (409, "SOURCE_NOT_FOUND"):
            return .sourceMismatch
        case (429, "RATE_LIMITED"): return .rateLimited
        case (429, "BUDGET_EXHAUSTED"): return .budgetExhausted
        case (503, "SERVICE_UNAVAILABLE"), (503, "GUIDANCE_UNAVAILABLE"):
            return .unavailable
        case (502, "INVALID_MODEL_RESPONSE"): return .invalidResponse
        case (504, "MODEL_TIMEOUT"): return .timedOut
        case (500 ... 599, nil): return .unavailable
        default: return .invalidResponse
        }
    }

    private static func sendLive(_ request: URLRequest) async throws -> (Data, URLResponse) {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieStorage = nil
        config.urlCredentialStorage = nil
        config.urlCache = nil
        config.timeoutIntervalForRequest = 35
        config.timeoutIntervalForResource = 40
        let session = URLSession(configuration: config, delegate: StepGuidanceRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (bytes, response) = try await session.bytes(for: request)
        var body = Data()
        for try await byte in bytes {
            try Task.checkCancellation()
            guard body.count < 65_536 else { throw StepGuidanceError.invalidResponse }
            body.append(byte)
        }
        return (body, response)
    }

    private struct ServiceEnvelope: Decodable { let ok: Bool }
    private struct CapabilityWire: Decodable {
        struct Membership: Decodable { let isActive: Bool; let expireAt: Double? }
        let ok: Bool
        let serviceVersion: Int
        let enabled: Bool
        let membership: Membership
    }
}

/// Reusable runtime mode for UI verification. Production always uses live transports.
struct StepGuidanceRuntime: Sendable {
    let personal: DeepSeekStepGuidanceService
    let managed: any ManagedStepGuidanceServing

    static var current: Self {
        #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-ui-testing-step-guidance") {
                return Self(
                    personal: DeepSeekStepGuidanceService(transport: { request in
                        try await StepGuidanceUITestFixture.respond(request)
                    }),
                    managed: ManagedStepGuidanceService(endpoint: nil)
                )
            }
        #endif
        return Self(personal: DeepSeekStepGuidanceService(), managed: ManagedStepGuidanceService())
    }
}

#if DEBUG
    private enum StepGuidanceUITestFixture {
        static func respond(_ request: URLRequest) async throws -> (Data, URLResponse) {
            guard let body = request.httpBody,
                  let object = try JSONSerialization.jsonObject(with: body) as? [String: Any],
                  let messages = object["messages"] as? [[String: String]],
                  let system = messages.first(where: { $0["role"] == "system" })?["content"],
                  let user = messages.first(where: { $0["role"] == "user" })?["content"],
                  let source = fixtureSource(from: system) else {
                throw URLError(.cannotParseResponse)
            }
            let isQuestion = user != "请讲解当前步骤，说明操作要点，但不要替我执行或勾选。"
            let draft: [String: Any] = [
                "status": "answer",
                "answer": isQuestion
                    ? "你的问题只按当前步骤原文核对；原文没有说明的地方，请以原方为准。"
                    : "先把锅烧热，再按原文加入食用油；看到油热后再继续下一步。",
                "sourceIDs": [source],
            ]
            let content = String(decoding: try JSONSerialization.data(withJSONObject: draft), as: UTF8.self)
            let envelope: [String: Any] = [
                "choices": [[
                    "finish_reason": "stop",
                    "message": ["role": "assistant", "content": content],
                ]],
            ]
            return (
                try JSONSerialization.data(withJSONObject: envelope),
                HTTPURLResponse(url: DeepSeekStepGuidanceService.endpoint, statusCode: 200,
                                httpVersion: nil, headerFields: nil)!
            )
        }

        /// The local fixture reads only the current step source emitted in the system prompt.
        private static func fixtureSource(from system: String) -> String? {
            guard let marker = system.range(of: "SOURCE DATA: "),
                  let data = String(system[marker.upperBound...]).data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let stepID = object["stepID"] as? String else { return nil }
            return "step:\(stepID)"
        }
    }
#endif

private final class StepGuidanceRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
