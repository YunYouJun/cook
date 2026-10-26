import RealityKit
import XCTest
@testable import Cook

final class CloudKitchenAssetTests: XCTestCase {
    /// Exercise the real exported USDZ with Apple's loader, not a mocked scene.
    @MainActor
    func testSharedModelLoadsWithNamedIngredientsAndPot() throws {
        let asset = try XCTUnwrap(CloudKitchenAsset.bundled)
        let url = try XCTUnwrap(Bundle.main.url(forResource: asset.model, withExtension: "usdz"))
        let model = try Entity.load(contentsOf: url)
        XCTAssertNotNil(model.findEntity(named: "CookPot"))
        XCTAssertEqual(asset.ingredients.count, 8)
        XCTAssertEqual(Set(asset.ingredients.map(\.name)).count, 8)
        let store = try RecipeStore.loadBundled(inMemory: true)
        for ingredient in asset.ingredients {
            XCTAssertNotNil(model.findEntity(named: ingredient.node), ingredient.name)
            XCTAssertNotNil(model.findEntity(named: "Selection_\(ingredient.id)"), ingredient.name)
            XCTAssertTrue(store.stuffItems.contains { $0.name == ingredient.name }, ingredient.name)
            XCTAssertNotNil(UIImage(named: ingredient.icon), ingredient.name)
        }
        XCTAssertLessThan(try Data(contentsOf: url).count, 4 * 1024 * 1024)
        XCTAssertEqual(asset.camera.center.count, 3)
        XCTAssertEqual(asset.camera.direction.count, 3)
        for hull in [asset.camera.horizontalHull, asset.camera.verticalHull] {
            XCTAssertGreaterThan(hull.count, 2)
            XCTAssertTrue(hull.allSatisfy { $0.count == 2 && $0.allSatisfy(\.isFinite) })
        }
    }
}
