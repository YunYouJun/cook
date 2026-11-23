import CryptoKit
import Darwin
import Foundation
import Security

/// 与 @yunlefun/sso 的 SSO v3 协议一致；不接收 URL 中的会话或长期凭据。
struct YunlefunSSO {
    static let issuer = "https://www.yunle.fun"
    static let clientID = "cook-mobile"
    static let callbackHost = "cook.yunyoujun.cn"
    static let callbackPath = "/auth/callback"
    static let redirectURI = "https://\(callbackHost)\(callbackPath)?platform=native"
    static let scope = "identity:bootstrap"
    static let lifetime: TimeInterval = 600

    let nonce: String
    let verifier: String
    let createdAt: Date

    init(now: Date = Date()) throws {
        nonce = try Self.randomToken()
        verifier = try Self.randomToken()
        createdAt = now
    }

    var authorizationURL: URL { authorizationURL(path: "/auth/sso") }
    var appAuthorizationURL: URL { authorizationURL(path: "/auth/app") }

    private func authorizationURL(path: String) -> URL {
        var url = URLComponents(string: Self.issuer + path)!
        url.queryItems = [
            URLQueryItem(name: "client_id", value: Self.clientID),
            URLQueryItem(name: "redirect_uri", value: Self.redirectURI),
            URLQueryItem(name: "scope", value: Self.scope),
            URLQueryItem(name: "nonce", value: nonce),
            URLQueryItem(name: "code_challenge", value: Data(SHA256.hash(data: Data(verifier.utf8))).base64URL),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "prompt", value: "select_account"),
        ]
        return url.url!
    }

    static func isCallback(_ url: URL) -> Bool {
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        parts.fragment = nil
        return parts.string == redirectURI
    }

    enum Callback {
        case authorized([String: String])
        case denied
        case selectAccountOnWeb
    }

    func callbackResult(_ callback: URL, now: Date = Date()) throws -> Callback {
        guard now >= createdAt, now.timeIntervalSince(createdAt) < Self.lifetime,
              Self.isCallback(callback), let fragment = callback.fragment,
              fragment.hasPrefix("ylf_sso="), fragment.count < 4096 else {
            throw YunlefunAccountError.invalidCallback
        }
        let encoded = String(fragment.dropFirst("ylf_sso=".count))
        guard let data = Data(base64URL: encoded),
              let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(payload.keys).isSubset(of: ["nonce", "iss", "ok", "code", "reason"]),
              payload["nonce"] as? String == nonce, payload["iss"] as? String == Self.issuer,
              let ok = payload["ok"] as? Bool else { throw YunlefunAccountError.invalidCallback }
        guard ok else {
            guard payload["code"] == nil else { throw YunlefunAccountError.invalidCallback }
            if payload["reason"] as? String == "access_denied" { return .denied }
            if payload["reason"] as? String == "account_selection_required" { return .selectAccountOnWeb }
            throw YunlefunAccountError.authorizationFailed
        }
        guard payload["reason"] == nil, let code = payload["code"] as? String,
              code.range(of: #"\A[A-Za-z0-9_-]{43}\z"#, options: .regularExpression) != nil else {
            throw YunlefunAccountError.invalidCallback
        }
        return .authorized(["action": "exchangeSsoCode", "clientId": Self.clientID,
                            "redirectUri": Self.redirectURI, "scope": Self.scope,
                            "code": code, "nonce": nonce, "codeVerifier": verifier])
    }

    func exchangeFields(callback: URL, now: Date = Date()) throws -> [String: String] {
        switch try callbackResult(callback, now: now) {
        case .authorized(let fields): return fields
        case .denied: throw CancellationError()
        case .selectAccountOnWeb: throw YunlefunAccountError.authorizationFailed
        }
    }

    static func randomToken() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw YunlefunAccountError.secureStorage
        }
        return Data(bytes).base64URL
    }
}

/// The browser receives only a hash of the App verifier. Safari's initial redirect
/// supplies the binding to its own, separate HttpOnly cookie.
struct YunlefunAccountWebHandoff {
    static let endpoint = URL(string: "\(YunlefunSSO.issuer)/api/session/cook-handoff")!
    static let lifetime: TimeInterval = 45

    enum Transport {
        case inAppSafari
        case systemBrowser
    }

    struct BrowserCompletion {
        let approvalFields: [String: String]
        let destination: BrowserDestination
    }

    struct BrowserDestination: Codable, Equatable {
        let request: String
        let binding: String

        var url: URL? {
            guard YunlefunAccountWebHandoff.isBinding(request),
                  YunlefunAccountWebHandoff.isBinding(binding) else { return nil }
            var page = URLComponents(string: YunlefunSSO.issuer + "/auth/cook-account")!
            page.queryItems = [.init(name: "request", value: request), .init(name: "binding", value: binding)]
            return page.url
        }
    }

    struct Validity: Codable, Equatable {
        let createdAt: Date
        let startedAt: UInt64

        init(now: Date, uptime: UInt64 = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)) {
            createdAt = now
            startedAt = uptime
        }

        func remaining(at date: Date = .now, uptime: UInt64 = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)) -> TimeInterval {
            let wallAge = date.timeIntervalSince(createdAt)
            guard wallAge >= 0, uptime >= startedAt else { return 0 }
            let elapsed = TimeInterval(uptime - startedAt) / 1_000_000_000
            return max(0, YunlefunAccountWebHandoff.lifetime - max(wallAge, elapsed))
        }
    }

    private let verifier: String
    private let request: String
    let validity: Validity

    init(now: Date = Date()) throws {
        verifier = try YunlefunSSO.randomToken()
        request = Data(SHA256.hash(data: Data(verifier.utf8))).base64URL
        validity = Validity(now: now)
    }

    var startURL: URL {
        startURL(transport: .inAppSafari)
    }

    var systemBrowserStartURL: URL {
        startURL(transport: .systemBrowser)
    }

    private func startURL(transport: Transport) -> URL {
        var parts = URLComponents(url: Self.endpoint, resolvingAgainstBaseURL: false)!
        parts.queryItems = [.init(name: "request", value: request)]
        if transport == .systemBrowser {
            parts.queryItems?.append(.init(name: "transport", value: "browser"))
        }
        return parts.url!
    }

    func approvalFields(for redirect: URL, now: Date = Date()) -> [String: String]? {
        let prefix = "\(Self.endpoint.path)/\(request)/"
        guard isCurrent(at: now),
              let parts = URLComponents(url: redirect, resolvingAgainstBaseURL: false),
              parts.scheme == "https", parts.host == Self.endpoint.host,
              parts.user == nil, parts.password == nil, parts.port == nil, parts.fragment == nil, parts.query == nil,
              parts.percentEncodedPath.hasPrefix(prefix) else { return nil }
        let binding = String(parts.percentEncodedPath.dropFirst(prefix.count))
        guard Self.isBinding(binding) else { return nil }
        return approvalFields(binding: binding)
    }

    /// The system authentication window closes on this HTTPS callback. Resume on
    /// the original website so it can redeem using the same browser's HttpOnly cookie.
    func systemBrowserCompletion(for callback: URL, now: Date = Date()) -> BrowserCompletion? {
        let prefix = "cook_handoff=\(request)."
        guard isCurrent(at: now), YunlefunSSO.isCallback(callback),
              let fragment = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.percentEncodedFragment,
              fragment.hasPrefix(prefix) else { return nil }
        let binding = String(fragment.dropFirst(prefix.count))
        guard Self.isBinding(binding) else { return nil }
        return BrowserCompletion(approvalFields: approvalFields(binding: binding),
                                 destination: BrowserDestination(request: request, binding: binding))
    }

    func isCurrent(at date: Date = .now) -> Bool {
        validity.remaining(at: date) > 0
    }

    private static func isBinding(_ value: String) -> Bool {
        value.range(of: #"\A[a-zA-Z0-9_-]{43}\z"#, options: .regularExpression) != nil
    }

    private func approvalFields(binding: String) -> [String: String] {
        return ["action": "approveCookWebHandoff", "clientId": YunlefunSSO.clientID,
                "verifier": verifier, "binding": binding]
    }
}

extension Data {
    fileprivate var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    fileprivate init?(base64URL: String) {
        guard !base64URL.isEmpty,
              base64URL.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil else { return nil }
        let value = base64URL.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        self.init(base64Encoded: value + String(repeating: "=", count: (4 - value.count % 4) % 4))
    }
}
