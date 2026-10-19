import Foundation

/// 图文做法正文里的一段（packages/data/recipe-text.json，结构见 schema/recipe-text.schema.json）。
/// 文字是行内 Markdown，可能有加粗和外链。
public enum RecipeTextBlock: Decodable, Hashable, Sendable {
    case heading(String)
    case paragraph(String)
    /// 列表项里的换行是补充说明或子项
    case list(ordered: Bool, start: Int, items: [String])

    private enum CodingKeys: String, CodingKey {
        case type, text, ordered, start, items
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "heading":
            self = .heading(try container.decode(String.self, forKey: .text))
        case "list":
            self = .list(
                ordered: try container.decode(Bool.self, forKey: .ordered),
                start: try container.decodeIfPresent(Int.self, forKey: .start) ?? 1,
                items: try container.decode([String].self, forKey: .items)
            )
        default:
            // 新版数据加了不认识的类型时按段落显示
            self = .paragraph(try container.decodeIfPresent(String.self, forKey: .text) ?? "")
        }
    }
}

/// 一道菜的图文做法正文，App 离线显示，不用打开原文网页
public struct RecipeText: Decodable, Hashable, Sendable {
    public var blocks: [RecipeTextBlock]
    /// 原文里的配图数量；图片不收录，提示去原文看
    public var images: Int

    private enum CodingKeys: String, CodingKey {
        case blocks, images
    }

    public init(blocks: [RecipeTextBlock], images: Int = 0) {
        self.blocks = blocks
        self.images = images
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        blocks = try container.decode([RecipeTextBlock].self, forKey: .blocks)
        images = try container.decodeIfPresent(Int.self, forKey: .images) ?? 0
    }
}

/// recipe-text.json：图文做法正文和出处，按菜谱 id 查
public struct RecipeTextLibrary: Decodable, Sendable {
    public struct Source: Decodable, Hashable, Sendable {
        public var name: String
        public var url: String
        public var license: String
        public var commit: String
    }

    public var source: Source
    private var recipes: [String: RecipeText]

    public static let fileName = "recipe-text.json"

    /// 从数据目录读取；没有这个文件时返回 nil（旧数据包、在线更新的数据可能不带）
    public static func load(from directory: URL) throws -> RecipeTextLibrary? {
        let url = directory.appendingPathComponent(fileName)
        guard FileManager.default.fileExists(atPath: url.path) else {
            return nil
        }
        return try JSONDecoder().decode(RecipeTextLibrary.self, from: Data(contentsOf: url))
    }

    public var count: Int {
        recipes.count
    }

    public func text(for recipeId: Int) -> RecipeText? {
        recipes[String(recipeId)]
    }
}
