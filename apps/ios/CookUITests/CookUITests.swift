import XCTest

/// 按 VoiceOver 读到的名字走一遍主流程：选食材 → 筛选 → 看看能做什么 → 打开菜谱 → 收藏。
/// 这里只用无障碍标签找控件，标签不对或控件读不到，这个用例就会失败。CI 在 iPhone 和 iPad 模拟器上各跑一遍，覆盖窄屏和宽屏两种布局。
final class CookUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testCookFlowByAccessibilityLabels() {
        let app = XCUIApplication()
        app.launch()

        // 食材按钮只读名字，不读 emoji，选中后带「已选」状态
        let potato = app.buttons["土豆"]
        XCTAssertTrue(potato.waitForExistence(timeout: 10))
        potato.tap()
        XCTAssertTrue(potato.isSelected)
        app.buttons["番茄"].tap()
        app.buttons["鸡蛋"].tap()

        app.buttons["筛选"].tap()
        let stirFry = app.buttons["炒菜"]
        XCTAssertTrue(stirFry.waitForExistence(timeout: 5))
        stirFry.tap()
        XCTAssertTrue(stirFry.isSelected)
        app.buttons["完成"].tap()

        // 宽屏（iPad、折叠屏展开）左边选食材、右边直接出结果，没有底部按钮；窄屏点底部按钮进结果页
        let showResults = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "看看能做什么")).firstMatch
        if app.windows.firstMatch.horizontalSizeClass == .regular {
            XCTAssertFalse(showResults.exists)
        } else {
            XCTAssertTrue(showResults.waitForExistence(timeout: 5))
            XCTAssertTrue(showResults.isEnabled)
            showResults.tap()
        }

        // 结果行合成一个元素，读出菜名和厨具难度
        let recipe = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄炒蛋")).firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 5))
        recipe.tap()

        // 收藏存在本机数据库里，同一台模拟器上次跑完可能已经收藏过，先取消再收藏
        let unfavorite = app.buttons["取消收藏"]
        if unfavorite.waitForExistence(timeout: 2) {
            unfavorite.tap()
        }
        let favorite = app.buttons["收藏"]
        XCTAssertTrue(favorite.waitForExistence(timeout: 5))
        favorite.tap()
        XCTAssertTrue(unfavorite.waitForExistence(timeout: 5))
    }
}
