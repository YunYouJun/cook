import Foundation

/// Generated alongside the USDZ from the website's scene; names are the shared
/// contract between the model, native hit testing and the recipe ingredient IDs.
struct CloudKitchenAsset: Decodable {
    struct Ingredient: Decodable, Identifiable {
        let id: String
        let name: String
        let node: String
        let icon: String
    }

    struct Camera: Decodable {
        let center: [Float]
        let direction: [Float]
        let horizontalHull: [[Float]]
        let verticalHull: [[Float]]
    }

    let version: Int
    let model: String
    let ingredients: [Ingredient]
    let camera: Camera

    static func icon(for name: String) -> String? {
        bundled?.ingredients.first { $0.name == name }?.icon
    }

    static let bundled: CloudKitchenAsset? = {
        guard let url = Bundle.main.url(forResource: "CloudKitchenManifest", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let asset = try? JSONDecoder().decode(Self.self, from: data),
              asset.version == 1 else { return nil }
        return asset
    }()
}
