import CookCore
import StoreKit
import StoreKitTest
import XCTest

/// Runs only under the dedicated local StoreKit scheme, with a .local SKU.
/// Purchases use the real membership scene and Apple's local confirmation UI.
final class MembershipStoreKitUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor func testAutoRenewingScenePurchaseKeepsPaidTransactionAfterCancellingRenewal() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "MembershipProducts", withExtension: "storekit"))
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState(); session.clearTransactions(); session.disableDialogs = false
        clearSessionAfterAppTeardown(session)
        let productID = "fun.yunle.cook.local.autorenewing"
        let app = launchScene(reset: true)
        let list = app.collectionViews.matching(identifier: "membership.content").element
        let buy = list.buttons.matching(identifier: "membership.buy.\(productID)").element
        reveal(buy, in: list)
        buy.tap()
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        // A cold StoreKit service can still be presenting its remote UI after
        // 15 seconds. Keep the case's overall 120-second limit unchanged.
        let confirmationReady = system.navigationBars.matching(identifier: "Xcode").element.waitForExistence(timeout: 30)
        let confirmation = XCTAttachment(screenshot: app.screenshot())
        confirmation.name = "Local Apple subscription confirmation"; confirmation.lifetime = .keepAlways; add(confirmation)
        let hierarchy = XCTAttachment(string: system.debugDescription)
        hierarchy.name = "Apple subscription confirmation hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        XCTAssertTrue(confirmationReady)
        let pay = system.buttons.matching(identifier: "pay-now").element
        XCTAssertTrue(pay.waitForExistence(timeout: 5))
        pay.tap()
        dismissLocalSuccessIfPresented(system)
        let message = list.staticTexts.matching(identifier: "membership.message").element
        reveal(message, in: list)
        XCTAssertEqual(message.waitForState("label CONTAINS %@", timeout: 15, argument: "测试购买记录已确认"), .completed)
        XCTAssertTrue(system.navigationBars.matching(identifier: "Xcode").element.waitForNonExistence(timeout: 10))
        let active = list.staticTexts.matching(identifier: "membership.active").element
        reveal(active, in: list, upwards: false)
        XCTAssertFalse(list.staticTexts.matching(identifier: "membership.expiry").element.label.isEmpty)
        let original = try XCTUnwrap(session.allTransactions().first { $0.productIdentifier == productID })
        XCTAssertTrue(original.autoRenewingEnabled)
        try session.disableAutoRenewForTransaction(identifier: original.identifier)
        let cancelled = try XCTUnwrap(session.allTransactions().first { $0.identifier == original.identifier })
        XCTAssertFalse(cancelled.autoRenewingEnabled)
        XCTAssertEqual(cancelled.state, .purchased, "Stopping renewal must retain the paid transaction")
        let paid = XCTAttachment(screenshot: app.screenshot())
        paid.name = "Paid subscription retained after cancellation"
        paid.lifetime = .keepAlways; add(paid)
    }

    @MainActor func testCancelledRenewalRestoresFinishedHistoryAfterProcessRestart() async throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "MembershipProducts", withExtension: "storekit"))
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState(); session.clearTransactions(); session.disableDialogs = true
        clearSessionAfterAppTeardown(session)
        // Independent external purchase fixture. The first App process must
        // confirm and finish it; the second restores that completed history.
        let token = MembershipAccountToken.forUser("membership-ui-a")
        let transaction = try await session.buyProduct(identifier: "fun.yunle.cook.local.autorenewing", options: [.appAccountToken(token)])
        XCTAssertEqual(transaction.environment, .xcode)
        XCTAssertEqual(transaction.appAccountToken, token)
        XCTAssertNil(transaction.revocationDate)
        let app = launchScene(reset: true)
        let expiry = restoreInScene(app)
        let original = try XCTUnwrap(session.allTransactions().first { UInt64($0.identifier) == transaction.id })
        XCTAssertTrue(original.autoRenewingEnabled)
        try session.disableAutoRenewForTransaction(identifier: original.identifier)
        let cancelled = try XCTUnwrap(session.allTransactions().first { $0.identifier == original.identifier })
        XCTAssertFalse(cancelled.autoRenewingEnabled)
        XCTAssertEqual(cancelled.state, .purchased)
        app.terminate()

        // StoreKit may also deliver a renewal-status update to the new process.
        // Explicit restore clears that notice before reading verified history;
        // cancellation or an empty/invalid result cannot leave it confirmed.
        let restarted = launchScene(reset: false)
        XCTAssertEqual(restoreInScene(restarted), expiry)
        let history = XCTAttachment(screenshot: restarted.screenshot())
        history.name = "Paid subscription retained after cancellation and restore"
        history.lifetime = .keepAlways; add(history)
    }

    @MainActor func testUserCancelsLocalAppleConfirmationWithoutGrantingMembership() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "MembershipProducts", withExtension: "storekit"))
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState(); session.clearTransactions(); session.disableDialogs = false
        clearSessionAfterAppTeardown(session)
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
        XCTAssertTrue(confirmation.waitForNonExistence(timeout: 10))
        let message = list.staticTexts.matching(identifier: "membership.message").element
        for _ in 0..<8 where !message.exists || !message.isHittable { list.swipeUp() }
        XCTAssertEqual(message.waitForState("label CONTAINS %@", timeout: 10, argument: "已取消购买"), .completed)
        let inactive = list.staticTexts.matching(identifier: "membership.inactive").element
        reveal(inactive, in: list, upwards: false)
        XCTAssertEqual(list.staticTexts.matching(identifier: "membership.active").count, 0)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Cancelled local Apple purchase"
        screen.lifetime = .keepAlways
        add(screen)
    }

    @MainActor func testRefundFromOutsideAppDeliversVerifiedRevocationWithoutManualRefresh() async throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "MembershipProducts", withExtension: "storekit"))
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState(); session.clearTransactions(); session.disableDialogs = true
        clearSessionAfterAppTeardown(session)
        let token = MembershipAccountToken.forUser("membership-ui-b")
        let transaction = try await session.buyProduct(identifier: "fun.yunle.cook.local.nonrenewing", options: [.appAccountToken(token)])
        XCTAssertEqual(transaction.environment, .xcode)
        XCTAssertEqual(transaction.appAccountToken, token)
        let app = launchScene(reset: true, owner: "b")
        _ = restoreInScene(app) // The App, not the UI runner, confirms and finishes.
        try session.refundTransaction(identifier: try XCTUnwrap(UInt(exactly: transaction.id)))
        let list = app.collectionViews.matching(identifier: "membership.content").element
        let inactive = list.staticTexts.matching(identifier: "membership.inactive").element
        // No restore/refresh action after the refund: only the real SDK update
        // can reconcile the owned, verified revocation and refresh entitlement.
        XCTAssertTrue(inactive.waitForExistence(timeout: 10))
        reveal(inactive, in: list, upwards: false)
        XCTAssertEqual(list.staticTexts.matching(identifier: "membership.active").count, 0)
        let revoked = XCTAttachment(screenshot: app.screenshot())
        revoked.name = "Verified external refund reconciled by App updates"
        revoked.lifetime = .keepAlways; add(revoked)
        requestRestore(in: app)
        reveal(inactive, in: list, upwards: false)
        XCTAssertEqual(list.staticTexts.matching(identifier: "membership.active").count, 0)
    }

    @MainActor private func launchScene(reset: Bool, owner: String = "a") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-membership", "-ui-testing-membership-storekit",
                               "-ui-testing-reset-consent", "-ui-testing-disable-animations"]
        if reset { app.launchArguments.append("-ui-testing-reset-membership") }
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_MEMBERSHIP_FIXTURE_SETTLEMENT"] = "confirmed"
        app.launchEnvironment["COOK_MEMBERSHIP_FIXTURE_OWNER"] = owner
        terminateAppAfterTest(app); app.launch()
        let mine = app.buttons.matching(NSPredicate(format: "label == %@", "我的")).firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 15)); mine.tap()
        let entry = app.buttons.matching(identifier: "settings.membership").element
        XCTAssertTrue(entry.waitForExistence(timeout: 10)); entry.tap()
        XCTAssertTrue(app.collectionViews.matching(identifier: "membership.content").element.waitForExistence(timeout: 10))
        return app
    }

    @MainActor private func restoreInScene(_ app: XCUIApplication) -> String {
        requestRestore(in: app)
        let list = app.collectionViews.matching(identifier: "membership.content").element
        let active = list.staticTexts.matching(identifier: "membership.active").element
        reveal(active, in: list, upwards: false)
        return list.staticTexts.matching(identifier: "membership.expiry").element.label
    }

    @MainActor private func requestRestore(in app: XCUIApplication) {
        let list = app.collectionViews.matching(identifier: "membership.content").element
        let restore = list.buttons.matching(identifier: "membership.restore").element
        reveal(restore, in: list)
        XCTAssertEqual(restore.waitForState("enabled == true", timeout: 10), .completed)
        restore.tap()
        let message = list.staticTexts.matching(identifier: "membership.message").element
        reveal(message, in: list)
        XCTAssertEqual(message.waitForState("label CONTAINS %@", timeout: 15, argument: "测试购买记录已确认"), .completed)
    }

    @MainActor private func dismissLocalSuccessIfPresented(_ system: XCUIApplication) {
        let success = system.alerts.matching(NSPredicate(format: "label IN %@", ["You’re all set.", "You're all set."])).element
        if success.waitForExistence(timeout: 5) {
            let okay = success.buttons.matching(identifier: "OK").element
            XCTAssertTrue(okay.waitForExistence(timeout: 5))
            okay.tap()
            XCTAssertTrue(success.waitForNonExistence(timeout: 10))
        }
    }

    @MainActor private func clearSessionAfterAppTeardown(_ session: SKTestSession) {
        // XCTest teardown blocks run in reverse registration order. Register
        // before launchScene so the App and its observer stop before reset.
        addTeardownBlock {
            await MainActor.run { session.clearTransactions() }
        }
    }

    @MainActor private func reveal(_ element: XCUIElement, in list: XCUIElement, upwards: Bool = true) {
        for _ in 0..<12 {
            if element.exists && element.isHittable { return }
            if upwards { list.swipeUp() } else { list.swipeDown() }
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }
}
