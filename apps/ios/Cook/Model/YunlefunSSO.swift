import CryptoKit
import Foundation
import Security

/// 与 @yunlefun/sso 的 SSO v3 协议一致；不接收 URL 中的会话或长期凭据。
struct YunlefunSSO {
    static let issuer = "https://www.yunle.fun"
    static let clientID = "cook-mobile"
    static let redirectURI = "https://cook.yunyoujun.cn/auth/callback?platform=native"
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
              code.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil else {
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
