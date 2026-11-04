import UIKit
import XCTest

final class DietaryGoalsUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testNextDayLunchAndWeightGoalIntersectAndShowSourceOilUnits() {
        verifyNextDayLunchAndWeightGoal(contentSize: .large)
    }

    @MainActor
    func testNextDayLunchAndWeightGoalAtAccessibilitySize() {
        verifyNextDayLunchAndWeightGoal(contentSize: .accessibilityExtraExtraExtraLarge)
    }

    @MainActor
    private func verifyNextDayLunchAndWeightGoal(contentSize: UIContentSizeCategory) {
        let app = self.app(contentSize: contentSize)
        let filters = app.buttons["cook.filters"].firstMatch
        XCTAssertTrue(filters.waitForExistence(timeout: 15))
        filters.tap()
        let options = app.collectionViews["filter.options"]
        let lunch = options.buttons["discovery.scene.next-day-lunch"]
        XCTAssertTrue(lunch.waitForExistence(timeout: 10))
        app.revealFilterOption(lunch)
        lunch.tap()
        XCTAssertEqual(lunch.waitForState("isSelected == true", timeout: 10), .completed)
        let count = app.descendants(matching: .any).matching(identifier: "filter.resultCount").firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: 10))
        XCTAssertEqual(count.label.filter { $0.isASCII && $0.isNumber }, "10")
        XCTAssertTrue(options.staticTexts["discovery.explanation"].label.contains("冷藏"))
        app.buttons["filter.done"].tap()

        select("weight-aware", in: app)
        filters.tap()
        XCTAssertTrue(count.waitForExistence(timeout: 10))
        XCTAssertEqual(count.label.filter { $0.isASCII && $0.isNumber }, "9")
        app.buttons["filter.done"].tap()
        showResults(app)
        guard let recipe = revealRecipe("dietary.recipe.333", in: app, maximumSwipes: 5) else { return }
        recipe.tap()
        let reviewed = app.buttons["dietary.openReviewedRecipe"]
        XCTAssertTrue(reviewed.waitForExistence(timeout: 10))
        reviewed.tap()
        let reason = app.staticTexts["dietary.reviewReason"]
        XCTAssertTrue(reason.waitForExistence(timeout: 10))
        XCTAssertTrue(reason.label.contains("5–10 ml"), reason.label)
        capture(app, named: "weight-aware-next-day-lunch-source-units-\(contentSize.rawValue)")
    }
    @MainActor
    private func app(contentSize: UIContentSizeCategory = .large, showsIntroduction: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-dietary", "-ui-testing-reset-presentation",
                               "-UIPreferredContentSizeCategoryName", contentSize.rawValue]
        if showsIntroduction { app.launchArguments.append("-ui-testing-show-dietary-introduction") }
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        terminateAppAfterTest(app)
        app.launch()
        let presentation = app.cookingToolbarButton("cook.presentation")
        XCTAssertTrue(presentation.waitForExistence(timeout: 15))
        app.selectCookingPresentation("食材快选")
        return app
    }

    @MainActor
    private func goalOption(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.collectionViews.matching(identifier: "dietary.choices").firstMatch
            .buttons.matching(identifier: identifier).firstMatch
    }

    @MainActor
    private func select(_ goal: String, in app: XCUIApplication) {
        let openedFilters = openGoalPicker(in: app)
        let option = goalOption("dietary.goal.\(goal)", in: app)
        XCTAssertTrue(option.waitForExistence(timeout: 10))
        XCTAssertTrue(app.collectionViews.matching(identifier: "dietary.choices").firstMatch
            .buttons.matching(identifier: "dietary.goal.weight-aware").firstMatch.exists)
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
    private func openFilterOptions(in app: XCUIApplication,
                                   file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let filters = app.cookingToolbarButton("cook.filters")
        XCTAssertEqual(filters.waitForState("exists == true AND isHittable == true", timeout: 15), .completed,
                       file: file, line: line)
        filters.tap()
        // Require the presented sheet's own toolbar before snapshotting its List.
        let done = app.navigationBars.matching(identifier: "筛选").firstMatch
            .buttons.matching(identifier: "filter.done").firstMatch
        XCTAssertEqual(done.waitForState("exists == true AND isHittable == true", timeout: 10), .completed,
                       "筛选弹层应完成呈现并可操作", file: file, line: line)
        let options = app.collectionViews.matching(identifier: "filter.options").firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 10), file: file, line: line)
        return options
    }

    @MainActor
    private func waitForGoalPicker(in app: XCUIApplication) {
        let done = app.navigationBars.matching(identifier: "饮食目标").firstMatch
            .buttons.matching(identifier: "dietary.done").firstMatch
        XCTAssertEqual(done.waitForState("exists == true AND isHittable == true", timeout: 10), .completed,
                       "饮食目标弹层应完成呈现并可操作")
        XCTAssertTrue(app.collectionViews.matching(identifier: "dietary.choices").firstMatch.waitForExistence(timeout: 10))
    }

    @MainActor
    private func openFiltersGoalPicker(in app: XCUIApplication) {
        let picker = openFilterOptions(in: app).buttons.matching(identifier: "dietary.openPicker").firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        picker.tap()
        waitForGoalPicker(in: app)
    }

    @MainActor
    private func finishGoalSelection(in app: XCUIApplication, closingFilters: Bool = false) {
        let activeControl = closingFilters
            ? app.cookingToolbarButton("cook.filters")
            : app.navigationBars.buttons.matching(identifier: "results.options").firstMatch
        app.completeDietarySelection(closingFilters: closingFilters, returningTo: activeControl)
    }

    @MainActor
    private func openResultsGoalPicker(in app: XCUIApplication) {
        let options = app.navigationBars.buttons.matching(identifier: "results.options").firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 10))
        options.tap()
        let goal = app.buttons["results.dietaryGoal"]
        XCTAssertTrue(goal.waitForExistence(timeout: 10))
        goal.tap()
        waitForGoalPicker(in: app)
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
    private func assertGoalValue(_ value: String, in app: XCUIApplication, standaloneResults: Bool = false,
                                 file: StaticString = #filePath, line: UInt = #line) {
        // The control belongs to the navigation bar, not the potentially large
        // recipe list that appears after removing the dietary goal.
        let resultsOptions = app.navigationBars.buttons.matching(identifier: "results.options").firstMatch
        if standaloneResults {
            XCTAssertEqual(resultsOptions.waitForState("exists == true AND isHittable == true", timeout: 10), .completed, file: file, line: line)
            let expectedLabel = "更多选项，饮食目标：\(value)"
            let result: XCTWaiter.Result = resultsOptions.wait(for: \.label, toEqual: expectedLabel, timeout: 10) ? .completed : .timedOut
            if result != .completed {
                let hierarchy = XCTAttachment(string: app.debugDescription)
                hierarchy.name = "results-goal-accessibility"
                hierarchy.lifetime = .keepAlways
                add(hierarchy)
                capture(app, named: "results-goal-value-mismatch")
            }
            XCTAssertEqual(result, .completed, file: file, line: line)
            return
        }
        let filters = app.cookingToolbarButton("cook.filters")
        let picker = openFilterOptions(in: app, file: file, line: line)
            .buttons.matching(identifier: "dietary.openPicker").firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 10), file: file, line: line)
        XCTAssertEqual(picker.value as? String, value, file: file, line: line)
        let done = app.navigationBars.matching(identifier: "筛选").firstMatch.buttons.matching(identifier: "filter.done").firstMatch
        done.tap()
        XCTAssertEqual(filters.waitForState("exists == true AND isHittable == true", timeout: 10), .completed, file: file, line: line)
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
        revealCookingControl(app.collectionViews.matching(identifier: "cook.chooser").firstMatch
            .buttons.matching(identifier: "discovery.decide").firstMatch, in: app)
    }

    @MainActor
    private func revealCookingControl(_ control: XCUIElement, in app: XCUIApplication, towardTop: Bool = false) {
        let chooser = app.collectionViews.matching(identifier: "cook.chooser").firstMatch
        XCTAssertTrue(chooser.waitForExistence(timeout: 10))
        let window = app.windows.firstMatch
        let windowFrame = app.frame
        // Collection containers need not have their own tap point on older iOS.
        // The page must be interactive; the individual target is checked below.
        XCTAssertEqual(app.cookingToolbarButton("cook.filters")
            .waitForState("exists == true AND isHittable == true", timeout: 10), .completed)
        let bar = app.navigationBars.matching(identifier: "找菜").firstMatch
        let action: XCUIElement? = window.horizontalSizeClass == .regular
            ? nil : app.buttons.matching(identifier: "cook.showResults").firstMatch
        // Navigation and footer boundaries stay fixed during this scroll. Resolve them once;
        // repeated Window/CollectionView snapshots can stall on a busy split-view runner.
        let navigationBottom = bar.frame.maxY
        let actionTop: CGFloat
        if let action {
            XCTAssertTrue(action.waitForExistence(timeout: 10), "找菜页应显示固定结果入口")
            // Empty matches intentionally disable this button, but it still covers content.
            if action.isEnabled {
                XCTAssertEqual(action.waitForState("isHittable == true", timeout: 10), .completed,
                               "有候选时，固定结果入口应恢复可操作")
            }
            let actionFrame = action.frame
            XCTAssertTrue(!actionFrame.isEmpty && windowFrame.intersects(actionFrame), "固定结果入口应在窗口中可见")
            actionTop = actionFrame.minY
        } else {
            actionTop = windowFrame.maxY
        }
        var lastViewport = CGRect.null
        var lastControlFrame = CGRect.null
        for _ in 0..<30 {
            let chooserFrame = chooser.frame
            XCTAssertTrue(windowFrame.intersects(chooserFrame), "找菜列表应在当前窗口中可见")
            let frame = control.exists ? control.frame : CGRect.null
            lastControlFrame = frame
            let hasFrame = !frame.isNull && !frame.isEmpty
            let top = max(chooserFrame.minY, navigationBottom) + 8
            let bottom = min(chooserFrame.maxY, actionTop) - 8
            let viewport = CGRect(x: chooserFrame.minX, y: top, width: chooserFrame.width, height: bottom - top)
            lastViewport = viewport
            guard viewport.height > 0 else { break }
            if hasFrame && frame.minY >= top && frame.maxY <= bottom && control.isHittable { return }

            let scrollBack = hasFrame ? frame.minY < top : towardTop
            let needed = hasFrame ? (scrollBack ? top - frame.minY : frame.maxY - bottom) + 12 : viewport.height * 0.45
            // A full swipe can jump from below the fixed footer to above the navigation bar.
            // Drag only the measured overlap and hold before release to avoid a fling back past it.
            let distance = min(max(needed, 44), viewport.height * 0.45)
            let startY = viewport.midY + (scrollBack ? -distance / 2 : distance / 2)
            let endY = viewport.midY + (scrollBack ? distance / 2 : -distance / 2)
            let origin = window.coordinate(withNormalizedOffset: .zero)
            origin.withOffset(CGVector(dx: viewport.midX - windowFrame.minX, dy: startY - windowFrame.minY))
                .press(forDuration: 0.1,
                       thenDragTo: origin.withOffset(CGVector(dx: viewport.midX - windowFrame.minX, dy: endY - windowFrame.minY)),
                       withVelocity: .slow, thenHoldForDuration: 0.2)
        }
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "find-recipes-control-accessibility"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
        capture(app, named: "find-recipes-control-not-visible")
        XCTFail("找菜操作应能完整滚入可点击区域：\(control.identifier)，控件：\(lastControlFrame)，可见区域：\(lastViewport)")
    }

    @MainActor
    func testGoalBrowsingOpensReviewedTextAndPreservesGoalWhenDeciding() {
        let app = app(showsIntroduction: true)
        select("less-added-sugar", in: app)
        XCTAssertFalse(app.collectionViews.matching(identifier: "cook.chooser").firstMatch
            .descendants(matching: .any).matching(identifier: "dietary.introduction").firstMatch.exists)
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
        let article = app.descendants(matching: .any).matching(identifier: "recipe.textContent").firstMatch
        XCTAssertTrue(article.waitForExistence(timeout: 10))
        XCTAssertFalse(article.staticTexts.matching(NSPredicate(format: "label == %@", "预估热量")).firstMatch.exists)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "dietary-reviewed-source"
        attachment.lifetime = .keepAlways
        add(attachment)
        returnToFindRecipes(app)
        assertGoalValue("减少添加糖", in: app)
        revealCookingControl(app.collectionViews.matching(identifier: "cook.chooser").firstMatch
            .buttons.matching(identifier: "discovery.decide").firstMatch, in: app)
        app.buttons["discovery.decide"].tap()
        app.confirmDecidedRecipe()
        XCTAssertTrue(app.buttons["dietary.openReviewedRecipe"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testGoalSelectionIsSavedAndUpdatedInFiltersAcrossLaunches() {
        // This persistence journey intentionally launches the App several times.
        executionTimeAllowance = 600
        let app = app(showsIntroduction: true)
        select("balanced-pairing", in: app)
        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-dietary" }
        app.launch()
        assertGoalValue("均衡搭配", in: app)
        XCTAssertFalse(app.collectionViews.matching(identifier: "cook.chooser").firstMatch
            .descendants(matching: .any).matching(identifier: "dietary.introduction").firstMatch.exists)

        openFiltersGoalPicker(in: app)
        let updatedGoal = goalOption("dietary.goal.less-added-sugar", in: app)
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
        XCTAssertTrue(goalOption("dietary.goal.none", in: app).isSelected)
        finishGoalSelection(in: app, closingFilters: openedFilters)
        app.terminate()
        app.launch()
        assertGoalValue("减少添加糖", in: app)
        select("none", in: app)
        app.terminate()
        app.launch()
        XCTAssertTrue(app.cookingToolbarButton("cook.filters").waitForExistence(timeout: 15))
        assertGoalValue("不限目标", in: app)
        let reopenedFilters = openGoalPicker(in: app)
        XCTAssertTrue(reopenedFilters)
        XCTAssertTrue(goalOption("dietary.goal.none", in: app).isSelected)
        assertRememberControlAbsent(in: app)
        finishGoalSelection(in: app, closingFilters: reopenedFilters)
    }

    @MainActor
    func testGoalEntryStaysInFiltersAcrossLaunchesAndSelectionRemainsVisible() {
        let app = app(showsIntroduction: true)
        let introduction = app.collectionViews.matching(identifier: "cook.chooser").firstMatch
            .descendants(matching: .any).matching(identifier: "dietary.introduction").firstMatch
        XCTAssertFalse(introduction.exists)
        XCTAssertFalse(app.collectionViews.matching(identifier: "cook.chooser").firstMatch
            .buttons.matching(identifier: "dietary.openPicker").firstMatch.exists)
        capture(app, named: "dietary-filter-entry")

        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-dietary" }
        app.launch()
        XCTAssertTrue(app.cookingToolbarButton("cook.filters").waitForExistence(timeout: 15))
        XCTAssertFalse(introduction.exists)
        XCTAssertFalse(app.collectionViews.matching(identifier: "cook.chooser").firstMatch
            .buttons.matching(identifier: "dietary.openPicker").firstMatch.exists)

        let openedFilters = openGoalPicker(in: app)
        XCTAssertTrue(openedFilters)
        let goal = goalOption("dietary.goal.balanced-pairing", in: app)
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
        // Slow CI snapshots completed this multi-page journey at 360s without
        // an assertion failure; keep a bound that covers the complete workflow.
        executionTimeAllowance = 600
        let app = app()
        select("less-added-sugar", in: app)
        // Resolve the unique ingredient directly; retain the user-facing label assertion.
        let beef = app.buttons.matching(identifier: "cook.ingredient.牛肉").firstMatch
        XCTAssertTrue(beef.waitForExistence(timeout: 10))
        XCTAssertEqual(beef.label, "牛肉")
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
        XCTAssertEqual(beef.waitForState("isSelected == true", timeout: 10), .completed)
        if app.windows.firstMatch.horizontalSizeClass == .regular {
            XCTAssertTrue(app.collectionViews.matching(identifier: "dietary.results").firstMatch
                .buttons.matching(identifier: "dietary.clearGoal").firstMatch.waitForExistence(timeout: 10))
        } else {
            XCTAssertFalse(action.isEnabled, "目标与食材没有匹配时不应打开空结果页")
        }
        // 无候选时 iPhone 的结果入口禁用，仍能在找菜页直接取消目标。
        let cancel = app.collectionViews.matching(identifier: "cook.chooser").firstMatch
            .buttons.matching(identifier: "cook.clearGoal").firstMatch
        revealCookingControl(cancel, in: app, towardTop: true)
        XCTAssertTrue(cancel.waitForExistence(timeout: 10))
        cancel.tap()
        XCTAssertTrue(cancel.waitForNonExistence(timeout: 10))
        assertGoalValue("不限目标", in: app)
        revealCookingControl(beef, in: app)
        XCTAssertTrue(beef.isSelected)
        showResults(app)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已选：牛肉")).firstMatch.exists)
        let openedFilters: Bool
        if app.windows.firstMatch.horizontalSizeClass == .regular {
            openedFilters = openGoalPicker(in: app)
        } else {
            openResultsGoalPicker(in: app)
            openedFilters = false
        }
        XCTAssertTrue(goalOption("dietary.goal.none", in: app).isSelected)
        finishGoalSelection(in: app, closingFilters: openedFilters)
    }

    @MainActor
    func testClearingGoalFromResultsKeepsPickerPresentedUntilDone() {
        let app = app()
        select("less-added-sugar", in: app)
        showResults(app, standalone: true)
        openResultsGoalPicker(in: app)
        let none = goalOption("dietary.goal.none", in: app)
        XCTAssertTrue(none.waitForExistence(timeout: 10))
        none.tap()
        XCTAssertTrue(none.isSelected)
        let done = app.navigationBars.matching(identifier: "饮食目标").firstMatch.buttons.matching(identifier: "dietary.done").firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        assertRememberControlAbsent(in: app)
        done.tap()
        assertGoalValue("不限目标", in: app, standaloneResults: true)
        let candidate = app.collectionViews.matching(identifier: "dietary.results").firstMatch
            .buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "dietary.recipe.")).firstMatch
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
        let recipe = app.collectionViews.matching(identifier: "dietary.results").firstMatch
            .buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "dietary.recipe.")).firstMatch
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
        let option = goalOption("dietary.goal.less-added-sugar", in: app)
        for _ in 0..<8 {
            if option.exists && option.isHittable { break }
            app.swipeUp()
        }
        // The sheet's List can relayout after its first hit-test snapshot.
        // Synchronize the actual interaction instead of asserting a second
        // instantaneous snapshot immediately after the scrolling check.
        XCTAssertEqual(option.waitForState("exists == true AND isHittable == true", timeout: 10), .completed)
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
        let decide = app.collectionViews.matching(identifier: "cook.chooser").firstMatch
            .buttons.matching(identifier: "discovery.decide").firstMatch
        XCTAssertTrue(decide.isEnabled)
        capture(app, named: "dietary-accessibility-unified-find-recipes")
        decide.tap()
        app.confirmDecidedRecipe()
        let reviewed = app.buttons["dietary.openReviewedRecipe"]
        XCTAssertTrue(reviewed.waitForExistence(timeout: 10))
        reviewed.tap()
        XCTAssertTrue(app.otherElements["dietary.reviewSummary"].waitForExistence(timeout: 10))
    }

    @MainActor
    private func assertRememberControlAbsent(in app: XCUIApplication,
                                            file: StaticString = #filePath, line: UInt = #line) {
        // The policy covers the choice list and its toolbar, not unrelated app tabs.
        let choices = app.collectionViews.matching(identifier: "dietary.choices").firstMatch
        let navigation = app.navigationBars.matching(identifier: "饮食目标").firstMatch
        for container in [choices, navigation] {
            XCTAssertTrue(container.waitForExistence(timeout: 10), file: file, line: line)
            XCTAssertFalse(container.buttons.matching(identifier: "dietary.remember").firstMatch.exists, file: file, line: line)
            XCTAssertFalse(container.switches.matching(identifier: "dietary.remember").firstMatch.exists, file: file, line: line)
        }
    }

    @MainActor
    private func revealRecipe(_ identifier: String, in app: XCUIApplication, maximumSwipes: Int,
                              file: StaticString = #filePath, line: UInt = #line) -> XCUIElement? {
        // Keep each row lookup inside the confirmed result list.
        let results = app.collectionViews.matching(identifier: "dietary.results").firstMatch
        let recipe = results.buttons.matching(identifier: identifier).firstMatch
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
