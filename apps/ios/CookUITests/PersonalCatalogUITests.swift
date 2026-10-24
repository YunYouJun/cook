import XCTest

final class PersonalCatalogUITests: XCTestCase {
    @MainActor
    func testCustomIngredientHideRestoreAndSelection() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        let mine = app.buttons.matching(identifier: "我的").firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 30))
        mine.tap()
        app.buttons["食材和厨具"].tap()
        let field = app.textFields["名称（最多 30 字）"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("鹰嘴豆\n")
        XCTAssertTrue(app.buttons["删除鹰嘴豆"].waitForExistence(timeout: 10))
        let hide = app.buttons["隐藏番茄"]
        if !hide.isHittable { app.swipeUp() }
        XCTAssertTrue(hide.waitForExistence(timeout: 10))
        hide.tap()
        XCTAssertTrue(app.buttons["恢复番茄"].waitForExistence(timeout: 10))
        app.buttons["恢复番茄"].tap()
        XCTAssertTrue(app.buttons["隐藏番茄"].waitForExistence(timeout: 10))
        app.navigationBars.buttons.firstMatch.tap()
        // A scroll in the manager minimizes the iOS 26+ tab bar; expand it before switching.
        app.swipeDown()
        let cook = app.buttons.matching(identifier: "做菜").firstMatch
        XCTAssertTrue(cook.waitForExistence(timeout: 10))
        cook.tap()
        let ingredient = app.buttons.matching(identifier: "鹰嘴豆").firstMatch
        for _ in 0..<5 where !ingredient.isHittable { app.swipeUp() }
        XCTAssertTrue(ingredient.waitForExistence(timeout: 10))
        ingredient.tap()
        XCTAssertTrue(ingredient.isSelected || ingredient.value as? String == "已选")
    }
}
