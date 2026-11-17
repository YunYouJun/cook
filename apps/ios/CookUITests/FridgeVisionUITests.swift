import UIKit
import XCTest

final class FridgeVisionUITests: XCTestCase {
    @MainActor
    func testCancelFromPreviewLeavesFridgeEmptyAndOfflineEntryAvailable() {
        let app = openPreview()
        XCTAssertFalse(app.buttons["fridge.vision.save"].exists)
        app.buttons["取消"].tap()
        XCTAssertTrue(app.staticTexts["冰箱是空的"].waitForExistence(timeout: 10))
        app.buttons["拍照识别"].firstMatch.tap()
        XCTAssertTrue(app.buttons["fridge.camera"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["fridge.photoLibrary"].exists)
    }

    @MainActor
    func testConfirmedBatchStoresOnlyCheckedNames() {
        let app = openPreview()
        app.buttons["fridge.vision.upload"].tap()
        let milk = app.buttons["取消勾选牛奶"]
        XCTAssertTrue(milk.waitForExistence(timeout: 10))
        milk.tap()
        let save = app.buttons["fridge.vision.save"]
        XCTAssertEqual(save.label, "加入冰箱（2 样）")
        save.tap()

        XCTAssertTrue(fridgeRow("番茄", in: app).waitForExistence(timeout: 10))
        XCTAssertTrue(fridgeRow("鸡蛋", in: app).exists)
        XCTAssertFalse(fridgeRow("牛奶", in: app).exists)
        XCTAssertTrue(app.buttons["fridge.vision.open"].isHittable, "批量识图入口仍应在已有食材的冰箱页面直接可见")
    }

    @MainActor
    func testNonmemberCanSeeBenefitsAndSwitchToFreeOfflinePhotoWithoutUploading() {
        let app = openVision(nonmember: true)
        let benefits = app.buttons["fridge.vision.membership"]
        XCTAssertTrue(benefits.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["fridge.vision.upload"].exists)
        XCTAssertFalse(app.buttons["从照片选择"].exists)
        benefits.tap()
        let title = app.navigationBars["会员"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["冰箱 AI 批量识图"].exists)
        title.buttons.firstMatch.tap()

        let offline = app.buttons["fridge.vision.offline"]
        XCTAssertTrue(offline.waitForExistence(timeout: 10))
        offline.tap()
        XCTAssertTrue(app.buttons["fridge.camera"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["fridge.photoLibrary"].exists)
    }

    @MainActor
    private func openPreview() -> XCUIApplication {
        let app = openVision()
        XCTAssertTrue(app.buttons["fridge.vision.upload"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["fridge.vision.recipient"].label.contains("DeepSeek"))
        return app
    }

    @MainActor
    private func openVision(nonmember: Bool = false) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        let photo = UIGraphicsImageRenderer(size: CGSize(width: 150, height: 150)).image { renderer in
            UIColor.systemGreen.setFill()
            renderer.fill(CGRect(x: 0, y: 0, width: 150, height: 150))
        }
        app.launchEnvironment["COOK_UI_TEST_FRIDGE_VISION_JPEG"] = photo.jpegData(compressionQuality: 0.8)!.base64EncodedString()
        if nonmember { app.launchEnvironment["COOK_UI_TEST_FRIDGE_VISION_NONMEMBER"] = "1" }
        terminateAppAfterTest(app)
        app.launch()
        let fridge = app.buttons["冰箱"].firstMatch
        XCTAssertTrue(fridge.waitForExistence(timeout: 30))
        fridge.tap()
        let entry = app.buttons["fridge.vision.open"]
        XCTAssertTrue(entry.waitForExistence(timeout: 10))
        XCTAssertTrue(entry.isHittable)
        XCTAssertEqual(entry.label, "AI 识图（会员）")
        entry.tap()
        return app
    }

    private func fridgeRow(_ name: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
    }
}
