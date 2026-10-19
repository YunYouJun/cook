import Foundation

public enum CookDataError: Error, Equatable {
    /// 数据结构比 App 新，需要升级 App；调用方应继续用内置数据
    case unsupportedSchemaVersion(Int)
}

/// packages/data 里 Web 和 iOS 共用的全部数据。
/// App 内置一份；在线更新时下载同样结构的目录，按 meta.dataVersion 判断是否更新。
public struct CookData: Sendable {
    /// 本版 CookCore 能读的数据结构版本，对应 packages/cook 的 DATA_SCHEMA_VERSION
    public static let supportedSchemaVersion = 1

    public var meta: RecipeMeta
    public var recipes: [Recipe]
    public var incompatibleRules: [IncompatibleRule]
    public var aliases: [String: String]
    public var food: FoodCatalog
    public var pantry: Pantry

    public var normalizer: StuffNormalizer {
        StuffNormalizer(aliases: aliases)
    }

    /// 从目录读取：recipe-meta.json、recipe.json 或 recipe-chunk-*.json、incompatible-foods.json、aliases.json、food.json、pantry.json
    public static func load(from directory: URL) throws -> CookData {
        let decoder = JSONDecoder()
        func read<T: Decodable>(_ type: T.Type, _ name: String) throws -> T {
            try decoder.decode(type, from: Data(contentsOf: directory.appendingPathComponent(name)))
        }

        let meta = try read(RecipeMeta.self, "recipe-meta.json")
        guard meta.schemaVersion == supportedSchemaVersion else {
            throw CookDataError.unsupportedSchemaVersion(meta.schemaVersion)
        }

        let recipes: [Recipe]
        if meta.chunked {
            recipes = try (meta.chunks ?? [])
                .sorted { $0.index < $1.index }
                .flatMap { try read([Recipe].self, "recipe-chunk-\($0.index).json") }
        } else {
            recipes = try read([Recipe].self, "recipe.json")
        }
        let incompatibleRules = try read([IncompatibleRule].self, "incompatible-foods.json")
        let aliases = try read([String: String].self, "aliases.json")
        let food = try read(FoodCatalog.self, "food.json")
        let pantry = try read(Pantry.self, "pantry.json")

        return CookData(
            meta: meta,
            recipes: recipes,
            incompatibleRules: incompatibleRules,
            aliases: aliases,
            food: food,
            pantry: pantry
        )
    }
}
