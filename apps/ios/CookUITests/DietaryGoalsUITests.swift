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
        app.selectCookingPresentation("食材快选")
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

    /// 目标入口统一收进筛选，首屏只保留当前条件。
    @MainActor
    @discardableResult
    private func openGoalPicker(in app: XCUIApplication) -> Bool {
        openFiltersGoalPicker(in: app)
        return true
    }

    @MainActor
    private func openFiltersGoalPicker(in app: XCUIApplication) {
        let filters = app.buttons["cook.filters"].firstMatch
        XCTAssertTrue(filters.waitForExistence(timeout: 15))
        filters.tap()
        // Resolve the unique row inside its sheet instead of lazily traversing every app button.
        let options = app.collectionViews["filter.options"]
        let picker = options.buttons["dietary.openPicker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        picker.tap()
    }

    @MainActor
    private func finishGoalSelection(in app: XCUIApplication, closingFilters: Bool = false) {
        let done = app.buttons["dietary.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 10))
        done.tap()
        XCTAssertTrue(done.waitForNonExistence(timeout: 10))
        if closingFilters {
            let filterDone = app.buttons["filter.done"]
            XCTAssertTrue(filterDone.waitForExistence(timeout: 10))
            filterDone.tap()
            XCTAssertTrue(filterDone.waitForNonExistence(timeout: 10))
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
    private func showResults(_ app: XCUIApplication, standalone: Bool = false) {
        if app.windows.firstMatch.horizontalSizeClass == .regular {
            if standalone {
                // 独立结果页的更多菜单负责目标弹层；实时右栏共用左侧筛选入口。
                app.selectCookingPresentation("云厨房")
                let cook = app.buttons["kitchen.cook"]
                XCTAssertTrue(cook.waitForExistence(timeout: 10))
                cook.tap()
            }
            XCTAssertTrue(app.collectionViews["dietary.results"].waitForExistence(timeout: 15))
        } else {
            let button = app.buttons["cook.showResults"]
            XCTAssertTrue(button.waitForExistence(timeout: 10))
            button.tap()
        }
    }

    @MainActor
    private func assertGoalValue(_ value: String, in app: XCUIApplication,
                                 file: StaticString = #filePath, line: UInt = #line) {
        let resultsOptions = app.buttons["results.options"].firstMatch
        if resultsOptions.exists && resultsOptions.isHittable {
            XCTAssertEqual(resultsOptions.value as? String, value, file: file, line: line)
            return
        }
        let filters = app.buttons["cook.filters"].firstMatch
        XCTAssertTrue(filters.waitForExistence(timeout: 15), file: file, line: line)
        filters.tap()
        let picker = app.collectionViews["filter.options"].buttons["dietary.openPicker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10), file: file, line: line)
        XCTAssertEqual(picker.value as? String, value, file: file, line: line)
        let done = app.buttons["filter.done"]
        done.tap()
        XCTAssertTrue(done.waitForNonExistence(timeout: 10), file: file, line: line)
    }

    @MainActor
    private func returnToFindRecipes(_ app: XCUIApplication) {
        // 正文 → 详情 → 结果；iPhone 再返回食材页，iPad 的左栏始终可见。
        for expected in [app.buttons["recipe.openKitchen"], app.collectionViews["dietary.results"]] {
            let bar = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable }) ?? app.navigationBars.firstMatch
            let back = bar.buttons.element(boundBy: 0)
            XCTAssertTrue(back.isHittable)
            back.tap()
            XCTAssertTrue(expected.waitForExistence(timeout: 10))
        }
        if app.windows.firstMatch.horizontalSizeClass != .regular {
            let bar = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable }) ?? app.navigationBars.firstMatch
            bar.buttons.element(boundBy: 0).tap()
        }
        revealCookingControl(app.buttons["discovery.decide"], in: app)
        XCTAssertFalse(app.buttons["随机"].exists)
    }

    @MainActor
    private func revealCookingControl(_ control: XCUIElement, in app: XCUIApplication, towardTop: Bool = false) {
        let chooser = app.collectionViews["cook.chooser"]
        XCTAssertTrue(chooser.waitForExistence(timeout: 10))
        for _ in 0..<30 {
            let frame = control.exists ? control.frame : CGRect.null
            let window = app.windows.firstMatch
            let bars = app.navigationBars.allElementsBoundByIndex.filter(\.isHittable)
            let bar = bars.first(where: { !frame.isNull && frame.midX >= $0.frame.minX && frame.midX <= $0.frame.maxX })
                ?? bars.first ?? app.navigationBars.firstMatch
            let top = bar.isHittable ? bar.frame.maxY : window.frame.minY
            let action = app.buttons["cook.showResults"]
            let bottom = window.horizontalSizeClass != .regular && action.exists && action.isHittable
                ? action.frame.minY : window.frame.maxY
            if control.isHittable && !frame.isNull && frame.minY >= top && frame.maxY <= bottom { return }
            if towardTop || (!frame.isNull && frame.minY < top) {
                chooser.swipeDown(velocity: .slow)
            } else {
                chooser.swipeUp(velocity: .slow)
            }
        }
        XCTFail("找菜操作应能完整滚入可点击区域：\(control.identifier)")
    }

    @MainActor
    func testGoalBrowsingOpensReviewedTextAndPreservesGoalWhenDeciding() {
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
        returnToFindRecipes(app)
        assertGoalValue("减少添加糖", in: app)
        revealCookingControl(app.buttons["discovery.decide"], in: app)
        app.buttons["discovery.decide"].tap()
        XCTAssertTrue(app.buttons["dietary.openReviewedRecipe"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testGoalSelectionIsSavedAndUpdatedInFiltersAcrossLaunches() {
        let app = app(showsIntroduction: true)
        select("balanced-pairing", in: app)
        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-dietary" }
        app.launch()
        assertGoalValue("均衡搭配", in: app)
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
        assertGoalValue("减少添加糖", in: app)

        app.buttons["清空"].tap()
        assertGoalValue("不限目标", in: app)
        let openedFilters = openGoalPicker(in: app)
        XCTAssertTrue(openedFilters)
        XCTAssertTrue(app.buttons["dietary.goal.none"].isSelected)
        finishGoalSelection(in: app, closingFilters: openedFilters)
        app.terminate()
        app.launch()
        assertGoalValue("减少添加糖", in: app)
        select("none", in: app)
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["cook.filters"].firstMatch.waitForExistence(timeout: 15))
        assertGoalValue("不限目标", in: app)
        let reopenedFilters = openGoalPicker(in: app)
        XCTAssertTrue(reopenedFilters)
        XCTAssertTrue(app.buttons["dietary.goal.none"].isSelected)
        assertRememberControlAbsent(in: app)
        finishGoalSelection(in: app, closingFilters: reopenedFilters)
    }

    @MainActor
    func testGoalEntryStaysInFiltersAcrossLaunchesAndSelectionRemainsVisible() {
        let app = app(showsIntroduction: true)
        let introduction = app.descendants(matching: .any).matching(identifier: "dietary.introduction").firstMatch
        XCTAssertFalse(introduction.exists)
        XCTAssertFalse(app.buttons["dietary.openPicker"].exists)
        capture(app, named: "dietary-filter-entry")

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
        let summary = app.buttons["cook.clearGoal"]
        XCTAssertTrue(summary.waitForExistence(timeout: 10))
        XCTAssertTrue(summary.label.contains("均衡搭配"))
        assertGoalValue("均衡搭配", in: app)
        capture(app, named: "dietary-compact-active")
        openFiltersGoalPicker(in: app)
        XCTAssertTrue(goal.waitForExistence(timeout: 10))
        XCTAssertTrue(goal.isSelected)
        finishGoalSelection(in: app, closingFilters: true)
    }

    @MainActor
    func testEmptyGoalCanBeCancelledWithoutLosingSelectedIngredients() {
        let app = app()
        select("less-added-sugar", in: app)
        let beef = app.buttons["牛肉"]
        XCTAssertTrue(beef.waitForExistence(timeout: 10))
        // Keep the whole chip above the fixed action bar before tapping it.
        let action = app.buttons["cook.showResults"]
        for _ in 0..<8 {
            if beef.isHittable && (app.windows.firstMatch.horizontalSizeClass == .regular || !action.exists || beef.frame.maxY < action.frame.minY) { break }
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
        if app.windows.firstMatch.horizontalSizeClass == .regular {
            XCTAssertTrue(app.buttons["dietary.clearGoal"].waitForExistence(timeout: 10))
        } else {
            XCTAssertFalse(action.isEnabled, "目标与食材没有匹配时不应打开空结果页")
        }
        // 无候选时 iPhone 的结果入口禁用，仍能在找菜页直接取消目标。
        let cancel = app.buttons["cook.clearGoal"]
        revealCookingControl(cancel, in: app, towardTop: true)
        XCTAssertTrue(cancel.waitForExistence(timeout: 10))
        cancel.tap()
        XCTAssertTrue(cancel.waitForNonExistence(timeout: 10))
        assertGoalValue("不限目标", in: app)
        revealCookingControl(beef, in: app)
        XCTAssertTrue(beef.isSelected)
        showResults(app)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已选：牛肉")).firstMatch.exists)
        let resultOptions = app.buttons["results.options"].firstMatch
        let openedFilters: Bool
        if resultOptions.exists && resultOptions.isHittable {
            openResultsGoalPicker(in: app)
            openedFilters = false
        } else {
            openedFilters = openGoalPicker(in: app)
        }
        XCTAssertTrue(app.buttons["dietary.goal.none"].isSelected)
        finishGoalSelection(in: app, closingFilters: openedFilters)
    }

    @MainActor
    func testClearingGoalFromResultsKeepsPickerPresentedUntilDone() {
        let app = app()
        select("less-added-sugar", in: app)
        showResults(app, standalone: true)
        openResultsGoalPicker(in: app)
        let none = app.buttons["dietary.goal.none"]
        XCTAssertTrue(none.waitForExistence(timeout: 10))
        none.tap()
        XCTAssertTrue(none.isSelected)
        let done = app.buttons["dietary.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        assertRememberControlAbsent(in: app)
        done.tap()
        assertGoalValue("不限目标", in: app)
        let candidate = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "dietary.recipe.")).firstMatch
        XCTAssertTrue(candidate.waitForExistence(timeout: 10), "取消目标后继续浏览当前目录，不退回空页面")
    }

    @MainActor
    func testCloudKitchenKeepsGoalAndCanBrowseWithoutIngredients() {
        let app = app()
        select("less-added-sugar", in: app)
        app.selectCookingPresentation("云厨房")
        let cook = app.buttons["kitchen.cook"]
        XCTAssertTrue(cook.waitForExistence(timeout: 15))
        XCTAssertTrue(cook.isEnabled)
        XCTAssertTrue(cook.label.contains("12"))
        assertGoalValue("减少添加糖", in: app)
        cook.tap()
        let recipe = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "dietary.recipe.")).firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 15))
    }

    @MainActor
    func testNativeGoalPickerAccessibilityAndReviewedSummary() throws {
        try verifyNativeGoalFlow(contentSize: .large, auditTypes: [.contrast, .dynamicType, .hitRegion, .sufficientElementDescription, .textClipped, .trait])
    }

    @MainActor
    func testLargestTextCanChooseSavedGoalReadAndDecide() throws {
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
        returnToFindRecipes(app)
        let decide = app.buttons["discovery.decide"]
        XCTAssertTrue(decide.isEnabled)
        capture(app, named: "dietary-accessibility-unified-find-recipes")
        decide.tap()
        let reviewed = app.buttons["dietary.openReviewedRecipe"]
        XCTAssertTrue(reviewed.waitForExistence(timeout: 10))
        reviewed.tap()
        XCTAssertTrue(app.otherElements["dietary.reviewSummary"].waitForExistence(timeout: 10))
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
        // Query the native list role instead of a whole-app Any lookup after scrolling.
        let results = app.collectionViews["dietary.results"]
        guard results.waitForExistence(timeout: 5) else {
            XCTFail("查找 \(identifier) 前未显示菜谱结果列表", file: file, line: line)
            return nil
        }
        for _ in 0..<maximumSwipes {
            guard app.state == .runningForeground else {
                XCTFail("查找 \(identifier) 时应用意外离开前台", file: file, line: line)
                return nil
            }
            guard results.exists else {
                XCTFail("查找 \(identifier) 时意外离开菜谱结果列表", file: file, line: line)
                return nil
            }
            if recipe.exists && recipe.isHittable { return recipe }
            let frame = results.frame
            // Use the app viewport without another lazy Window query after scrolling.
            let visibleFrame = frame.intersection(app.frame)
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
