import XCTest

final class PrivateRecipeVideoUITests: XCTestCase {
    @MainActor
    func testVideoPlaybackPickerCancellationAndRemoval() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launchEnvironment["COOK_UI_TEST_VIDEO_SOURCE"] = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "private-recipe-video", withExtension: "mp4")).path
        app.launch()
        let mine = app.buttons.matching(identifier: "我的").firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 30))
        mine.tap()
        let library = app.buttons["我的菜谱"]
        XCTAssertTrue(library.waitForExistence(timeout: 10))
        library.tap()
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "视频测试蒸蛋")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        let export = app.buttons["导出视频文件"]
        XCTAssertTrue(export.waitForExistence(timeout: 20), "The actual fixture must load through AVFoundation before export is offered")
        XCTAssertTrue(app.descendants(matching: .any)["private-recipe-video-player"].firstMatch.exists)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "private-video-playback"
        shot.lifetime = .keepAlways
        add(shot)
        app.swipeUp()
        let edit = app.buttons["编辑菜谱"]
        XCTAssertTrue(edit.waitForExistence(timeout: 10))
        edit.tap()
        let replace = app.buttons["替换视频"]
        for _ in 0..<4 where !replace.isHittable { app.swipeUp() }
        XCTAssertTrue(replace.waitForExistence(timeout: 10))
        replace.tap()
        // This is the real system picker; cancelling it must retain the existing attachment.
        let pickerBack = app.buttons["DOC.navBarButton.backInHistory"]
        XCTAssertTrue(pickerBack.waitForExistence(timeout: 10))
        let pickerShot = XCTAttachment(screenshot: app.screenshot())
        pickerShot.name = "private-video-system-picker"
        pickerShot.lifetime = .keepAlways
        add(pickerShot)
        print(app.navigationBars.debugDescription)
        let dismissButtons = app.buttons.matching(NSPredicate(format: "label IN %@", ["取消", "Cancel", "关闭", "Close"]))
        let pickerBar = app.navigationBars["FullDocumentManagerViewControllerNavigationBar"]
        let phoneCancel = pickerBar.buttons.matching(NSPredicate(format: "label IN %@", ["取消", "Cancel"])).firstMatch
        let pickerCancel = phoneCancel.exists ? phoneCancel : dismissButtons.firstMatch
        XCTAssertTrue(pickerCancel.waitForExistence(timeout: 10))
        // iOS 27's glass proxy overlays this UIButton in the accessibility tree. Target the
        // observed navigation button's center, rather than the editor's underlying Cancel.
        pickerCancel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: pickerBack)
        XCTAssertEqual(XCTWaiter().wait(for: [closed], timeout: 10), .completed)
        XCTAssertTrue(replace.waitForExistence(timeout: 10))
        let remove = app.buttons["移除视频"]
        for _ in 0..<3 where !remove.isHittable { app.swipeUp() }
        remove.tap()
        XCTAssertTrue(app.buttons["关联视频"].waitForExistence(timeout: 10))
        app.buttons["取消"].tap()
        app.swipeDown()
        XCTAssertTrue(export.waitForExistence(timeout: 10), "Cancelling removal must preserve the saved record and file")
        app.swipeUp()
        app.buttons["编辑菜谱"].tap()
        for _ in 0..<4 where !app.buttons["移除视频"].isHittable { app.swipeUp() }
        app.buttons["移除视频"].tap()
        app.buttons["保存"].tap()
        XCTAssertTrue(app.navigationBars["视频测试蒸蛋"].waitForExistence(timeout: 10))
        XCTAssertFalse(export.exists)
    }
}
