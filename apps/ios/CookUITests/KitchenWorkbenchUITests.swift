import XCTest

final class KitchenWorkbenchUITests: XCTestCase {
    @MainActor
    func testTabletopKeepsTimerReachableAndPreservesProgressAcrossLayouts() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openKitchen(app)
        chooseLayout("台面", in: app)
        let start = app.buttons["开始计时"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        let heading = app.staticTexts["recipe.stepProgress"]
        XCTAssertTrue(heading.isHittable)
        XCTAssertLessThan(heading.frame.maxY, start.frame.minY, "做法在上，计时操作在下")
        let egg = app.buttons["kitchen.ingredient.鸡蛋"]
        XCTAssertTrue(egg.isHittable)
        egg.tap()
        XCTAssertEqual(egg.value as? String, "已备好")
        start.tap()
        app.buttons["暂停"].tap()
        let paused = app.staticTexts["kitchen.countdown"].label
        let countdownFrame = app.staticTexts["kitchen.countdown"].frame
        if app.windows.firstMatch.frame.width >= 390 {
            XCTAssertEqual(app.buttons["继续"].frame.minY, app.buttons["重置"].frame.minY, accuracy: 1,
                           "默认字号下计时按钮并排，给材料清单留出空间")
        }
        capture(app, "kitchen-tabletop")

        // 滚动下半屏的材料时，计时操作保持可见且位置不变。
        egg.swipeUp()
        XCTAssertTrue(app.buttons["继续"].isHittable)
        XCTAssertEqual(app.staticTexts["kitchen.countdown"].frame, countdownFrame)
        capture(app, "kitchen-tabletop-scrolled")

        chooseLayout("自动", in: app)
        chooseLayout("台面", in: app)
        XCTAssertEqual(app.staticTexts["kitchen.countdown"].label, paused)
        capture(app, "kitchen-tabletop-layout-restored")
        let preparation = app.otherElements["kitchen.preparation"]
        XCTAssertTrue(preparation.waitForExistence(timeout: 5))
        for _ in 0..<6 {
            if egg.exists && egg.isHittable { break }
            // 布局切换会保留列表位置；离屏行的坐标可能落在固定计时栏后面。
            // 只在计时栏下方的可见列表中拖动，避免误触“继续”。
            let frame = preparation.frame
            let top = max(frame.minY, app.buttons["重置"].frame.maxY) + 24
            let bottom = frame.maxY - 24
            XCTAssertGreaterThan(bottom - top, 44)
            let origin = app.windows.firstMatch.coordinate(withNormalizedOffset: .zero)
            origin.withOffset(CGVector(dx: frame.midX, dy: top))
                .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: frame.midX, dy: bottom)))
        }
        XCTAssertTrue(egg.exists && egg.isHittable)
        XCTAssertEqual(egg.value as? String, "已备好")
        XCTAssertEqual(app.staticTexts["kitchen.countdown"].label, paused)
        capture(app, "kitchen-tabletop-ingredient-restored")
        app.buttons["继续"].tap()
        XCTAssertTrue(app.buttons["暂停"].waitForExistence(timeout: 10))
        app.buttons["重置"].tap()
        XCTAssertEqual(app.staticTexts["kitchen.countdown"].label, "05:00")
    }

    @MainActor
    private func chooseLayout(_ layout: String, in app: XCUIApplication) {
        app.buttons["kitchen.layout"].tap()
        app.buttons[layout].tap()
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons[layout])
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 5), .completed)
    }


    @MainActor
    func testSupplementaryReferenceLocatesStepWithoutCompletingIt() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openKitchen(app)

        let reference = app.buttons["recipe.stepReference.10"]
        let window = app.windows.firstMatch
        for _ in 0..<12 where !reference.isHittable {
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.30, dy: 0.84))
                .press(forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.30, dy: 0.30)))
        }
        XCTAssertTrue(reference.isHittable)
        let lastText = app.staticTexts["recipe.stepText.11"]
        let lastButton = app.buttons["recipe.step.11"]
        XCTAssertTrue(lastText.isHittable)
        XCTAssertEqual(lastText.frame.midY, lastButton.frame.midY, accuracy: 1.5, "单行文字与编号垂直居中")
        capture(app, "supplementary-step-link")

        let progress = app.staticTexts["recipe.stepProgress"].label
        let target = app.buttons["recipe.step.10"]
        reference.tap()
        let targetCard = app.otherElements["recipe.stepCard.10"]
        XCTAssertEqual(targetCard.value as? String, "已定位", "即使目标已在可视区域，也明确高亮对应步骤")
        capture(app, "supplementary-jump-result")
        waitForVisibleStep(target, in: app)
        XCTAssertEqual(target.value as? String, "未完成")
        XCTAssertEqual(app.staticTexts["recipe.stepProgress"].label, progress)
        let faded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (targetCard.value as? String) != "已定位"
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [faded], timeout: 10), .completed)

        // 再次点击仍可定位；跳转不依赖某个已改变的选择状态。
        for _ in 0..<8 where !reference.isHittable {
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.30, dy: 0.84))
                .press(forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.30, dy: 0.45)))
        }
        reference.tap()
        XCTAssertEqual(targetCard.value as? String, "已定位")
        waitForVisibleStep(target, in: app)
        XCTAssertEqual(app.staticTexts["recipe.stepProgress"].label, progress)
    }

    @MainActor
    private func waitForVisibleStep(_ step: XCUIElement, in app: XCUIApplication) {
        var previousFrame = CGRect.null
        let located = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let frame = step.frame
            defer { previousFrame = frame }
            let window = app.windows.firstMatch.frame
            let headerBottom = app.buttons["recipe.continueStep"].frame.maxY
            let timer = app.buttons["kitchen.timerShortcut"]
            let bottom = timer.exists ? timer.frame.minY : window.maxY
            // 目标原先可能在屏幕上方，正确定位反而会增大 y；验证可见区域与动画完成。
            // 不在滚动动画中询问点击位置，避免 XCTest 对被裁剪按钮抛出定位错误。
            guard !frame.isEmpty && frame == previousFrame && window.contains(frame)
                && frame.minY >= headerBottom && frame.maxY <= bottom else { return false }
            // The model frame can settle before the native hit-test state during a scroll.
            return step.isHittable
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [located], timeout: 10), .completed)
        XCTAssertTrue(step.isHittable)
    }

    @MainActor
    func testServingsSynchronizeQuantitiesPreparationAndRestore() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openKitchen(app)
        let recipeServings = app.steppers["recipe.servings"]
        XCTAssertTrue(recipeServings.waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["recipe.amount.鸡蛋"].label, "2 个")
        let stepText = app.staticTexts["recipe.stepText.4"]
        XCTAssertTrue(stepText.label.contains("1 g 的盐"))
        let oilStep = app.staticTexts["recipe.stepText.5"]
        let remainingSalt = app.staticTexts["recipe.stepText.10"]
        XCTAssertTrue(oilStep.label.contains("食用油（8 ml）"))
        XCTAssertTrue(remainingSalt.label.contains("剩余的盐（0.5–1 g）"))
        recipeServings.buttons.element(boundBy: 1).tap()
        recipeServings.buttons.element(boundBy: 1).tap()
        XCTAssertEqual(app.staticTexts["recipe.amount.鸡蛋"].label, "5 个")
        XCTAssertEqual(app.staticTexts["recipe.amount.食用油"].label, "20 ml")
        XCTAssertEqual(app.staticTexts["recipe.amount.盐"].label, "4.5–6 g")
        XCTAssertTrue(stepText.label.contains("3 g 的盐"), "步骤中的分次用量独立于整菜总量")
        XCTAssertFalse(stepText.label.contains("份数"))
        XCTAssertTrue(stepText.label.contains("1ml 醋"), "原文固定的可选用量保留")
        XCTAssertTrue(oilStep.label.contains("食用油（20 ml）"))
        XCTAssertTrue(remainingSalt.label.contains("剩余的盐（1.5–3 g）"))
        app.buttons["recipe.continueStep"].tap()
        let step = app.buttons["recipe.step.4"]
        step.tap()
        XCTAssertEqual(step.value as? String, "已完成")
        XCTAssertTrue(remainingSalt.label.contains("剩余的盐（1.5–3 g）"), "勾选不重复扣减用量")
        capture(app, "recipe-step-three-servings")
        let left = app.windows.firstMatch
        left.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.85))
            .press(forDuration: 0.1, thenDragTo: left.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.55)))
        capture(app, "recipe-remaining-salt")
        if app.segmentedControls.buttons["备料与计时"].exists {
            app.segmentedControls.buttons["备料与计时"].tap()
        }
        let egg = app.buttons["kitchen.ingredient.鸡蛋"]
        XCTAssertTrue(egg.label.contains("5 个"))
        XCTAssertTrue(app.buttons["kitchen.ingredient.食用油"].label.contains("20 ml"))
        egg.tap()
        XCTAssertEqual(egg.value as? String, "已备好")
        capture(app, "recipe-three-servings")
        let kitchenServings = app.steppers["kitchen.servings"]
        XCTAssertEqual(kitchenServings.value as? String, "3 份")
        kitchenServings.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(egg.label.contains("3 个"))
        XCTAssertEqual(egg.value as? String, "未备好")
        if app.segmentedControls.buttons["做法"].exists { app.segmentedControls.buttons["做法"].tap() }
        XCTAssertEqual(recipeServings.value as? String, "2 份")
        XCTAssertEqual(app.staticTexts["recipe.amount.鸡蛋"].label, "3 个")
        XCTAssertTrue(stepText.label.contains("2 g 的盐"))
        XCTAssertTrue(oilStep.label.contains("食用油（12 ml）"))
        XCTAssertTrue(remainingSalt.label.contains("剩余的盐（1–2 g）"))
        XCTAssertEqual(step.value as? String, "已完成", "份数变化不改变步骤身份或完成记录")
        app.buttons["kitchen.close"].tap()
        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-kitchen" }
        app.launch()
        openKitchen(app)
        XCTAssertEqual(recipeServings.value as? String, "2 份")
        XCTAssertEqual(app.staticTexts["recipe.amount.食用油"].label, "12 ml")
        XCTAssertTrue(stepText.label.contains("2 g 的盐"))
        XCTAssertEqual(step.value as? String, "已完成")
        XCTAssertTrue(remainingSalt.label.contains("剩余的盐（1–2 g）"))
        capture(app, "recipe-servings-restored")
    }

    @MainActor
    func testPreparationTimerLayoutAndReopening() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openKitchen(app)
        XCTAssertTrue(app.staticTexts["recipe.stepProgress"].waitForExistence(timeout: 10))
        let isWide = !app.segmentedControls.buttons["备料与计时"].exists
        if isWide {
            XCTAssertFalse(app.segmentedControls.buttons["备料与计时"].exists)
        } else {
            let steps = app.staticTexts["操作"]
            for _ in 0 ..< 6 where !steps.isHittable { app.swipeUp() }
            XCTAssertTrue(steps.isHittable)
            app.segmentedControls.buttons["备料与计时"].tap()
            app.segmentedControls.buttons["做法"].tap()
            XCTAssertTrue(steps.isHittable, "查看备料再回来时保留阅读位置")
            app.segmentedControls.buttons["备料与计时"].tap()
        }

        let tomato = app.buttons["kitchen.ingredient.鸡蛋"]
        XCTAssertTrue(tomato.waitForExistence(timeout: 10))
        XCTAssertEqual(tomato.value as? String, "未备好")
        tomato.tap()
        XCTAssertEqual(tomato.value as? String, "已备好")
        app.buttons["开始计时"].tap()
        XCTAssertTrue(app.buttons["暂停"].waitForExistence(timeout: 10))
        app.buttons["暂停"].tap()
        XCTAssertTrue(app.staticTexts["已暂停"].waitForExistence(timeout: 10))
        let paused = app.staticTexts["kitchen.countdown"].label
        capture(app, isWide ? "kitchen-wide" : "kitchen-compact")

        // 旋转触发布局重建，备料与计时状态仍由外层会话持有。
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForOrientation(app, landscape: true)
        if app.segmentedControls.buttons["备料与计时"].exists {
            app.segmentedControls.buttons["备料与计时"].tap()
        }
        XCTAssertEqual(tomato.value as? String, "已备好")
        XCTAssertEqual(app.staticTexts["kitchen.countdown"].label, paused)
        capture(app, "kitchen-landscape")
        XCUIDevice.shared.orientation = .portrait
        waitForOrientation(app, landscape: false)

        app.buttons["kitchen.close"].tap()
        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-kitchen" }
        app.launch()
        openKitchen(app)
        if app.segmentedControls.buttons["备料与计时"].exists {
            app.segmentedControls.buttons["备料与计时"].tap()
        }
        XCTAssertEqual(tomato.value as? String, "已备好")
        XCTAssertTrue(app.staticTexts["已暂停"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["kitchen.countdown"].label, paused)
        app.buttons["继续"].tap()
        XCTAssertTrue(app.buttons["暂停"].waitForExistence(timeout: 10))
        app.buttons["重置"].tap()
        XCTAssertTrue(app.buttons["开始计时"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["kitchen.countdown"].label, "05:00")
    }

    @MainActor
    func testStepsCanBeCompletedUndoneAndRestored() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openKitchen(app)
        let progress = app.staticTexts["recipe.stepProgress"]
        XCTAssertTrue(progress.waitForExistence(timeout: 10))
        XCTAssertEqual(progress.label, "已完成 0 / 11 步")
        capture(app, "recipe-overview")
        app.buttons["recipe.continueStep"].tap()
        let step = app.buttons["recipe.step.1"]
        waitForVisibleStep(step, in: app)
        XCTAssertGreaterThan(step.frame.width, 250, "整条卡片都是按钮，而非只有编号")
        let currentTop = step.frame.minY
        // 特意点击正文右侧的留白，防止回归为只有文字或编号可点。
        step.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap()
        XCTAssertEqual(step.value as? String, "已完成")
        XCTAssertEqual(progress.label, "已完成 1 / 11 步")
        let next = app.buttons["recipe.step.2"]
        waitForVisibleStep(next, in: app)
        XCTAssertEqual(next.frame.minY, currentTop, accuracy: 1, "下一步自动滚动到原来当前步骤的位置")
        XCTAssertEqual(app.buttons["recipe.continueStep"].label, "继续第 2 步")

        // 自动推进后回看上一条；点击正文可撤销，撤销时不强制滚动。
        let window = app.windows.firstMatch
        for _ in 0..<4 {
            // XCTest 会把固定进度栏后面的行也报告为 hittable，需确认整行已离开遮挡区域。
            if step.frame.minY >= app.buttons["recipe.continueStep"].frame.maxY + 20 { break }
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.30, dy: 0.45))
                .press(forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.30, dy: 0.70)))
        }
        waitForVisibleStep(step, in: app)
        let previousFrame = step.frame
        app.staticTexts["recipe.stepText.1"].tap()
        XCTAssertEqual(step.value as? String, "未完成")
        XCTAssertEqual(progress.label, "已完成 0 / 11 步")
        XCTAssertEqual(step.frame, previousFrame, "撤销时保留阅读位置，状态变化不改变卡片高度")
        app.staticTexts["recipe.stepText.1"].tap()
        waitForVisibleStep(next, in: app)
        capture(app, "recipe-step-progress")
        app.buttons["kitchen.close"].tap()
        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-kitchen" }
        app.launch()
        openKitchen(app)
        XCTAssertEqual(progress.label, "已完成 1 / 11 步")
        XCTAssertEqual(app.buttons["recipe.continueStep"].label, "继续第 2 步")
        let resetControl = app.buttons["recipe.resetSteps"]
        XCTAssertGreaterThanOrEqual(resetControl.frame.width, 44)
        XCTAssertGreaterThanOrEqual(resetControl.frame.height, 44)
        resetControl.tap()
        // Native confirmation actions may discard SwiftUI identifiers. Exclude the trigger
        // explicitly and wait for the separate action, on both sheets and iPad popovers.
        let resetConfirmation = app.buttons.matching(NSPredicate(
            format: "label == %@ AND identifier != %@", "重置步骤", "recipe.resetSteps"
        )).firstMatch
        XCTAssertTrue(resetConfirmation.waitForExistence(timeout: 10))
        resetConfirmation.tap()
        XCTAssertEqual(progress.label, "已完成 0 / 11 步")

        app.buttons["recipe.continueStep"].tap()
        for number in 1...11 {
            let current = app.buttons["recipe.step.\(number)"]
            waitForVisibleStep(current, in: app)
            current.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap()
            XCTAssertEqual(current.value as? String, "已完成")
            XCTAssertEqual(progress.label, "已完成 \(number) / 11 步")
        }
        XCTAssertTrue(app.staticTexts["做好了，开饭吧"].exists)
        XCTAssertFalse(app.buttons["recipe.continueStep"].exists)
        capture(app, "recipe-steps-finished")
    }

    @MainActor
    func testWholeRowsAdvanceAcrossRecipeSections() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openKitchen(app, recipeName: "尖椒炒牛肉")
        let progress = app.staticTexts["recipe.stepProgress"]
        XCTAssertEqual(progress.label, "已完成 0 / 13 步")
        app.buttons["recipe.continueStep"].tap()

        let instructions = ["蒜剁成蒜泥", "葱切段", "姜切成姜片", "尖椒切成段", "牛肉放入碗中"]
        for (index, instruction) in instructions.enumerated() {
            // 章节内编号会重新开始，使用正文区分不同章节的同号步骤。
            let step = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "recipe.step.", instruction)).firstMatch
            waitForVisibleStep(step, in: app)
            if index == 1 { capture(app, "pepper-beef-current-step") }
            if index == 4 { capture(app, "pepper-beef-next-section") }
            step.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap()
            XCTAssertEqual(step.value as? String, "已完成")
            XCTAssertEqual(progress.label, "已完成 \(index + 1) / 13 步")
        }
    }

    @MainActor
    private func openKitchen(_ app: XCUIApplication, recipeName: String = "番茄炒蛋") {
        let library = app.buttons.matching(NSPredicate(format: "label == %@", "菜谱")).firstMatch
        XCTAssertTrue(library.waitForExistence(timeout: 20))
        library.tap()
        let search = app.searchFields["搜索菜名或食材"]
        // 竖向展开的内屏使用系统分栏导航，菜谱侧栏可能默认收起。
        if !search.waitForExistence(timeout: 3), app.buttons["显示边栏"].exists {
            app.buttons["显示边栏"].tap()
        }
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("\(recipeName)\n")
        let recipe = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", recipeName)).firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 10))
        recipe.tap()
        // End search on either size class before entering the full-screen workbench.
        let closeSearch = app.buttons.matching(NSPredicate(format: "label IN %@", ["关闭", "取消"])).firstMatch
        if closeSearch.exists && closeSearch.isHittable { closeSearch.tap() }
        // 浮层侧栏会遮挡详情；先通过原生按钮收起，再点击工作台入口。
        if app.buttons["隐藏边栏"].exists {
            let sidebar = app.buttons["隐藏边栏"]
            let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: sidebar)
            XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 5), .completed)
            sidebar.tap()
        }
        let open = app.buttons["recipe.openKitchen"]
        XCTAssertTrue(open.waitForExistence(timeout: 10))
        let hittable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: open)
        XCTAssertEqual(XCTWaiter.wait(for: [hittable], timeout: 10), .completed)
        open.tap()
        XCTAssertTrue(app.buttons["kitchen.close"].waitForExistence(timeout: 10))
    }

    @MainActor
    private func waitForOrientation(_ app: XCUIApplication, landscape: Bool) {
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let window = app.windows.firstMatch.frame
            let countdown = app.staticTexts["kitchen.countdown"].frame
            return (window.width > window.height) == landscape && window.contains(countdown)
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 10), .completed)
    }

    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
