import CryptoKit
import Foundation
import XCTest
@testable import Cook

private func callback(_ transaction: YunlefunSSO, changes: [String: Any] = [:], redirect: String = YunlefunSSO.redirectURI) throws -> URL {
    var payload: [String: Any] = ["nonce": transaction.nonce, "iss": YunlefunSSO.issuer, "ok": true,
                                  "code": String(repeating: "a", count: 43)]
    payload.merge(changes) { _, new in new }
    if payload["ok"] as? Bool == false, changes["code"] == nil {
        payload.removeValue(forKey: "code")
    }
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
        XCTAssertEqual(url.path, "/auth/sso")
        let app = URLComponents(url: transaction.appAuthorizationURL, resolvingAgainstBaseURL: false)!
        XCTAssertEqual(app.host, url.host)
        XCTAssertEqual(app.path, "/auth/app")
        XCTAssertEqual(app.queryItems, url.queryItems)
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

    func testWebsiteHandoffOnlyApprovesItsOwnFirstPartyBrowserRedirect() throws {
        let now = Date()
        let handoff = try YunlefunAccountWebHandoff(now: now)
        let start = try XCTUnwrap(URLComponents(url: handoff.startURL, resolvingAgainstBaseURL: false))
        let challenge = try XCTUnwrap(start.queryItems?.first?.value)
        let binding = String(repeating: "b", count: 43)
        let path = "/api/session/cook-handoff/\(challenge)/\(binding)"
        let authorized = URL(string: "https://www.yunle.fun" + path)!
        let fields = try XCTUnwrap(handoff.approvalFields(for: authorized, now: now))
        let verifier = try XCTUnwrap(fields["verifier"])
        let expected = Data(SHA256.hash(data: Data(verifier.utf8))).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        XCTAssertEqual(challenge, expected)
        XCTAssertEqual(fields, ["action": "approveCookWebHandoff", "clientId": "cook-mobile",
                                "verifier": verifier, "binding": binding])
        XCTAssertFalse(handoff.startURL.absoluteString.contains(verifier))
        XCTAssertNil(handoff.approvalFields(for: authorized, now: now.addingTimeInterval(45)))
        XCTAssertNil(try YunlefunAccountWebHandoff(now: now).approvalFields(for: authorized, now: now))
        for invalid in ["https://elsewhere.example" + path,
                        authorized.absoluteString + "?next=/settings",
                        authorized.absoluteString + "#fragment",
                        authorized.absoluteString + "/" + binding] {
            XCTAssertNil(handoff.approvalFields(for: URL(string: invalid)!, now: now))
        }
    }

    func testSystemBrowserHandoffOnlyResumesTheBrowserBoundToThisAttempt() throws {
        let now = Date()
        let handoff = try YunlefunAccountWebHandoff(now: now)
        let start = try XCTUnwrap(URLComponents(url: handoff.systemBrowserStartURL, resolvingAgainstBaseURL: false))
        let request = try XCTUnwrap(start.queryItems?.first(where: { $0.name == "request" })?.value)
        XCTAssertEqual(start.queryItems?.first(where: { $0.name == "transport" })?.value, "browser")
        let binding = String(repeating: "b", count: 43)
        let callback = "\(YunlefunSSO.redirectURI)#cook_handoff=\(request).\(binding)"
        let result = try XCTUnwrap(handoff.systemBrowserCompletion(for: URL(string: callback)!, now: now))
        let verifier = try XCTUnwrap(result.approvalFields["verifier"])
        let safari = URL(string: "https://www.yunle.fun/api/session/cook-handoff/\(request)/\(binding)")!
        XCTAssertEqual(result.approvalFields, handoff.approvalFields(for: safari, now: now))
        let url = try XCTUnwrap(result.destination.url)
        XCTAssertEqual(url.absoluteString, "https://www.yunle.fun/auth/cook-account?request=\(request)&binding=\(binding)")
        XCTAssertFalse(start.string!.contains(verifier))
        XCTAssertFalse(url.absoluteString.contains(verifier))
        XCTAssertNil(handoff.approvalFields(for: URL(string: callback)!, now: now))
        XCTAssertNil(handoff.systemBrowserCompletion(for: safari, now: now))
        XCTAssertNil(handoff.systemBrowserCompletion(for: URL(string: callback)!, now: now.addingTimeInterval(45)))
        XCTAssertNil(try YunlefunAccountWebHandoff(now: now).systemBrowserCompletion(for: URL(string: callback)!, now: now))
        for invalid in [
            callback.replacingOccurrences(of: "cook.yunyoujun.cn", with: "elsewhere.example"),
            callback.replacingOccurrences(of: "platform=native", with: "platform=native&next=https://elsewhere.example"),
            callback + ".extra",
            callback.replacingOccurrences(of: "cook_handoff=", with: "ylf_sso="),
        ] {
            XCTAssertNil(handoff.systemBrowserCompletion(for: URL(string: invalid)!, now: now))
        }
    }

    func testWebsiteHandoffDeadlineCannotBeExtendedByChangingTheSystemClock() {
        let now = Date()
        let validity = YunlefunAccountWebHandoff.Validity(now: now, uptime: 60_000_000_000)
        XCTAssertEqual(validity.remaining(at: now.addingTimeInterval(10), uptime: 80_000_000_000), 25)
        XCTAssertEqual(validity.remaining(at: now.addingTimeInterval(10), uptime: 105_000_000_000), 0)
        XCTAssertEqual(validity.remaining(at: now.addingTimeInterval(45), uptime: 80_000_000_000), 0)
        XCTAssertEqual(validity.remaining(at: now.addingTimeInterval(-1), uptime: 80_000_000_000), 0)
        XCTAssertEqual(validity.remaining(at: now, uptime: 1_000_000_000), 0)
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
    var onRefresh: (() -> Void)?
    var websiteApprovals: [([String: String], YunlefunCredential)] = []
    var onWebsiteApproval: (() async throws -> Void)?
    var weChatSignInCount = 0
    var weChatBindingCount = 0
    var boundSubject: String?
    func signIn(fields: [String: String], deviceID: String) async throws -> YunlefunCredential {
        signInCount += 1
        if let failure { throw failure }
        return credential
    }
    func refresh(_ credential: YunlefunCredential) async throws -> YunlefunCredential {
        refreshCount += 1
        if let failure { throw failure }
        onRefresh?()
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
    func signInWithWeChat(code: String, deviceID: String) async throws -> YunlefunCredential {
        weChatSignInCount += 1
        if let failure { throw failure }
        return credential
    }
    func bindWeChat(code: String, credential: YunlefunCredential) async throws {
        weChatBindingCount += 1
        boundSubject = credential.subject
    }
    func approveAccountWebHandoff(fields: [String: String], credential: YunlefunCredential) async throws {
        websiteApprovals.append((fields, credential))
        try await onWebsiteApproval?()
    }
}

@MainActor
private final class WeChatAuthorizationStub: WeChatAuthorizing {
    var isAvailable = true
    var cancelled = false
    var count = 0
    func authorize() async throws -> String {
        count += 1
        if cancelled { throw CancellationError() }
        return "wechat-code"
    }
    func handle(_ url: URL) -> Bool { false }
    func handle(_ activity: NSUserActivity) -> Bool { false }
}

@MainActor
private final class AuthorizationStub: YunlefunAuthorizing {
    var cancelled = false
    var invalid = false
    var transactions: [YunlefunSSO] = []
    func authorize(_ transaction: YunlefunSSO) async throws -> URL {
        transactions.append(transaction)
        if cancelled { throw CancellationError() }
        return try callback(transaction, changes: invalid ? ["nonce": "forged"] : [:])
    }
    func cancel() { cancelled = true }
}

@MainActor
final class YunlefunAppAuthorizationTests: XCTestCase {
    func testUnavailableAppFallsBackToExistingWebAuthorization() async throws {
        let service = AccountServiceStub()
        let browser = AuthorizationStub()
        var launchedURL: URL?
        let app = YunlefunAppAuthorization(openApp: { launchedURL = $0; return false })
        let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(),
                                         authorization: browser, appAuthorization: app)

        await store.signIn()

        let launch = try XCTUnwrap(launchedURL)
        let web = try XCTUnwrap(browser.transactions.first)
        XCTAssertEqual(launch.path, "/auth/app")
        XCTAssertFalse(launch.absoluteString.contains(web.nonce))
        XCTAssertEqual(browser.transactions.count, 1)
        XCTAssertEqual(service.signInCount, 1)
        XCTAssertEqual(store.account?.id, service.credential.subject)
        XCTAssertFalse(store.isAwaitingYunlefunApp)
    }

    func testAppCallbackMustMatchTheActiveAttemptAndExchangesOnlyOnce() async throws {
        let service = AccountServiceStub()
        let browser = AuthorizationStub()
        let launched = expectation(description: "Opened Yunlefun app")
        var nonce: String?
        let app = YunlefunAppAuthorization(openApp: {
            nonce = URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "nonce" }?.value
            launched.fulfill()
            return true
        })
        let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(),
                                         authorization: browser, appAuthorization: app)
        let fixture = try YunlefunSSO()
        let valid = try callback(fixture, changes: ["nonce": "replace"])
        XCTAssertTrue(store.handleYunlefunCallback(valid)) // No attempt yet.
        let task = Task { await store.signIn() }
        defer { task.cancel() }
        await fulfillment(of: [launched], timeout: 2)
        XCTAssertTrue(store.isAwaitingYunlefunApp)
        XCTAssertTrue(store.handleYunlefunCallback(valid))
        XCTAssertTrue(store.isAwaitingYunlefunApp)
        XCTAssertEqual(service.signInCount, 0)
        let response = try callback(fixture, changes: ["nonce": XCTUnwrap(nonce)])
        XCTAssertTrue(store.handleYunlefunCallback(response))
        await task.value
        XCTAssertTrue(store.handleYunlefunCallback(response))
        XCTAssertEqual(service.signInCount, 1)
        XCTAssertTrue(browser.transactions.isEmpty)
        XCTAssertEqual(store.account?.id, service.credential.subject)
        XCTAssertFalse(store.isAwaitingYunlefunApp)
    }

    func testSelectAnotherAccountUsesFreshWebAttemptWhileDenialDoesNot() async throws {
        for reason in ["account_selection_required", "access_denied"] {
            let service = AccountServiceStub()
            let browser = AuthorizationStub()
            let launched = expectation(description: "Opened app for \(reason)")
            var nonce: String?
            let app = YunlefunAppAuthorization(openApp: {
                nonce = URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "nonce" }?.value
                launched.fulfill()
                return true
            })
            let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(),
                                             authorization: browser, appAuthorization: app)
            let fixture = try YunlefunSSO()
            let task = Task { await store.signIn() }
            defer { task.cancel() }
            await fulfillment(of: [launched], timeout: 2)
            let currentNonce = try XCTUnwrap(nonce)
            let result = try callback(fixture, changes: ["nonce": currentNonce, "ok": false, "reason": reason])
            XCTAssertTrue(store.handleYunlefunCallback(result))
            await task.value
            let usedBrowser = reason == "account_selection_required"
            XCTAssertEqual(browser.transactions.count, usedBrowser ? 1 : 0)
            XCTAssertEqual(service.signInCount, usedBrowser ? 1 : 0)
            if usedBrowser { XCTAssertNotEqual(browser.transactions.first?.nonce, currentNonce) }
        }
    }

    func testManuallySwitchingToWebInvalidatesThePendingAppCallback() async throws {
        let service = AccountServiceStub()
        let browser = AuthorizationStub()
        let launched = expectation(description: "Opened app before switching to web")
        var appNonce: String?
        let app = YunlefunAppAuthorization(openApp: {
            appNonce = URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "nonce" }?.value
            launched.fulfill()
            return true
        })
        let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(),
                                         authorization: browser, appAuthorization: app)
        let task = Task { await store.signIn() }
        defer { task.cancel() }
        await fulfillment(of: [launched], timeout: 2)
        XCTAssertTrue(store.isAwaitingYunlefunApp)
        let staleCallback = try callback(YunlefunSSO(), changes: ["nonce": XCTUnwrap(appNonce)])
        store.continueYunlefunOnWeb()
        XCTAssertTrue(store.handleYunlefunCallback(staleCallback))
        await task.value
        XCTAssertFalse(store.isAwaitingYunlefunApp)
        XCTAssertEqual(browser.transactions.count, 1)
        XCTAssertNotEqual(browser.transactions.first?.nonce, appNonce)
        XCTAssertEqual(service.signInCount, 1)
    }
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
    func loadApprovedWebsite() throws -> YunlefunAccountStore.ApprovedWebsite? { nil }
    func saveApprovedWebsite(_ website: YunlefunAccountStore.ApprovedWebsite?) throws {
        throw YunlefunAccountError.secureStorage
    }
}

@MainActor
final class YunlefunAccountStoreTests: XCTestCase {
    func testRejectedBrowserLaunchRestoresAndOpensTheSameApprovedDeletionRequest() async throws {
        let service = AccountServiceStub()
        let vault = MemoryYunlefunAccountVault()
        let store = YunlefunAccountStore(service: service, vault: vault, authorization: AuthorizationStub())
        await store.signIn()
        let session = try XCTUnwrap(store.websiteSession)
        let handoff = try YunlefunAccountWebHandoff()
        let start = URLComponents(url: handoff.systemBrowserStartURL, resolvingAgainstBaseURL: false)
        let request = try XCTUnwrap(start?.queryItems?.first { $0.name == "request" }?.value)
        let binding = String(repeating: "b", count: 43)
        let callback = try XCTUnwrap(URL(string: "\(YunlefunSSO.redirectURI)#cook_handoff=\(request).\(binding)"))
        let completion = try XCTUnwrap(handoff.systemBrowserCompletion(for: callback))
        let website = try await store.approveAccountWebsiteForBrowser(completion, handoff: handoff, for: session, entry: .delete)
        XCTAssertEqual(vault.approvedWebsite, website)
        let saved = try JSONEncoder().encode(website)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: saved) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["subject", "deviceID", "entry", "destination", "validity"])
        XCTAssertEqual(Set((json["destination"] as? [String: String] ?? [:]).keys), ["request", "binding"])
        vault.approvedWebsite = try JSONDecoder().decode(YunlefunAccountStore.ApprovedWebsite.self, from: saved)

        var opened: [URL] = []
        await store.openApprovedAccountWebsite(website) { url in
            opened.append(url)
            return false
        }
        XCTAssertEqual(store.approvedWebsite, website)

        let restored = YunlefunAccountStore(service: service, vault: vault, authorization: AuthorizationStub())
        await restored.restore()
        let recovered = try XCTUnwrap(restored.approvedWebsite)
        XCTAssertEqual(recovered, website)
        XCTAssertEqual(recovered.entry, .delete)
        await restored.openApprovedAccountWebsite(recovered) { url in
            opened.append(url)
            return true
        }
        await restored.openApprovedAccountWebsite(recovered) { _ in
            XCTFail("A dispatched website must not open again")
            return true
        }
        XCTAssertEqual(opened, Array(repeating: try XCTUnwrap(completion.destination.url), count: 2))
        XCTAssertEqual(service.websiteApprovals.count, 1)
        XCTAssertNil(restored.approvedWebsite)
        XCTAssertNil(vault.approvedWebsite)
    }

    func testRestoringAnExpiredOrMismatchedBrowserApprovalDiscardsIt() async throws {
        let service = AccountServiceStub()
        let vault = MemoryYunlefunAccountVault()
        try vault.save(service.credential)
        let token = String(repeating: "b", count: 43)
        let now = Date()
        let valid = YunlefunAccountWebHandoff.Validity(now: now)
        for (subject, deviceID, binding, validity) in [
            ("other-user", service.credential.deviceID, token, valid),
            (service.credential.subject, "another-session", token, valid),
            (service.credential.subject, service.credential.deviceID, token + "\n", valid),
            (service.credential.subject, service.credential.deviceID, token,
             YunlefunAccountWebHandoff.Validity(now: now.addingTimeInterval(-46))),
        ] {
            let website = YunlefunAccountStore.ApprovedWebsite(subject: subject, deviceID: deviceID, entry: .manage,
                destination: .init(request: token, binding: binding), validity: validity)
            vault.approvedWebsite = website
            let store = YunlefunAccountStore(service: service, vault: vault, authorization: AuthorizationStub())
            await store.restore()
            XCTAssertNil(store.approvedWebsite)
            XCTAssertNil(vault.approvedWebsite)
            await store.openApprovedAccountWebsite(website) { _ in
                XCTFail("An invalid approval must not open a browser")
                return true
            }
        }
        XCTAssertTrue(service.websiteApprovals.isEmpty)
    }

    func testWebsiteApprovalRejectsACallbackFromBeforeSigningBackIntoTheSameAccount() async throws {
        let service = AccountServiceStub()
        let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(), authorization: AuthorizationStub())
        let handoff = try YunlefunAccountWebHandoff()
        await store.signIn()
        let original = try XCTUnwrap(store.websiteSession)
        await store.signOut()
        await store.signIn()
        let current = try XCTUnwrap(store.websiteSession)
        XCTAssertEqual(original.subject, current.subject)
        XCTAssertNotEqual(original, current)

        let fields = ["action": "approveCookWebHandoff"]
        do {
            try await store.approveAccountWebHandoff(fields: fields, handoff: handoff, for: original)
            XCTFail("A callback from the old session cannot approve browser access")
        } catch YunlefunAccountError.expiredSession {}
        XCTAssertTrue(service.websiteApprovals.isEmpty)
        try await store.approveAccountWebHandoff(fields: fields, handoff: YunlefunAccountWebHandoff(), for: current)
        XCTAssertEqual(service.websiteApprovals.map { $0.0 }, [fields])
    }

    func testWebsiteApprovalRetriesARejectedTokenOnlyWhileTheOriginalRequestIsCurrent() async throws {
        for expiresDuringRefresh in [false, true] {
            let service = AccountServiceStub()
            let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(), authorization: AuthorizationStub())
            await store.signIn()
            let session = try XCTUnwrap(store.websiteSession)
            let start = Date()
            var clock = start
            let handoff = try YunlefunAccountWebHandoff(now: start)
            let initial = service.credential
            let renewed = YunlefunCredential(accessToken: "renewed", refreshToken: "rotated", subject: initial.subject,
                                            expiresAt: initial.expiresAt, deviceID: initial.deviceID)
            var rejected = false
            service.onWebsiteApproval = {
                if !rejected {
                    rejected = true
                    throw YunlefunAccountError.expiredSession
                }
            }
            service.credential = renewed
            service.onRefresh = {
                if expiresDuringRefresh { clock = start.addingTimeInterval(YunlefunAccountWebHandoff.lifetime) }
            }
            let fields = ["action": "approveCookWebHandoff", "binding": "same-browser"]

            do {
                try await store.approveAccountWebHandoff(fields: fields, handoff: handoff, for: session, now: { clock })
                XCTAssertFalse(expiresDuringRefresh)
            } catch YunlefunAccountError.invalidCallback {
                XCTAssertTrue(expiresDuringRefresh)
            }
            XCTAssertEqual(service.refreshCount, 1)
            XCTAssertEqual(service.websiteApprovals.map { $0.1 }, expiresDuringRefresh ? [initial] : [initial, renewed])
            XCTAssertTrue(service.websiteApprovals.allSatisfy { $0.0 == fields })
        }
    }

    func testInterruptedWebsiteApprovalResendsTheSameBrowserRequestWithOneOptionalRenewal() async throws {
        for renewFirst in [false, true] {
            let service = AccountServiceStub()
            let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(), authorization: AuthorizationStub())
            await store.signIn()
            let session = try XCTUnwrap(store.websiteSession)
            let handoff = try YunlefunAccountWebHandoff()
            let initial = service.credential
            let renewed = YunlefunCredential(accessToken: "renewed", refreshToken: "rotated", subject: initial.subject,
                                            expiresAt: initial.expiresAt, deviceID: initial.deviceID)
            service.credential = renewed
            var failures: [Error] = renewFirst
                ? [YunlefunAccountError.expiredSession, AccountWebHandoffError.interrupted]
                : [AccountWebHandoffError.interrupted, YunlefunAccountError.expiredSession]
            service.onWebsiteApproval = {
                if !failures.isEmpty { throw failures.removeFirst() }
            }
            let fields = ["action": "approveCookWebHandoff", "binding": "same-browser", "verifier": "same-verifier"]

            try await store.approveAccountWebHandoff(fields: fields, handoff: handoff, for: session)

            XCTAssertEqual(service.websiteApprovals.map { $0.0 }, [fields, fields, fields])
            XCTAssertEqual(service.websiteApprovals.map { $0.1 }, renewFirst ? [initial, renewed, renewed] : [initial, initial, renewed])
            XCTAssertEqual(service.refreshCount, 1)
        }
    }

    func testInterruptedWebsiteApprovalStopsAfterOneRetryOrAtTheOriginalDeadline() async throws {
        for expiresDuringRequest in [false, true] {
            let service = AccountServiceStub()
            let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(), authorization: AuthorizationStub())
            await store.signIn()
            let session = try XCTUnwrap(store.websiteSession)
            let start = Date()
            var clock = start
            let handoff = try YunlefunAccountWebHandoff(now: start)
            service.onWebsiteApproval = {
                if expiresDuringRequest { clock = start.addingTimeInterval(YunlefunAccountWebHandoff.lifetime) }
                throw AccountWebHandoffError.interrupted
            }

            do {
                try await store.approveAccountWebHandoff(fields: ["action": "approveCookWebHandoff"], handoff: handoff,
                                                         for: session, now: { clock })
                XCTFail("An interrupted approval must end with an error when it cannot continue")
            } catch YunlefunAccountError.invalidCallback {
                XCTAssertTrue(expiresDuringRequest)
            } catch AccountWebHandoffError.interrupted {
                XCTAssertFalse(expiresDuringRequest)
            }
            XCTAssertEqual(service.websiteApprovals.count, expiresDuringRequest ? 1 : 2)
            XCTAssertEqual(service.refreshCount, 0)
        }
    }

    func testInterruptedWebsiteApprovalDoesNotRetryACancelledOperation() async throws {
        let service = AccountServiceStub()
        let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(), authorization: AuthorizationStub())
        await store.signIn()
        let session = try XCTUnwrap(store.websiteSession)
        let handoff = try YunlefunAccountWebHandoff()
        service.onWebsiteApproval = {
            withUnsafeCurrentTask { $0?.cancel() }
            throw AccountWebHandoffError.interrupted
        }
        let operation = Task {
            try await store.approveAccountWebHandoff(fields: ["action": "approveCookWebHandoff"], handoff: handoff, for: session)
        }
        do {
            try await operation.value
            XCTFail("Cancellation must stop the approval")
        } catch is CancellationError {}
        XCTAssertEqual(service.websiteApprovals.count, 1)
    }

    func testDelayedWebsiteApprovalCannotOpenAfterSigningBackIntoTheSameAccount() async throws {
        for interrupted in [false, true] {
            let service = AccountServiceStub()
            let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(), authorization: AuthorizationStub())
            await store.signIn()
            let session = try XCTUnwrap(store.websiteSession)
            let started = expectation(description: "website approval sent")
            let (reply, finish) = AsyncStream<Void>.makeStream()
            service.onWebsiteApproval = {
                started.fulfill()
                for await _ in reply { break }
                if interrupted { throw AccountWebHandoffError.interrupted }
            }
            let handoff = try YunlefunAccountWebHandoff()
            let operation = Task {
                try await store.approveAccountWebHandoff(fields: ["action": "approveCookWebHandoff"], handoff: handoff, for: session)
            }
            defer { finish.finish(); operation.cancel() }
            await fulfillment(of: [started], timeout: 2)
            await store.signOut()
            await store.signIn()
            finish.finish()
            do {
                try await operation.value
                XCTFail("The old browser must not continue after relogin")
            } catch YunlefunAccountError.expiredSession {}
            XCTAssertEqual(service.websiteApprovals.count, 1)
        }
    }

    func testWeChatBindingRequiresCurrentAccountAndKeepsItsSession() async {
        let service = AccountServiceStub()
        let weChat = WeChatAuthorizationStub()
        let store = YunlefunAccountStore(service: service, vault: MemoryYunlefunAccountVault(),
                                         authorization: AuthorizationStub(), weChat: weChat)
        await store.bindWeChat()
        XCTAssertEqual(weChat.count, 0)
        await store.signIn()
        await store.bindWeChat()
        XCTAssertEqual(service.weChatBindingCount, 1)
        XCTAssertEqual(service.boundSubject, "user-1")
        XCTAssertEqual(weChat.count, 1)
        XCTAssertEqual(store.account?.id, "user-1")
        await store.signOut()
        await store.signInWithWeChat()
        XCTAssertEqual(store.account?.id, "user-1")
        XCTAssertEqual(service.weChatSignInCount, 1)
    }

    func testUnlinkedWeChatAndCancelledBindingDoNotReplaceSession() async {
        let service = AccountServiceStub()
        let weChat = WeChatAuthorizationStub()
        let vault = MemoryYunlefunAccountVault()
        let store = YunlefunAccountStore(service: service, vault: vault,
                                         authorization: AuthorizationStub(), weChat: weChat)
        service.failure = WeChatAccountError.bindingRequired
        await store.signInWithWeChat()
        XCTAssertNil(vault.credential)
        XCTAssertEqual(store.message, WeChatAccountError.bindingRequired.errorDescription)
        service.failure = nil
        await store.signIn()
        weChat.cancelled = true
        let revision = store.sessionRevision
        await store.bindWeChat()
        XCTAssertEqual(store.sessionRevision, revision)
        XCTAssertEqual(vault.credential, service.credential)
        XCTAssertEqual(service.weChatBindingCount, 0)
        XCTAssertNil(store.message)
    }
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
    func testWebsiteApprovalDistinguishesTemporaryFailuresFromDefinitiveRejections() async throws {
        let responses: [(Int, String, Bool)] = [
            (408, "{}", true),
            (503, #"{"reason":"identity_unavailable"}"#, true),
            (504, "{}", true),
            (409, #"{"reason":"handoff_already_approved"}"#, false),
            (429, #"{"reason":"rate_limited"}"#, false),
            (503, #"{"reason":"not_configured"}"#, false),
        ]
        let transport = AccountTransportStub(responses.map { ($0.0, $0.1) })
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        let fields = ["action": "approveCookWebHandoff", "clientId": "cook-mobile"]
        for (status, _, retryable) in responses {
            do {
                try await service.approveAccountWebHandoff(fields: fields, credential: AccountServiceStub().credential)
                XCTFail("HTTP \(status) did not approve the browser")
            } catch AccountWebHandoffError.interrupted {
                XCTAssertTrue(retryable, "HTTP \(status)")
            } catch YunlefunAccountError.unavailable {
                XCTAssertFalse(retryable, "HTTP \(status)")
            }
        }
    }

    func testWebsiteApprovalClassifiesLostConnectionAndPreservesTLSAndCancellationErrors() async throws {
        for code: URLError.Code in [.networkConnectionLost, .serverCertificateUntrusted, .cancelled] {
            let service = YunlefunAccountService(transport: { _ in throw URLError(code) })
            do {
                try await service.approveAccountWebHandoff(fields: ["action": "approveCookWebHandoff"],
                                                           credential: AccountServiceStub().credential)
                XCTFail("A transport error is not an approval")
            } catch AccountWebHandoffError.interrupted {
                XCTAssertEqual(code, .networkConnectionLost)
            } catch let error as URLError {
                XCTAssertNotEqual(code, .networkConnectionLost)
                XCTAssertEqual(error.code, code)
            }
        }
    }

    func testWebsiteHandoffCapabilityDoesNotSendCredentialsAndApprovalUsesCurrentBearer() async throws {
        let transport = AccountTransportStub([(200, #"{"supported":true}"#), (200, #"{"ok":true}"#)])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        let supported = try await service.supportsAccountWebHandoff()
        XCTAssertTrue(supported)
        let fields = ["action": "approveCookWebHandoff", "clientId": "cook-mobile", "verifier": "verifier", "binding": "browser"]
        try await service.approveAccountWebHandoff(fields: fields, credential: AccountServiceStub().credential)
        let requests = await transport.requests
        XCTAssertEqual(requests[0].url?.absoluteString, "https://www.yunle.fun/api/session/cook-handoff")
        XCTAssertEqual(requests[0].httpMethod, "GET")
        XCTAssertNil(requests[0].value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(requests[1].url?.absoluteString, "https://api.yunle.fun/sso-ticket")
        XCTAssertEqual(requests[1].value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertEqual(requests[1].value(forHTTPHeaderField: "x-device-id"), "installation")
        XCTAssertEqual(try JSONDecoder().decode([String: String].self, from: XCTUnwrap(requests[1].httpBody)), fields)
    }

    func testOnlyMissingWebsiteCapabilityFallsBackToTheLegacyPage() async throws {
        let transport = AccountTransportStub([(404, "{}"), (503, "{}")])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        let supported = try await service.supportsAccountWebHandoff()
        XCTAssertFalse(supported)
        do {
            _ = try await service.supportsAccountWebHandoff()
            XCTFail("A transient outage must not open a possibly different browser account")
        } catch let error as YunlefunAccountError {
            guard case .unavailable = error else { return XCTFail("Unexpected handoff probe error") }
        }
    }

    func testMacHandoffRequiresAnExplicitBrowserCapability() async throws {
        let transport = AccountTransportStub([
            (200, #"{"supported":true}"#),
            (200, #"{"supported":true}"#),
            (200, #"{"supported":true,"systemBrowser":true}"#),
        ])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        let iPhone = try await service.supportsAccountWebHandoff()
        let beforeWebsiteUpdate = try await service.supportsAccountWebHandoff(in: .systemBrowser)
        let afterWebsiteUpdate = try await service.supportsAccountWebHandoff(in: .systemBrowser)
        XCTAssertTrue(iPhone)
        XCTAssertFalse(beforeWebsiteUpdate)
        XCTAssertTrue(afterWebsiteUpdate)
    }

    func testWeChatSignupPhoneVerificationDoesNotCreateAnExistingAccount() async throws {
        let transport = AccountTransportStub([
            (200, #"{"ok":true,"challenge":"sms-challenge"}"#),
            (200, #"{"ok":true,"next":"existing"}"#),
        ])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        let challenge = try await service.sendWeChatSignupSMS(phone: "13900000000", captcha: nil, deviceID: "device")
        let next = try await service.verifyWeChatSignupSMS(challenge: challenge, otp: "123456", deviceID: "device")
        guard case .existingAccount = next else { return XCTFail("Existing phone reached registration") }
        let requests = await transport.requests
        XCTAssertEqual(requests.count, 2)
        XCTAssertTrue(requests.allSatisfy { $0.value(forHTTPHeaderField: "x-device-id") == "device" })
    }

    func testWeChatSignupSendsOnlyCompletePhoneNumbersAndSMSCodes() async throws {
        let transport = AccountTransportStub([
            (200, #"{"challenge":"sms-challenge"}"#),
            (200, #"{"next":"existing"}"#),
        ])
        let form = WeChatSignupForm(service: YunlefunAccountService(transport: { try await transport.send($0) }))

        for invalid in ["+86 139 0000 00001", "13900000000 ext 1", "+85 139 0000 0000"] {
            form.changePhone(invalid)
            XCTAssertEqual(form.phone, invalid)
            XCTAssertFalse(form.canSubmitPhone)
            do {
                try await form.sendSMS()
                XCTFail("An invalid number must not request SMS")
            } catch YunlefunAccountError.invalidResponse {}
        }
        let invalidPhoneRequests = await transport.requests
        XCTAssertTrue(invalidPhoneRequests.isEmpty)
        form.changePhone("+86\u{00a0}(139) 0000-0000")
        XCTAssertEqual(form.phone, "13900000000")
        XCTAssertTrue(form.canSubmitPhone)
        try await form.sendSMS()

        for invalid in ["1234567", "12A345", "１２３４５６"] {
            form.changeOTP(invalid)
            XCTAssertEqual(form.otp, invalid)
            XCTAssertFalse(form.canVerifySMS)
            do {
                try await form.verifySMS(challenge: "sms-challenge")
                XCTFail("An invalid code must not reach verification")
            } catch WeChatSignupError.invalidOTP {}
        }
        let invalidCodeRequests = await transport.requests
        XCTAssertEqual(invalidCodeRequests.count, 1)
        form.changeOTP("123\u{202f}456")
        XCTAssertTrue(form.canVerifySMS)
        try await form.verifySMS(challenge: "sms-challenge")

        let requests = await transport.requests
        XCTAssertEqual(requests.count, 2)
        let payloads = try requests.map { try JSONDecoder().decode([String: String].self, from: XCTUnwrap($0.httpBody)) }
        XCTAssertEqual(payloads[0]["phone"], "13900000000")
        XCTAssertEqual(payloads[1]["otp"], "123456")
    }

    func testWeChatSignupCanVerifyTheOriginalSMSWhileCaptchaIsRequiredForResend() async throws {
        let transport = AccountTransportStub([
            (200, #"{"challenge":"original-sms"}"#),
            (400, #"{"reason":"signup_captcha_required"}"#),
            (200, #"{"challenge":"picture","image":"fixture"}"#),
            (200, #"{"next":"confirm","proof":"phone-proof"}"#),
        ])
        let form = WeChatSignupForm(service: YunlefunAccountService(transport: { try await transport.send($0) }))
        form.phone = "13900000000"
        try await form.sendSMS()
        form.otp = "123456"
        let retryAt = form.retrySMSAt

        try await form.sendSMS()
        guard case .sms(let challenge) = form.step else { return XCTFail("The original SMS step should remain usable") }
        XCTAssertEqual(challenge, "original-sms")
        XCTAssertEqual(form.otp, "123456")
        XCTAssertEqual(form.retrySMSAt, retryAt)
        XCTAssertEqual(form.captcha?.challenge, "picture")
        XCTAssertFalse(form.canSubmitCaptcha)

        try await form.verifySMS(challenge: challenge)
        guard case .confirm(let proof) = form.step else { return XCTFail("The original code should verify the phone") }
        XCTAssertEqual(proof, "phone-proof")
        XCTAssertTrue(form.otp.isEmpty)
        XCTAssertNil(form.captcha)
        let requests = await transport.requests
        let body = try XCTUnwrap(requests.last?.httpBody)
        let fields = try JSONDecoder().decode([String: String].self, from: body)
        XCTAssertEqual(fields["challenge"], "original-sms")
        XCTAssertEqual(fields["otp"], "123456")
    }

    func testWeChatSignupReplacesTheOriginalSMSOnlyAfterAnAcceptedCaptchaResend() async throws {
        let transport = AccountTransportStub([
            (200, #"{"challenge":"original-sms"}"#),
            (400, #"{"reason":"signup_captcha_required"}"#),
            (200, #"{"challenge":"first-picture","image":"fixture"}"#),
            (400, #"{"reason":"signup_captcha_invalid"}"#),
            (200, #"{"challenge":"second-picture","image":"fixture"}"#),
            (200, #"{"challenge":"replacement-sms"}"#),
        ])
        let form = WeChatSignupForm(service: YunlefunAccountService(transport: { try await transport.send($0) }))
        form.phone = "13900000000"
        try await form.sendSMS()
        form.otp = "123456"
        try await form.sendSMS()

        form.captchaAnswer = "WRONG"
        do {
            try await form.sendSMS()
            XCTFail("A rejected picture answer should display an error")
        } catch WeChatSignupError.captchaInvalid {}
        guard case .sms(let original) = form.step else { return XCTFail("The original SMS step should remain usable") }
        XCTAssertEqual(original, "original-sms")
        XCTAssertEqual(form.otp, "123456")
        XCTAssertEqual(form.captcha?.challenge, "second-picture")
        XCTAssertTrue(form.captchaAnswer.isEmpty)

        form.captchaAnswer = "RIGHT"
        XCTAssertTrue(form.canSubmitCaptcha)
        try await form.sendSMS()
        guard case .sms(let replacement) = form.step else { return XCTFail("The replacement SMS should be active") }
        XCTAssertEqual(replacement, "replacement-sms")
        XCTAssertTrue(form.otp.isEmpty)
        XCTAssertNil(form.captcha)
        XCTAssertTrue(form.captchaAnswer.isEmpty)
        let requests = await transport.requests
        let body = try XCTUnwrap(requests.last?.httpBody)
        let fields = try JSONDecoder().decode([String: String].self, from: body)
        XCTAssertEqual(fields["captchaChallenge"], "second-picture")
        XCTAssertEqual(fields["captchaAnswer"], "RIGHT")
    }

    func testExplicitWeChatSignupExchangesOnlyTheNewAccountTicket() async throws {
        let transport = AccountTransportStub([
            (200, #"{"ok":true,"ticket":"new-ticket","subject":"new-user"}"#),
            (200, #"{"access_token":"access","refresh_token":"refresh","sub":"new-user","expires_in":7200}"#),
        ])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        let credential = try await service.registerWithWeChat(proof: "phone-proof", code: "wechat-code", deviceID: "device")
        XCTAssertEqual(credential.subject, "new-user")
        let requests = await transport.requests
        let body = try XCTUnwrap(requests[0].httpBody)
        let fields = try JSONDecoder().decode([String: String].self, from: body)
        XCTAssertEqual(fields, ["action": "registerCookWeChat", "clientId": "cook-mobile", "proof": "phone-proof", "code": "wechat-code"])
        XCTAssertEqual(requests[1].url?.path, "/auth/v1/signin/custom")
    }

    func testWeChatSignupKeepsUnconfirmedRepliesSeparateFromAnAccountCreationReceipt() async throws {
        for (status, body) in [(408, "request timeout"), (502, "gateway response"), (200, "incomplete reply")] {
            let transport = AccountTransportStub([(status, body)])
            let service = YunlefunAccountService(transport: { try await transport.send($0) })
            do {
                _ = try await service.registerWithWeChat(proof: "phone-proof", code: "wechat-code", deviceID: "device")
                XCTFail("Expected an unconfirmed registration for HTTP \(status)")
            } catch WeChatSignupError.unconfirmed {}
            let requests = await transport.requests
            XCTAssertEqual(requests.count, 1)
        }

        let transport = AccountTransportStub([(200, #"{"ok":true,"ticket":"new-ticket"}"#)])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        do {
            _ = try await service.registerWithWeChat(proof: "phone-proof", code: "wechat-code", deviceID: "device")
            XCTFail("Expected an account creation receipt that could not complete sign-in")
        } catch WeChatSignupError.accountCreated {}
        let requests = await transport.requests
        XCTAssertEqual(requests.count, 1)
    }

    func testWeChatSignupDistinguishesLostConnectionFromDisabledRegistration() async throws {
        let lost = YunlefunAccountService(transport: { _ in throw URLError(.networkConnectionLost) })
        do {
            _ = try await lost.registerWithWeChat(proof: "phone-proof", code: "wechat-code", deviceID: "device")
            XCTFail("Expected an unconfirmed registration after losing the connection")
        } catch WeChatSignupError.unconfirmed {}

        let transport = AccountTransportStub([(503, #"{"ok":false,"reason":"not_configured"}"#)])
        let disabled = YunlefunAccountService(transport: { try await transport.send($0) })
        do {
            _ = try await disabled.registerWithWeChat(proof: "phone-proof", code: "wechat-code", deviceID: "device")
            XCTFail("Expected the service to report that registration was disabled")
        } catch WeChatAccountError.unavailable {}
    }

    func testWeChatSignupRevokesASessionThatDoesNotMatchTheNewAccount() async throws {
        let transport = AccountTransportStub([
            (200, #"{"ok":true,"ticket":"new-ticket","subject":"new-user"}"#),
            (200, #"{"access_token":"access","refresh_token":"refresh","sub":"wrong-user","expires_in":7200}"#),
            (200, "{}"),
        ])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        do {
            _ = try await service.registerWithWeChat(proof: "phone-proof", code: "wechat-code", deviceID: "device")
            XCTFail("Registration ticket opened a different account")
        } catch WeChatSignupError.accountCreated {}
        let requests = await transport.requests
        XCTAssertEqual(requests.map { $0.url?.path }, ["/sso-ticket", "/auth/v1/signin/custom", "/auth/v1/user/signout"])
    }

    func testWeChatExchangesCodeWithYunlefunAndUsesExistingUIDTicket() async throws {
        let transport = AccountTransportStub([
            (200, #"{"ok":true,"ticket":"sso-ticket","subject":"user-1"}"#),
            (200, #"{"access_token":"access","refresh_token":"refresh","sub":"user-1","expires_in":7200}"#),
        ])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        let credential = try await service.signInWithWeChat(code: "wechat-code", deviceID: "installation")
        XCTAssertEqual(credential.subject, "user-1")
        let requests = await transport.requests
        XCTAssertEqual(requests.map { $0.url?.path }, ["/sso-ticket", "/auth/v1/signin/custom"])
        XCTAssertEqual(requests[0].url?.host, "api.yunle.fun")
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Origin"), "https://cook.yunyoujun.cn")
        XCTAssertNil(requests[0].value(forHTTPHeaderField: "Authorization"))
        let exchange = try JSONSerialization.jsonObject(with: requests[0].httpBody!) as? [String: String]
        XCTAssertEqual(exchange, ["action": "exchangeCookWeChatCode", "clientId": "cook-mobile", "code": "wechat-code"])
        let signIn = try JSONSerialization.jsonObject(with: requests[1].httpBody!) as? [String: String]
        XCTAssertEqual(signIn, ["provider_id": "custom", "ticket": "sso-ticket"])
    }

    func testWeChatBindingUsesCurrentBearerAndNeverSendsUID() async throws {
        let transport = AccountTransportStub([(200, #"{"ok":true}"#)])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        try await service.bindWeChat(code: "wechat-code", credential: AccountServiceStub().credential)
        let requests = await transport.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.url?.absoluteString, "https://api.yunle.fun/sso-ticket")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertEqual(request.value(forHTTPHeaderField: "x-device-id"), "installation")
        let body = try JSONSerialization.jsonObject(with: request.httpBody!) as? [String: String]
        XCTAssertEqual(body, ["action": "bindCookWeChat", "clientId": "cook-mobile", "code": "wechat-code"])
    }

    func testWeChatRejectsSessionForAnUnexpectedUID() async throws {
        let transport = AccountTransportStub([
            (200, #"{"ok":true,"ticket":"sso-ticket","subject":"user-1"}"#),
            (200, #"{"access_token":"access","refresh_token":"refresh","sub":"wrong-user","expires_in":7200}"#),
            (200, "{}"),
        ])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        do {
            _ = try await service.signInWithWeChat(code: "wechat-code", deviceID: "installation")
            XCTFail("Ticket opened a different account")
        } catch YunlefunAccountError.invalidResponse {}
        let requests = await transport.requests
        XCTAssertEqual(requests.last?.url?.path, "/auth/v1/user/signout")
    }

    func testWeChatDoesNotRetryOrCreateAccountForAnUnboundIdentity() async throws {
        let transport = AccountTransportStub([(404, #"{"ok":false,"reason":"wechat_binding_required"}"#)])
        let service = YunlefunAccountService(transport: { try await transport.send($0) })
        do {
            _ = try await service.signInWithWeChat(code: "wechat-code", deviceID: "installation")
            XCTFail("Unbound identity returned a session")
        } catch WeChatAccountError.bindingRequired {}
        let requests = await transport.requests
        XCTAssertEqual(requests.count, 1)
    }

    func testProfileDecodesAvatarWithoutRequiringItForLogin() throws {
        let profile = try JSONDecoder().decode(YunlefunAccount.self, from: Data(#"{"sub":"user-1","name":"云游君","picture":"https://images.example/avatar.png"}"#.utf8))
        XCTAssertEqual(profile.avatarURL?.absoluteString, "https://images.example/avatar.png")
        let legacy = try JSONDecoder().decode(YunlefunAccount.self, from: Data(#"{"sub":"user-1"}"#.utf8))
        XCTAssertNil(legacy.avatarURL)
        var account = profile
        for picture in ["http://images.example/avatar.png", "file:///avatar.png", "data:image/png;base64,abc", "https://user:password@images.example/avatar.png", "invalid"] {
            account.picture = picture
            XCTAssertNil(account.avatarURL)
        }
    }

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

    func testAuthenticatedProfileAllowsOptionalAndDocumentedUsableStatuses() async throws {
        for body in [#"{"sub":"user-1","name":"云游君"}"#,
                     #"{"sub":"user-1","status":null}"#,
                     #"{"sub":"user-1","status":"DEFAULT"}"#,
                     #"{"sub":"user-1","status":"ACTIVE"}"#,
                     #"{"sub":"user-1","status":"active"}"#] {
            let transport = AccountTransportStub([(200, body)])
            let service = YunlefunAccountService(transport: { try await transport.send($0) })
            let user = try await service.account(AccountServiceStub().credential)
            XCTAssertEqual(user.id, "user-1")
        }
    }

    func testRejectsIdentityMismatchAnonymousAndDisabledUsers() async {
        for body in [#"{"sub":"other","status":"ACTIVE"}"#,
                     #"{"sub":"other"}"#,
                     #"{"sub":"user-1","status":"DISABLED"}"#,
                     #"{"sub":"user-1","status":"BLOCKED"}"#,
                     #"{"sub":"user-1","status":"blocked"}"#,
                     #"{"sub":"user-1","status":"PENDING"}"#,
                     #"{"sub":"user-1","status":"unknown"}"#,
                     #"{"sub":"user-1","status":""}"#,
                     #"{"sub":"user-1","is_anonymous":true}"#,
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
