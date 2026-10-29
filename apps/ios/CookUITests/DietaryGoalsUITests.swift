import UIKit
import XCTest

final class DietaryGoalsUITests: XCTestCase {
    @MainActor
    private func app(contentSize: UIContentSizeCategory = .large, showsIntroduction: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-dietary", "-ui-testing-reset-presentation",
                               "-UIPreferredContentSizeCategoryName", contentSize.rawValue]
        if showsIntroduction { app.launchArguments.append("-ui-testing-show-dietary-introduction") }
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        let presentation = app.buttons["cook.presentation"]
        XCTAssertTrue(presentation.waitForExistence(timeout: 15))
        if presentation.label.contains("切换到列表") { presentation.tap() }
        return app
    }

    @MainActor
    private func select(_ goal: String, in app: XCUIApplication) {
        let openedFilters = openGoalPicker(in: app)
        let option = app.buttons["dietary.goal.\(goal)"]
        XCTAssertTrue(option.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["dietary.goal.weight-aware"].exists)
        option.tap()
        XCTAssertTrue(option.isSelected)
        finishGoalSelection(in: app, closingFilters: openedFilters)
    }

    /// First-use guidance and active summaries open directly; subsequent changes live in filters.
    @MainActor
    @discardableResult
    private func openGoalPicker(in app: XCUIApplication) -> Bool {
        let introduction = app.buttons["dietary.introduction.choose"]
        if introduction.exists && introduction.isHittable {
            introduction.tap()
            return false
        }
        let summary = app.buttons["dietary.openPicker"].firstMatch
        if summary.exists && summary.isHittable {
            summary.tap()
            return false
        }
        openFiltersGoalPicker(in: app)
        return true
    }

    @MainActor
    private func openFiltersGoalPicker(in app: XCUIApplication) {
        let filters = app.buttons["cook.filters"].firstMatch
        XCTAssertTrue(filters.waitForExistence(timeout: 15))
        filters.tap()
        let picker = app.buttons["dietary.openPicker"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        picker.tap()
    }

    @MainActor
    private func finishGoalSelection(in app: XCUIApplication, closingFilters: Bool = false) {
        let done = app.buttons["dietary.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 10))
        done.tap()
        if closingFilters {
            let filterDone = app.buttons["filter.done"]
            XCTAssertTrue(filterDone.waitForExistence(timeout: 10))
            filterDone.tap()
        }
    }

    @MainActor
    private func openResultsGoalPicker(in app: XCUIApplication) {
        let options = app.buttons["results.options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 10))
        options.tap()
        let goal = app.buttons["results.dietaryGoal"]
        XCTAssertTrue(goal.waitForExistence(timeout: 10))
        goal.tap()
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
        let app = app(showsIntroduction: true)
        select("less-added-sugar", in: app)
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "dietary.introduction").firstMatch.exists)
        showResults(app)
        guard let row = revealRecipe("dietary.recipe.333", in: app, maximumSwipes: 5) else { return }
        XCTAssertTrue(row.isHittable)
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
        let scope = app.staticTexts["random.scope"]
        XCTAssertTrue(scope.waitForExistence(timeout: 10))
        XCTAssertTrue(scope.label.contains("减少添加糖"))
        app.buttons["random.options"].tap()
        let goal = app.buttons["random.dietaryGoal"]
        XCTAssertTrue(goal.waitForExistence(timeout: 10))
        goal.tap()
        XCTAssertTrue(app.buttons["dietary.goal.less-added-sugar"].isSelected)
        finishGoalSelection(in: app)
        app.buttons["random.openRecipe"].tap()
        XCTAssertTrue(app.buttons["dietary.openReviewedRecipe"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testGoalSelectionIsSavedAndUpdatedInFiltersAcrossLaunches() {
        let app = app(showsIntroduction: true)
        select("balanced-pairing", in: app)
        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-dietary" }
        app.launch()
        let picker = app.buttons["dietary.openPicker"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 15))
        XCTAssertEqual(picker.value as? String, "均衡搭配")
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "dietary.introduction").firstMatch.exists)

        openFiltersGoalPicker(in: app)
        let updatedGoal = app.buttons["dietary.goal.less-added-sugar"]
        XCTAssertTrue(updatedGoal.waitForExistence(timeout: 10))
        updatedGoal.tap()
        XCTAssertTrue(updatedGoal.isSelected)
        assertRememberControlAbsent(in: app)
        XCTAssertTrue(app.staticTexts["dietary.persistenceNote"].exists)
        capture(app, named: "dietary-automatically-saved-picker")
        finishGoalSelection(in: app, closingFilters: true)
        app.terminate()
        app.launch()
        XCTAssertTrue(picker.waitForExistence(timeout: 15))
        XCTAssertEqual(picker.value as? String, "减少添加糖")

        app.buttons["清空"].tap()
        XCTAssertFalse(picker.exists)
        let openedFilters = openGoalPicker(in: app)
        XCTAssertTrue(openedFilters)
        XCTAssertTrue(app.buttons["dietary.goal.none"].isSelected)
        finishGoalSelection(in: app, closingFilters: openedFilters)
        app.terminate()
        app.launch()
        XCTAssertTrue(picker.waitForExistence(timeout: 15))
        XCTAssertEqual(picker.value as? String, "减少添加糖")
        select("none", in: app)
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["cook.filters"].firstMatch.waitForExistence(timeout: 15))
        XCTAssertFalse(picker.exists)
        let reopenedFilters = openGoalPicker(in: app)
        XCTAssertTrue(reopenedFilters)
        XCTAssertTrue(app.buttons["dietary.goal.none"].isSelected)
        assertRememberControlAbsent(in: app)
        finishGoalSelection(in: app, closingFilters: reopenedFilters)
    }

    @MainActor
    func testSkippedIntroductionStaysCollapsedAcrossLaunchesAndGoalRemainsAvailableInFilters() {
        let app = app(showsIntroduction: true)
        let introduction = app.descendants(matching: .any).matching(identifier: "dietary.introduction").firstMatch
        XCTAssertTrue(introduction.waitForExistence(timeout: 10))
        capture(app, named: "dietary-first-use")
        let skip = app.buttons["dietary.introduction.skip"]
        XCTAssertTrue(skip.waitForExistence(timeout: 10))
        skip.tap()
        XCTAssertFalse(introduction.exists)
        XCTAssertFalse(app.buttons["dietary.openPicker"].exists)
        capture(app, named: "dietary-collapsed")

        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-dietary" }
        app.launch()
        XCTAssertTrue(app.buttons["cook.filters"].firstMatch.waitForExistence(timeout: 15))
        XCTAssertFalse(introduction.exists)
        XCTAssertFalse(app.buttons["dietary.openPicker"].exists)

        let openedFilters = openGoalPicker(in: app)
        XCTAssertTrue(openedFilters)
        let goal = app.buttons["dietary.goal.balanced-pairing"]
        XCTAssertTrue(goal.waitForExistence(timeout: 10))
        goal.tap()
        finishGoalSelection(in: app, closingFilters: openedFilters)
        let summary = app.buttons["dietary.openPicker"].firstMatch
        XCTAssertTrue(summary.waitForExistence(timeout: 10))
        XCTAssertEqual(summary.value as? String, "均衡搭配")
        capture(app, named: "dietary-compact-active")
        summary.tap()
        XCTAssertTrue(goal.waitForExistence(timeout: 10))
        XCTAssertTrue(goal.isSelected)
        finishGoalSelection(in: app)
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
        XCTAssertFalse(app.buttons["dietary.openPicker"].exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已选：牛肉")).firstMatch.exists)
        openResultsGoalPicker(in: app)
        XCTAssertTrue(app.buttons["dietary.goal.none"].isSelected)
        finishGoalSelection(in: app)
    }

    @MainActor
    func testClearingGoalFromResultsKeepsPickerPresentedUntilDone() {
        let app = app()
        select("less-added-sugar", in: app)
        showResults(app)
        openResultsGoalPicker(in: app)
        let none = app.buttons["dietary.goal.none"]
        XCTAssertTrue(none.waitForExistence(timeout: 10))
        none.tap()
        XCTAssertTrue(none.isSelected)
        let done = app.buttons["dietary.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        assertRememberControlAbsent(in: app)
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
        let openedFilters = openGoalPicker(in: app)
        let option = app.buttons["dietary.goal.less-added-sugar"]
        for _ in 0..<8 {
            if option.exists && option.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(option.isHittable)
        option.tap()
        let auditScreen = XCTAttachment(screenshot: app.screenshot())
        auditScreen.name = "dietary-picker-before-audits"
        auditScreen.lifetime = .keepAlways
        add(auditScreen)
        let auditHierarchy = XCTAttachment(string: app.debugDescription)
        auditHierarchy.name = "dietary-picker-hierarchy-before-audits"
        auditHierarchy.lifetime = .keepAlways
        add(auditHierarchy)
        // Keep each category within the simulator's audit deadline on slower CI hosts.
        // Every selected category runs and every reported issue still fails the test.
        for auditType in auditTypes {
            print("Starting dietary accessibility audit: \(auditType.rawValue), content size: \(contentSize.rawValue)")
            try app.performAccessibilityAudit(for: auditType) { issue in
                print("Dietary accessibility audit: \(issue.element?.debugDescription ?? "unidentified element") — \(issue.detailedDescription)")
                return false
            }
            print("Passed dietary accessibility audit: \(auditType.rawValue)")
        }
        XCTAssertTrue(option.isSelected)
        assertRememberControlAbsent(in: app)
        let picker = XCTAttachment(screenshot: app.screenshot())
        picker.name = "dietary-accessibility-picker"
        picker.lifetime = .keepAlways
        add(picker)
        finishGoalSelection(in: app, closingFilters: openedFilters)
        showResults(app)
        guard let recipe = revealRecipe("dietary.recipe.333", in: app, maximumSwipes: 25) else { return }
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

    @MainActor
    private func assertRememberControlAbsent(in app: XCUIApplication,
                                            file: StaticString = #filePath, line: UInt = #line) {
        // Cover both legacy native controls without querying every descendant role.
        XCTAssertFalse(app.buttons["dietary.remember"].exists, file: file, line: line)
        XCTAssertFalse(app.switches["dietary.remember"].exists, file: file, line: line)
    }

    @MainActor
    private func revealRecipe(_ identifier: String, in app: XCUIApplication, maximumSwipes: Int,
                              file: StaticString = #filePath, line: UInt = #line) -> XCUIElement? {
        let recipe = app.buttons[identifier]
        let results = app.descendants(matching: .any).matching(identifier: "dietary.results").firstMatch
        guard results.waitForExistence(timeout: 5) else {
            XCTFail("查找 \(identifier) 前未显示菜谱结果列表", file: file, line: line)
            return nil
        }
        for _ in 0..<maximumSwipes {
            guard results.exists else {
                XCTFail("查找 \(identifier) 时意外离开菜谱结果列表", file: file, line: line)
                return nil
            }
            if recipe.exists && recipe.isHittable { return recipe }
            let frame = results.frame
            let visibleFrame = frame.intersection(app.windows.firstMatch.frame)
            guard !visibleFrame.isNull && !visibleFrame.isEmpty else {
                XCTFail("查找 \(identifier) 时菜谱结果列表没有可见滚动区域", file: file, line: line)
                return nil
            }
            // A short custom drag made too little progress through tall recipe rows.
            // Keep the gesture scoped to the native list and let XCTest choose its swipe span.
            results.swipeUp(velocity: .slow)
            guard results.exists else {
                XCTFail("滚动查找 \(identifier) 后意外离开菜谱结果列表", file: file, line: line)
                return nil
            }
        }
        guard recipe.exists && recipe.isHittable else {
            XCTFail("在 \(maximumSwipes) 次结果列表滚动后仍无法点击 \(identifier)", file: file, line: line)
            return nil
        }
        return recipe
    }

    @MainActor
    private func capture(_ app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
