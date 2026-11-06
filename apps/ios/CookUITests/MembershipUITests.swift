import UIKit
import XCTest

final class MembershipUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor func testUnconfirmedPaymentSurvivesRestartAndAnotherAccountCannotClaimIt() {
        let app = launch(reset: true, settlement: "offline")
        openMembership(app)
        let list = app.collectionViews.matching(identifier: "membership.content").element
        let buy = list.buttons.matching(identifier: "membership.buy.fun.yunle.cook.member_month").element
        reveal(buy, in: list)
        buy.tap()
        let message = list.staticTexts.matching(identifier: "membership.message").element
        reveal(message, in: list)
        XCTAssertEqual(message.waitForState("label CONTAINS %@", timeout: 10, argument: "购买记录正在核对"), .completed)
        XCTAssertEqual(list.staticTexts.matching(identifier: "membership.active").count, 0)
        let switchAccount = list.buttons.matching(identifier: "membership.fixture.switchAccount").element
        reveal(switchAccount, in: list)
        switchAccount.tap()
        reveal(message, in: list)
        XCTAssertEqual(message.waitForState("label CONTAINS %@", timeout: 10, argument: "当前账号无法认领"), .completed)
        XCTAssertEqual(list.staticTexts.matching(identifier: "membership.active").count, 0)
        app.terminate()

        let restarted = launch(reset: false, settlement: "confirmed")
        openMembership(restarted)
        let status = restarted.collectionViews.matching(identifier: "membership.content").element
            .staticTexts.matching(identifier: "membership.active").element
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        let screenshot = XCTAttachment(screenshot: restarted.screenshot())
        screenshot.name = "Confirmed membership after restart"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    @MainActor func testLargestTextDarkMembershipExplainsBothPurchaseModesAndPending() {
        let app = launch(reset: true, settlement: "confirmed", large: true, purchaseResult: "pending")
        openMembership(app)
        let list = app.collectionViews.matching(identifier: "membership.content").element
        let oneTime = list.buttons.matching(identifier: "membership.buy.fun.yunle.cook.member_month").element
        reveal(oneTime, in: list)
        XCTAssertTrue(oneTime.isHittable)
        let renewable = list.buttons.matching(identifier: "membership.buy.fun.yunle.cook.subscription_month").element
        reveal(renewable, in: list)
        XCTAssertTrue(renewable.isHittable)
        renewable.tap()
        let message = list.staticTexts.matching(identifier: "membership.message").element
        reveal(message, in: list)
        XCTAssertEqual(message.waitForState("label CONTAINS %@", timeout: 10, argument: "等待批准"), .completed)
        XCTAssertEqual(list.staticTexts.matching(identifier: "membership.active").count, 0)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Membership largest text dark pending"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    @MainActor private func launch(reset: Bool, settlement: String, large: Bool = false, purchaseResult: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-membership", "-ui-testing-disable-animations", "-ui-testing-reset-consent",
                               "-UIPreferredContentSizeCategoryName", large ? UIContentSizeCategory.accessibilityExtraExtraExtraLarge.rawValue : UIContentSizeCategory.large.rawValue]
        if reset { app.launchArguments.append("-ui-testing-reset-membership") }
        if large { app.launchArguments.append("-ui-testing-dark") }
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_MEMBERSHIP_FIXTURE_SETTLEMENT"] = settlement
        app.launchEnvironment["COOK_MEMBERSHIP_FIXTURE_RESULT"] = purchaseResult
        terminateAppAfterTest(app)
        app.launch()
        return app
    }

    @MainActor private func openMembership(_ app: XCUIApplication) {
        let mine = app.buttons.matching(NSPredicate(format: "label == %@", "我的")).element
        XCTAssertTrue(mine.waitForExistence(timeout: 15)); mine.tap()
        let list = app.collectionViews.matching(identifier: "settings.list").element
        let entry = list.buttons.matching(identifier: "settings.membership").element
        reveal(entry, in: list); entry.tap()
        XCTAssertTrue(app.collectionViews.matching(identifier: "membership.content").element.waitForExistence(timeout: 10))
    }

    @MainActor private func reveal(_ element: XCUIElement, in list: XCUIElement) {
        for _ in 0..<12 {
            if element.exists && element.isHittable { return }
            list.swipeUp()
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }
}
