import UIKit
import XCTest

final class SearchHistoryUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testHistoryPersistsRefillsWithoutSubmittingAndCanBeDeleted() {
        let app = launch()
        for name in ["番茄炒蛋", "蒸蛋", "红烧肉", "可乐鸡翅"] { submit(name, in: app) }
        let query = app.openCookingSearch()
        query.tap()
        let oldest = app.buttons["cook.history.entry.番茄炒蛋"]
        XCTAssertTrue(app.buttons["cook.history.entry.可乐鸡翅"].waitForExistence(timeout: 5))
        XCTAssertTrue(oldest.isHittable)
        capture(app, "search-page-history")
        oldest.tap()
        XCTAssertEqual(query.value as? String, "番茄炒蛋")
        XCTAssertFalse(app.buttons["cook.showResults"].isHittable, "搜索页不显示首页的结果操作")
        app.closeCookingSearch()
        XCTAssertEqual(app.buttons["cook.clearKeyword"].label, "清除菜名：可乐鸡翅", "回填后返回不能应用旧条件")
        app.openCookingSearch()
        app.buttons["cook.history.remove.红烧肉"].tap()
        XCTAssertFalse(app.buttons["cook.history.entry.红烧肉"].exists)
        app.terminate()
        app.launchArguments += ["-ui-testing-keep-search-history"]
        app.launch()
        app.openCookingSearch()
        XCTAssertTrue(oldest.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["cook.history.entry.红烧肉"].exists)
        capture(app, "search-history-reloaded")
        app.buttons["cook.history.clear"].tap()
        let confirm = app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "清空搜索历史", "cook.history.clear")).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()
        app.terminate()
        app.launch()
        app.openCookingSearch()
        XCTAssertTrue(app.staticTexts["cook.searchExample"].waitForExistence(timeout: 5))
        XCTAssertFalse(oldest.exists)
    }

    @MainActor
    func testCancelledPreviewIsNotRecordedAndAppliedPreviewIsRecorded() {
        let app = launch(dark: true)
        let query = app.openCookingSearch()
        query.tap()
        query.typeText("鸡蛋，不要辣")
        app.previewCookingSearch()
        XCTAssertTrue(app.buttons["intent.cancel"].waitForExistence(timeout: 5))
        app.buttons["intent.cancel"].tap()
        XCTAssertTrue(query.waitForExistence(timeout: 5))
        XCTAssertEqual(query.value as? String, "鸡蛋，不要辣", "取消预览应保留搜索草稿")
        app.buttons["cook.clearInput"].tap()
        XCTAssertFalse(app.buttons["cook.history.entry.鸡蛋，不要辣"].exists)
        query.typeText("鸡蛋，不要辣")
        app.previewCookingSearch()
        XCTAssertTrue(app.buttons["intent.apply"].waitForExistence(timeout: 5))
        app.buttons["intent.apply"].tap()
        XCTAssertTrue(query.waitForNonExistence(timeout: 10))
        app.openCookingSearch()
        XCTAssertTrue(app.buttons["cook.history.entry.鸡蛋，不要辣"].waitForExistence(timeout: 5))
        capture(app, "search-history-dark")
    }

    @MainActor
    func testSearchCancelKeepsHomeStableAndKitchenSharesHistory() {
        let app = launch()
        let potato = app.collectionViews["cook.chooser"].buttons["土豆"]
        let originalY = potato.frame.minY
        let query = app.openCookingSearch()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "进入搜索页应自动聚焦")
        query.typeText("未提交的条件")
        capture(app, "search-page-editing")
        app.closeCookingSearch()
        XCTAssertEqual(potato.frame.minY, originalY, accuracy: 1, "返回后首页食材位置保持稳定")
        XCTAssertFalse(app.buttons["cook.clearKeyword"].exists)
        capture(app, "search-home-stable")
        app.selectCookingPresentation("云厨房")
        app.openCookingSearch()
        XCTAssertFalse(app.buttons["cook.history.entry.未提交的条件"].exists)
        query.typeText("番茄炒蛋\n")
        XCTAssertTrue(query.waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.buttons["kitchen.cook"].waitForExistence(timeout: 5))
        app.openCookingSearch()
        XCTAssertTrue(app.buttons["cook.history.entry.番茄炒蛋"].waitForExistence(timeout: 5))
        app.closeCookingSearch()
        XCTAssertEqual(app.buttons["cook.clearKeyword"].label, "清除菜名：番茄炒蛋")
    }

    @MainActor
    private func submit(_ name: String, in app: XCUIApplication) {
        let query = app.openCookingSearch()
        query.tap()
        query.typeText(name + "\n")
        XCTAssertTrue(query.waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.buttons["cook.search.open"].waitForExistence(timeout: 5))
    }

    @MainActor
    private func launch(dark: Bool = false) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        if dark { app.launchArguments += ["-ui-testing-dark"] }
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        terminateAppAfterTest(app)
        app.launch()
        XCTAssertTrue(app.buttons["cook.search.open"].waitForExistence(timeout: 15))
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
