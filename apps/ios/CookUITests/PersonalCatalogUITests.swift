import XCTest

final class PersonalCatalogUITests: XCTestCase {
    @MainActor
    func testCustomIngredientHideRestoreAndSelection() {
        let app = openCatalog()
        app.buttons["添加条目"].tap()
        let field = app.textFields["名称（最多 30 字）"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("鹰嘴豆\n")
        XCTAssertTrue(app.buttons["删除鹰嘴豆"].waitForExistence(timeout: 10))
        capture(app, "ios-ingredients")
        app.segmentedControls.buttons["厨具"].tap()
        XCTAssertTrue(app.buttons["隐藏烤箱"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["隐藏番茄"].exists)
        capture(app, "ios-tools")
        app.segmentedControls.buttons["蔬菜"].tap()
        let hide = app.buttons["隐藏番茄"]
        if !hide.isHittable { app.swipeUp() }
        XCTAssertTrue(hide.waitForExistence(timeout: 10))
        hide.tap()
        XCTAssertTrue(app.buttons["恢复番茄"].waitForExistence(timeout: 10))
        app.buttons["恢复番茄"].tap()
        XCTAssertTrue(app.buttons["隐藏番茄"].waitForExistence(timeout: 10))
        let search = app.searchFields["搜索当前分类"]
        search.tap()
        search.typeText("鹰嘴豆")
        XCTAssertTrue(app.buttons["删除鹰嘴豆"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["隐藏土豆"].exists)
        let closeSearch = app.buttons.matching(NSPredicate(format: "label IN %@", ["关闭", "取消"])).firstMatch
        if closeSearch.exists {
            closeSearch.tap()
        } else {
            // iPad keeps search inline; clearing the submitted query restores navigation.
            search.typeText("\n")
            let clearSearch = search.buttons.firstMatch
            XCTAssertTrue(clearSearch.waitForExistence(timeout: 10))
            clearSearch.tap()
        }
        // Inline iPad search contributes a clear-text button before the back button.
        let back = app.buttons["BackButton"]
        XCTAssertTrue(back.waitForExistence(timeout: 10))
        back.tap()
        XCTAssertTrue(app.buttons["食材和厨具"].waitForExistence(timeout: 10))
        // The manager can minimize the iOS 26+ tab bar. Tapping its current tab
        // expands it even when the root list is already at the top and cannot scroll back.
        capture(app, "ios-catalog-returned-to-settings")
        let mine = app.buttons.matching(identifier: "我的").firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 10))
        mine.tap()
        let cook = app.buttons.matching(identifier: "找菜").firstMatch
        XCTAssertTrue(cook.waitForExistence(timeout: 10))
        cook.tap()
        let ingredient = app.buttons.matching(identifier: "鹰嘴豆").firstMatch
        for _ in 0..<5 where !ingredient.isHittable { app.swipeUp() }
        XCTAssertTrue(ingredient.waitForExistence(timeout: 10))
        ingredient.tap()
        XCTAssertTrue(ingredient.isSelected || ingredient.value as? String == "已选")
        capture(app, "ios-custom-ingredient-selected")

        app.buttons["筛选"].tap()
        let avoid = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "忌口")).firstMatch
        for _ in 0..<4 where !avoid.isHittable { app.swipeUp() }
        XCTAssertTrue(avoid.waitForExistence(timeout: 10))
        avoid.tap()
        let avoidSearch = app.searchFields["搜索食材或别名"]
        XCTAssertTrue(avoidSearch.waitForExistence(timeout: 10))
        avoidSearch.tap()
        avoidSearch.typeText("鹰嘴豆")
        let excluded = app.buttons["avoid.item.鹰嘴豆"]
        XCTAssertTrue(excluded.waitForExistence(timeout: 10), "个人食材仍可在忌口选择器中搜索")
        excluded.tap()
        XCTAssertTrue(app.buttons["avoid.remove.鹰嘴豆"].waitForExistence(timeout: 10))
        capture(app, "ios-custom-ingredient-excluded")
    }

    @MainActor
    func testCustomToolDuplicateNameAndDeletion() {
        let app = openCatalog()
        app.segmentedControls.buttons["厨具"].tap()
        app.buttons["添加条目"].tap()
        let field = app.textFields["名称（最多 30 字）"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("塔吉锅\n")
        let remove = app.buttons["删除塔吉锅"]
        XCTAssertTrue(remove.waitForExistence(timeout: 10), "New tools must appear in the selected category")
        capture(app, "ios-custom-tools")

        app.buttons["添加条目"].tap()
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("塔吉锅\n")
        XCTAssertTrue(app.staticTexts["这个名称已经存在；隐藏的内置条目可在下方恢复。"].waitForExistence(timeout: 10))
        XCTAssertEqual(field.value as? String, "塔吉锅", "Failed saves must keep the draft in the editor")
        app.buttons["取消"].tap()
        XCTAssertTrue(remove.waitForExistence(timeout: 10))
        remove.tap()
        let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: remove)
        XCTAssertEqual(XCTWaiter().wait(for: [removed], timeout: 10), .completed)
        XCTAssertTrue(app.buttons["隐藏烤箱"].exists, "Deleting a custom tool must preserve built-in tools")
    }

    @MainActor
    private func openCatalog() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        let mine = app.buttons.matching(identifier: "我的").firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 30))
        mine.tap()
        app.buttons["食材和厨具"].tap()
        return app
    }

    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
