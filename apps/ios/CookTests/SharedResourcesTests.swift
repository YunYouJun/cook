import UIKit
import XCTest
@testable import Cook

final class SharedResourcesTests: XCTestCase {
    func testBundledWeChatSupportResourcesLoad() throws {
        // 确认 Xcode 工程实际打包了共享 JSON 与图标，而不只是源码引用正确。
        let url = AppLinks.shared.wecomKf
        XCTAssertEqual(url.scheme, "https")
        XCTAssertEqual(url.host, "work.weixin.qq.com")
        XCTAssertTrue(url.path.hasPrefix("/kfid/"))
        let image = try XCTUnwrap(UIImage(named: "wechat"))
        XCTAssertGreaterThan(image.size.width, 0)
        XCTAssertGreaterThan(image.size.height, 0)
    }
}
