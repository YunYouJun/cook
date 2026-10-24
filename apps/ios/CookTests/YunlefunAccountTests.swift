import CryptoKit
import Foundation
import XCTest
@testable import Cook

private func callback(_ transaction: YunlefunSSO, changes: [String: Any] = [:], redirect: String = YunlefunSSO.redirectURI) throws -> URL {
    var payload: [String: Any] = ["nonce": transaction.nonce, "iss": YunlefunSSO.issuer, "ok": true,
                                  "code": String(repeating: "a", count: 43)]
    payload.merge(changes) { _, new in new }
    let encoded = try JSONSerialization.data(withJSONObject: payload).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    return URL(string: redirect + "#ylf_sso=" + encoded)!
}

final class YunlefunSSOTests: XCTestCase {
    func testRequestUsesRegistryBindingAndS256() throws {
        let transaction = try YunlefunSSO()
        let url = URLComponents(url: transaction.authorizationURL, resolvingAgainstBaseURL: false)!
        let query = Dictionary(uniqueKeysWithValues: url.queryItems!.map { ($0.name, $0.value!) })
        XCTAssertEqual(url.scheme, "https")
        XCTAssertEqual(url.host, "www.yunle.fun")
        XCTAssertEqual(query["client_id"], "cook-mobile")
        XCTAssertEqual(query["redirect_uri"], YunlefunSSO.redirectURI)
        XCTAssertEqual(query["scope"], "identity:bootstrap")
        XCTAssertEqual(query["code_challenge_method"], "S256")
        let challenge = Data(SHA256.hash(data: Data(transaction.verifier.utf8))).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        XCTAssertEqual(query["code_challenge"], challenge)
        XCTAssertNotEqual(query["code_challenge"], transaction.verifier)
        XCTAssertEqual(transaction.nonce.count, 43)
        XCTAssertNotEqual(transaction.nonce, try YunlefunSSO().nonce)
    }

    func testValidCallbackOnlyExchangesCodeAndOriginalBinding() throws {
        let transaction = try YunlefunSSO()
        let fields = try transaction.exchangeFields(callback: callback(transaction))
        XCTAssertEqual(fields["clientId"], "cook-mobile")
        XCTAssertEqual(fields["codeVerifier"], transaction.verifier)
        XCTAssertEqual(fields["nonce"], transaction.nonce)
        XCTAssertEqual(fields["redirectUri"], YunlefunSSO.redirectURI)
        XCTAssertNil(fields["uid"])
    }

    func testRejectsWrongOriginQueryIssuerNonceAndEmbeddedTokens() throws {
        let transaction = try YunlefunSSO()
        for redirect in ["http://cook.yunyoujun.cn/auth/callback?platform=native",
                         "https://evil.example/auth/callback?platform=native",
                         "https://cook.yunyoujun.cn/auth/callback",
                         "https://cook.yunyoujun.cn/auth/callback?platform=native&extra=1"] {
            XCTAssertThrowsError(try transaction.exchangeFields(callback: callback(transaction, redirect: redirect)))
        }
        for changes: [String: Any] in [["nonce": "wrong"], ["iss": "https://evil.example"], ["code": "short"],
                                       ["access_token": "secret"], ["refresh_token": "secret"], ["ticket": "secret"], ["session": [:]]] {
            XCTAssertThrowsError(try transaction.exchangeFields(callback: callback(transaction, changes: changes)))
        }
    }

    func testRejectsExpiredFutureAndPreviousAttemptCallbacks() throws {
        let now = Date()
        let transaction = try YunlefunSSO(now: now)
        let url = try callback(transaction)
        XCTAssertThrowsError(try transaction.exchangeFields(callback: url, now: now.addingTimeInterval(600)))
        XCTAssertThrowsError(try transaction.exchangeFields(callback: url, now: now.addingTimeInterval(-1)))
        XCTAssertThrowsError(try YunlefunSSO().exchangeFields(callback: url))
        XCTAssertThrowsError(try transaction.exchangeFields(callback: URL(string: url.absoluteString + "&ylf_sso=duplicate")!))
    }

    func testDeniedAuthorizationIsCancellation() throws {
        let transaction = try YunlefunSSO()
        XCTAssertThrowsError(try transaction.exchangeFields(callback: callback(transaction, changes: ["ok": false, "reason": "access_denied"]))) {
            XCTAssertTrue($0 is CancellationError)
        }
    }
}

@MainActor
private final class AccountServiceStub: YunlefunAccountServing {
    var credential = YunlefunCredential(accessToken: "access", refreshToken: "refresh", subject: "user-1",
                                       expiresAt: Date().addingTimeInterval(3600), deviceID: "installation")
    var user = YunlefunAccount(id: "user-1", name: "云游君", username: nil, status: "ACTIVE", isAnonymous: false)
    var refreshCount = 0
    var signInCount = 0
    var signOutCount = 0
    var failure: Error?
    var profileFailure: Error?
    func signIn(fields: [String: String], deviceID: String) async throws -> YunlefunCredential {
        signInCount += 1
        if let failure { throw failure }
        return credential
    }
    func refresh(_ credential: YunlefunCredential) async throws -> YunlefunCredential {
        refreshCount += 1
        if let failure { throw failure }
        return self.credential
    }
    func account(_ credential: YunlefunCredential) async throws -> YunlefunAccount {
        if let profileFailure { throw profileFailure }
        if let failure { throw failure }
        return user
    }
    func signOut(_ credential: YunlefunCredential) async throws {
        signOutCount += 1
        if let failure { throw failure }
    }
}

@MainActor
private final class AuthorizationStub: YunlefunAuthorizing {
    var cancelled = false
    var invalid = false
    func authorize(_ transaction: YunlefunSSO) async throws -> URL {
        if cancelled { throw CancellationError() }
        return try callback(transaction, changes: invalid ? ["nonce": "forged"] : [:])
    }
    func cancel() { cancelled = true }
}

@MainActor
private final class FailingAccountVault: YunlefunAccountVault {
    var credential: YunlefunCredential?
    var failSave = false
    var failClear = false
    func load() throws -> YunlefunCredential? { credential }
    func save(_ credential: YunlefunCredential) throws {
        if failSave { throw YunlefunAccountError.secureStorage }
        self.credential = credential
    }
    func clear() throws {
        if failClear { throw YunlefunAccountError.secureStorage }
        credential = nil
    }
}

@MainActor
final class YunlefunAccountStoreTests: XCTestCase {
    func testKeychainFailuresDoNotReportSuccessfulLoginOrLogout() async {
        let service = AccountServiceStub()
        let vault = FailingAccountVault()
        vault.failSave = true
        let store = YunlefunAccountStore(service: service, vault: vault, authorization: AuthorizationStub())
        await store.signIn()
        XCTAssertNil(store.account)
        XCTAssertNil(vault.credential)
        XCTAssertNotNil(store.message)
        vault.failSave = false
        await store.signIn()
        vault.failClear = true
        await store.signOut()
        XCTAssertNotNil(store.account)
        XCTAssertNotNil(vault.credential)
        XCTAssertEqual(service.signOutCount, 0)
    }

    func testSignInPersistsSessionAndSignOutClearsOnlyAccount() async {
        let service = AccountServiceStub()
        let vault = MemoryYunlefunAccountVault()
        let store = YunlefunAccountStore(service: service, vault: vault, authorization: AuthorizationStub())
        await store.restore()
        XCTAssertNil(store.account)
        await store.signIn()
        XCTAssertEqual(store.account?.id, "user-1")
        XCTAssertEqual(vault.credential, service.credential)
        await store.signOut()
        XCTAssertNil(vault.credential)
        XCTAssertNil(store.account)
        XCTAssertEqual(service.signOutCount, 1)
    }

    func testCancelledOrForgedAuthorizationNeverExchangesCode() async {
        for cancel in [true, false] {
            let service = AccountServiceStub()
            let browser = AuthorizationStub()
            browser.cancelled = cancel
            browser.invalid = !cancel
            let vault = MemoryYunlefunAccountVault()
            let store = YunlefunAccountStore(service: service, vault: vault, authorization: browser)
            await store.signIn()
            XCTAssertEqual(service.signInCount, 0)
            XCTAssertNil(vault.credential)
            XCTAssertNil(store.account)
            XCTAssertEqual(store.message == nil, cancel)
            XCTAssertFalse(store.isBusy)
        }
    }

    func testRestoreRefreshesAndSavesRotatedTokenBeforeProfileRequest() async {
        let service = AccountServiceStub()
        service.profileFailure = URLError(.notConnectedToInternet)
        let vault = MemoryYunlefunAccountVault()
        vault.credential = YunlefunCredential(accessToken: "old", refreshToken: "old-refresh", subject: "user-1",
                                             expiresAt: .distantPast, deviceID: "installation")
        let store = YunlefunAccountStore(service: service, vault: vault, authorization: AuthorizationStub())
        await store.restore()
        XCTAssertEqual(service.refreshCount, 1)
        XCTAssertEqual(vault.credential, service.credential)
        XCTAssertNil(store.account)
        XCTAssertTrue(store.canRetry)
        service.profileFailure = nil
        await store.retry()
        XCTAssertEqual(service.refreshCount, 1)
        XCTAssertEqual(store.account?.id, "user-1")
    }

    func testExpiredSessionIsClearedButNetworkFailureCanRetry() async {
        for expired in [true, false] {
            let service = AccountServiceStub()
            service.failure = expired ? YunlefunAccountError.expiredSession : YunlefunAccountError.unavailable
            let vault = MemoryYunlefunAccountVault()
            vault.credential = service.credential
            let store = YunlefunAccountStore(service: service, vault: vault, authorization: AuthorizationStub())
            await store.restore()
            XCTAssertEqual(vault.credential == nil, expired)
            XCTAssertEqual(store.canRetry, !expired)
            XCTAssertNil(store.account)
        }
    }

    func testSignOutClearsLocalCredentialEvenWhenServerIsOffline() async {
        let service = AccountServiceStub()
        let vault = MemoryYunlefunAccountVault()
        vault.credential = service.credential
        let store = YunlefunAccountStore(service: service, vault: vault, authorization: AuthorizationStub())
        await store.restore()
        service.failure = URLError(.notConnectedToInternet)
        await store.signOut()
        XCTAssertNil(vault.credential)
        XCTAssertNil(store.account)
        XCTAssertNotNil(store.message)
    }
}

private actor AccountTransportStub {
    var requests: [URLRequest] = []
    var responses: [(Int, String)]
    init(_ responses: [(Int, String)]) { self.responses = responses }
    func send(_ request: URLRequest) throws -> (Data, URLResponse) {
        requests.append(request)
        let (status, body) = responses.removeFirst()
        return (Data(body.utf8), HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}

@MainActor
final class YunlefunAccountServiceTests: XCTestCase {
    func testRefreshCannotSwitchToAnotherSubject() async {
        let transport = AccountTransportStub([(200, #"{"access_token":"access","refresh_token":"refresh","sub":"other-user","expires_in":7200}"#)])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        do {
            _ = try await service.refresh(AccountServiceStub().credential)
            XCTFail("Refresh switched identity")
        } catch { XCTAssertTrue(error is YunlefunAccountError) }
        let requests = await transport.requests
        XCTAssertEqual(requests.first?.url?.path, "/auth/v1/token")
    }

    func testExchangeAndCustomSignInUseDocumentedEndpoints() async throws {
        let transport = AccountTransportStub([
            (200, #"{"ok":true,"ticket":"single-use-ticket"}"#),
            (200, #"{"access_token":"access","refresh_token":"refresh","sub":"user-1","expires_in":7200}"#),
            (200, #"{"sub":"user-1","name":"云游君","status":"ACTIVE"}"#),
        ])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        let credential = try await service.signIn(fields: ["action": "exchangeSsoCode", "code": "one-time-code"], deviceID: "installation")
        let account = try await service.account(credential)
        XCTAssertEqual(account.id, "user-1")
        let requests = await transport.requests
        XCTAssertEqual(requests[0].url?.absoluteString, "https://api.yunle.fun/sso-ticket")
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Origin"), "https://cook.yunyoujun.cn")
        XCTAssertNil(requests[0].value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(requests[1].url?.path, "/auth/v1/signin/custom")
        let body = try JSONSerialization.jsonObject(with: requests[1].httpBody!) as! [String: String]
        XCTAssertEqual(body, ["provider_id": "custom", "ticket": "single-use-ticket"])
        XCTAssertEqual(requests[2].httpMethod, "GET")
        XCTAssertEqual(requests[2].value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertEqual(requests[2].value(forHTTPHeaderField: "x-device-id"), "installation")
    }

    func testRejectsIdentityMismatchAnonymousAndDisabledUsers() async {
        for body in [#"{"sub":"other","status":"ACTIVE"}"#,
                     #"{"sub":"user-1","status":"DISABLED"}"#,
                     #"{"sub":"user-1","status":"ACTIVE","is_anonymous":true}"#] {
            let transport = AccountTransportStub([(200, body)])
            let service = YunlefunAccountService(transport: { try await transport.send($0) })
            do {
                _ = try await service.account(AccountServiceStub().credential)
                XCTFail("Invalid identity accepted")
            } catch { XCTAssertTrue(error is YunlefunAccountError) }
        }
    }

    func testRejectsRedirectsAndUnexpectedSessionInExchangeResponse() async {
        for response in [(302, #"{"ok":true,"ticket":"ticket"}"#),
                         (200, #"{"ok":true,"ticket":"ticket","access_token":"secret"}"#)] {
            let transport = AccountTransportStub([response])
            let service = YunlefunAccountService(transport: { try await transport.send($0) })
            do {
                _ = try await service.signIn(fields: [:], deviceID: "installation")
                XCTFail("Unexpected response accepted")
            } catch { XCTAssertTrue(error is YunlefunAccountError) }
            let requests = await transport.requests
            XCTAssertEqual(requests.count, 1)
        }
    }
}
