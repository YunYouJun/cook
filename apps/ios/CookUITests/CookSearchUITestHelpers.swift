import XCTest

@MainActor
extension XCUIApplication {
    @discardableResult
    func openCookingSearch(file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let query = textFields["cook.query"]
        if query.exists && query.isHittable { return query }
        let entry = buttons["cook.search.open"]
        let chooser = collectionViews["cook.chooser"]
        let content = chooser.exists ? chooser : collectionViews["kitchen.content"]
        for _ in 0..<16 {
            if entry.exists && entry.isHittable { break }
            content.swipeDown(velocity: .fast)
        }
        XCTAssertTrue(entry.waitForExistence(timeout: 10), file: file, line: line)
        entry.tap()
        XCTAssertTrue(query.waitForExistence(timeout: 10), file: file, line: line)
        XCTAssertTrue(query.isHittable, file: file, line: line)
        return query
    }

    func previewCookingSearch(file: StaticString = #filePath, line: UInt = #line) {
        let preview = buttons["intent.preview"]
        XCTAssertTrue(preview.waitForExistence(timeout: 10), file: file, line: line)
        XCTAssertTrue(preview.isHittable, file: file, line: line)
        // iPad sheet movement during keyboard presentation can leave XCTest's
        // cached hit point below the row. Use its current visible frame instead.
        preview.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(buttons["intent.cancel"].waitForExistence(timeout: 10), file: file, line: line)
    }

    func closeCookingSearch(file: StaticString = #filePath, line: UInt = #line) {
        let cancel = buttons["cook.search.cancel"]
        if cancel.exists {
            cancel.tap()
        } else {
            navigationBars["搜索"].buttons.element(boundBy: 0).tap()
        }
        XCTAssertTrue(textFields["cook.query"].waitForNonExistence(timeout: 10), file: file, line: line)
        XCTAssertTrue(buttons["cook.search.open"].waitForExistence(timeout: 10), file: file, line: line)
    }
}
