import XCTest

/// 按 VoiceOver 读到的名字走一遍主流程：选食材 → 筛选 → 看看能做什么 → 打开菜谱 → 收藏。
/// 这里只用无障碍标签找控件，标签不对或控件读不到，这个用例就会失败。CI 在 iPhone 和 iPad 模拟器上各跑一遍，覆盖窄屏和宽屏两种布局。
/// CI 机器比本机慢得多，每一步都等控件真的出现、能点、状态变过来再往下走，不靠固定的几秒
final class CookUITests: XCTestCase {
    /// 一步最多等多久。只是上限，控件出现了就立刻往下走，本机跑不会变慢
    private let timeout: TimeInterval = 30

    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testCookFlowByAccessibilityLabels() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        // 真机上跑时不去建 CloudKit 结构（见 CloudKitSchema），免得首屏多等几秒
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()

        // 食材按钮只读名字，不读 emoji，选中后带「已选」状态
        for name in ["土豆", "番茄", "鸡蛋"] {
            let stuff = app.buttons[name]
            tap(stuff)
            wait(stuff, "isSelected == true")
        }

        tap(app.buttons["筛选"])
        let stirFry = app.buttons["炒菜"]
        tap(stirFry)
        wait(stirFry, "isSelected == true")
        tap(app.buttons["完成"])
        // 窄屏的筛选是半屏弹层，盖着底部按钮；等它收起再点，免得点到正在收起的弹层上
        wait(stirFry, "exists == false")

        // 宽屏（iPad、折叠屏展开）左边选食材、右边直接出结果，没有底部按钮；窄屏点底部按钮进结果页
        let showResults = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "看看能做什么")).firstMatch
        if app.windows.firstMatch.horizontalSizeClass == .regular {
            XCTAssertFalse(showResults.exists)
        } else {
            wait(showResults, "isEnabled == true")
            tap(showResults)
        }

        // 结果行合成一个元素，读出菜名和厨具难度
        tap(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄炒蛋")).firstMatch)

        // 检查收藏按钮及保存后的状态；UI 测试使用独立的内存数据库。
        let favorite = app.buttons["收藏"]
        let unfavorite = app.buttons["取消收藏"]
        wait(app.buttons.matching(NSPredicate(format: "label IN %@", ["收藏", "取消收藏"])).firstMatch, "exists == true")
        if unfavorite.exists {
            tap(unfavorite)
        }
        tap(favorite)
        wait(unfavorite, "exists == true")
    }

    /// 等控件出现再点；在屏幕外时 tap() 会自己滚过去
    @MainActor
    private func tap(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        wait(element, "exists == true", file: file, line: line)
        element.tap()
    }

    @MainActor
    func testFilterCountUpdatesWithoutClosingSheet() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()

        tap(app.buttons["筛选"])
        let hint = app.descendants(matching: .any)["filter.selectionHint"].firstMatch
        wait(hint, "exists == true")
        let count = app.descendants(matching: .any)["filter.resultCount"].firstMatch
        XCTAssertFalse(count.exists)
        tap(app.buttons["完成"])
        wait(hint, "exists == false")

        tap(app.buttons["番茄"])
        tap(app.buttons["鸡蛋"])
        tap(app.buttons["筛选"])
        wait(count, "exists == true")
        let initialLabel = count.label
        let stirFry = app.buttons["炒菜"]
        tap(stirFry)
        wait(stirFry, "isSelected == true")
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label != %@", initialLabel), object: count)
        XCTAssertEqual(XCTWaiter().wait(for: [changed], timeout: timeout), .completed)
        XCTAssertTrue(app.buttons["完成"].exists, "结果数量在弹层内即时更新")
        tap(app.buttons["清除"])
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", initialLabel), object: count)
        XCTAssertEqual(XCTWaiter().wait(for: [restored], timeout: timeout), .completed)
        tap(app.buttons["完成"])
        wait(stirFry, "exists == false")

        if app.windows.firstMatch.horizontalSizeClass != .regular {
            let results = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "看看能做什么")).firstMatch
            wait(results, "exists == true")
            // isNumber 也会把「什么」的「什」识别成数字，只比较界面显示的十进制数字。
            let resultCount = results.label.filter { $0.isASCII && $0.isNumber }
            let previewCount = initialLabel.filter { $0.isASCII && $0.isNumber }
            XCTAssertEqual(resultCount, previewCount, "筛选预览和结果入口数量一致")
        }
    }

    @MainActor
    func testPantryShelfRemembersChoicesAndRestoresDefaults() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()

        tap(app.buttons["筛选"])
        let pantry = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "家里常备")).firstMatch
        tap(pantry)
        let oil = app.buttons["pantry.item.油"]
        let salt = app.buttons["pantry.item.盐"]
        let pepper = app.buttons["pantry.item.胡椒粉"]
        let ginger = app.buttons["pantry.item.姜"]
        wait(oil, "isSelected == true")
        wait(salt, "isSelected == true")
        tap(pepper)
        wait(pepper, "isSelected == true")
        tap(oil)
        wait(oil, "isSelected == false")
        // 半屏弹层先滚到下一层，懒加载的葱姜蒜进入视口后才有无障碍元素。
        if !ginger.exists { app.swipeUp() }
        tap(ginger)
        wait(ginger, "isSelected == true")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        wait(pantry, "label CONTAINS '等 3 种'")
        tap(pantry)
        wait(oil, "isSelected == false")
        wait(pepper, "isSelected == true")
        if !ginger.exists { app.swipeUp() }
        wait(ginger, "isSelected == true")
        tap(app.buttons["恢复默认"])
        if !oil.exists { app.swipeDown() }
        wait(oil, "isSelected == true")
        wait(salt, "isSelected == true")
        wait(pepper, "isSelected == false")
        wait(ginger, "isSelected == false")
        wait(app.buttons["恢复默认"], "isEnabled == false")
    }

    @MainActor
    func testAvoidPickerSearchKeepsSelectionsAvailableForRemoval() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        tap(app.buttons["筛选"])
        let avoid = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "忌口")).firstMatch
        if !avoid.exists { app.swipeUp() }
        tap(avoid)
        let search = app.searchFields["搜索食材或别名"]

        for (query, name) in [("西红柿", "番茄"), ("虾", "虾")] {
            tap(search)
            if search.buttons["清除文本"].exists { tap(search.buttons["清除文本"]) }
            search.typeText(query)
            let item = app.buttons["avoid.item.\(name)"]
            tap(item)
            wait(item, "isSelected == true")
            wait(app.buttons["avoid.remove.\(name)"], "exists == true")
        }
        tap(search.buttons["清除文本"])
        search.typeText("不存在的食材")
        wait(app.staticTexts["avoid.noSearchResults"], "exists == true")
        let tomato = app.buttons["avoid.remove.番茄"]
        let shrimp = app.buttons["avoid.remove.虾"]
        wait(tomato, "exists == true")
        wait(shrimp, "exists == true")
        tap(tomato)
        wait(tomato, "exists == false")
        wait(shrimp, "exists == true")

        tap(app.buttons.matching(NSPredicate(format: "label IN %@", ["关闭", "取消"])).firstMatch)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        wait(avoid, "label CONTAINS '虾'")
        tap(avoid)
        wait(shrimp, "exists == true")
        let clear = app.navigationBars["忌口"].buttons["清空"]
        tap(clear)
        wait(app.staticTexts["avoid.emptySelection"], "exists == true")
        wait(shrimp, "exists == false")
        wait(clear, "isEnabled == false")
    }

    /// 等控件满足条件，超时就让用例失败，失败信息里写明是哪个控件、等的什么
    @MainActor
    private func wait(_ element: XCUIElement, _ condition: String, file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: condition), object: element)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertEqual(result, .completed, "等了 \(Int(timeout)) 秒，\(element.description) 仍不满足 \(condition)", file: file, line: line)
    }

    /// SwiftUI 把带标签的整行也暴露成 Switch；真正的系统开关是行内另一个无障碍元素。
    /// 按实际边界找到它，避免在大屏上按整行宽度计算点击位置时点到空白处。
    @MainActor
    private func tapSwitch(_ row: XCUIElement, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) throws {
        wait(row, "isHittable == true", file: file, line: line)
        let bounds = row.frame
        let control = app.switches.allElementsBoundByIndex.first { element in
            let frame = element.frame
            return frame.width > 0 && frame.width < bounds.width && bounds.contains(frame)
        }
        let target = try XCTUnwrap(control, "未找到开关行内的系统开关", file: file, line: line)
        tap(target, file: file, line: line)
    }

    @MainActor
    func testPrivacyPolicyAndConsentChoicesSurviveRelaunch() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_LEGACY_CONSENT"] = "1"
        app.launch()
        let decline = app.buttons["consent.decline"]
        XCTAssertTrue(decline.waitForExistence(timeout: timeout), "旧版默认开关也需要重新选择")
        tap(app.buttons["隐私政策"])
        XCTAssertTrue(app.staticTexts["云食册隐私政策"].waitForExistence(timeout: timeout))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        tap(decline)
        tap(app.buttons["我的"].firstMatch)
        app.swipeUp()
        let share = app.switches["privacy.shareSignals"]
        XCTAssertTrue(share.waitForExistence(timeout: timeout))
        wait(share, "value == '0'")
        try tapSwitch(share, in: app)
        wait(share, "value == '1'")

        app.terminate()
        app.launchArguments = ["-ui-testing"]
        app.launchEnvironment.removeValue(forKey: "COOK_UI_TEST_LEGACY_CONSENT")
        app.launch()
        XCTAssertTrue(app.buttons["我的"].firstMatch.waitForExistence(timeout: timeout))
        XCTAssertFalse(decline.exists, "明确选择后不再弹出")
        tap(app.buttons["我的"].firstMatch)
        app.swipeUp()
        wait(share, "value == '1'")
        try tapSwitch(share, in: app)
        wait(share, "value == '0'")
        tap(app.buttons["隐私政策"])
        XCTAssertTrue(app.staticTexts["云食册隐私政策"].waitForExistence(timeout: timeout))
    }

    @MainActor
    func testFavoriteFailureCanBeRetriedWithoutLosingState() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_UI_TEST_FAVORITE_FAILURES"] = "2"
        app.launch()
        XCTAssertTrue(app.buttons["番茄"].waitForExistence(timeout: timeout))
        tap(app.buttons["番茄"])
        wait(app.buttons["番茄"], "isSelected == true")
        tap(app.buttons["鸡蛋"])
        wait(app.buttons["鸡蛋"], "isSelected == true")
        let showResults = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "看看能做什么")).firstMatch
        if app.windows.firstMatch.horizontalSizeClass != .regular { tap(showResults) }
        let recipe = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄炒蛋")).firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: timeout))
        tap(recipe)
        let favorite = app.navigationBars.buttons["收藏"]
        XCTAssertTrue(favorite.waitForExistence(timeout: timeout))
        tap(favorite)
        let failure = app.alerts["收藏未能保存"]
        XCTAssertTrue(failure.waitForExistence(timeout: timeout))
        tap(failure.buttons["重试"])
        XCTAssertTrue(failure.waitForExistence(timeout: timeout), "连续失败时仍能显示错误")
        tap(failure.buttons["重试"])
        XCTAssertTrue(app.buttons["取消收藏"].waitForExistence(timeout: timeout))
        XCTAssertFalse(failure.exists)
    }

    @MainActor
    func testFridgeRemainsUsableAfterDecliningStatistics() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launch()
        tap(app.buttons["consent.decline"])
        wait(app.buttons["consent.decline"], "exists == false")
        tap(app.buttons["冰箱"].firstMatch)
        tap(app.buttons["放点食材"])
        let tomato = app.buttons["番茄"]
        tap(tomato)
        wait(tomato, "isSelected == true")
        tap(app.buttons["放进去（1）"])
        wait(app.buttons["放进去（1）"], "exists == false")
        wait(app.switches["临期提醒"], "exists == true")
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄")).firstMatch
        tap(item)
        tap(app.buttons["吃完了，移出冰箱"])
        wait(app.staticTexts["冰箱是空的"], "exists == true")
    }
}
