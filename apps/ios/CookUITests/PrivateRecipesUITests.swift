import XCTest

final class PrivateRecipesUITests: XCTestCase {
    @MainActor
    func testPrivateRecipeCreateEditDelete() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        // iPad's floating tab bar exposes both its wrapper and child as the same button.
        // Select the first matching tab, then verify navigation by waiting for the library.
        let mine = app.buttons.matching(identifier: "我的").firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 30))
        mine.tap()
        let library = app.buttons["我的菜谱"]
        XCTAssertTrue(library.waitForExistence(timeout: 10))
        library.tap()
        app.buttons["新建菜谱"].tap()
        let name = "测试" + String(UUID().uuidString.prefix(6))
        let nameField = app.textFields["菜名（最多 30 字）"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 10))
        nameField.tap()
        nameField.typeText(name)
        let ingredients = app.textFields["鸡蛋、丝瓜"]
        ingredients.tap()
        ingredients.typeText("鸡蛋")
        app.buttons["保存"].tap()
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "New context saves must update the live @Query list")
        row.tap()
        app.buttons["编辑菜谱"].tap()
        XCTAssertTrue(nameField.waitForExistence(timeout: 10))
        nameField.tap()
        nameField.typeText("新")
        app.buttons["保存"].tap()
        XCTAssertTrue(app.navigationBars[name + "新"].waitForExistence(timeout: 10))
        app.buttons["删除菜谱"].tap()
        app.buttons["删除"].tap()
        XCTAssertTrue(app.navigationBars["我的菜谱"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch.exists)
    }
}
