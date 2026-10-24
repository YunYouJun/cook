import Foundation
import XCTest
@testable import Cook

final class CookUniversalLinkTests: XCTestCase {
    func testRegisteredNamespaceSupportsBaseAndCallbackPaths() throws {
        for value in ["https://cook.yunyoujun.cn/auth/wechat/",
                      "https://cook.yunyoujun.cn/auth/wechat/return?code=test&state=test",
                      "https://cook.yunyoujun.cn:443/auth/wechat/"] {
            XCTAssertTrue(CookUniversalLink.isWeChatLink(try XCTUnwrap(URL(string: value))), value)
        }
    }

    func testRejectsOtherOriginsRoutesAndAmbiguousPaths() throws {
        for value in ["http://cook.yunyoujun.cn/auth/wechat/", "cook://auth/wechat/",
                      "https://cook.yunyoujun.cn.evil.example/auth/wechat/",
                      "https://evil.example/auth/wechat/", "https://cook.yunyoujun.cn:444/auth/wechat/",
                      "https://user@cook.yunyoujun.cn/auth/wechat/",
                      "https://cook.yunyoujun.cn/auth/callback?platform=native",
                      "https://cook.yunyoujun.cn/auth/wechat-other/",
                      "https://cook.yunyoujun.cn/auth/wechat", "/auth/wechat/",
                      "https://cook.yunyoujun.cn/auth/wechat/../callback",
                      "https://cook.yunyoujun.cn/auth/wechat/%2e%2e/callback",
                      "https://cook.yunyoujun.cn/auth/wechat/%2fcallback"] {
            XCTAssertFalse(CookUniversalLink.isWeChatLink(try XCTUnwrap(URL(string: value))), value)
        }
    }

    @MainActor
    func testLinkOnlyNavigatesAndInvalidLinkKeepsCurrentRoute() throws {
        let router = AppRouter()
        router.showFridgeHome()
        XCTAssertFalse(router.openUniversalLink(try XCTUnwrap(URL(string: "https://evil.example/auth/wechat/"))))
        XCTAssertEqual(router.tab, .fridge)
        XCTAssertTrue(router.openUniversalLink(try XCTUnwrap(URL(string: "https://cook.yunyoujun.cn/auth/wechat/?access_token=ignored&uid=ignored"))))
        XCTAssertEqual(router.tab, .settings)
        XCTAssertEqual(router.fridgeHomeRequests, 1)
    }
}
