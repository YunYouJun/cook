import UIKit
import XCTest

final class FridgeVisionUITests: XCTestCase {
    @MainActor
    func testPendingMembershipCheckKeepsOfflinePhotoAvailable() {
        let app = openVision(accessPending: true)
        let progress = app.staticTexts["正在核对会员资格…"]
        XCTAssertTrue(progress.waitForExistence(timeout: 5))
        XCTAssertTrue(app.navigationBars["AI 识图"].buttons["取消"].isHittable)
        keepScreenshot("核对会员资格", in: app)

        app.buttons["fridge.vision.offline"].tap()
        XCTAssertTrue(app.buttons["fridge.camera"].waitForExistence(timeout: 10))
        XCTAssertFalse(progress.exists)
    }

    @MainActor
    func testSignedOutUserCanOpenVisionFromToolbarWithoutFixture() {
        let app = openFridge()
        let entry = app.navigationBars["冰箱"].buttons["fridge.vision.open"]
        XCTAssertTrue(entry.isHittable)
        XCTAssertEqual(entry.label, "AI 识图")
        keepScreenshot("冰箱识图入口", in: app)
        entry.tap()
        XCTAssertTrue(app.buttons["登录云乐坊账号"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["fridge.vision.offline"].exists)
    }

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
        XCTAssertTrue(app.navigationBars["冰箱"].buttons["fridge.vision.open"].isHittable,
                      "已有食材时仍可通过工具栏识图")
    }

    @MainActor
    func testTimeoutShowsToastAndKeepsPhotoAvailableToRetry() {
        let app = openPreview(result: "timeout")
        let upload = app.buttons["fridge.vision.upload"]
        upload.tap()

        let toast = app.staticTexts["fridge.vision.failureToast"]
        XCTAssertTrue(toast.waitForExistence(timeout: 4))
        XCTAssertEqual(toast.label, "AI 识图超时，请重试。")
        keepScreenshot("识图超时提示", in: app)
        XCTAssertTrue(toast.waitForNonExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["AI 识图超时，请重试。"].exists)
        XCTAssertTrue(upload.isHittable)
        upload.tap()
        XCTAssertTrue(toast.waitForExistence(timeout: 4))
    }

    @MainActor
    func testEmptyResultShowsToastAndOffersAnotherPhoto() {
        let app = openPreview(result: "empty")
        app.buttons["fridge.vision.upload"].tap()

        let toast = app.staticTexts["fridge.vision.failureToast"]
        XCTAssertTrue(toast.waitForExistence(timeout: 4))
        XCTAssertEqual(toast.label, "没有识别出食材，请换一张照片。")
        XCTAssertFalse(app.buttons["fridge.vision.save"].exists)
        app.buttons["换一张照片"].tap()
        XCTAssertTrue(app.buttons["从照片选择"].waitForExistence(timeout: 5), "换图应直接回到选图")
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
    private func openPreview(result: String = "success") -> XCUIApplication {
        let app = openVision(result: result)
        XCTAssertTrue(app.buttons["fridge.vision.upload"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["fridge.vision.recipient"].label.contains("DeepSeek"))
        return app
    }

    @MainActor
    private func openVision(result: String = "success", nonmember: Bool = false, accessPending: Bool = false) -> XCUIApplication {
        let app = openFridge(result: result, nonmember: nonmember, accessPending: accessPending)
        let entry = app.navigationBars["冰箱"].buttons["fridge.vision.open"]
        XCTAssertTrue(entry.waitForExistence(timeout: 10))
        XCTAssertTrue(entry.isHittable)
        XCTAssertEqual(entry.label, "AI 识图")
        entry.tap()
        return app
    }

    @MainActor
    private func openFridge(result: String? = nil, nonmember: Bool = false, accessPending: Bool = false) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        if let result {
            let photo = UIGraphicsImageRenderer(size: CGSize(width: 150, height: 150)).image { renderer in
                UIColor.systemGreen.setFill()
                renderer.fill(CGRect(x: 0, y: 0, width: 150, height: 150))
            }
            app.launchEnvironment["COOK_UI_TEST_FRIDGE_VISION_JPEG"] = photo.jpegData(compressionQuality: 0.8)!.base64EncodedString()
            app.launchEnvironment["COOK_UI_TEST_FRIDGE_VISION_RESULT"] = result
        }
        if nonmember { app.launchEnvironment["COOK_UI_TEST_FRIDGE_VISION_NONMEMBER"] = "1" }
        if accessPending { app.launchEnvironment["COOK_UI_TEST_FRIDGE_VISION_ACCESS_PENDING"] = "1" }
        terminateAppAfterTest(app)
        app.launch()
        let fridge = app.buttons["冰箱"].firstMatch
        XCTAssertTrue(fridge.waitForExistence(timeout: 30))
        fridge.tap()
        XCTAssertTrue(app.navigationBars["冰箱"].waitForExistence(timeout: 10))
        return app
    }

    @MainActor
    private func keepScreenshot(_ name: String, in app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func fridgeRow(_ name: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
    }
}
