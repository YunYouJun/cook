import UIKit
import XCTest

final class RandomRecipeUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testLibraryDiceImmediatelySuggestsAndRerollsWithoutChangingScope() {
        let app = launch()
        XCTAssertFalse(app.buttons["随机"].exists)
        app.buttons["菜谱"].firstMatch.tap()
        let favorites = app.segmentedControls.buttons["收藏"]
        wait(favorites, "isHittable == true")
        favorites.tap()
        openRandom(in: app)
        let card = decidedRecipe(in: app)
        wait(card, "exists == true AND label != '' AND label != '正在挑选'")
        let previous = card.label
        let again = app.buttons["cook.decide.again"]
        XCTAssertTrue(again.isHittable)
        again.tap()
        wait(app.buttons["cook.decide.open"], "isEnabled == true AND isHittable == true")
        wait(card, "label != %@ AND label != '正在挑选'", previous)
        capture(app, "library-random-motion-result")

        app.buttons["cook.decide.close"].tap()
        wait(app.buttons["library.random"], "isHittable == true")
        XCTAssertTrue(favorites.isSelected, "Random selection must preserve the library's browsing scope")
        openRandom(in: app)
        app.confirmDecidedRecipe()
        wait(favoriteButton(in: app), "isHittable == true")
        assertNativeRecipeContent(in: app)
        capture(app, "library-random-detail-from-empty-favorites")
    }

    @MainActor
    func testRandomIgnoresNameSearchAndKeepsIngredientAndAvoidanceMatches() {
        let app = launch()
        let query = app.textFields["cook.query"]
        query.tap()
        query.typeText("鸡蛋，不要辣，不要牛肉\n")
        query.tap()
        query.typeText("没有这道菜的搜索词\n")
        app.buttons["菜谱"].firstMatch.tap()
        openRandom(in: app)
        let context = app.staticTexts["cook.decide.context"]
        XCTAssertTrue(context.label.contains("已有食材：鸡蛋"))
        XCTAssertTrue(context.label.contains("忌口：牛肉"), context.label)
        XCTAssertTrue(context.label.contains("不吃辣"))
        XCTAssertFalse(context.label.contains("菜名："))
        capture(app, "library-random-shared-conditions")
        app.confirmDecidedRecipe()
        wait(favoriteButton(in: app), "isHittable == true")
        let owned = app.staticTexts["已有"]
        for _ in 0..<8 {
            if owned.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(owned.isHittable, "The real ingredient match must reach the recipe detail")
        XCTAssertTrue(app.staticTexts["鸡蛋"].exists)
    }

    @MainActor
    func testLargestTextRandomSharesDietaryGoalAndPreservesEmptyResults() {
        let app = launch(contentSize: .accessibilityExtraExtraExtraLarge)
        app.buttons["菜谱"].firstMatch.tap()
        openRandom(in: app)
        app.buttons["cook.decide.filters"].tap()
        let picker = app.collectionViews["filter.options"].buttons["dietary.openPicker"]
        wait(picker, "isHittable == true")
        picker.tap()
        let goal = app.buttons["dietary.goal.less-added-sugar"]
        wait(goal, "isHittable == true")
        goal.tap()
        app.completeDietarySelection(closingFilters: true,
            returningTo: app.buttons.matching(identifier: "cook.decide.filters").firstMatch)
        wait(app.staticTexts["cook.decide.context"], "label CONTAINS '减少添加糖'")
        XCTAssertTrue(app.buttons["cook.decide.open"].isHittable, "Large-text actions must stay reachable")
        capture(app, "library-random-largest-text")
        app.confirmDecidedRecipe()
        wait(favoriteButton(in: app), "isHittable == true")
        XCTAssertTrue(app.buttons["dietary.openReviewedRecipe"].exists)

        if app.windows.firstMatch.horizontalSizeClass != .regular {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            wait(app.buttons["library.random"], "isHittable == true")
        }
        selectTab("找菜", current: "菜谱", in: app)
        let query = app.textFields["cook.query"]
        reveal(query, in: app)
        query.tap()
        query.typeText("牛肉\n")
        selectTab("菜谱", current: "找菜", in: app)
        openRandom(in: app)
        wait(app.staticTexts["cook.decide.empty"], "exists == true")
        XCTAssertFalse(app.buttons["cook.decide.open"].exists, "An empty reviewed pool must never fall back to ordinary recipes")
        XCTAssertFalse(app.buttons["cook.decide.again"].exists)
        capture(app, "library-random-largest-text-empty")
    }

    @MainActor
    private func openRandom(in app: XCUIApplication) {
        let dice = app.buttons["library.random"]
        wait(dice, "isHittable == true")
        dice.tap()
        wait(app.buttons["cook.decide.close"], "isHittable == true")
    }

    @MainActor
    private func decidedRecipe(in app: XCUIApplication) -> XCUIElement {
        app.staticTexts.matching(identifier: "cook.decide.recipe").firstMatch
    }

    @MainActor
    private func favoriteButton(in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label IN %@", ["收藏", "取消收藏"])).firstMatch
    }

    @MainActor
    private func selectTab(_ name: String, current: String, in app: XCUIApplication) {
        let tab = app.buttons[name].firstMatch
        if !tab.exists || !tab.isHittable { app.buttons[current].firstMatch.tap() }
        wait(tab, "isHittable == true")
        tab.tap()
    }

    @MainActor
    private func launch(contentSize: UIContentSizeCategory = .large) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        var arguments = XCUIApplication.standardCookingLaunchArguments
        if let index = arguments.firstIndex(of: "-UIPreferredContentSizeCategoryName") {
            arguments[index + 1] = contentSize.rawValue
        }
        app.launchArguments = arguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        terminateAppAfterTest(app)
        app.launch()
        wait(app.buttons["cook.presentation"], "isHittable == true")
        return app
    }

    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<12 {
            if element.isHittable { return }
            app.swipeUp()
        }
        XCTFail("Control must be reachable: \(element.identifier)")
    }

    @MainActor
    private func wait(_ element: XCUIElement, _ predicate: String, _ argument: String? = nil,
                      file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(element.waitForState(predicate, timeout: 20, argument: argument), .completed, file: file, line: line)
    }

    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
