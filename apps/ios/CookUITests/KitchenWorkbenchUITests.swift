import XCTest

final class KitchenWorkbenchUITests: XCTestCase {
    @MainActor
    func testTabletopKeepsTimerReachableAndPreservesProgressAcrossLayouts() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openKitchen(app)
        chooseLayout("台面", in: app)
        let start = app.buttons["开始计时"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        let heading = app.staticTexts["必备原料和工具"]
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
        if !egg.isHittable {
            app.buttons["kitchen.ingredient.葱花（可选）"].swipeDown()
        }
        XCTAssertEqual(egg.value as? String, "已备好")
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
    func testPreparationTimerLayoutAndReopening() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent", "-ui-testing-reset-kitchen"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        openKitchen(app)
        XCTAssertTrue(app.staticTexts["必备原料和工具"].waitForExistence(timeout: 10))
        let isWide = !app.segmentedControls.buttons["备料与计时"].exists
        if !isWide {
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
        if isWide {
            XCTAssertLessThan(app.staticTexts["必备原料和工具"].frame.maxX, tomato.frame.minX,
                              "展开时做法与备料分列显示")
        }
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
    private func openKitchen(_ app: XCUIApplication) {
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
        search.typeText("番茄炒蛋\n")
        let recipe = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄炒蛋")).firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 10))
        recipe.tap()
        // 浮层侧栏会遮挡详情；先通过原生按钮收起，再点击工作台入口。
        if app.buttons["隐藏边栏"].exists {
            // 搜索激活时侧栏按钮仍在辅助功能树里，但没有可点击位置。
            let closeSearch = app.buttons["关闭"]
            if closeSearch.isHittable { closeSearch.tap() }
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
