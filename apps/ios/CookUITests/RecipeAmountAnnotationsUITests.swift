import UIKit
import XCTest

final class RecipeAmountAnnotationsUITests: XCTestCase {
    @MainActor
    func testBundledAnnotationScalesAndRestoresInWorkbench() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openBroccoli(app)
        let servings = app.steppers["recipe.servings"]
        let amount = app.staticTexts["recipe.amount.西兰花"]
        XCTAssertTrue(servings.waitForExistence(timeout: 10))
        XCTAssertTrue(amount.label.contains("200 g"), amount.label)
        servings.buttons.element(boundBy: 1).tap()
        servings.buttons.element(boundBy: 1).tap()
        XCTAssertTrue(amount.label.contains("600 g"), amount.label)
        XCTAssertTrue(app.staticTexts["recipe.amount.大蒜"].label.contains("9–12 瓣"))
        let tableAttachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        tableAttachment.name = "annotation-table-three-servings"
        tableAttachment.lifetime = .keepAlways
        add(tableAttachment)
        app.buttons["recipe.continueStep"].tap()
        let step = app.buttons["recipe.step.1"]
        let visible = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: step)
        XCTAssertEqual(XCTWaiter.wait(for: [visible], timeout: 10), .completed)
        step.tap()
        XCTAssertEqual(app.staticTexts["recipe.stepProgress"].label, "已完成 1 / 9 步")
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "annotation-three-servings"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.buttons["kitchen.close"].tap()
        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-kitchen" }
        app.launch()
        openBroccoli(app)
        XCTAssertTrue(amount.label.contains("600 g"), amount.label)
        XCTAssertEqual(app.staticTexts["recipe.stepProgress"].label, "已完成 1 / 9 步")
    }

    @MainActor
    private func openBroccoli(_ app: XCUIApplication) {
        let ingredient = app.buttons["西兰花"]
        XCTAssertTrue(ingredient.waitForExistence(timeout: 20))
        if !ingredient.isSelected { ingredient.tap() }
        if app.windows.firstMatch.horizontalSizeClass == .regular {
            let results = app.descendants(matching: .any).matching(identifier: "dietary.results").firstMatch
            XCTAssertTrue(results.waitForExistence(timeout: 10))
        } else {
            let results = app.buttons["cook.showResults"]
            XCTAssertTrue(results.waitForExistence(timeout: 10))
            results.tap()
        }
        let recipe = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "蒜蓉西兰花")).firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 10))
        recipe.tap()
        let open = app.buttons["recipe.openKitchen"]
        XCTAssertTrue(open.waitForExistence(timeout: 10))
        open.tap()
        XCTAssertTrue(app.buttons["kitchen.close"].waitForExistence(timeout: 10))
    }
}
