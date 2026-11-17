import UIKit
import XCTest

final class ExpandedSearchUITests: XCTestCase {
    @MainActor
    func testClosingWithKeyboardRestoresTheSameHomePosition() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .phone, "Only iPhone uses in-place search.")
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + [
            "-ui-testing-reset-consent", "-cook.search.phoneStyle", "expanded",
        ]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        terminateAppAfterTest(app)
        let decline = app.buttons["不分享，继续使用"]
        if decline.waitForExistence(timeout: 2) { decline.tap() }

        let entry = app.buttons["cook.search.open"]
        XCTAssertTrue(entry.waitForExistence(timeout: 10))
        let restingY = entry.frame.midY
        checkClose(app, restingY: restingY)

        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.49))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.462))
        start.press(forDuration: 0.12, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.20)
        XCTAssertTrue(entry.isHittable)
        XCTAssertLessThan(entry.frame.midY, restingY - 5)
        checkClose(app, restingY: restingY)
    }

    @MainActor
    private func checkClose(_ app: XCUIApplication, restingY: CGFloat,
                            file: StaticString = #filePath, line: UInt = #line) {
        let entry = app.buttons["cook.search.open"]
        let action = app.buttons["cook.showResults"]
        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(action.exists, file: file, line: line)
        XCTAssertTrue(tabBar.exists, file: file, line: line)
        let entryY = entry.frame.midY
        let actionY = action.frame.midY
        let tabBarY = tabBar.frame.midY
        let query = app.openCookingSearch(file: file, line: line)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), file: file, line: line)
        XCTAssertEqual(query.frame.midY, restingY, accuracy: 1, file: file, line: line)
        XCTAssertTrue(app.buttons["cook.search.cancel"].exists, file: file, line: line)

        app.closeCookingSearch(file: file, line: line)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5), file: file, line: line)
        XCTAssertEqual(entry.frame.midY, entryY, accuracy: 1, file: file, line: line)
        XCTAssertEqual(action.frame.midY, actionY, accuracy: 1, file: file, line: line)
        XCTAssertEqual(tabBar.frame.midY, tabBarY, accuracy: 1, file: file, line: line)
    }
}
