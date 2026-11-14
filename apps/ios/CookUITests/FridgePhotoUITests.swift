import UIKit
import XCTest

final class FridgePhotoUITests: XCTestCase {
    @MainActor
    func testFruitExamplesCanBeReadWithoutAddingFridgeItems() {
        let app = openFridge()
        app.buttons["拍照识别"].firstMatch.tap()
        let blueberry = app.buttons["fridge.example.blueberry"]
        for _ in 0..<3 where !blueberry.isHittable { app.swipeUp() }
        XCTAssertTrue(blueberry.waitForExistence(timeout: 10))
        blueberry.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "购买日期填实际买回的那天")).firstMatch.exists)
        let watermelon = app.buttons["fridge.example.watermelon"]
        for _ in 0..<4 where !watermelon.isHittable { app.swipeUp() }
        XCTAssertTrue(watermelon.waitForExistence(timeout: 10))
        watermelon.tap()
        let cutInstructions = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "实际切开的那天")).firstMatch
        for _ in 0..<4 where !cutInstructions.isHittable { app.swipeUp() }
        XCTAssertTrue(cutInstructions.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["fridge.scan.save"].exists, "阅读示例不能进入待保存的识别结果")
        capture(app, "fridge-fruit-examples")
        app.buttons["取消"].tap()
        XCTAssertTrue(app.staticTexts["冰箱是空的"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testPhotoLabelCanBeConfirmedSavedAndRemoved() {
        let app = openFridge(label: "MFG: 2026-10-01\nSHELF LIFE: 30 DAYS")
        app.buttons["拍照识别"].firstMatch.tap()
        let name = app.textFields["fridge.scan.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 30))
        XCTAssertEqual(app.buttons.matching(identifier: "fridge.scan.save").count, 1)
        name.tap()
        name.typeText("牛奶\n")
        XCTAssertTrue(app.buttons["fridge.scan.save"].isEnabled)
        XCTAssertTrue(app.staticTexts["生产日期与保质期推算"].exists)
        capture(app, "fridge-label-review")
        app.buttons["fridge.scan.save"].tap()
        XCTAssertTrue(app.navigationBars["冰箱"].waitForExistence(timeout: 10))
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "牛奶")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10))
        item.tap()
        XCTAssertTrue(app.staticTexts["生产日期"].waitForExistence(timeout: 10))
        app.buttons["吃完了，移出冰箱"].tap()
        XCTAssertTrue(app.staticTexts["冰箱是空的"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testProduceSuggestionCanBeChosenWithoutLabelDates() {
        let app = openFridge(label: "FRESH PRODUCE")
        app.buttons["拍照识别"].firstMatch.tap()
        let name = app.textFields["fridge.scan.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 30))
        XCTAssertFalse(app.buttons["fridge.scan.save"].isEnabled)
        name.tap()
        name.typeText("草莓\n")
        let suggestion = app.buttons["fridge.scan.useSuggestion"]
        for _ in 0..<5 where !suggestion.isHittable { app.swipeUp() }
        XCTAssertTrue(suggestion.waitForExistence(timeout: 10))
        suggestion.tap()
        capture(app, "fridge-produce-suggestion")
        XCTAssertTrue(app.buttons["fridge.scan.save"].isEnabled)
        app.buttons["fridge.scan.save"].tap()
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "草莓")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10))
        XCTAssertTrue(item.label.contains("建议后天吃完"), item.label)
        capture(app, "fridge-suggested-date")
        item.tap()
        XCTAssertTrue(app.staticTexts["建议吃完日期"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testPhotoCancellationLeavesFridgeEmptyAndManualFruitStillWorks() {
        let app = openFridge()
        app.buttons["拍照识别"].firstMatch.tap()
        XCTAssertTrue(app.buttons["fridge.camera"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["fridge.photoLibrary"].exists)
        app.buttons["取消"].tap()
        XCTAssertTrue(app.staticTexts["冰箱是空的"].waitForExistence(timeout: 10))
        app.buttons["放点食材"].tap()
        let apple = app.buttons["苹果"]
        for _ in 0..<6 where !apple.isHittable { app.swipeUp() }
        XCTAssertTrue(apple.waitForExistence(timeout: 10))
        apple.tap()
        app.buttons["放进去（1）"].tap()
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "苹果")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10))
        XCTAssertTrue(item.label.contains("建议 28 天内吃完"), item.label)
    }

    @MainActor
    func testFruitCanMoveFromCustomCategoryBackToAutomaticGrouping() {
        let categoryName = "零食"
        let renamedCategoryName = "零食盒"
        let app = openFridge()
        app.buttons["放点食材"].tap()
        app.buttons["新建分类"].tap()
        let name = app.textFields["fridge.category.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText(categoryName)
        app.buttons["保存"].tap()

        for fruit in ["蓝莓", "西瓜", "苹果"] {
            let chip = app.buttons[fruit]
            for _ in 0..<4 where !chip.isHittable { app.swipeUp() }
            XCTAssertTrue(chip.isHittable, fruit)
            chip.tap()
        }
        app.buttons["放进去（3）"].tap()
        let grouped = app.buttons["按分类"]
        XCTAssertTrue(grouped.waitForExistence(timeout: 10))
        grouped.tap()
        XCTAssertTrue(app.staticTexts[categoryName].exists)
        XCTAssertFalse(app.staticTexts["水果"].exists)
        let apple = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "苹果")).firstMatch
        XCTAssertTrue(apple.exists)
        XCTAssertTrue(apple.label.contains("建议 28 天内吃完"), apple.label)
        capture(app, "fridge-custom-category")

        apple.tap()
        let picker = app.buttons["fridge.category.picker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        picker.tap()
        app.buttons["自动归类（水果）"].tap()
        app.buttons["完成"].tap()
        XCTAssertTrue(app.staticTexts["水果"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts[categoryName].exists)
        XCTAssertTrue(apple.label.contains("建议 28 天内吃完"), apple.label)

        app.buttons["管理分类"].tap()
        let category = app.buttons[categoryName]
        XCTAssertTrue(category.waitForExistence(timeout: 10))
        category.tap()
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText("盒")
        app.buttons["保存"].tap()
        let renamed = app.buttons[renamedCategoryName]
        XCTAssertTrue(renamed.waitForExistence(timeout: 10))
        renamed.swipeLeft()
        app.buttons["删除"].tap()
        app.alerts.buttons["删除分类"].tap()
        XCTAssertTrue(renamed.waitForNonExistence(timeout: 10))
        app.buttons["完成"].tap()
        let watermelon = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "西瓜")).firstMatch
        XCTAssertTrue(watermelon.waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts[categoryName].exists)
        XCTAssertFalse(app.staticTexts[renamedCategoryName].exists)
    }

    @MainActor
    private func openFridge(label: String? = nil) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        if let label {
            let image = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 650)).image { renderer in
                UIColor.white.setFill()
                renderer.fill(CGRect(x: 0, y: 0, width: 1200, height: 650))
                (label as NSString).draw(
                    in: CGRect(x: 60, y: 60, width: 1080, height: 530),
                    withAttributes: [.font: UIFont.systemFont(ofSize: 64), .foregroundColor: UIColor.black])
            }
            app.launchEnvironment["COOK_FRIDGE_PHOTO_FIXTURE"] = image.pngData()!.base64EncodedString()
        }
        terminateAppAfterTest(app)
        app.launch()
        let fridge = app.buttons["冰箱"].firstMatch
        XCTAssertTrue(fridge.waitForExistence(timeout: 30))
        fridge.tap()
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
