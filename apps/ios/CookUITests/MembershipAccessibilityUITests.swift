import UIKit
import XCTest

/// Full/manual regression owns system audits. Daily financial coverage retains
/// the standard and largest-text interaction paths without repeating this audit.
final class MembershipAccessibilityUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }
    @MainActor func testMembershipLargestTextAccessibilityAudit() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-ui-testing-membership", "-ui-testing-reset-membership", "-ui-testing-reset-consent",
                               "-ui-testing-disable-animations", "-UIPreferredContentSizeCategoryName", UIContentSizeCategory.accessibilityExtraExtraExtraLarge.rawValue]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_MEMBERSHIP_FIXTURE_SETTLEMENT"] = "confirmed"
        terminateAppAfterTest(app); app.launch()
        let mine = app.buttons.matching(NSPredicate(format: "label == %@", "我的")).firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 15)); mine.tap()
        let entry = app.buttons.matching(identifier: "settings.membership").element
        XCTAssertTrue(entry.waitForExistence(timeout: 10)); entry.tap()
        let list = app.collectionViews.matching(identifier: "membership.content").element
        XCTAssertTrue(list.waitForExistence(timeout: 10))
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "Membership largest text accessibility"; image.lifetime = .keepAlways; add(image)
        try app.performAccessibilityAudit(for: [.contrast, .elementDetection, .hitRegion, .sufficientElementDescription, .textClipped, .trait]) { issue in
            print("Membership audit: \(issue.compactDescription); \(issue.detailedDescription)")
            print("Membership audit element: \(issue.element?.debugDescription ?? "unavailable")")
            // Diagnostics only; every reported issue still fails this audit.
            return false
        }
    }
}
