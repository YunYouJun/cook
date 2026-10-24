import Foundation

/// Version 1 of the private recipe file shared with Web. Milliseconds, never Date's default Codable epoch.
public struct PrivateRecipe: Codable, Equatable, Identifiable, Sendable {
    public var customId: String
    public var name: String
    public var stuff: [String]
    public var tools: [String]
    public var bv: String?
    public var link: String?
    public var note: String?
    public var tags: [String]?
    public var methods: [String]?
    public var difficulty: String?
    public var createdAt: Int64
    public var updatedAt: Int64
    public var copiedFrom: String?
    public var id: String { customId }

    public init(customId: String, name: String, stuff: [String], tools: [String], bv: String? = nil, link: String? = nil, note: String? = nil, createdAt: Int64, updatedAt: Int64) {
        self.customId = customId
        self.name = name
        self.stuff = stuff
        self.tools = tools
        self.bv = bv
        self.link = link
        self.note = note
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    public var sourceURL: URL? {
        if let bv, !bv.isEmpty { return URL(string: "https://www.bilibili.com/video/\(bv)") }
        guard let link, PrivateRecipeFile.isSafeURL(link) else { return nil }
        return URL(string: link)
    }

    public func hasSameContent(as other: PrivateRecipe) -> Bool {
        name == other.name && stuff == other.stuff && tools == other.tools
            && (bv ?? "") == (other.bv ?? "") && (link ?? "") == (other.link ?? "")
            && (note ?? "") == (other.note ?? "") && (tags ?? []) == (other.tags ?? [])
            && (methods ?? []) == (other.methods ?? []) && (difficulty ?? "") == (other.difficulty ?? "")
    }

    public func validated() throws -> PrivateRecipe {
        guard PrivateRecipeFile.validID(customId), !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              name.utf16.count <= 30, !stuff.isEmpty, Self.validList(stuff), Self.validList(tools),
              createdAt >= 0, updatedAt >= createdAt, updatedAt <= 9_007_199_254_740_991,
              (note?.utf16.count ?? 0) <= 20000, difficulty.map({ ["", "简单", "普通", "困难"].contains($0) }) ?? true,
              tags.map(Self.validList) ?? true, methods.map(Self.validList) ?? true,
              bv.map({ $0.isEmpty || $0.range(of: "^BV[0-9a-z]{10}$", options: [.regularExpression, .caseInsensitive]) != nil }) ?? true,
              link.map({ $0.isEmpty || PrivateRecipeFile.isSafeURL($0) }) ?? true,
              copiedFrom.map(PrivateRecipeFile.validID) ?? true
        else { throw PrivateRecipeFileError.invalid }
        return self
    }

    private static func validList(_ list: [String]) -> Bool {
        list.count <= 64 && list.allSatisfy { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.utf16.count <= 100 }
    }
}

public enum PrivateRecipeFileError: LocalizedError {
    case invalid, tooLarge, duplicateID, tooMany
    public var errorDescription: String? {
        switch self {
        case .invalid: return "无法识别这份私人菜谱备份，原有菜谱未改变。"
        case .tooLarge: return "文件超过 5 MB，原有菜谱未改变。"
        case .duplicateID: return "文件里同一个编号有不同内容，原有菜谱未改变。"
        case .tooMany: return "合并后超过 5000 道菜谱，原有菜谱未改变。"
        }
    }
}

public struct PrivateRecipeFile: Codable, Equatable, Sendable {
    public static let byteLimit = 5 * 1024 * 1024
    public static let recipeLimit = 5000
    public var app = "cook"
    public var kind = "custom-recipes"
    public var version = 1
    public var exportedAt: Int64
    public var recipes: [PrivateRecipe]

    public init(recipes: [PrivateRecipe], exportedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        self.recipes = recipes
        self.exportedAt = exportedAt
    }

    public static func validID(_ id: String) -> Bool {
        id.range(of: "^[A-Za-z0-9_:-]{1,160}$", options: .regularExpression) != nil
    }

    public static func isSafeURL(_ text: String) -> Bool {
        guard text.utf16.count <= 4096, text.rangeOfCharacter(from: .whitespacesAndNewlines.union(.controlCharacters)) == nil,
              let url = URLComponents(string: text), ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil
        else { return false }
        return true
    }

    public static func parse(_ text: String) throws -> PrivateRecipeFile {
        guard text.utf8.count <= byteLimit else { throw PrivateRecipeFileError.tooLarge }
        // Reject unknown recipe fields instead of silently dropping data from a newer exporter.
        let fields: Set<String> = ["customId", "name", "stuff", "tools", "bv", "link", "note", "tags", "methods", "difficulty", "createdAt", "updatedAt", "copiedFrom"]
        guard let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let records = object["recipes"] as? [[String: Any]],
              records.allSatisfy({ Set($0.keys).isSubset(of: fields) && !$0.values.contains(where: { $0 is NSNull }) })
        else { throw PrivateRecipeFileError.invalid }
        let file: PrivateRecipeFile
        do { file = try JSONDecoder().decode(PrivateRecipeFile.self, from: Data(text.utf8)) }
        catch { throw PrivateRecipeFileError.invalid }
        guard file.app == "cook", file.kind == "custom-recipes", file.version == 1,
              file.exportedAt >= 0, file.exportedAt <= 9_007_199_254_740_991,
              file.recipes.count <= recipeLimit else { throw PrivateRecipeFileError.invalid }
        var seen: [String: PrivateRecipe] = [:]
        var recipes: [PrivateRecipe] = []
        for item in file.recipes {
            let recipe = try item.validated()
            if let prior = seen[recipe.customId] {
                guard prior == recipe else { throw PrivateRecipeFileError.duplicateID }
            } else {
                seen[recipe.customId] = recipe
                recipes.append(recipe)
            }
        }
        return PrivateRecipeFile(recipes: recipes, exportedAt: file.exportedAt)
    }

    public func export() throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted, .withoutEscapingSlashes]
        let text = String(decoding: try encoder.encode(self), as: UTF8.self)
        _ = try Self.parse(text)
        return text
    }
}

public struct PrivateRecipeImport: Sendable {
    public var additions: [PrivateRecipe] = []
    public var conflicts: [PrivateRecipe] = []
    public var duplicates: [PrivateRecipe] = []
    private let currentCount: Int

    public init(current: [PrivateRecipe], incoming: [PrivateRecipe], deletedIDs: Set<String> = []) {
        currentCount = current.count
        for recipe in incoming {
            let previous = current.first { $0.customId == recipe.customId }
            let copied = current.contains { $0.copiedFrom == recipe.customId && $0.hasSameContent(as: recipe) }
            if copied || previous?.hasSameContent(as: recipe) == true {
                duplicates.append(recipe)
            } else if previous != nil || deletedIDs.contains(recipe.customId) {
                conflicts.append(recipe)
            } else {
                additions.append(recipe)
            }
        }
    }

    /// Returns additions only. Conflicts never replace an existing identity, even if incoming is newer.
    public func recordsToInsert(keepCopies: Bool, currentIDs: Set<String>, createID: () -> String = { "custom-" + UUID().uuidString.lowercased() }) throws -> [PrivateRecipe] {
        var records = additions
        var ids = currentIDs.union(additions.map(\.customId))
        if keepCopies {
            for recipe in conflicts {
                var copy = recipe
                copy.customId = createID()
                copy.copiedFrom = recipe.customId
                guard PrivateRecipeFile.validID(copy.customId), !ids.contains(copy.customId) else { throw PrivateRecipeFileError.invalid }
                records.append(copy)
                ids.insert(copy.customId)
            }
        }
        // Deleted identities reserve IDs, but only live recipes consume capacity.
        guard currentCount + records.count <= PrivateRecipeFile.recipeLimit else { throw PrivateRecipeFileError.tooMany }
        return records
    }
}
