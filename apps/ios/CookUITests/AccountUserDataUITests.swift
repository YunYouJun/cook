import UIKit
import XCTest

final class AccountUserDataUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testAccountSyncConsentCopyAndDisable() { exercise(large: false) }

    @MainActor
    func testAccountSyncAtLargestTypeInDarkAppearance() { exercise(large: true) }

    @MainActor
    private func exercise(large: Bool) {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent", "-ui-testing-account-sync",
                               "-ui-testing-disable-animations", "-UIPreferredContentSizeCategoryName",
                               large ? UIContentSizeCategory.accessibilityExtraExtraExtraLarge.rawValue : UIContentSizeCategory.large.rawValue]
        if large { app.launchArguments.append("-ui-testing-dark") }
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        terminateAppAfterTest(app)
        let mine = app.buttons["我的"].firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 15))
        mine.tap()
        let settings = app.buttons["数据与同步"].firstMatch
        reveal(settings, app: app)
        settings.tap()
        var toggle: XCUIElement { app.switches["sync.enabled"].firstMatch }
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        // SwiftUI exposes the entire labelled row as an outer switch. Its
        // midpoint is the label; use the observed native switch underneath.
        var control: XCUIElement { toggle.switches.firstMatch }
        XCTAssertTrue(control.waitForExistence(timeout: 10))
        XCTAssertEqual(control.value as? String, "0")
        control.tap()
        let consentScreen = XCTAttachment(screenshot: app.screenshot())
        consentScreen.name = "Account sync consent"
        consentScreen.lifetime = .keepAlways
        add(consentScreen)
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "Account sync consent hierarchy"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
        let enable = app.buttons["启用同步"].firstMatch
        XCTAssertTrue(enable.waitForExistence(timeout: 10))
        enable.tap()
        XCTAssertEqual(control.waitForState("value == '1'", timeout: 10), .completed)
        var counts: XCUIElement { app.staticTexts["sync.counts"].firstMatch }
        XCTAssertEqual(counts.waitForState("label == %@", timeout: 10, argument: "账号记录、0 个收藏，0 条浏览记录"), .completed)
        let copy = app.buttons["sync.copyLocal"].firstMatch
        reveal(copy, app: app)
        copy.tap()
        let confirm = app.buttons["复制收藏和浏览历史"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        confirm.tap()
        XCTAssertEqual(counts.waitForState("label == %@", timeout: 10, argument: "账号记录、1 个收藏，1 条浏览记录"), .completed)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = large ? "Account sync largest text dark" : "Account sync standard light"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        reveal(toggle, app: app, upwards: false)
        control.tap()
        XCTAssertEqual(control.waitForState("value == '0'", timeout: 10), .completed)
        XCTAssertTrue(app.buttons["sync.now"].waitForNonExistence(timeout: 10))
    }

    @MainActor
    private func reveal(_ element: XCUIElement, app: XCUIApplication, upwards: Bool = true) {
        for _ in 0..<6 {
            if element.exists && element.isHittable { return }
            if upwards { app.swipeUp() } else { app.swipeDown() }
        }
        XCTAssertTrue(element.isHittable)
    }
}
