import XCTest
@testable import CookCore

final class CookEntitlementPolicyTests: XCTestCase {
    func testStorefrontDefaultsConservativelyToMainlandChina() {
        XCTAssertTrue(CookStorefrontPolicy.isMainlandChina(countryCode: nil))
        XCTAssertTrue(CookStorefrontPolicy.isMainlandChina(countryCode: ""))
        XCTAssertTrue(CookStorefrontPolicy.isMainlandChina(countryCode: "  "))
        XCTAssertTrue(CookStorefrontPolicy.isMainlandChina(countryCode: "CHN"))
        XCTAssertTrue(CookStorefrontPolicy.isMainlandChina(countryCode: "chn"))
        XCTAssertTrue(CookStorefrontPolicy.isMainlandChina(countryCode: " chn "))

        for code in ["HKG", "MAC", "TWN", "USA", "CAN", "JPN", "AUS", "GBR", "SGP"] {
            XCTAssertFalse(CookStorefrontPolicy.isMainlandChina(countryCode: code), code)
        }
    }

    func testOnlineAIAndSubscriptionUIDependOnStorefrontAndBuildGates() {
        for country in [nil, "", "CHN", "chn"] {
            XCTAssertFalse(CookStorefrontPolicy.allowsOnlineAI(countryCode: country, mainlandAIEnabled: false, overseasAIEnabled: true))
            XCTAssertFalse(CookStorefrontPolicy.allowsAISubscriptionUI(countryCode: country, mainlandAIEnabled: false, overseasAIEnabled: true))
            XCTAssertTrue(CookStorefrontPolicy.allowsOnlineAI(countryCode: country, mainlandAIEnabled: true, overseasAIEnabled: false))
        }

        for country in ["HKG", "MAC", "TWN", "USA", "CAN", "JPN", "AUS", "GBR", "SGP"] {
            XCTAssertFalse(CookStorefrontPolicy.allowsOnlineAI(countryCode: country, mainlandAIEnabled: false, overseasAIEnabled: false))
            XCTAssertTrue(CookStorefrontPolicy.allowsOnlineAI(countryCode: country, mainlandAIEnabled: false, overseasAIEnabled: true))
            XCTAssertTrue(CookStorefrontPolicy.allowsAISubscriptionUI(countryCode: country, mainlandAIEnabled: false, overseasAIEnabled: true))
            XCTAssertTrue(CookStorefrontPolicy.allowsOnlineAI(countryCode: country, mainlandAIEnabled: true, overseasAIEnabled: false))
        }
    }

    func testFreeQuotaLimitsAndProBypass() {
        XCTAssertTrue(CookEntitlementPolicy.canAddFridgeItem(currentInStockCount: 11, hasProAccess: false))
        XCTAssertFalse(CookEntitlementPolicy.canAddFridgeItem(currentInStockCount: 12, hasProAccess: false))
        XCTAssertFalse(CookEntitlementPolicy.canAddFridgeItem(currentInStockCount: 20, hasProAccess: false))
        XCTAssertTrue(CookEntitlementPolicy.canAddFridgeItem(currentInStockCount: 12, hasProAccess: true))
        XCTAssertTrue(CookEntitlementPolicy.canAddFridgeItem(currentInStockCount: 200, hasProAccess: true))

        XCTAssertTrue(CookEntitlementPolicy.canAddPrivateRecipe(currentCount: 2, hasProAccess: false))
        XCTAssertFalse(CookEntitlementPolicy.canAddPrivateRecipe(currentCount: 3, hasProAccess: false))
        XCTAssertTrue(CookEntitlementPolicy.canAddPrivateRecipe(currentCount: 3, hasProAccess: true))

        XCTAssertTrue(CookEntitlementPolicy.canOpenSpecialShowcase(index: 0, hasProAccess: false))
        XCTAssertFalse(CookEntitlementPolicy.canOpenSpecialShowcase(index: 1, hasProAccess: false))
        XCTAssertFalse(CookEntitlementPolicy.canOpenSpecialShowcase(index: 2, hasProAccess: false))
        XCTAssertTrue(CookEntitlementPolicy.canOpenSpecialShowcase(index: 1, hasProAccess: true))
    }

    func testSnapshotHandlesLifetimeAndSubscriptionExpirationBoundaries() {
        let now = Date(timeIntervalSince1970: 1_795_000_000)

        let free = CookProSnapshot()
        XCTAssertFalse(free.hasActiveAISubscription(at: now))
        XCTAssertFalse(free.hasProAccess(at: now))

        let lifetime = CookProSnapshot(hasProLifetime: true)
        XCTAssertFalse(lifetime.hasActiveAISubscription(at: now))
        XCTAssertTrue(lifetime.hasProAccess(at: now))

        let expired = CookProSnapshot(
            aiSubscriptionExpiresAt: now,
            aiSubscriptionProductID: CookProProductID.aiMonth
        )
        XCTAssertFalse(expired.hasActiveAISubscription(at: now))
        XCTAssertFalse(expired.hasProAccess(at: now))

        let wrongProduct = CookProSnapshot(
            aiSubscriptionExpiresAt: now.addingTimeInterval(60),
            aiSubscriptionProductID: CookProProductID.proLifetime
        )
        XCTAssertFalse(wrongProduct.hasActiveAISubscription(at: now))

        let activeYear = CookProSnapshot(
            aiSubscriptionExpiresAt: now.addingTimeInterval(60),
            aiSubscriptionProductID: CookProProductID.aiYear
        )
        XCTAssertTrue(activeYear.hasActiveAISubscription(at: now))
        XCTAssertTrue(activeYear.hasProAccess(at: now))
    }
}
