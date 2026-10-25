import Foundation

/// 图文做法正文里的一段（packages/data/recipe-text.json，结构见 schema/recipe-text.schema.json）。
/// 文字是行内 Markdown，可能有加粗和外链。
public enum RecipeTextBlock: Decodable, Hashable, Sendable {
    case heading(String)
    case paragraph(String)
    /// 列表项里的换行是补充说明或子项
    case list(ordered: Bool, start: Int, items: [String])
    /// 配图：src 相对 RecipeTextLibrary.imageBase；宽高用来在加载前按比例占位
    case image(src: String, width: Int, height: Int, alt: String?)

    private enum CodingKeys: String, CodingKey {
        case type, text, ordered, start, items, src, width, height, alt
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
        case "image":
            self = .image(
                src: try container.decode(String.self, forKey: .src),
                width: try container.decode(Int.self, forKey: .width),
                height: try container.decode(Int.self, forKey: .height),
                alt: try container.decodeIfPresent(String.self, forKey: .alt)
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
    public var amountAnnotations: RecipeAmountAnnotations.Entry?

    private enum CodingKeys: String, CodingKey { case blocks }

    public init(blocks: [RecipeTextBlock]) {
        self.blocks = blocks
    }

    /// 配图张数，没配置配图地址时提示去原文看
    public var imageCount: Int {
        blocks.filter {
            if case .image = $0 { return true }
            return false
        }.count
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
    /// 配图地址前缀（以 / 结尾），没有时不显示配图
    public var imageBase: URL?
    private var recipes: [String: RecipeText]
    private var amountAnnotations: RecipeAmountAnnotations?

    private enum CodingKeys: String, CodingKey { case source, imageBase, recipes }

    public static let fileName = "recipe-text.json"

    /// 从数据目录读取；没有这个文件时返回 nil（旧数据包、在线更新的数据可能不带）
    public static func load(from directory: URL) throws -> RecipeTextLibrary? {
        let url = directory.appendingPathComponent(fileName)
        guard FileManager.default.fileExists(atPath: url.path) else {
            return nil
        }
        var library = try JSONDecoder().decode(RecipeTextLibrary.self, from: Data(contentsOf: url))
        library.amountAnnotations = RecipeAmountAnnotations.load(from: directory)
        return library
    }

    public var count: Int {
        recipes.count
    }

    public func text(for recipeId: Int) -> RecipeText? {
        guard var text = recipes[String(recipeId)] else { return nil }
        text.amountAnnotations = amountAnnotations?.entry(for: recipeId, source: text)
        return text
    }

    /// 配图的完整地址；没配置 imageBase 时为 nil
    public func imageURL(_ src: String) -> URL? {
        guard let imageBase else { return nil }
        return URL(string: src, relativeTo: imageBase)?.absoluteURL
    }
}
