import Foundation

public enum CatalogCategory: String, Codable, CaseIterable, Sendable {
    case vegetable, meat, staple, tools
    public var title: String {
        switch self { case .vegetable: return "蔬菜"; case .meat: return "肉和蛋"; case .staple: return "主食"; case .tools: return "厨具" }
    }
    public var emoji: String {
        switch self { case .vegetable: return "🥬"; case .meat: return "🥩"; case .staple: return "🍚"; case .tools: return "🍳" }
    }
}

public struct CatalogEntry: Codable, Equatable, Sendable, Identifiable {
    public var category: CatalogCategory
    public var name: String
    public var id: String { "\(category == .tools ? "tool" : "stuff"):\(name)" }
    public init(category: CatalogCategory, name: String) { self.category = category; self.name = name }
}

public enum PersonalCatalogError: LocalizedError {
    case invalid, duplicate, tooLarge
    public var errorDescription: String? {
        switch self {
        case .invalid: return "无法识别食材和厨具配置。名称需为 1–30 个字符，不能包含换行或控制字符。"
        case .duplicate: return "这个名称已经存在；隐藏的内置条目可在下方恢复。"
        case .tooLarge: return "文件不能超过 256 KB，最多包含 500 个自定义条目和 500 个隐藏条目。"
        }
    }
}

public struct PersonalCatalog: Codable, Equatable, Sendable {
    public var app = "cook"
    public var kind = "personal-catalog"
    public var version = 1
    public var items: [CatalogEntry] = []
    public var hidden: [CatalogEntry] = []
    public static let fileLimit = 256 * 1024
    public init() {}

    public static func parse(_ text: String) throws -> Self {
        guard text.utf8.count <= fileLimit else { throw PersonalCatalogError.tooLarge }
        let data = Data(text.utf8)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any], Set(object.keys) == Set(["app", "kind", "version", "items", "hidden"]) else { throw PersonalCatalogError.invalid }
        for field in ["items", "hidden"] {
            guard let entries = object[field] as? [[String: Any]], entries.allSatisfy({ Set($0.keys) == Set(["category", "name"]) }) else { throw PersonalCatalogError.invalid }
        }
        let result = try JSONDecoder().decode(Self.self, from: data)
        try result.validate()
        return result
    }
    public func validate() throws {
        guard app == "cook", kind == "personal-catalog", version == 1 else { throw PersonalCatalogError.invalid }
        for entries in [items, hidden] {
            guard entries.count <= 500 else { throw PersonalCatalogError.tooLarge }
            var seen: Set<String> = []
            for entry in entries {
                guard !entry.name.isEmpty, entry.name == entry.name.trimmingCharacters(in: .whitespacesAndNewlines), entry.name.utf16.count <= 30,
                      !entry.name.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) else { throw PersonalCatalogError.invalid }
                guard seen.insert(entry.id).inserted else { throw PersonalCatalogError.duplicate }
            }
        }
    }
    public func export() throws -> String {
        try validate()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(self)
        guard data.count <= Self.fileLimit else { throw PersonalCatalogError.tooLarge }
        return String(decoding: data, as: UTF8.self)
    }
    public func merging(_ incoming: Self) throws -> Self {
        try incoming.validate()
        var next = self
        next.items += incoming.items.filter { entry in !items.contains(where: { $0.id == entry.id }) }
        next.hidden += incoming.hidden.filter { entry in !hidden.contains(where: { $0.id == entry.id }) }
        try next.validate()
        return next
    }
    public func applying(to base: FoodCatalog, includeHidden: Bool = false) -> FoodCatalog {
        func group(_ category: CatalogCategory) -> [StuffItem] {
            let originals = base.items(in: category)
            return originals.filter { item in includeHidden || !hidden.contains(CatalogEntry(category: category, name: item.name)) }
                + items.filter { $0.category == category && !originals.map(\.name).contains($0.name) }.map { StuffItem(name: $0.name, emoji: category.emoji) }
        }
        return FoodCatalog(vegetable: group(.vegetable), meat: group(.meat), staple: group(.staple), tools: group(.tools))
    }
}

public extension FoodCatalog {
    func items(in category: CatalogCategory) -> [StuffItem] {
        switch category { case .vegetable: return vegetable; case .meat: return meat; case .staple: return staple; case .tools: return tools }
    }
}
