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

        // 收藏存在本机数据库里，同一台模拟器上次跑完可能已经收藏过：先等详情页的收藏按钮出来（两种状态都算），收藏过就先取消
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

    /// 等控件满足条件，超时就让用例失败，失败信息里写明是哪个控件、等的什么
    @MainActor
    private func wait(_ element: XCUIElement, _ condition: String, file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: condition), object: element)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertEqual(result, .completed, "等了 \(Int(timeout)) 秒，\(element.description) 仍不满足 \(condition)", file: file, line: line)
    }
}
