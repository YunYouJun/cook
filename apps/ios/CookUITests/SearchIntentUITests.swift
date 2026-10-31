import UIKit
import XCTest

final class SearchIntentUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    private func launch(largeDark: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        if largeDark {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", UIContentSizeCategory.accessibilityExtraExtraExtraLarge.rawValue, "-ui-testing-dark"]
        }
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launch()
        return app
    }

    @MainActor
    private func preview(_ app: XCUIApplication, text: String) {
        let search = app.textFields["cook.query"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        XCTAssertTrue(search.isHittable)
        search.tap()
        search.typeText(text)
        XCTAssertTrue(app.buttons["intent.preview"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["intent.preview"].isHittable)
        app.buttons["intent.preview"].tap()
        XCTAssertTrue(app.buttons["intent.apply"].waitForExistence(timeout: 10))
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "intent-preview-layout"
        screen.lifetime = .keepAlways
        add(screen)
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "intent-preview-hierarchy"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
    }

    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, fullyVisible: Bool = false) {
        let form = app.collectionViews.matching(identifier: "intent.form").firstMatch
        for _ in 0..<14 {
            let viewport = form.frame.intersection(app.windows.firstMatch.frame).insetBy(dx: 0, dy: 8)
            if element.isHittable, !fullyVisible || viewport.contains(element.frame) { return }
            form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.65))
                .press(forDuration: 0.1, thenDragTo: form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)),
                       withVelocity: .slow, thenHoldForDuration: 0.1)
        }
        XCTAssertTrue(element.isHittable)
        if fullyVisible {
            XCTAssertTrue(form.frame.intersection(app.windows.firstMatch.frame).contains(element.frame))
        }
    }

    @MainActor
    private func appendTomatoReparseAndCancel(_ app: XCUIApplication, attachmentName: String,
                                              largestText: Bool = false) {
        let form = app.collectionViews["intent.form"]
        XCTAssertTrue(form.waitForExistence(timeout: 10))
        let field = form.textFields["intent.text"]
        // Settings navigation and large text can leave the form scrolled below its input.
        for _ in 0..<8 {
            let viewport = form.frame.intersection(app.frame)
            if field.exists && field.isHittable && viewport.contains(field.frame) { break }
            form.swipeDown(velocity: .slow)
        }
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertTrue(field.isHittable)
        XCTAssertTrue(form.frame.intersection(app.frame).contains(field.frame))
        XCTAssertEqual(field.value as? String, "鸡蛋，不要辣")
        // Focus after the existing text instead of assuming a tap preserves the insertion point.
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        field.typeText("，番茄")
        XCTAssertEqual(field.value as? String, "鸡蛋，不要辣，番茄")
        XCTAssertFalse(app.buttons["intent.apply"].isEnabled)
        XCTAssertFalse(form.buttons["移除食材鸡蛋"].exists)
        // Hiding the iPad system keyboard leaves the field focused. Finish editing
        // through the native toolbar so the sheet settles before the next action.
        let finishEditing = app.buttons["intent.finishEditing"]
        XCTAssertTrue(finishEditing.waitForExistence(timeout: 5))
        XCTAssertTrue(finishEditing.isEnabled)
        XCTAssertTrue(finishEditing.isHittable)
        if largestText {
            let editing = XCTAttachment(screenshot: app.screenshot())
            editing.name = "\(attachmentName)-editing"
            editing.lifetime = .keepAlways
            add(editing)
        }
        finishEditing.tap()
        XCTAssertTrue(finishEditing.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "鸡蛋，不要辣，番茄")
        let local = form.buttons["intent.local"]
        reveal(local, in: app, fullyVisible: true)
        let ready = XCTAttachment(screenshot: app.screenshot())
        ready.name = attachmentName
        ready.lifetime = .keepAlways
        add(ready)
        // Use the visible row center after explicitly ending input focus.
        local.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let tomato = form.buttons["移除食材番茄"]
        if largestText {
            // Materialize the new large-text row without querying a missing lazy element's frame.
            for _ in 0..<14 {
                let viewport = form.frame.intersection(app.frame)
                if tomato.exists && tomato.isHittable && viewport.contains(tomato.frame) { break }
                form.swipeUp(velocity: .slow)
            }
        }
        XCTAssertTrue(tomato.waitForExistence(timeout: 10))
        if largestText {
            XCTAssertTrue(tomato.isHittable)
            XCTAssertTrue(form.frame.intersection(app.frame).contains(tomato.frame))
        }
        app.buttons["intent.cancel"].tap()
        if largestText {
            XCTAssertTrue(form.waitForNonExistence(timeout: 10))
            let chooser = app.collectionViews["cook.chooser"]
            XCTAssertTrue(chooser.waitForExistence(timeout: 10))
            // Inspect each large-text chip before scrolling its lazy row out of view.
            let tomatoChip = chooser.buttons["番茄"]
            for _ in 0..<8 {
                if tomatoChip.exists && tomatoChip.isHittable { break }
                chooser.swipeDown(velocity: .slow)
            }
            XCTAssertTrue(tomatoChip.waitForExistence(timeout: 10))
            XCTAssertTrue(tomatoChip.isHittable)
            XCTAssertFalse(tomatoChip.isSelected)
            let eggChip = chooser.buttons["鸡蛋"]
            for _ in 0..<14 {
                if eggChip.exists && eggChip.isHittable { break }
                chooser.swipeUp(velocity: .slow)
            }
            XCTAssertTrue(eggChip.waitForExistence(timeout: 10))
            XCTAssertTrue(eggChip.isHittable)
            XCTAssertFalse(eggChip.isSelected)
            return
        }
        XCTAssertTrue(app.buttons["鸡蛋"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["鸡蛋"].isSelected)
        XCTAssertFalse(app.buttons["番茄"].isSelected)
    }

    @MainActor
    func testEditableLocalPreviewCancelAndApply() {
        let app = launch()
        preview(app, text: "鸡蛋和西葫芦，不要辣，减少添加糖")
        XCTAssertTrue(app.buttons["移除食材鸡蛋"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["移除目标减少添加糖"].exists)
        XCTAssertFalse(app.buttons["intent.smart"].exists)
        for label in ["移除食材鸡蛋", "移除食材西葫芦", "移除不吃辣", "移除目标减少添加糖"] {
            let tag = app.buttons[label]
            // A 44 pt frame can arrive as 43.99999999999994 after coordinate conversion.
            XCTAssertGreaterThanOrEqual(tag.frame.width, 44 - 0.001)
            XCTAssertGreaterThanOrEqual(tag.frame.height, 44 - 0.001)
        }
        // Tap the transparent top inset outside the 36 pt capsule.
        app.buttons["移除食材西葫芦"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.04)).tap()
        XCTAssertFalse(app.buttons["移除食材西葫芦"].exists)
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "本地可编辑找菜条件"
        image.lifetime = .keepAlways
        add(image)
        app.buttons["intent.cancel"].tap()
        XCTAssertTrue(app.buttons["鸡蛋"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["鸡蛋"].isSelected)
        let search = app.textFields["cook.query"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        XCTAssertTrue(search.isHittable)
        search.tap()
        search.typeText("鸡蛋，不要辣")
        XCTAssertTrue(app.buttons["intent.preview"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["intent.preview"].isHittable)
        app.buttons["intent.preview"].tap()
        XCTAssertTrue(app.buttons["intent.apply"].waitForExistence(timeout: 10))
        app.buttons["intent.apply"].tap()
        XCTAssertTrue(app.buttons["鸡蛋"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["鸡蛋"].isSelected)
    }

    @MainActor
    func testLargeDarkPreviewAndUnavailableManagedService() {
        let app = launch(largeDark: true)
        preview(app, text: "鸡蛋，减少添加糖，孕期饮食")
        XCTAssertFalse(app.buttons["intent.apply"].isEnabled)
        reveal(app.buttons["移除食材鸡蛋"], in: app)
        XCTAssertTrue(app.buttons["移除食材鸡蛋"].exists)
        let goal = app.buttons["移除目标减少添加糖"]
        reveal(goal, in: app, fullyVisible: true)
        XCTAssertTrue(goal.isHittable)
        XCTAssertGreaterThanOrEqual(goal.frame.height, 44)
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "大字号深色待确认条件"
        image.lifetime = .keepAlways
        add(image)
        app.buttons["intent.cancel"].tap()
        app.buttons["我的"].firstMatch.tap()
        let settings = app.buttons["settings.ai"]
        for _ in 0..<5 where !settings.isHittable { app.swipeUp() }
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "aiSettings.managed").firstMatch.waitForExistence(timeout: 10))
    }

    @MainActor
    func testPreviewSettingsAndChangedTextKeepConditionsUnapplied() {
        let app = launch()
        preview(app, text: "鸡蛋，不要辣")
        app.buttons["移除食材鸡蛋"].tap()
        let form = app.collectionViews["intent.form"]
        let field = form.textFields["intent.text"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        let finishEditing = app.buttons["intent.finishEditing"]
        XCTAssertTrue(finishEditing.waitForExistence(timeout: 5))
        let settings = form.buttons["intent.settings"]
        reveal(settings, in: app)
        settings.tap()
        let aiForm = app.collectionViews["aiSettings.form"]
        XCTAssertTrue(aiForm.waitForExistence(timeout: 10))
        XCTAssertTrue(aiForm.buttons["aiSettings.managed"].waitForExistence(timeout: 10))
        XCTAssertTrue(finishEditing.waitForNonExistence(timeout: 5))
        let keyInput = aiForm.secureTextFields["aiSettings.keyInput"]
        // Prepare the actual settings field with bounded native scrolling inside its own form.
        for _ in 0..<14 {
            if keyInput.exists && keyInput.isHittable { break }
            aiForm.swipeUp(velocity: .slow)
        }
        XCTAssertTrue(keyInput.waitForExistence(timeout: 5))
        XCTAssertTrue(keyInput.isHittable)
        keyInput.tap()
        keyInput.typeText("x")
        let saveKey = aiForm.buttons["aiSettings.saveKey"]
        XCTAssertTrue(saveKey.waitForExistence(timeout: 5))
        XCTAssertTrue(saveKey.isEnabled)
        XCTAssertTrue(finishEditing.waitForNonExistence(timeout: 5))
        // Leave the in-memory key draft unsaved and return through the real navigation control.
        let back = app.navigationBars["AI 设置"].buttons.element(boundBy: 0)
        XCTAssertTrue(back.waitForExistence(timeout: 5))
        back.tap()
        XCTAssertTrue(app.buttons["intent.apply"].waitForExistence(timeout: 10))
        XCTAssertFalse(form.buttons["移除食材鸡蛋"].exists)
        XCTAssertTrue(app.buttons["intent.apply"].isEnabled)

        appendTomatoReparseAndCancel(app, attachmentName: "intent-reparse-after-finishing-editing")
    }

    @MainActor
    func testLargestDarkChangedTextCanFinishEditingReparseAndCancel() {
        let app = launch(largeDark: true)
        preview(app, text: "鸡蛋，不要辣")
        XCTAssertTrue(app.buttons["intent.apply"].isEnabled)
        appendTomatoReparseAndCancel(app, attachmentName: "intent-largest-dark-reparse-after-finishing-editing",
                                    largestText: true)
    }
}
