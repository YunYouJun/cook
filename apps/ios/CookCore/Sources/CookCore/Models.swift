import Foundation

/// 一道菜，对应 packages/data/recipe.json 的一项（结构见 packages/data/schema/recipe.schema.json）。
public struct Recipe: Codable, Hashable, Sendable, Identifiable {
    /// 固定编号：收藏、历史和同步都以它为主键，不会修改或复用
    public var id: Int
    public var name: String
    public var link: String?
    /// B 站视频号（BV 号或旧版 av 号）
    public var bv: String?
    /// 需要的食材，只有这里列出的才算要求，不解析菜名
    public var stuff: [String]
    public var emojis: [String]?
    /// 简单、普通、困难；没有记录时为 nil
    public var difficulty: String?
    public var tags: [String]
    public var methods: [String]
    public var tools: [String]

    public init(
        id: Int,
        name: String,
        link: String? = nil,
        bv: String? = nil,
        stuff: [String],
        emojis: [String]? = nil,
        difficulty: String? = nil,
        tags: [String] = [],
        methods: [String] = [],
        tools: [String] = []
    ) {
        self.id = id
        self.name = name
        self.link = link
        self.bv = bv
        self.stuff = stuff
        self.emojis = emojis
        self.difficulty = difficulty
        self.tags = tags
        self.methods = methods
        self.tools = tools
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        link = try container.decodeIfPresent(String.self, forKey: .link)
        bv = try container.decodeIfPresent(String.self, forKey: .bv)
        stuff = try container.decodeIfPresent([String].self, forKey: .stuff) ?? []
        emojis = try container.decodeIfPresent([String].self, forKey: .emojis)
        difficulty = try container.decodeIfPresent(String.self, forKey: .difficulty)
        tags = try container.decodeIfPresent([String].self, forKey: .tags) ?? []
        methods = try container.decodeIfPresent([String].self, forKey: .methods) ?? []
        tools = try container.decodeIfPresent([String].self, forKey: .tools) ?? []
    }

    /// B 站视频页
    public var videoURL: URL? {
        guard let bv, !bv.isEmpty else { return nil }
        return URL(string: "https://www.bilibili.com/video/\(bv)")
    }
}

/// 食物相克规则（incompatible-foods.json）
public struct IncompatibleRule: Codable, Hashable, Sendable {
    public var foodA: String
    public var foodB: String
    public var reason: String

    public init(foodA: String, foodB: String, reason: String) {
        self.foodA = foodA
        self.foodB = foodB
        self.reason = reason
    }
}

/// 食材或厨具（food.json 的一项）
public struct StuffItem: Codable, Hashable, Sendable {
    public var name: String
    public var emoji: String
    public var image: String?
    public var alias: String?
    public var icon: String?
    public var label: String?
}

/// 食材和厨具词典（food.json）
public struct FoodCatalog: Codable, Hashable, Sendable {
    public var vegetable: [StuffItem]
    public var meat: [StuffItem]
    public var staple: [StuffItem]
    public var tools: [StuffItem]
}

/// 常备调料（pantry.json）：可选项和默认勾选项
public struct Pantry: Codable, Hashable, Sendable {
    public var options: [String]
    public var defaults: [String]
}

/// 食材冷藏大约能放几天（shelf-life.json）。冰箱里按它给默认到期日，只是参考，用户可以改
public struct ShelfLife: Codable, Hashable, Sendable {
    /// 没列出的食材（比如自己输入的）按这个天数估
    public var defaultDays: Int
    public var items: [String: Int]

    enum CodingKeys: String, CodingKey {
        case defaultDays = "default"
        case items
    }

    public init(defaultDays: Int, items: [String: Int]) {
        self.defaultDays = defaultDays
        self.items = items
    }

    public func days(for name: String) -> Int {
        items[name] ?? defaultDays
    }
}

/// 菜谱数据的元信息（recipe-meta.json）
public struct RecipeMeta: Codable, Hashable, Sendable {
    public struct Chunk: Codable, Hashable, Sendable {
        public var index: Int
        public var hash: String
        public var count: Int
        public var ids: [Int]?
    }

    /// 数据结构版本，见 CookData.supportedSchemaVersion
    public var schemaVersion: Int
    /// 数据内容的 hash，内容不变则不变；按它判断 CDN 上的数据是否更新
    public var dataVersion: String
    public var chunked: Bool
    public var total: Int
    public var chunkSize: Int?
    public var chunks: [Chunk]?
}
