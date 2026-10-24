import XCTest

final class KitchenWorkbenchUITests: XCTestCase {
    @MainActor
    func testSupplementaryReferenceLocatesStepWithoutCompletingIt() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
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
        let before = target.frame.minY
        reference.tap()
        let targetCard = app.otherElements["recipe.stepCard.10"]
        XCTAssertEqual(targetCard.value as? String, "已定位", "即使目标已在可视区域，也明确高亮对应步骤")
        capture(app, "supplementary-jump-result")
        let located = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            target.isHittable && target.frame.minY <= before
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [located], timeout: 10), .completed)
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
        XCTAssertTrue(target.isHittable)
        XCTAssertEqual(app.staticTexts["recipe.stepProgress"].label, progress)
    }

    @MainActor
    func testServingsSynchronizeQuantitiesPreparationAndRestore() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
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
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openKitchen(app)
        XCTAssertTrue(app.staticTexts["recipe.stepProgress"].waitForExistence(timeout: 10))
        let isWide = app.windows.firstMatch.frame.width >= 760
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
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openKitchen(app)
        let progress = app.staticTexts["recipe.stepProgress"]
        XCTAssertTrue(progress.waitForExistence(timeout: 10))
        XCTAssertEqual(progress.label, "已完成 0 / 11 步")
        capture(app, "recipe-overview")
        app.buttons["recipe.continueStep"].tap()
        let step = app.buttons["recipe.step.1"]
        let hittable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: step)
        XCTAssertEqual(XCTWaiter.wait(for: [hittable], timeout: 10), .completed)
        step.tap()
        XCTAssertEqual(step.value as? String, "已完成")
        XCTAssertEqual(progress.label, "已完成 1 / 11 步")
        step.tap()
        XCTAssertEqual(step.value as? String, "未完成")
        XCTAssertEqual(progress.label, "已完成 0 / 11 步")
        step.tap()
        capture(app, "recipe-step-progress")
        app.buttons["kitchen.close"].tap()
        app.terminate()
        app.launchArguments.removeAll { $0 == "-ui-testing-reset-kitchen" }
        app.launch()
        openKitchen(app)
        XCTAssertEqual(progress.label, "已完成 1 / 11 步")
        XCTAssertEqual(app.buttons["recipe.continueStep"].label, "继续第 2 步")
        app.buttons["重置步骤"].tap()
        let resets = app.buttons.matching(identifier: "重置步骤")
        resets.element(boundBy: resets.count - 1).tap()
        XCTAssertEqual(progress.label, "已完成 0 / 11 步")
    }

    @MainActor
    private func openKitchen(_ app: XCUIApplication) {
        for name in ["番茄", "鸡蛋"] {
            let ingredient = app.buttons[name]
            XCTAssertTrue(ingredient.waitForExistence(timeout: 20))
            if !ingredient.isSelected { ingredient.tap() }
        }
        if app.windows.firstMatch.horizontalSizeClass != .regular {
            let results = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "看看能做什么")).firstMatch
            XCTAssertTrue(results.waitForExistence(timeout: 10))
            results.tap()
        }
        let recipe = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄炒蛋")).firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 10))
        recipe.tap()
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
