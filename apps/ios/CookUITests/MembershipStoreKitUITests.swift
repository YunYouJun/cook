import StoreKitTest
import XCTest

/// Runs only under the dedicated local StoreKit scheme, with a .local SKU.
/// This cancels the actual Apple confirmation sheet, unlike error injection.
final class MembershipStoreKitUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor func testUserCancelsLocalAppleConfirmationWithoutGrantingMembership() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "MembershipProducts", withExtension: "storekit"))
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState(); session.clearTransactions(); session.disableDialogs = false
        defer { session.clearTransactions() }
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-membership", "-ui-testing-membership-storekit", "-ui-testing-reset-membership",
                               "-ui-testing-reset-consent", "-ui-testing-disable-animations"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_MEMBERSHIP_FIXTURE_SETTLEMENT"] = "confirmed"
        terminateAppAfterTest(app)
        app.launch()
        let mine = app.buttons.matching(NSPredicate(format: "label == %@", "我的")).firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 15)); mine.tap()
        let entry = app.buttons.matching(identifier: "settings.membership").element
        XCTAssertTrue(entry.waitForExistence(timeout: 10)); entry.tap()
        let list = app.collectionViews.matching(identifier: "membership.content").element
        let buy = list.buttons.matching(identifier: "membership.buy.fun.yunle.cook.local.nonrenewing").element
        XCTAssertTrue(buy.waitForExistence(timeout: 10))
        for _ in 0..<8 where !buy.isHittable { list.swipeUp() }
        XCTAssertTrue(buy.isHittable); buy.tap()
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let confirmation = system.otherElements.matching(identifier: "payment-sheet").element
        XCTAssertTrue(confirmation.waitForExistence(timeout: 15), "The real local Apple confirmation must appear")
        let confirmationImage = XCTAttachment(screenshot: app.screenshot())
        confirmationImage.name = "Local Apple confirmation before cancellation"
        confirmationImage.lifetime = .keepAlways; add(confirmationImage)
        let cancel = system.navigationBars.matching(identifier: "Xcode").buttons.matching(identifier: "dismiss").element
        XCTAssertTrue(cancel.waitForExistence(timeout: 5), "Close the local Xcode sheet without confirming payment")
        cancel.tap()
        let message = list.staticTexts.matching(identifier: "membership.message").element
        for _ in 0..<8 where !message.exists || !message.isHittable { list.swipeUp() }
        XCTAssertEqual(message.waitForState("label CONTAINS %@", timeout: 10, argument: "已取消购买"), .completed)
        XCTAssertEqual(list.staticTexts.matching(identifier: "membership.active").count, 0)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Cancelled local Apple purchase"
        screen.lifetime = .keepAlways
        add(screen)
    }
}
