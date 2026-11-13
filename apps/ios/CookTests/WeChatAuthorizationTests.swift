import XCTest
@testable import Cook

@MainActor
final class WeChatAuthorizationTests: XCTestCase {
    func testDisabledBuildDoesNotCallSDK() async {
        let auth = WeChatAuthorization(enabled: false, driver: .init(isInstalled: {
            XCTFail("Disabled builds must not probe WeChat")
            return true
        }))
        XCTAssertFalse(auth.isAvailable)
        do {
            _ = try await auth.authorize()
            XCTFail("Disabled builds must not launch WeChat")
        } catch WeChatAccountError.unavailable {} catch { XCTFail("Unexpected error") }
    }

    func testSDKReplyMustMatchAnActiveRequest() async throws {
        let sent = expectation(description: "WeChat request sent")
        var state = ""
        var reply: CookWeChatResponse?
        let auth = WeChatAuthorization(enabled: true, driver: .init(
            isInstalled: { true },
            register: { id, link in
                XCTAssertEqual(id, WeChatAuthorization.appID)
                XCTAssertEqual(link, WeChatAuthorization.universalLink)
                return true
            },
            send: { value, completed in
                state = value
                completed(true)
                sent.fulfill()
            },
            handleActivity: { _, completion in reply = completion; return true }
        ))
        let validLink = try XCTUnwrap(URL(string: "https://cook.yunyoujun.cn/auth/wechat/?code=ignored"))
        XCTAssertFalse(auth.handle(validLink), "A link received outside authorization is only a navigation link")
        let attempt = Task { try await auth.authorize() }
        defer { attempt.cancel() }
        await fulfillment(of: [sent], timeout: 2)
        XCTAssertEqual(state.count, 43)
        XCTAssertFalse(auth.handle(try XCTUnwrap(URL(string: "https://example.org/auth/wechat/"))))
        XCTAssertTrue(auth.handle(validLink))
        let response = try XCTUnwrap(reply)
        response(0, "another-transaction", "wrong-code")
        response(0, state, "verified-code")
        let code = try await attempt.value
        XCTAssertEqual(code, "verified-code")
        XCTAssertFalse(auth.handle(validLink), "A completed transaction cannot handle a replay")
    }

    func testCancellationAndInvalidCodesNeverReturnAnAuthorizationCode() throws {
        for status in [-2, -4] {
            XCTAssertThrowsError(try WeChatAuthorization.validatedCode(status: status, code: "ignored")) {
                XCTAssertTrue($0 is CancellationError)
            }
        }
        for code in [nil, "", "injected&key", String(repeating: "x", count: 513)] {
            XCTAssertThrowsError(try WeChatAuthorization.validatedCode(status: 0, code: code))
        }
        XCTAssertThrowsError(try WeChatAuthorization.validatedCode(status: -1, code: "valid-looking"))
    }
}
