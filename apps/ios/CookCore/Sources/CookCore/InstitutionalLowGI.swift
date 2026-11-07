import Foundation

/// Editorial recipe references, never measured GI values or a nutrition certification.
public struct InstitutionalLowGICatalog: Sendable {
    public static let fileName = "recipe-institutional-low-gi.json"
    public static let tag = "机构低 GI 食谱"
    public static let publisher = "GI News / University of Sydney"
    public static let authorityURL = "https://glycemicindex.com/2026/01/copyright-and-permission-105/"
    public static let explanation = "来源为悉尼大学 GI News 的低 GI 食谱栏目，属于机构食谱参考，未提供整道菜的实验室实测 GI 值。请按原方的食材品种、比例和做法制作；不表示无糖、低热量或适合个人疾病饮食。原方为英文外链，阅读需联网。"

    public struct Entry: Decodable, Sendable {
        public let recipeFingerprint: String
        public let sourceURL: String
        public let sourceRecipeTitle: String
        public let classificationTitle: String
        public let author: String
        public let reviewedAt: String
        public let requirements: String
        public let ingredients: [String]
        public let avoidIngredients: [String]
        public let unresolvedIngredients: [String]
        public let ingredientEvidence: String
        public let preparationEvidence: String

        fileprivate func valid(for recipe: Recipe) -> Bool {
            let date = DateFormatter()
            date.locale = Locale(identifier: "en_US_POSIX")
            date.timeZone = TimeZone(secondsFromGMT: 0)
            date.dateFormat = "yyyy-MM-dd"
            date.isLenient = false
            guard let parsedDate = date.date(from: reviewedAt), date.string(from: parsedDate) == reviewedAt,
                  recipeFingerprint == DietaryCatalog.fingerprint(recipe), recipe.link == sourceURL,
                  recipe.bv?.isEmpty != false, recipe.tags.contains(InstitutionalLowGICatalog.tag),
                  sourceURL.range(of: #"^https://glycemicindex\.com/\d{4}/\d{2}/low-gi-recipes?-of-the-month(?:-\d+)?/$"#, options: .regularExpression) != nil,
                  ["Low GI Recipe of the Month", "Low GI Recipes of the Month"].contains(classificationTitle),
                  [sourceRecipeTitle, author, requirements, ingredientEvidence, preparationEvidence].allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }),
                  !ingredients.isEmpty, Set(ingredients).count == ingredients.count,
                  !avoidIngredients.isEmpty, Set(recipe.stuff).isSubset(of: Set(avoidIngredients)) else { return false }
            return true
        }
    }

    private struct Document: Decodable {
        let schemaVersion: Int
        let policyVersion: Int
        let dataVersion: String
        let publisher: String
        let authorityURL: String
        let claimKind: String
        let recipes: [String: Entry]
    }

    public let dataVersion: String
    public let entries: [Int: Entry]
    public var isAvailable: Bool { entries.count >= 10 }

    public static func load(from directory: URL, recipes: [Recipe]) throws -> Self {
        try Self(data: Data(contentsOf: directory.appendingPathComponent(fileName)), recipes: recipes)
    }

    public init(data: Data, recipes: [Recipe]) throws {
        let document = try JSONDecoder().decode(Document.self, from: data)
        guard document.schemaVersion == 1, document.policyVersion == 1 else { throw DietaryCatalogError.unsupportedVersion }
        guard document.publisher == Self.publisher, document.authorityURL == Self.authorityURL,
              document.claimKind == "institutional-editorial",
              document.dataVersion.range(of: #"^[a-f0-9]{12}$"#, options: .regularExpression) != nil else { throw DietaryCatalogError.invalidVersion }
        let identities = document.recipes.values.map { "\($0.sourceURL)\n\($0.sourceRecipeTitle)" }
        guard Set(identities).count == identities.count else { throw DietaryCatalogError.invalidVersion }
        let byID = Dictionary(recipes.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        entries = Dictionary(uniqueKeysWithValues: document.recipes.compactMap { key, entry in
            guard let id = Int(key), String(id) == key, let recipe = byID[id], entry.valid(for: recipe) else { return nil }
            return (id, entry)
        })
        dataVersion = document.dataVersion
    }

    public func entry(for recipe: Recipe) -> Entry? {
        guard isAvailable, let entry = entries[recipe.id], entry.recipeFingerprint == DietaryCatalog.fingerprint(recipe) else { return nil }
        return entry
    }

    /// Main-ingredient matching still runs in the recommender; this adds the reviewed seasoning exclusions.
    public func permits(_ recipe: Recipe, options: RecommendationOptions, normalizer: StuffNormalizer) -> Bool {
        guard let entry = entry(for: recipe) else { return false }
        let avoid = Set(normalizer.unique((options.avoid ?? []) + (options.noSpicy == true ? ["辣椒"] : [])))
        return avoid.isEmpty || (entry.unresolvedIngredients.isEmpty && avoid.isDisjoint(with: normalizer.unique(entry.avoidIngredients)))
    }
}
