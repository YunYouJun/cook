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
    func testAccountIsOptionalAndExplainsICloudDataBoundary() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        tap(app.buttons["我的"].firstMatch)
        let signIn = app.buttons["account.signIn"]
        wait(signIn, "exists == true AND isEnabled == true")
        XCTAssertFalse(app.buttons["account.signOut"].exists)
        XCTAssertTrue(app.buttons["我的菜谱"].exists)
        // 首页展示入口，数据说明和导入操作集中在二级页。
        XCTAssertFalse(app.buttons["从文件导入"].exists)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "云乐坊账号与 iCloud"
        attachment.lifetime = .keepAlways
        add(attachment)
        tap(app.buttons["数据与同步"])
        XCTAssertTrue(app.buttons["从文件导入"].waitForExistence(timeout: timeout))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "云乐坊账号的登录和退出不会改变这些数据")).firstMatch.exists)
        // 基础功能不要求账号，登录入口不会挡住菜谱筛选。
        tap(app.buttons["做菜"].firstMatch)
        tap(app.buttons["番茄"])
        wait(app.buttons["番茄"], "isSelected == true")
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
            // 明确检查 VoiceOver label。iOS 26.5 的快捷 identifier 查询曾返回不存在，
            // 但同一失败现场的完整无障碍树和截图均包含该按钮。
            let stuff = app.buttons.matching(NSPredicate(format: "label == %@", name)).firstMatch
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
    func testClearingHistoryRequiresConfirmationAndKeepsFavorites() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        tap(app.buttons["番茄"])
        tap(app.buttons["鸡蛋"])
        if app.windows.firstMatch.horizontalSizeClass != .regular {
            tap(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "看看能做什么")).firstMatch)
        }
        let recipe = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄炒蛋")).firstMatch
        tap(recipe)
        tap(app.navigationBars.buttons["收藏"])
        wait(app.buttons["取消收藏"], "exists == true")
        tap(app.buttons["菜谱"].firstMatch)
        tap(app.segmentedControls.buttons["最近浏览"])
        wait(recipe, "exists == true")
        tap(app.buttons["清空"])
        wait(app.buttons["清空浏览记录"], "exists == true")
        let cancel = app.buttons.matching(NSPredicate(format: "label IN %@", ["取消", "Cancel"])).firstMatch
        if cancel.exists {
            tap(cancel)
        } else {
            // iPad presents a popover whose standard cancellation is an outside tap.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.9)).tap()
        }
        wait(app.buttons["清空浏览记录"], "exists == false")
        wait(recipe, "exists == true")
        tap(app.buttons["清空"])
        tap(app.buttons["清空浏览记录"])
        wait(app.staticTexts["还没有浏览记录"], "exists == true")
        XCTAssertFalse(app.buttons["清空"].exists)
        tap(app.segmentedControls.buttons["收藏"])
        wait(recipe, "exists == true")
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
        capture(app, "ios-filter-live-count")
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
        for _ in 0..<4 where !pantry.isHittable { app.swipeUp() }
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
        capture(app, "ios-pantry-defaults")
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
        capture(app, "ios-avoid-selection-during-search")

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

    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
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
    private func tapSwitch(_ row: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        wait(row, "isHittable == true", file: file, line: line)
        // SwiftUI 同时暴露整行和行内 UISwitch，直接限定到该行的系统控件。
        let control = row.switches.firstMatch
        wait(control, "isHittable == true", file: file, line: line)
        let value = control.value as? String
        XCTAssertTrue(value == "0" || value == "1", "系统开关应提供明确的开关状态", file: file, line: line)
        // 点击当前滑块，而非轨道中点；CI 曾在中点合成点击后仍保持开启。
        let thumb = control.coordinate(withNormalizedOffset: CGVector(dx: value == "1" ? 0.75 : 0.25, dy: 0.5))
        thumb.press(forDuration: 0.1)
    }

    @MainActor
    private func openPrivacySettings(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        tap(app.buttons["我的"].firstMatch, file: file, line: line)
        wait(app.navigationBars["我的"], "isHittable == true", file: file, line: line)
        let list = app.collectionViews["settings.list"]
        wait(list, "exists == true", file: file, line: line)
        // 滚动容器自身未必有可点击的命中点；用页内首行确认当前设置页已可交互。
        wait(list.buttons["我的菜谱"], "isHittable == true", file: file, line: line)
        let privacy = list.buttons["settings.privacy"]
        // The privacy controls now live on a second-level page; scroll to its entry first.
        for _ in 0..<6 where !privacy.isHittable {
            let start = list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
            let end = list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
            start.press(forDuration: 0.01, thenDragTo: end)
        }
        tap(privacy, file: file, line: line)
        wait(app.navigationBars["隐私"], "exists == true", file: file, line: line)
        let share = app.switches["privacy.shareSignals"]
        wait(share, "isHittable == true", file: file, line: line)
        return share
    }

    @MainActor
    func testPrivacyPolicyAndConsentChoicesSurviveRelaunch() {
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
        let share = openPrivacySettings(in: app)
        wait(share, "value == '0'")
        tapSwitch(share)
        wait(share, "value == '1'")

        app.terminate()
        app.launchArguments = ["-ui-testing"]
        app.launchEnvironment.removeValue(forKey: "COOK_UI_TEST_LEGACY_CONSENT")
        app.launch()
        XCTAssertTrue(app.buttons["我的"].firstMatch.waitForExistence(timeout: timeout))
        XCTAssertFalse(decline.exists, "明确选择后不再弹出")
        let restoredShare = openPrivacySettings(in: app)
        wait(restoredShare, "value == '1'")
        tapSwitch(restoredShare)
        wait(restoredShare, "value == '0'")

        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["我的"].firstMatch.waitForExistence(timeout: timeout))
        XCTAssertFalse(decline.exists, "撤回同意也应在重启后保留")
        wait(openPrivacySettings(in: app), "value == '0'")
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
