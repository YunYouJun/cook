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

    @MainActor
    func testRecipeFeedbackKeepsRecipeAndDraftOnFailure() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        tap(app.buttons["番茄"])
        tap(app.buttons["鸡蛋"])
        let showResults = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "看看能做什么")).firstMatch
        if app.windows.firstMatch.horizontalSizeClass != .regular { tap(showResults) }
        tap(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄炒蛋")).firstMatch)
        let report = app.buttons["recipe.feedback"]
        for _ in 0..<4 where !report.isHittable { app.swipeUp() }
        tap(report)
        wait(app.navigationBars["反馈这道菜"], "exists == true")
        XCTAssertTrue(app.staticTexts["番茄炒蛋"].exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已自动携带菜名、编号和做法链接")).firstMatch.exists)
        let note = app.descendants(matching: .any).matching(identifier: "recipe-feedback.note").firstMatch
        tap(note)
        note.typeText("Video keeps loading")
        let submit = app.buttons["recipe-feedback.submit"]
        for _ in 0..<3 where !submit.isHittable { app.swipeUp() }
        tap(submit)
        wait(app.staticTexts["recipe-feedback.error"], "exists == true")
        XCTAssertEqual(note.value as? String, "Video keeps loading")
        XCTAssertTrue(submit.isEnabled)
    }

    @MainActor
    func testRecipeContributionHasDedicatedFieldsAndKeepsDraftOnFailure() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launchEnvironment["COOK_UI_TEST_FEEDBACK_ENVIRONMENT"] = "production"
        app.launch()
        tap(app.buttons["我的"].firstMatch)
        let help = app.buttons["settings.help"]
        for _ in 0..<5 where !help.isHittable { app.swipeUp() }
        tap(help)
        wait(app.buttons["help.contribution"], "exists == true")
        for identifier in ["help.feedback", "help.contribution", "help.support", "help.requests"] {
            // SwiftUI Link exposes a link, whereas NavigationLink exposes a button.
            XCTAssertTrue(app.descendants(matching: .any).matching(identifier: identifier).firstMatch.exists, identifier)
        }
        let helpScreenshot = XCTAttachment(screenshot: app.screenshot())
        helpScreenshot.name = "帮助与反馈入口"
        helpScreenshot.lifetime = .keepAlways
        add(helpScreenshot)
        tap(app.buttons["help.contribution"])
        wait(app.navigationBars["菜谱投稿"], "exists == true")
        XCTAssertFalse(app.buttons["feedback.category"].exists)
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "feedback.note").firstMatch.exists)
        let name = app.textFields["contribution.name"]
        let source = app.descendants(matching: .any).matching(identifier: "contribution.source").firstMatch
        let details = app.descendants(matching: .any).matching(identifier: "contribution.details").firstMatch
        let submit = app.buttons["feedback.submit"]
        XCTAssertEqual(submit.label, "提交菜谱")
        XCTAssertFalse(submit.isEnabled)
        let formScreenshot = XCTAttachment(screenshot: app.screenshot())
        formScreenshot.name = "菜谱投稿表单"
        formScreenshot.lifetime = .keepAlways
        add(formScreenshot)
        tap(name)
        name.typeText("Tomato eggs")
        XCTAssertFalse(submit.isEnabled)
        tap(source)
        source.typeText("https://example.com/recipe")
        XCTAssertTrue(submit.isEnabled)
        tap(details)
        details.typeText("Easy to cook")
        // -ui-testing injects a failing local transport; no request leaves the device.
        tap(submit)
        let failed = app.alerts["暂时没能提交"]
        wait(failed, "exists == true")
        XCTAssertTrue(failed.buttons["去支持中心"].exists)
        XCTAssertTrue(failed.buttons["打开邮件草稿"].exists)
        tap(failed.buttons["取消"])
        wait(failed, "exists == false")
        XCTAssertEqual(name.value as? String, "Tomato eggs")
        XCTAssertEqual(source.value as? String, "https://example.com/recipe")
        XCTAssertEqual(details.value as? String, "Easy to cook")
        XCTAssertTrue(submit.isEnabled)
    }

    @MainActor
    func testTestFeedbackFailureKeepsDraftWithoutProductionFallbacks() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launchEnvironment["COOK_UI_TEST_FEEDBACK_ENVIRONMENT"] = "test"
        app.launch()
        tap(app.buttons["我的"].firstMatch)
        let help = app.buttons["settings.help"]
        for _ in 0..<5 where !help.isHittable { app.swipeUp() }
        tap(help)
        tap(app.buttons["help.feedback"])
        wait(app.navigationBars["意见反馈"], "exists == true")
        tap(app.buttons["feedback.category"])
        tap(app.buttons["想要的菜没有"])
        XCTAssertTrue(app.navigationBars["意见反馈"].exists)
        XCTAssertFalse(app.navigationBars["菜谱投稿"].exists)
        XCTAssertFalse(app.textFields["contribution.name"].exists)
        wait(app.staticTexts["测试模式：反馈仅进入独立测试队列，不会发送到正式支持中心。"], "exists == true")
        let formScreenshot = XCTAttachment(screenshot: app.screenshot())
        formScreenshot.name = "意见反馈求菜建议"
        formScreenshot.lifetime = .keepAlways
        add(formScreenshot)
        let note = app.descendants(matching: .any).matching(identifier: "feedback.note").firstMatch
        tap(note)
        note.typeText("Keep this test draft")
        tap(app.buttons["发送反馈"])
        let failed = app.alerts["暂时没能提交"]
        wait(failed, "exists == true")
        XCTAssertTrue(failed.staticTexts["测试内容已保留，可以稍后重试。"].exists)
        XCTAssertFalse(failed.buttons["去支持中心"].exists)
        XCTAssertFalse(failed.buttons["打开邮件草稿"].exists)
        XCTAssertFalse(failed.buttons["复制邮箱和内容"].exists)
        tap(failed.buttons["取消"])
        wait(failed, "exists == false")
        XCTAssertEqual(note.value as? String, "Keep this test draft")
        XCTAssertTrue(app.buttons["发送反馈"].isEnabled)
    }

    @MainActor
    private func tap(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        wait(element, "exists == true", file: file, line: line)
        element.tap()
    }

    /// 等控件满足条件，超时就让用例失败，失败信息里写明是哪个控件、等的什么
    @MainActor
    private func wait(_ element: XCUIElement, _ condition: String, file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: condition), object: element)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        if result != .completed {
            let app = XCUIApplication()
            let hierarchy = XCTAttachment(string: app.debugDescription)
            hierarchy.name = "Accessibility hierarchy after failed wait"
            hierarchy.lifetime = .keepAlways
            add(hierarchy)
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "Screen after failed wait"
            screenshot.lifetime = .keepAlways
            add(screenshot)
        }
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
