import UIKit
import XCTest

final class CloudKitchenUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testKitchenAndListShareSelectionAndPotOpensRealRecipes() {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments
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

    @MainActor
    func testOptionalRotationMovesCameraKeepsTapsAndResetsWhenDisabled() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-UIPreferredContentSizeCategoryName", UIContentSizeCategory.large.rawValue]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        let presentation = app.buttons["cook.presentation"]
        XCTAssertTrue(presentation.waitForExistence(timeout: 20))
        if presentation.label == "切换到列表" { presentation.tap() }
        presentation.tap()

        let tomato = app.buttons["kitchen.scene.tomato"]
        let cameraMarker = app.buttons["kitchen.scene.pot"]
        let rotation = app.buttons["kitchen.rotation"]
        XCTAssertTrue(tomato.waitForExistence(timeout: 30))
        XCTAssertTrue(rotation.waitForExistence(timeout: 10))
        if rotation.value as? String == "已开启" { rotation.tap() }
        rotation.tap()
        XCTAssertEqual(rotation.value as? String, "已开启")
        // The tomato is close to the orbit center and can stay almost fixed on
        // a wide screen. The off-center pot is a reliable camera-motion marker.
        let original = cameraMarker.frame
        let start = tomato.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 90, dy: -20)))
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            hypot(cameraMarker.frame.midX - original.midX, cameraMarker.frame.midY - original.midY) > 3
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [moved], timeout: 5), .completed)
        XCTAssertFalse(tomato.isSelected, "Dragging must not select an ingredient")
        tomato.tap()
        waitUntil(tomato, "isSelected == true")
        tomato.tap()
        waitUntil(tomato, "isSelected == false")

        rotation.tap()
        XCTAssertEqual(rotation.value as? String, "已关闭")
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            hypot(cameraMarker.frame.midX - original.midX, cameraMarker.frame.midY - original.midY) < 2
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 5), .completed)
        rotation.tap()

        // Recreating the scene uses a fresh clone and keeps the interaction preference.
        presentation.tap()
        presentation.tap()
        XCTAssertTrue(tomato.waitForExistence(timeout: 10))
        XCTAssertEqual(rotation.value as? String, "已开启")
        rotation.tap()
        XCTAssertEqual(rotation.value as? String, "已关闭")
        let reset = cameraMarker.frame
        let resetStart = tomato.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        resetStart.press(forDuration: 0.1, thenDragTo: resetStart.withOffset(CGVector(dx: 90, dy: 0)))
        XCTAssertEqual(cameraMarker.frame.midX, reset.midX, accuracy: 2)
        XCTAssertEqual(cameraMarker.frame.midY, reset.midY, accuracy: 2)
        tomato.tap()
        waitUntil(tomato, "isSelected == true")
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(tomato.waitForExistence(timeout: 10))
        XCTAssertTrue(tomato.isSelected)
        XCTAssertEqual(rotation.value as? String, "已关闭")
        capture(app, name: "关闭旋转后恢复固定视角")
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
