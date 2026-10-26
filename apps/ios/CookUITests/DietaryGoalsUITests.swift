import UIKit
import XCTest

final class DietaryGoalsUITests: XCTestCase {
    @MainActor
    private func app(contentSize: UIContentSizeCategory = .large) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-dietary", "-ui-testing-reset-presentation",
                               "-UIPreferredContentSizeCategoryName", contentSize.rawValue]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        let presentation = app.buttons["cook.presentation"]
        XCTAssertTrue(presentation.waitForExistence(timeout: 15))
        if presentation.label.contains("切换到列表") { presentation.tap() }
        return app
    }

    @MainActor
    private func select(_ goal: String, in app: XCUIApplication, remember: Bool = false) {
        let picker = app.buttons["dietary.openPicker"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 15))
        picker.tap()
        let option = app.buttons["dietary.goal.\(goal)"]
        XCTAssertTrue(option.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["dietary.goal.weight-aware"].exists)
        option.tap()
        if remember {
            let toggle = app.switches["dietary.remember"]
            XCTAssertTrue(toggle.isEnabled)
            // SwiftUI exposes the labeled row as the switch; tap its trailing native control.
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
            XCTAssertEqual(toggle.value as? String, "1")
            let attachment = XCTAttachment(screenshot: app.screenshot())
            attachment.name = "native-dietary-picker"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        app.buttons["dietary.done"].tap()
    }

    @MainActor
    private func showResults(_ app: XCUIApplication) {
        if app.windows.firstMatch.horizontalSizeClass != .regular {
            let button = app.buttons["cook.showResults"]
            XCTAssertTrue(button.waitForExistence(timeout: 10))
            button.tap()
        }
    }

    @MainActor
    private func openRandom(_ app: XCUIApplication) {
        // iPad floating tabs use Cell/Other roles instead of TabBar/Button on newer SDKs.
        let random = app.descendants(matching: .any).matching(identifier: "dice").firstMatch
        let randomButton = app.buttons["随机"].firstMatch
        if !random.exists && !randomButton.exists {
            let collapsedTab = app.tabBars.buttons.firstMatch
            if collapsedTab.exists {
                collapsedTab.tap()
            } else {
                app.descendants(matching: .any).matching(identifier: "frying.pan").firstMatch.tap()
            }
        }
        let target = randomButton.exists ? randomButton : random
        XCTAssertTrue(target.waitForExistence(timeout: 5))
        target.tap()
    }

    @MainActor
    func testGoalBrowsingOpensReviewedTextAndPreservesGoalInRandom() {
        let app = app()
        select("less-added-sugar", in: app)
        showResults(app)
        let row = app.buttons["dietary.recipe.333"]
        for _ in 0..<5 {
            if row.exists && row.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        let reviewed = app.buttons["dietary.openReviewedRecipe"]
        XCTAssertTrue(reviewed.waitForExistence(timeout: 10))
        app.buttons["recipe.openKitchen"].tap()
        XCTAssertTrue(app.buttons["kitchen.close"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.otherElements["dietary.reviewSummary"].waitForExistence(timeout: 10))
        app.buttons["kitchen.close"].tap()
        reviewed.tap()
        XCTAssertTrue(app.otherElements["dietary.reviewSummary"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["dietary.reviewReason"].label.contains("未额外添加糖"))
        XCTAssertFalse(app.staticTexts["预估热量"].exists)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "dietary-reviewed-source"
        attachment.lifetime = .keepAlways
        add(attachment)
        openRandom(app)
        XCTAssertTrue(app.buttons["dietary.openPicker"].firstMatch.value as? String == "减少添加糖")
        app.buttons["random.openRecipe"].tap()
        XCTAssertTrue(app.buttons["dietary.openReviewedRecipe"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testRememberedGoalAndExplicitCancellationAcrossLaunches() {
        let app = app()
        select("balanced-pairing", in: app, remember: true)
        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-dietary" }
        app.launch()
        let picker = app.buttons["dietary.openPicker"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 15))
        XCTAssertEqual(picker.value as? String, "均衡搭配")
        app.buttons["清空"].tap()
        XCTAssertEqual(picker.value as? String, "不限目标")
        app.terminate()
        app.launch()
        XCTAssertTrue(picker.waitForExistence(timeout: 15))
        XCTAssertEqual(picker.value as? String, "均衡搭配")
        select("none", in: app)
        app.terminate()
        app.launch()
        XCTAssertTrue(picker.waitForExistence(timeout: 15))
        XCTAssertEqual(picker.value as? String, "不限目标")
    }

    @MainActor
    func testEmptyGoalResultsCanBeCancelledWithoutLosingSelectedIngredients() {
        let app = app()
        select("less-added-sugar", in: app)
        let beef = app.buttons["牛肉"]
        XCTAssertTrue(beef.waitForExistence(timeout: 10))
        // Keep the whole chip above the fixed action bar before tapping it.
        let action = app.buttons["cook.showResults"]
        for _ in 0..<8 {
            if beef.isHittable && (!action.exists || beef.frame.maxY < action.frame.minY) { break }
            let window = app.windows.firstMatch
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.65))
                .press(forDuration: 0.1,
                       thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5)),
                       withVelocity: .slow, thenHoldForDuration: 0.2)
        }
        XCTAssertTrue(beef.isHittable)
        beef.tap()
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isSelected == true"), object: beef)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 10), .completed)
        showResults(app)
        let cancel = app.buttons["dietary.clearGoal"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 10))
        cancel.tap()
        XCTAssertFalse(app.buttons["dietary.clearGoal"].exists)
        XCTAssertEqual(app.buttons["dietary.openPicker"].firstMatch.value as? String, "不限目标")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已选：牛肉")).firstMatch.exists)
    }

    @MainActor
    func testClearingGoalFromResultsKeepsPickerPresentedUntilDone() {
        let app = app()
        select("less-added-sugar", in: app)
        showResults(app)
        let results = app.descendants(matching: .any).matching(identifier: "dietary.results").firstMatch
        let picker = results.buttons["dietary.openPicker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        picker.tap()
        let none = app.buttons["dietary.goal.none"]
        XCTAssertTrue(none.waitForExistence(timeout: 10))
        none.tap()
        XCTAssertTrue(none.isSelected)
        let done = app.buttons["dietary.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        XCTAssertFalse(app.switches["dietary.remember"].isEnabled)
        done.tap()
        XCTAssertTrue(app.staticTexts["先选几样食材"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testCloudKitchenKeepsGoalAndCanBrowseWithoutIngredients() {
        let app = app()
        select("less-added-sugar", in: app)
        app.buttons["cook.presentation"].tap()
        let cook = app.buttons["kitchen.cook"]
        XCTAssertTrue(cook.waitForExistence(timeout: 15))
        XCTAssertTrue(cook.isEnabled)
        XCTAssertTrue(cook.label.contains("12"))
        XCTAssertEqual(app.buttons["dietary.openPicker"].firstMatch.value as? String, "减少添加糖")
        cook.tap()
        let recipe = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "dietary.recipe.")).firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 15))
    }

    @MainActor
    func testNativeGoalPickerAccessibilityAndReviewedSummary() throws {
        try verifyNativeGoalFlow(contentSize: .large, auditTypes: [.contrast, .dynamicType, .hitRegion, .sufficientElementDescription, .textClipped, .trait])
    }

    @MainActor
    func testLargestTextCanChooseRememberReadAndRoll() throws {
        // Contrast is audited at standard text size, where every choice fits in the viewport.
        // At the largest size, the simulator samples offscreen labels against the underlying window.
        try verifyNativeGoalFlow(contentSize: .accessibilityExtraExtraExtraLarge, auditTypes: [.dynamicType, .hitRegion, .sufficientElementDescription, .textClipped, .trait])
    }

    @MainActor
    private func verifyNativeGoalFlow(contentSize: UIContentSizeCategory, auditTypes: [XCUIAccessibilityAuditType]) throws {
        let app = app(contentSize: contentSize)
        app.buttons["dietary.openPicker"].firstMatch.tap()
        let option = app.buttons["dietary.goal.less-added-sugar"]
        for _ in 0..<8 {
            if option.exists && option.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(option.isHittable)
        option.tap()
        // Keep each category within the simulator's audit deadline on slower CI hosts.
        // Every selected category runs and every reported issue still fails the test.
        for auditType in auditTypes {
            try app.performAccessibilityAudit(for: auditType) { issue in
                print("Dietary accessibility audit: \(issue.element?.debugDescription ?? "unidentified element") — \(issue.detailedDescription)")
                return false
            }
        }
        let toggle = app.switches["dietary.remember"]
        for _ in 0..<8 {
            if toggle.exists && toggle.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(toggle.isHittable)
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        XCTAssertEqual(toggle.value as? String, "1")
        let picker = XCTAttachment(screenshot: app.screenshot())
        picker.name = "dietary-accessibility-picker"
        picker.lifetime = .keepAlways
        add(picker)
        app.buttons["dietary.done"].tap()
        showResults(app)
        let recipe = app.buttons["dietary.recipe.333"]
        for _ in 0..<25 {
            if recipe.exists && recipe.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(recipe.isHittable)
        recipe.tap()
        let source = app.buttons["dietary.openReviewedRecipe"]
        XCTAssertTrue(source.waitForExistence(timeout: 10))
        source.tap()
        let reason = app.staticTexts["dietary.reviewReason"]
        XCTAssertTrue(reason.waitForExistence(timeout: 10))
        XCTAssertTrue(reason.label.contains("未额外添加糖"))
        let summary = XCTAttachment(screenshot: app.screenshot())
        summary.name = "dietary-accessibility-summary"
        summary.lifetime = .keepAlways
        add(summary)
        openRandom(app)
        let randomRecipe = app.buttons["random.openRecipe"]
        XCTAssertTrue(randomRecipe.waitForExistence(timeout: 10))
        let roll = app.buttons["random.roll"]
        for _ in 0..<12 {
            if roll.exists && roll.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(roll.isHittable)
        XCTAssertTrue(roll.isEnabled)
        let random = XCTAttachment(screenshot: app.screenshot())
        random.name = "dietary-accessibility-random"
        random.lifetime = .keepAlways
        add(random)
    }

}
