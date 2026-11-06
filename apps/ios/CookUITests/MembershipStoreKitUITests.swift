import StoreKitTest
import XCTest

/// Runs only under the dedicated local StoreKit scheme, with a .local SKU.
/// Purchases use the real membership scene and Apple's local confirmation UI.
final class MembershipStoreKitUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor func testAutoRenewingScenePurchaseKeepsPaidHistoryAfterCancellingRenewal() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "MembershipProducts", withExtension: "storekit"))
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState(); session.clearTransactions(); session.disableDialogs = false
        defer { session.clearTransactions() }
        let productID = "fun.yunle.cook.local.autorenewing"
        let app = launchScene(reset: true)
        let list = app.collectionViews.matching(identifier: "membership.content").element
        let buy = list.buttons.matching(identifier: "membership.buy.\(productID)").element
        reveal(buy, in: list)
        buy.tap()
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        XCTAssertTrue(system.navigationBars.matching(identifier: "Xcode").element.waitForExistence(timeout: 15))
        let confirmation = XCTAttachment(screenshot: app.screenshot())
        confirmation.name = "Local Apple subscription confirmation"; confirmation.lifetime = .keepAlways; add(confirmation)
        let pay = system.buttons.matching(identifier: "pay-now").element
        XCTAssertTrue(pay.waitForExistence(timeout: 5))
        pay.tap()
        let message = list.staticTexts.matching(identifier: "membership.message").element
        reveal(message, in: list)
        XCTAssertEqual(message.waitForState("label CONTAINS %@", timeout: 15, argument: "测试购买记录已确认"), .completed)
        let active = list.staticTexts.matching(identifier: "membership.active").element
        reveal(active, in: list, upwards: false)
        let expiry = list.staticTexts.matching(identifier: "membership.expiry").element.label
        let original = try XCTUnwrap(session.allTransactions().first { $0.productIdentifier == productID })
        XCTAssertTrue(original.autoRenewingEnabled)
        // The real purchase confirmation was exercised above. Local restore
        // uses the documented no-dialog test setting for authentication UI.
        session.disableDialogs = true
        try session.disableAutoRenewForTransaction(identifier: original.identifier)
        let cancelled = try XCTUnwrap(session.allTransactions().first { $0.identifier == original.identifier })
        XCTAssertFalse(cancelled.autoRenewingEnabled)
        XCTAssertEqual(cancelled.state, .purchased, "Stopping renewal must retain the paid transaction")
        app.terminate()

        // StoreKit may also deliver a renewal-status update to the new process.
        // Explicit restore clears that notice before reading verified history;
        // cancellation or an empty/invalid result cannot leave it confirmed.
        let restarted = launchScene(reset: false)
        let restoredList = restarted.collectionViews.matching(identifier: "membership.content").element
        let restore = restoredList.buttons.matching(identifier: "membership.restore").element
        reveal(restore, in: restoredList)
        XCTAssertEqual(restore.waitForState("enabled == true", timeout: 10), .completed)
        restore.tap()
        let restoredMessage = restoredList.staticTexts.matching(identifier: "membership.message").element
        reveal(restoredMessage, in: restoredList)
        XCTAssertEqual(restoredMessage.waitForState("label CONTAINS %@", timeout: 15, argument: "测试购买记录已确认"), .completed)
        let restoredActive = restoredList.staticTexts.matching(identifier: "membership.active").element
        reveal(restoredActive, in: restoredList, upwards: false)
        XCTAssertEqual(restoredList.staticTexts.matching(identifier: "membership.expiry").element.label, expiry)
        let history = XCTAttachment(screenshot: restarted.screenshot())
        history.name = "Paid subscription retained after cancellation and restore"
        history.lifetime = .keepAlways; add(history)
    }

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

    @MainActor private func launchScene(reset: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-membership", "-ui-testing-membership-storekit",
                               "-ui-testing-reset-consent", "-ui-testing-disable-animations"]
        if reset { app.launchArguments.append("-ui-testing-reset-membership") }
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_MEMBERSHIP_FIXTURE_SETTLEMENT"] = "confirmed"
        terminateAppAfterTest(app); app.launch()
        let mine = app.buttons.matching(NSPredicate(format: "label == %@", "我的")).firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 15)); mine.tap()
        let entry = app.buttons.matching(identifier: "settings.membership").element
        XCTAssertTrue(entry.waitForExistence(timeout: 10)); entry.tap()
        XCTAssertTrue(app.collectionViews.matching(identifier: "membership.content").element.waitForExistence(timeout: 10))
        return app
    }

    @MainActor private func reveal(_ element: XCUIElement, in list: XCUIElement, upwards: Bool = true) {
        for _ in 0..<12 {
            if element.exists && element.isHittable { return }
            if upwards { list.swipeUp() } else { list.swipeDown() }
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }
}
