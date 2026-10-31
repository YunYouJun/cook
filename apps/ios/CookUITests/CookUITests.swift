import UIKit
import XCTest

extension XCUIApplication {
    /// Ordinary scenarios start from the same view, goal and text size.
    /// Reset flags leave presentation changes available to the actual UI.
    static var standardCookingLaunchArguments: [String] {
        ["-ui-testing", "-ui-testing-reset-presentation", "-ui-testing-reset-dietary",
         "-UIPreferredContentSizeCategoryName", UIContentSizeCategory.large.rawValue]
    }

    /// 菜单辅助字号使用“当前视图”副标题；以稳定标题前缀定位同一个原生选项。
    @MainActor
    func cookingPresentationOption(_ title: String) -> XCUIElement {
        buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", title, title)).firstMatch
    }

    /// 通过实际菜单选择布局；工具栏在部分系统上只转发无障碍标签。
    @MainActor
    func selectCookingPresentation(_ name: String, file: StaticString = #filePath, line: UInt = #line) {
        let menu = buttons["cook.presentation"]
        XCTAssertTrue(menu.waitForExistence(timeout: 20), file: file, line: line)
        if menu.value as? String == name || menu.label.contains(name) { return }
        menu.tap()
        let option = cookingPresentationOption(name)
        XCTAssertTrue(option.waitForExistence(timeout: 10), file: file, line: line)
        option.tap()
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@ OR label CONTAINS %@", name, name), object: menu)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 10), .completed, file: file, line: line)
    }
}

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
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
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
        tap(app.buttons["找菜"].firstMatch)
        selectCookingIngredient("番茄")
        wait(app.buttons["番茄"], "isSelected == true")
    }

    @MainActor
    func testCookFlowByAccessibilityLabels() {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        // 真机上跑时不去建 CloudKit 结构（见 CloudKitSchema），免得首屏多等几秒
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()

        // 食材按钮只读名字，不读 emoji，选中后带「已选」状态
        for name in ["土豆", "番茄", "鸡蛋"] {
            // 明确检查 VoiceOver label。iOS 26.5 的快捷 identifier 查询曾返回不存在，
            // 但同一失败现场的完整无障碍树和截图均包含该按钮。
            let stuff = app.buttons.matching(NSPredicate(format: "label == %@", name)).firstMatch
            selectCookingIngredient(name)
            wait(stuff, "isSelected == true")
        }

        tap(app.buttons["筛选"])
        let stirFry = app.buttons["炒菜"]
        revealFilterOption(stirFry, in: app)
        tap(stirFry)
        wait(stirFry, "isSelected == true")
        tap(app.buttons["完成"])
        // 窄屏的筛选是半屏弹层，盖着底部按钮；等它收起再点，免得点到正在收起的弹层上
        wait(stirFry, "exists == false")

        // iPhone 打开全部结果；iPad 已在右栏实时显示完整结果。
        showCookingResults(in: app)

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
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        selectCookingIngredient("番茄")
        selectCookingIngredient("鸡蛋")
        showCookingResults(in: app)
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
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
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
        // A small screen may have recycled the earlier Form rows while the keyboard was open.
        let form = app.descendants(matching: .any).matching(identifier: "feedback.form").firstMatch
        wait(form, "exists == true")
        for _ in 0..<6 where !name.exists || !name.isHittable { form.swipeDown() }
        XCTAssertEqual(name.value as? String, "Tomato eggs")
        for _ in 0..<6 where !source.exists || !source.isHittable { form.swipeUp() }
        XCTAssertEqual(source.value as? String, "https://example.com/recipe")
        for _ in 0..<6 where !details.exists || !details.isHittable { form.swipeUp() }
        XCTAssertEqual(details.value as? String, "Easy to cook")
        XCTAssertTrue(submit.isEnabled)
    }

    @MainActor
    func testTestFeedbackFailureKeepsDraftWithoutProductionFallbacks() {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
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
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        selectCookingIngredient("番茄")
        selectCookingIngredient("鸡蛋")
        showCookingResults(in: app)
        let recipe = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄炒蛋")).firstMatch
        tap(recipe)
        tap(app.navigationBars.buttons["收藏"])
        wait(app.buttons["取消收藏"], "exists == true")
        let library = app.buttons.matching(NSPredicate(format: "label IN %@", ["菜谱", "book"])).firstMatch
        if !library.exists {
            // A real scroll can collapse the native tab bar to its selected item.
            tap(app.tabBars.buttons.firstMatch)
        }
        tap(library)
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

    @MainActor
    private func showCookingResults(in app: XCUIApplication) {
        if app.windows.firstMatch.horizontalSizeClass == .regular {
            let results = app.descendants(matching: .any).matching(identifier: "dietary.results").firstMatch
            wait(results, "exists == true")
        } else {
            let button = app.buttons["cook.showResults"]
            wait(button, "isEnabled == true AND isHittable == true")
            tap(button)
        }
    }

    @MainActor
    private func revealFilterOption(_ option: XCUIElement, in app: XCUIApplication) {
        let list = app.descendants(matching: .any).matching(identifier: "filter.options").firstMatch
        for _ in 0..<8 where !option.isHittable { list.swipeUp() }
        XCTAssertTrue(option.isHittable)
    }

    /// 食材需要完全离开固定底栏遮挡，不能依赖 XCTest 的自动滚动。
    @MainActor
    private func selectCookingIngredient(_ name: String, file: StaticString = #filePath, line: UInt = #line) {
        let app = XCUIApplication()
        let ingredient = app.buttons.matching(NSPredicate(format: "label == %@", name)).firstMatch
        wait(ingredient, "exists == true", file: file, line: line)
        let window = app.windows.firstMatch
        let action = app.buttons["cook.showResults"]
        let navigationBar = app.navigationBars.firstMatch
        var scrolled = false
        for _ in 0..<8 {
            let top = navigationBar.exists ? navigationBar.frame.maxY + 8 : window.frame.minY + 8
            let bottom = window.horizontalSizeClass != .regular && action.exists ? action.frame.minY - 8 : window.frame.maxY - 8
            if ingredient.isHittable && ingredient.frame.minY >= top && ingredient.frame.maxY <= bottom { break }
            let above = ingredient.frame.minY < top
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: above ? 0.35 : 0.65))
                .press(forDuration: 0.1,
                       thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5)),
                       withVelocity: .slow, thenHoldForDuration: 0.2)
            scrolled = true
        }
        XCTAssertTrue(ingredient.isHittable, "食材应能直接点击", file: file, line: line)
        if window.horizontalSizeClass != .regular && action.exists {
            XCTAssertLessThanOrEqual(ingredient.frame.maxY, action.frame.minY - 8,
                                     "食材应完整露在固定操作栏上方", file: file, line: line)
        }
        if scrolled { capture(app, "revealed-ingredient-\(name)") }
        tap(ingredient, file: file, line: line)
        wait(ingredient, "isSelected == true", file: file, line: line)
    }

    /// 等控件出现再点；调用方负责先使其离开遮挡区域。
    @MainActor
    private func tap(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        wait(element, "exists == true", file: file, line: line)
        element.tap()
    }

    @MainActor
    func testFilterCountUpdatesWithoutClosingSheet() {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()

        tap(app.buttons["筛选"])
        let hint = app.descendants(matching: .any)["filter.selectionHint"].firstMatch
        let count = app.descendants(matching: .any)["filter.resultCount"].firstMatch
        wait(count, "exists == true")
        XCTAssertFalse(hint.exists)
        let catalogCount = count.label.filter { $0.isASCII && $0.isNumber }
        XCTAssertGreaterThan(Int(catalogCount) ?? 0, 0, "未选食材时也应预览默认目录数量")
        tap(app.buttons["完成"])
        wait(count, "exists == false")

        selectCookingIngredient("番茄")
        selectCookingIngredient("鸡蛋")
        tap(app.buttons["筛选"])
        wait(count, "exists == true")
        let initialLabel = count.label
        let stirFry = app.buttons["炒菜"]
        revealFilterOption(stirFry, in: app)
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
            let results = app.buttons["cook.showResults"]
            wait(results, "exists == true")
            // 只比较界面显示的十进制数字。
            let resultCount = results.label.filter { $0.isASCII && $0.isNumber }
            let previewCount = initialLabel.filter { $0.isASCII && $0.isNumber }
            XCTAssertEqual(resultCount, previewCount, "筛选预览和结果入口数量一致")
        }
    }

    @MainActor
    func testPantryShelfRemembersChoicesAndRestoresDefaults() {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()

        tap(app.buttons["筛选"])
        let pantry = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "家里常备")).firstMatch
        let options = app.descendants(matching: .any).matching(identifier: "filter.options").firstMatch
        for _ in 0..<6 {
            if pantry.exists && pantry.isHittable && options.frame.contains(pantry.frame) { break }
            options.swipeUp()
        }
        XCTAssertTrue(options.frame.contains(pantry.frame), "常备调料入口应完整进入弹层的可见列表")
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

        tap(app.buttons["BackButton"].firstMatch)
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
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        tap(app.buttons["筛选"])
        let avoid = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "忌口")).firstMatch
        let options = app.descendants(matching: .any).matching(identifier: "filter.options").firstMatch
        for _ in 0..<6 {
            if avoid.exists && avoid.isHittable { break }
            options.swipeUp()
        }
        tap(avoid)
        let search = app.searchFields["搜索食材或别名"]

        for (index, entry) in [("西红柿", "番茄"), ("虾", "虾")].enumerated() {
            let (query, name) = entry
            focusSearch(search, in: app)
            if index > 0 {
                tap(search.buttons["清除文本"])
                focusSearch(search, in: app)
            }
            search.typeText(query)
            XCTAssertEqual(search.value as? String, query)
            let item = app.buttons["avoid.item.\(name)"]
            tap(item)
            wait(item, "isSelected == true")
            wait(app.buttons["avoid.remove.\(name)"], "exists == true")
        }
        focusSearch(search, in: app)
        tap(search.buttons["清除文本"])
        focusSearch(search, in: app)
        search.typeText("不存在的食材")
        XCTAssertEqual(search.value as? String, "不存在的食材")
        wait(app.staticTexts["avoid.noSearchResults"], "exists == true")
        let tomato = app.buttons["avoid.remove.番茄"]
        let shrimp = app.buttons["avoid.remove.虾"]
        wait(tomato, "exists == true")
        wait(shrimp, "exists == true")
        tap(tomato)
        wait(tomato, "exists == false")
        wait(shrimp, "exists == true")
        capture(app, "ios-avoid-selection-during-search")

        let closeSearch = app.buttons.matching(NSPredicate(format: "label IN %@", ["关闭", "取消"])).firstMatch
        if closeSearch.exists {
            tap(closeSearch)
        } else {
            focusSearch(search, in: app)
            search.typeText("\n")
            let clearSearch = search.buttons["清除文本"]
            if clearSearch.exists { tap(clearSearch) }
        }
        tap(app.buttons["BackButton"].firstMatch)
        wait(avoid, "label CONTAINS '虾'")
        tap(avoid)
        wait(shrimp, "exists == true")
        let clear = app.navigationBars["忌口"].buttons["清空"]
        tap(clear)
        wait(app.staticTexts["avoid.emptySelection"], "exists == true")
        wait(shrimp, "exists == false")
        wait(clear, "isEnabled == false")
    }

    /// 搜索栏存在不代表可交互；等待可点击及键盘出现，超时保留现场并失败。
    @MainActor
    private func focusSearch(_ search: XCUIElement, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        wait(search, "isHittable == true", file: file, line: line)
        search.tap()
        wait(app.keyboards.firstMatch, "exists == true", file: file, line: line)
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
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
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
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments
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
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_UI_TEST_FAVORITE_FAILURES"] = "2"
        app.launch()
        XCTAssertTrue(app.buttons["番茄"].waitForExistence(timeout: timeout))
        selectCookingIngredient("番茄")
        wait(app.buttons["番茄"], "isSelected == true")
        selectCookingIngredient("鸡蛋")
        wait(app.buttons["鸡蛋"], "isSelected == true")
        showCookingResults(in: app)
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
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
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
