import XCTest

final class CloudKitchenUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testKitchenAndListShareSelectionAndPotOpensRealRecipes() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()

        let presentation = app.buttons["cook.presentation"]
        XCTAssertTrue(presentation.waitForExistence(timeout: 20))
        if presentation.label == "切换到列表" { presentation.tap() }
        XCTAssertFalse(app.segmentedControls["cook.presentation"].exists)
        let tomato = app.buttons.matching(NSPredicate(format: "label == %@", "番茄")).firstMatch
        XCTAssertTrue(tomato.waitForExistence(timeout: 10))
        tomato.tap()
        XCTAssertTrue(tomato.isSelected)
        capture(app, name: "列表的紧凑视图入口")

        presentation.tap()
        let modelTomato = app.buttons["kitchen.scene.tomato"]
        XCTAssertTrue(modelTomato.waitForExistence(timeout: 30))
        XCTAssertTrue(modelTomato.isSelected)
        // Tap the 3D ingredient at its projected screen location. This catches
        // overlapping targets accidentally selecting a neighboring ingredient.
        let modelEgg = app.buttons["kitchen.scene.egg"]
        modelEgg.tap()
        waitUntil(app.buttons.matching(NSPredicate(format: "label == %@", "鸡蛋")).firstMatch, "isSelected == true")
        XCTAssertEqual(app.staticTexts["kitchen.selectionCount"].label, "已选 2 样")

        presentation.tap()
        XCTAssertTrue(tomato.waitForExistence(timeout: 10))
        XCTAssertTrue(tomato.isSelected)
        presentation.tap()
        let pot = app.buttons["kitchen.scene.pot"]
        XCTAssertTrue(pot.waitForExistence(timeout: 30))
        capture(app, name: "原生云厨房与已选食材")
        XCTAssertTrue(app.webViews.count == 0)
        pot.tap()

        let recipe = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄炒蛋")).firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 15))
        capture(app, name: "点击锅后的真实推荐结果")
        recipe.tap()
        XCTAssertTrue(app.buttons["收藏"].waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(recipe.waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(pot.waitForExistence(timeout: 30))
        XCTAssertTrue(modelTomato.isSelected)
        app.buttons["清空"].tap()
        pot.tap()
        XCTAssertTrue(app.staticTexts["先选几样食材，再点锅开火。"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["kitchen.cook"].isEnabled)
    }

    private func waitUntil(_ element: XCUIElement, _ predicate: String) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: predicate), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 10), .completed)
    }

    @MainActor
    private func capture(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
