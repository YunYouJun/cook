import UIKit
import XCTest

final class SearchIntentUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    private func launch(largeDark: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        if largeDark {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", UIContentSizeCategory.accessibilityExtraExtraExtraLarge.rawValue, "-ui-testing-dark"]
        }
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        return app
    }

    @MainActor
    private func preview(_ app: XCUIApplication, text: String) {
        let search = app.searchFields.firstMatch
        if !search.isHittable { app.swipeDown() }
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText(text)
        XCTAssertTrue(app.buttons["intent.preview"].waitForExistence(timeout: 10))
        app.buttons["intent.preview"].tap()
        XCTAssertTrue(app.buttons["intent.apply"].waitForExistence(timeout: 10))
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "intent-preview-layout"
        screen.lifetime = .keepAlways
        add(screen)
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "intent-preview-hierarchy"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
    }

    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        let form = app.descendants(matching: .any).matching(identifier: "intent.form").firstMatch
        for _ in 0..<14 where !element.isHittable {
            form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.65))
                .press(forDuration: 0.1, thenDragTo: form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)),
                       withVelocity: .slow, thenHoldForDuration: 0.1)
        }
        XCTAssertTrue(element.isHittable)
    }

    @MainActor
    func testEditableLocalPreviewCancelAndApply() {
        let app = launch()
        preview(app, text: "鸡蛋和西葫芦，不要辣，减少添加糖")
        XCTAssertTrue(app.buttons["移除食材鸡蛋"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["移除目标减少添加糖"].exists)
        XCTAssertFalse(app.buttons["intent.smart"].exists)
        app.buttons["移除食材西葫芦"].tap()
        XCTAssertFalse(app.buttons["移除食材西葫芦"].exists)
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "本地可编辑找菜条件"
        image.lifetime = .keepAlways
        add(image)
        app.buttons["intent.cancel"].tap()
        XCTAssertTrue(app.buttons["鸡蛋"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["鸡蛋"].isSelected)
        let search = app.searchFields.firstMatch
        search.tap()
        search.typeText("鸡蛋，不要辣")
        app.buttons["intent.preview"].tap()
        XCTAssertTrue(app.buttons["intent.apply"].waitForExistence(timeout: 10))
        app.buttons["intent.apply"].tap()
        XCTAssertTrue(app.buttons["鸡蛋"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["鸡蛋"].isSelected)
    }

    @MainActor
    func testLargeDarkPreviewAndUnavailableManagedService() {
        let app = launch(largeDark: true)
        preview(app, text: "鸡蛋，孕期饮食")
        XCTAssertFalse(app.buttons["intent.apply"].isEnabled)
        reveal(app.buttons["移除食材鸡蛋"], in: app)
        XCTAssertTrue(app.buttons["移除食材鸡蛋"].exists)
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "大字号深色待确认条件"
        image.lifetime = .keepAlways
        add(image)
        app.buttons["intent.cancel"].tap()
        app.buttons["我的"].firstMatch.tap()
        let settings = app.buttons["settings.ai"]
        for _ in 0..<5 where !settings.isHittable { app.swipeUp() }
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "aiSettings.managed").firstMatch.waitForExistence(timeout: 10))
    }

    @MainActor
    func testPreviewSettingsAndChangedTextKeepConditionsUnapplied() {
        let app = launch()
        preview(app, text: "鸡蛋，不要辣")
        app.buttons["移除食材鸡蛋"].tap()
        let settings = app.buttons["intent.settings"]
        reveal(settings, in: app)
        settings.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "aiSettings.managed").firstMatch.waitForExistence(timeout: 10))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.buttons["intent.apply"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["移除食材鸡蛋"].exists)

        let field = app.descendants(matching: .any).matching(identifier: "intent.text").firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("，番茄")
        XCTAssertFalse(app.buttons["intent.apply"].isEnabled)
        app.buttons["intent.local"].tap()
        XCTAssertTrue(app.buttons["移除食材番茄"].waitForExistence(timeout: 10))
        app.buttons["intent.cancel"].tap()
        XCTAssertTrue(app.buttons["鸡蛋"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["鸡蛋"].isSelected)
        XCTAssertFalse(app.buttons["番茄"].isSelected)
    }
}
