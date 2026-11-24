#if targetEnvironment(simulator)
import CookCore
import StoreKit
import StoreKitTest
import XCTest
@testable import Cook

@MainActor final class NativeCookProStoreKitTests: XCTestCase {
    func testLocalCatalogAndVerifiedLifetimeEntitlementSurviveStoreReload() async throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "CookProProducts", withExtension: "storekit"))
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
        defer { session.clearTransactions() }

        let products = try await Product.products(for: CookProProductID.allProductIDs)
        let catalog = Dictionary(uniqueKeysWithValues: products.map { ($0.id, $0) })
        XCTAssertEqual(Set(catalog.keys), Set(CookProProductID.allProductIDs))
        XCTAssertEqual(catalog[CookProProductID.proLifetime]?.type, .nonConsumable)
        XCTAssertEqual(catalog[CookProProductID.aiMonth]?.type, .autoRenewable)
        XCTAssertEqual(catalog[CookProProductID.aiYear]?.type, .autoRenewable)
        XCTAssertEqual(catalog[CookProProductID.aiMonth]?.subscription?.subscriptionGroupID,
                       catalog[CookProProductID.aiYear]?.subscription?.subscriptionGroupID)

        let purchase = try await session.buyProduct(identifier: CookProProductID.proLifetime)
        XCTAssertEqual(purchase.environment, .xcode)
        XCTAssertEqual(purchase.productID, CookProProductID.proLifetime)

        let afterPurchase = CookProStore.live(isolated: false)
        await afterPurchase.refreshEntitlements()
        XCTAssertTrue(afterPurchase.hasProAccess)
        XCTAssertFalse(afterPurchase.hasActiveAISubscription)
        XCTAssertNil(afterPurchase.appleAIAuthorization)

        let afterReload = CookProStore.live(isolated: false)
        await afterReload.refreshEntitlements()
        XCTAssertTrue(afterReload.hasProAccess)
    }
}
#endif
