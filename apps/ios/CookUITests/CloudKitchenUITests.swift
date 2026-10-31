import UIKit
import XCTest

final class CloudKitchenUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testLargestTextViewMenuKeepsControlsAndSelectionUsable() {
        XCUIDevice.shared.orientation = .portrait
        let app = launchMenuFixture(contentSize: .accessibilityExtraExtraExtraLarge)
        let menu = app.buttons["cook.presentation"]
        capture(app, name: "largest-quick-initial")
        assertMenuControlFits(menu, in: app)
        let potato = app.buttons.matching(NSPredicate(format: "label == %@", "土豆")).firstMatch
        XCTAssertTrue(potato.isHittable, "最大字号下首屏仍应能选择第一种常用食材")
        potato.tap()
        waitUntil(potato, "isSelected == true")
        assertMenuHitArea(menu, currentSelection: "食材快选", in: app, usesCurrentSubtitle: true)

        menu.tap()
        let quick = app.cookingPresentationOption("食材快选")
        let kitchen = app.cookingPresentationOption("云厨房")
        XCTAssertTrue(quick.waitForExistence(timeout: 10))
        capture(app, name: "largest-menu-quick")
        assertMenuOptionFits(quick, in: app)
        assertMenuOptionFits(kitchen, in: app)
        assertMenuCurrentSelection(quick, usesCurrentSubtitle: true)
        kitchen.tap()

        waitUntil(menu, "value == '云厨房' OR label CONTAINS '云厨房'")
        let modelPotato = app.buttons["kitchen.scene.potato"]
        XCTAssertTrue(modelPotato.waitForExistence(timeout: 30))
        XCTAssertTrue(modelPotato.isSelected, "厨房应沿用快选中的土豆，不要求大字号台面始终在首屏")
        let carrot = app.buttons["kitchen.ingredient.胡萝卜"]
        reveal(carrot, in: app, fullyVisible: true)
        XCTAssertTrue(carrot.isHittable)
        XCTAssertLessThanOrEqual(carrot.frame.maxY, app.buttons["kitchen.cook"].frame.minY,
                                 "大字号食材应完整滚入固定底栏上方，不能靠压缩文字适配")
        capture(app, name: "largest-kitchen-ingredients")
        assertMenuControlFits(menu, in: app)
        assertMenuHitArea(menu, currentSelection: "云厨房", in: app, usesCurrentSubtitle: true)
        menu.tap()
        XCTAssertTrue(kitchen.waitForExistence(timeout: 10))
        capture(app, name: "largest-menu-kitchen")
        assertMenuOptionFits(quick, in: app)
        assertMenuOptionFits(kitchen, in: app)
        assertMenuCurrentSelection(kitchen, usesCurrentSubtitle: true)
        quick.tap()

        waitUntil(menu, "value == '食材快选' OR label CONTAINS '食材快选'")
        XCTAssertTrue(potato.waitForExistence(timeout: 10))
        XCTAssertTrue(potato.isHittable)
        XCTAssertTrue(potato.isSelected)
        assertMenuControlFits(menu, in: app)
        capture(app, name: "largest-quick-return")
    }

    @MainActor
    func testIPadLandscapeViewMenuPreservesLiveResults() throws {
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = launchMenuFixture()
        let window = app.windows.firstMatch
        guard window.horizontalSizeClass == .regular else {
            throw XCTSkip("此用例检查 iPad 横屏双栏")
        }
        XCTAssertGreaterThan(window.frame.width, window.frame.height)
        let menu = app.buttons["cook.presentation"]
        assertMenuControlFits(menu, in: app)
        let resultList = app.descendants(matching: .any).matching(identifier: "dietary.results").firstMatch
        XCTAssertTrue(resultList.waitForExistence(timeout: 10))
        XCTAssertTrue(resultList.isHittable)
        let potato = app.buttons.matching(NSPredicate(format: "label == %@", "土豆")).firstMatch
        XCTAssertTrue(potato.isHittable)
        potato.tap()
        waitUntil(potato, "isSelected == true")
        assertMenuHitArea(menu, currentSelection: "食材快选", in: app)

        menu.tap()
        let quick = app.cookingPresentationOption("食材快选")
        let kitchen = app.cookingPresentationOption("云厨房")
        XCTAssertTrue(quick.waitForExistence(timeout: 10))
        capture(app, name: "ipad-landscape-menu")
        assertMenuOptionFits(quick, in: app)
        assertMenuOptionFits(kitchen, in: app)
        XCTAssertTrue(quick.isSelected)
        kitchen.tap()
        let modelPotato = app.buttons["kitchen.scene.potato"]
        XCTAssertTrue(modelPotato.waitForExistence(timeout: 30))
        XCTAssertTrue(modelPotato.isSelected)
        assertMenuHitArea(menu, currentSelection: "云厨房", in: app)
        app.selectCookingPresentation("食材快选")

        XCTAssertTrue(potato.waitForExistence(timeout: 10))
        XCTAssertTrue(potato.isHittable)
        XCTAssertTrue(potato.isSelected)
        XCTAssertTrue(resultList.waitForExistence(timeout: 10))
        let selection = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已选：土豆")).firstMatch
        XCTAssertTrue(selection.waitForExistence(timeout: 10))
        let match = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "dietary.recipe.")).firstMatch
        XCTAssertTrue(match.waitForExistence(timeout: 10))
        XCTAssertTrue(match.isHittable)
        XCTAssertGreaterThan(match.frame.midX, potato.frame.maxX,
                             "返回快选后，完整匹配结果应继续显示在食材旁边")
        capture(app, name: "ipad-landscape-selected-results")
    }

    @MainActor
    func testViewMenuShowsCurrentSelectionAndKeepsIngredients() {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()

        let menu = app.buttons["cook.presentation"]
        XCTAssertTrue(menu.waitForExistence(timeout: 20))
        XCTAssertTrue(menu.value as? String == "食材快选" || menu.label.contains("食材快选"))
        let tomato = app.buttons.matching(NSPredicate(format: "label == %@", "番茄")).firstMatch
        XCTAssertTrue(tomato.isHittable, "默认视图应直接支持食材快选")
        tomato.tap()
        waitUntil(tomato, "isSelected == true")

        menu.tap()
        let quick = app.cookingPresentationOption("食材快选")
        let kitchen = app.cookingPresentationOption("云厨房")
        XCTAssertTrue(quick.waitForExistence(timeout: 10))
        XCTAssertTrue(quick.isHittable)
        XCTAssertTrue(kitchen.isHittable)
        capture(app, name: "view-menu-list")
        XCTAssertTrue(quick.isSelected, "菜单应标记当前使用的食材快选视图")
        kitchen.tap()

        let modelTomato = app.buttons["kitchen.scene.tomato"]
        XCTAssertTrue(modelTomato.waitForExistence(timeout: 30))
        XCTAssertTrue(modelTomato.isSelected, "切换布局应保留已选食材")
        XCTAssertTrue(menu.value as? String == "云厨房" || menu.label.contains("云厨房"))
        menu.tap()
        XCTAssertTrue(kitchen.waitForExistence(timeout: 10))
        XCTAssertTrue(quick.isHittable)
        capture(app, name: "view-menu-kitchen")
        XCTAssertTrue(kitchen.isSelected, "菜单应标记当前使用的云厨房视图")
        quick.tap()

        waitUntil(menu, "value == '食材快选' OR label CONTAINS '食材快选'")
        XCTAssertTrue(tomato.waitForExistence(timeout: 10))
        XCTAssertTrue(tomato.isSelected)
        capture(app, name: "view-menu-returned-quick-selection")
    }

    @MainActor
    func testInspirationCardScrollsAboveFixedActions() {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        let presentation = app.buttons["cook.presentation"]
        XCTAssertTrue(presentation.waitForExistence(timeout: 20))
        app.selectCookingPresentation("云厨房")
        let tomato = app.buttons["kitchen.scene.tomato"]
        XCTAssertTrue(tomato.waitForExistence(timeout: 30))
        XCTAssertTrue(tomato.isHittable)
        capture(app, name: "云厨房首屏与干净底栏")

        let header = app.descendants(matching: .any).matching(identifier: "discovery.cardHeader").firstMatch
        reveal(header, in: app)
        let action = app.buttons["kitchen.cook"]
        capture(app, name: "云厨房完整灵感卡片")
        XCTAssertLessThanOrEqual(header.frame.maxY, action.frame.minY,
                                 "推荐卡片应能完整滚动到底部固定操作区之上")
        for name in ["discovery.shuffle", "discovery.decide"] {
            let button = app.buttons[name]
            XCTAssertTrue(button.isHittable)
            XCTAssertGreaterThanOrEqual(button.frame.minY, header.frame.minY,
                                       "随机操作应在灵感标题之后")
            XCTAssertLessThanOrEqual(button.frame.maxY, action.frame.minY,
                                    "随机操作必须完整露在固定底栏之上")
        }
        app.buttons["discovery.decide"].tap()
        XCTAssertTrue(app.buttons["收藏"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testKitchenAndListShareSelectionAndPotOpensRealRecipes() {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()

        let presentation = app.buttons["cook.presentation"]
        XCTAssertTrue(presentation.waitForExistence(timeout: 20))
        app.selectCookingPresentation("食材快选")
        XCTAssertFalse(app.segmentedControls["cook.presentation"].exists)
        let tomato = app.buttons.matching(NSPredicate(format: "label == %@", "番茄")).firstMatch
        reveal(tomato, in: app)
        XCTAssertTrue(tomato.waitForExistence(timeout: 10))
        tomato.tap()
        XCTAssertTrue(tomato.isSelected)
        capture(app, name: "列表的紧凑视图入口")

        app.selectCookingPresentation("云厨房")
        let modelTomato = app.buttons["kitchen.scene.tomato"]
        XCTAssertTrue(modelTomato.waitForExistence(timeout: 30))
        XCTAssertTrue(modelTomato.isHittable, "切换云厨房后，应直接看到并操作台面食材")
        XCTAssertLessThan(modelTomato.frame.midY, app.buttons["kitchen.cook"].frame.minY,
                          "台面应显示在首屏内容区域，推荐和随机操作排在后面")
        let shuffle = app.buttons["discovery.shuffle"]
        if shuffle.isHittable {
            XCTAssertGreaterThan(shuffle.frame.minY, modelTomato.frame.maxY,
                                 "大屏能同时显示推荐时，随机操作也应排在台面后面")
        }
        XCTAssertTrue(modelTomato.isSelected)
        // Tap the 3D ingredient at its projected screen location. This catches
        // overlapping targets accidentally selecting a neighboring ingredient.
        let modelEgg = app.buttons["kitchen.scene.egg"]
        modelEgg.tap()
        waitUntil(app.buttons.matching(NSPredicate(format: "label == %@", "鸡蛋")).firstMatch, "isSelected == true")
        XCTAssertEqual(app.staticTexts["kitchen.selectionCount"].label, "已选 2 样")

        app.selectCookingPresentation("食材快选")
        reveal(tomato, in: app)
        XCTAssertTrue(tomato.waitForExistence(timeout: 10))
        XCTAssertTrue(tomato.isSelected)
        app.selectCookingPresentation("云厨房")
        let pot = app.buttons["kitchen.scene.pot"]
        reveal(pot, in: app)
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
        reveal(pot, in: app)
        XCTAssertTrue(app.buttons["kitchen.cook"].isEnabled, "没有食材也能浏览当前候选")
        pot.tap()
        let candidate = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "dietary.recipe.")).firstMatch
        XCTAssertTrue(candidate.waitForExistence(timeout: 10), "未选食材时，点击锅打开真实目录")
    }

    @MainActor
    func testOptionalRotationMovesCameraKeepsTapsAndResetsWhenDisabled() {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        let presentation = app.buttons["cook.presentation"]
        XCTAssertTrue(presentation.waitForExistence(timeout: 20))
        app.selectCookingPresentation("云厨房")

        let tomato = app.buttons["kitchen.scene.tomato"]
        let cameraMarker = app.buttons["kitchen.scene.pot"]
        let rotation = app.buttons["kitchen.rotation"]
        reveal(tomato, in: app)
        reveal(rotation, in: app)
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
        app.selectCookingPresentation("食材快选")
        app.selectCookingPresentation("云厨房")
        reveal(tomato, in: app)
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
    private func launchMenuFixture(contentSize: UIContentSizeCategory = .large) -> XCUIApplication {
        let app = XCUIApplication()
        var arguments = XCUIApplication.standardCookingLaunchArguments
        if let index = arguments.firstIndex(of: "-UIPreferredContentSizeCategoryName") {
            arguments[index + 1] = contentSize.rawValue
        }
        app.launchArguments = arguments
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        XCTAssertTrue(app.buttons["cook.presentation"].waitForExistence(timeout: 20))
        return app
    }

    @MainActor
    private func assertMenuControlFits(_ menu: XCUIElement, in app: XCUIApplication,
                                       file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(menu.isHittable, file: file, line: line)
        XCTAssertGreaterThanOrEqual(menu.frame.width, 44, file: file, line: line)
        XCTAssertTrue(app.windows.firstMatch.frame.contains(menu.frame), "视图入口应完整留在窗口内", file: file, line: line)
        let clear = app.buttons["清空"]
        XCTAssertTrue(clear.exists, file: file, line: line)
        XCTAssertFalse(menu.frame.intersects(clear.frame), "视图和清空按钮不能重叠", file: file, line: line)
    }

    /// 原生工具栏的 AX frame 可能仅报告文字按钮的可见范围；用真实边缘触摸验证 44pt 命中区域。
    @MainActor
    private func assertMenuHitArea(_ menu: XCUIElement, currentSelection: String, in app: XCUIApplication,
                                   usesCurrentSubtitle: Bool = false,
                                   file: StaticString = #filePath, line: UInt = #line) {
        let center = CGPoint(x: menu.frame.midX, y: menu.frame.midY)
        let target = CGRect(x: center.x - 22, y: center.y - 22, width: 44, height: 44)
        XCTAssertTrue(app.windows.firstMatch.frame.contains(target), "完整 44pt 命中区域必须留在窗口内", file: file, line: line)
        let bars = app.navigationBars.allElementsBoundByIndex.filter { $0.isHittable && $0.frame.contains(center) }
        XCTAssertTrue(bars.contains { $0.frame.contains(target) }, "完整 44pt 命中区域必须位于导航栏内", file: file, line: line)
        XCTAssertFalse(target.intersects(app.buttons["清空"].frame), "命中区域不能借用清空按钮的位置", file: file, line: line)

        for verticalOffset in [-21.0, 21.0] {
            // 两个触摸点在 44pt 目标内；AX 高度只有 36pt 时，它们落在 AX frame 的上下边缘之外。
            menu.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                .withOffset(CGVector(dx: 0, dy: verticalOffset)).tap()
            let quick = app.cookingPresentationOption("食材快选")
            let kitchen = app.cookingPresentationOption("云厨房")
            XCTAssertTrue(quick.waitForExistence(timeout: 10), "44pt 目标上／下边缘触摸应真正展开菜单", file: file, line: line)
            XCTAssertTrue(quick.isHittable, file: file, line: line)
            XCTAssertTrue(kitchen.isHittable, file: file, line: line)
            let current = app.cookingPresentationOption(currentSelection)
            assertMenuCurrentSelection(current, usesCurrentSubtitle: usesCurrentSubtitle, file: file, line: line)
            current.tap()
            let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: quick)
            XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 10), .completed, file: file, line: line)
            waitUntil(menu, "value == '\(currentSelection)' OR label CONTAINS '\(currentSelection)'")
        }
    }

    @MainActor
    private func assertMenuCurrentSelection(_ option: XCUIElement, usesCurrentSubtitle: Bool,
                                            file: StaticString = #filePath, line: UInt = #line) {
        if usesCurrentSubtitle {
            let marker = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                let subtitle = option.descendants(matching: .staticText)
                    .matching(NSPredicate(format: "label CONTAINS %@", "当前视图")).firstMatch
                return option.label.contains("当前视图")
                    || (option.value as? String)?.contains("当前视图") == true
                    || subtitle.exists
            }, object: nil)
            let hasCurrentMarker = XCTWaiter.wait(for: [marker], timeout: 10) == .completed
            let label = option.label
            let value = option.value
            if !hasCurrentMarker {
                let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
                screenshot.name = "menu-current-marker-missing-screen"
                screenshot.lifetime = .keepAlways
                add(screenshot)
                let hierarchy = XCTAttachment(string: "Option:\n\(option.debugDescription)\n\nApp:\n\(XCUIApplication().debugDescription)")
                hierarchy.name = "menu-current-marker-missing-accessibility"
                hierarchy.lifetime = .keepAlways
                add(hierarchy)
            }
            XCTAssertTrue(hasCurrentMarker,
                          "辅助字号菜单应以文字说明当前视图；label=\(label)，value=\(String(describing: value))", file: file, line: line)
        } else {
            XCTAssertTrue(option.isSelected, "菜单应勾选当前视图", file: file, line: line)
        }
    }

    @MainActor
    private func assertMenuOptionFits(_ option: XCUIElement, in app: XCUIApplication,
                                      file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(option.isHittable, file: file, line: line)
        XCTAssertTrue(app.windows.firstMatch.frame.contains(option.frame), "菜单选项应完整留在窗口内", file: file, line: line)
        // 系统菜单的 AX 行高随平台变化；通过真实选取验证，不把它当作入口按钮的命中范围。
    }

    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, fullyVisible: Bool = false) {
        let window = app.windows.firstMatch
        for _ in 0..<20 {
            let bar = app.navigationBars.allElementsBoundByIndex.first(where: { $0.isHittable }) ?? app.navigationBars.firstMatch
            let top = bar.isHittable ? bar.frame.maxY : window.frame.minY
            let kitchen = app.buttons["kitchen.cook"]
            let results = app.buttons["cook.showResults"]
            let bottom = kitchen.isHittable ? kitchen.frame.minY
                : window.horizontalSizeClass != .regular && results.isHittable ? results.frame.minY : window.frame.maxY
            let frame = element.exists ? element.frame : CGRect.null
            let withinViewport = fullyVisible
                ? frame.minY >= top && frame.maxY <= bottom
                : frame.midY >= top && frame.midY <= bottom
            if element.isHittable && !frame.isNull && withinViewport { return }
            let above = !frame.isNull && (fullyVisible ? frame.minY < top : frame.midY < top)
            let x = !frame.isNull ? frame.midX : bar.frame.midX
            let start = top + (bottom - top) * (above ? 0.3 : 0.7)
            let end = top + (bottom - top) * (above ? 0.7 : 0.3)
            let origin = window.coordinate(withNormalizedOffset: .zero)
            origin.withOffset(CGVector(dx: x - window.frame.minX, dy: start - window.frame.minY))
                .press(forDuration: 0.1,
                       thenDragTo: origin.withOffset(CGVector(dx: x - window.frame.minX, dy: end - window.frame.minY)),
                       withVelocity: .slow, thenHoldForDuration: 0.1)
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }

    @MainActor
    private func capture(_ app: XCUIApplication, name: String) {
        // 全屏截图保留 iPad 横屏的系统坐标，避免应用截图再次应用方向元数据。
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
