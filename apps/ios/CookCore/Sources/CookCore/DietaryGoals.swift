#if os(Linux)
    import Crypto
#else
    import CryptoKit
#endif
import Foundation

/// Voluntary cooking preferences; these do not prescribe a medical diet.
public enum DietaryGoal: String, CaseIterable, Codable, Hashable, Sendable, Identifiable {
    case weightAware = "weight-aware"
    case lessAddedSugar = "less-added-sugar"
    case balancedPairing = "balanced-pairing"

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .weightAware: return "控制体重"
        case .lessAddedSugar: return "减少添加糖"
        case .balancedPairing: return "均衡搭配"
        }
    }

    public var subtitle: String {
        switch self {
        case .weightAware: return "查看份量、添加油用量参考"
        case .lessAddedSugar: return "寻找做法中未额外添加糖的菜"
        case .balancedPairing: return "了解这道菜提供什么，还能搭配什么"
        }
    }

    public var explanation: String {
        switch self {
        case .weightAware: return "按原文核对份量和添加油，不代表低热量或低脂，也不计算每日能量。"
        case .lessAddedSugar: return "核对原料、调料及步骤中的添加糖。不表示总糖为零、低碳水或适合血糖管理。"
        case .balancedPairing: return "按做法中的食物类别给出搭配提示。单道菜不等于一餐营养齐全，仍需结合份量和整餐安排。"
        }
    }

    public var symbol: String {
        switch self {
        case .weightAware: return "scalemass"
        case .lessAddedSugar: return "cube"
        case .balancedPairing: return "fork.knife"
        }
    }
}

/// Reviewed food roles support pairing hints, rather than a nutrition score.
public enum DietaryFoodGroup: String, Codable, Hashable, Sendable, CaseIterable {
    case staple, protein, vegetables

    public var title: String {
        switch self {
        case .staple: return "主食"
        case .protein: return "蛋白质来源"
        case .vegetables: return "蔬菜"
        }
    }
}

public enum DietaryCatalogError: Error {
    case missingData, unsupportedVersion, invalidVersion
}

/// Optional annotations bind to the exact bundled recipe and text; stale entries fail closed.
public struct DietaryCatalog: Sendable {
    public enum Status: String, Decodable, Sendable { case pass, fail, unknown }

    public struct Assessment: Decodable, Sendable {
        public let status: Status
        public let reason: String
        public let evidence: [RecipeAmountAnnotations.Anchor]
    }

    public struct OilRange: Decodable, Sendable {
        public let lower: Double
        public let upper: Double
    }

    public struct PortionOil: Decodable, Sendable {
        public let referenceServings: Int
        public let batchOilGrams: OilRange
        public let servingEvidence: [RecipeAmountAnnotations.Anchor]
        public let oilEvidence: [RecipeAmountAnnotations.Anchor]

        public var perServingLower: Double { batchOilGrams.lower / Double(referenceServings) }
        public var perServingUpper: Double { batchOilGrams.upper / Double(referenceServings) }

        public var label: String {
            let lower = perServingLower.formatted(.number.precision(.fractionLength(0...2)))
            let upper = perServingUpper.formatted(.number.precision(.fractionLength(0...2)))
            return "每份添加油 \(lower == upper ? lower : "\(lower)–\(upper)") g"
        }
    }

    public struct Entry: Decodable, Sendable {
        public let recipeFingerprint: String
        public let sourceLink: String
        public let sourceFingerprint: String
        public let reviewedAt: String
        public let ingredients: [String]
        public let avoidIngredients: [String]
        public let unresolvedIngredients: [String]
        public let ingredientEvidence: [RecipeAmountAnnotations.Anchor]
        public let foodGroups: [DietaryFoodGroup]
        public let assessments: [String: Assessment]
        public let portionOil: PortionOil?

        public func assessment(for goal: DietaryGoal) -> Assessment? { assessments[goal.rawValue] }

        public var foodGroupSummary: String {
            DietaryFoodGroup.allCases.filter(foodGroups.contains).map(\.title).joined(separator: "、")
        }

        public var pairingHint: String {
            let missing = DietaryFoodGroup.allCases.filter { !foodGroups.contains($0) }.map(\.title)
            return missing.isEmpty
                ? "做法包含三类食物，可结合实际份量安排这一餐。"
                : "这一餐还可以搭配\(missing.joined(separator: "、"))。"
        }

        public func reason(for goal: DietaryGoal) -> String {
            switch goal {
            case .weightAware: return portionOil?.label ?? "份量、用油信息待核对"
            case .lessAddedSugar: return "已核对做法中的原料、调料和步骤，未额外添加糖。"
            case .balancedPairing: return "提供\(foodGroupSummary)。\(pairingHint)"
            }
        }

        fileprivate func valid(for recipe: Recipe, text: RecipeText) -> Bool {
            guard recipe.id >= 0, recipeFingerprint == DietaryCatalog.fingerprint(recipe),
                  sourceLink == recipe.link, URL(string: sourceLink)?.scheme == "https",
                  sourceFingerprint == RecipeAmountAnnotations.fingerprint(text),
                  reviewedAt.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil,
                  !ingredients.isEmpty, Set(ingredients).count == ingredients.count,
                  !ingredientEvidence.isEmpty, ingredientEvidence.allSatisfy({ $0.matches(text) }),
                  ingredients.allSatisfy({ name in !name.isEmpty && ingredientEvidence.contains { $0.text.contains(name) } }),
                  Set(ingredients).isSubset(of: Set(avoidIngredients)),
                  Set(unresolvedIngredients).isSubset(of: Set(ingredients)),
                  !foodGroups.isEmpty, Set(foodGroups).count == foodGroups.count,
                  Set(assessments.keys) == Set(DietaryGoal.allCases.map(\.rawValue)),
                  assessments.values.allSatisfy({ !$0.evidence.isEmpty && $0.evidence.allSatisfy { $0.matches(text) } }) else { return false }

            for goal in DietaryGoal.allCases {
                guard let assessment = assessment(for: goal) else { return false }
                switch (goal, assessment.status) {
                case (.lessAddedSugar, .pass):
                    guard assessment.reason == "no-added-sugar", unresolvedIngredients.isEmpty,
                          !ingredients.contains(where: { ["糖", "白糖", "冰糖", "砂糖", "糖浆", "蜂蜜"].contains($0) }) else { return false }
                case (.balancedPairing, .pass):
                    guard assessment.reason == "food-group-pairing" else { return false }
                case (.weightAware, .pass):
                    guard assessment.reason == "portion-oil-reference", let oil = portionOil,
                          (1...20).contains(oil.referenceServings),
                          oil.batchOilGrams.lower.isFinite, oil.batchOilGrams.upper.isFinite,
                          oil.batchOilGrams.lower >= 0, oil.batchOilGrams.upper >= oil.batchOilGrams.lower,
                          !oil.servingEvidence.isEmpty, !oil.oilEvidence.isEmpty,
                          (oil.servingEvidence + oil.oilEvidence).allSatisfy({ $0.matches(text) }) else { return false }
                default:
                    guard ["added-sugar", "unverified-seasoning", "unverified-portion-oil"].contains(assessment.reason) else { return false }
                }
            }
            return true
        }
    }

    private struct Document: Decodable {
        let schemaVersion: Int
        let policyVersion: Int
        let dataVersion: String
        let amountsFingerprint: String
        let enabledGoals: [DietaryGoal]
        let recipes: [String: Entry]
    }

    public static let fileName = "recipe-dietary.json"
    public let dataVersion: String
    public let entries: [Int: Entry]
    public let availableGoals: [DietaryGoal]
    public let rejectedEntryCount: Int
    private let texts: [Int: RecipeText]

    public static func load(from directory: URL, recipes: [Recipe]) throws -> Self {
        let url = directory.appendingPathComponent(fileName)
        guard FileManager.default.fileExists(atPath: url.path),
              let library = try RecipeTextLibrary.load(from: directory) else { throw DietaryCatalogError.missingData }
        let amounts = try Data(contentsOf: directory.appendingPathComponent(RecipeAmountAnnotations.fileName))
        return try Self(data: Data(contentsOf: url), recipes: recipes, amountsFingerprint: amountsFingerprint(amounts), textForRecipe: library.text(for:))
    }

    /// Validate once against the immutable source snapshot, before deriving any candidates.
    public init(data: Data, recipes: [Recipe], amountsFingerprint: String, textForRecipe: (Int) -> RecipeText?) throws {
        let document = try JSONDecoder().decode(Document.self, from: data)
        guard document.schemaVersion == 1, document.policyVersion == 1 else { throw DietaryCatalogError.unsupportedVersion }
        guard document.dataVersion.range(of: #"^[a-f0-9]{12}$"#, options: .regularExpression) != nil,
              Set(document.enabledGoals).count == document.enabledGoals.count,
              document.amountsFingerprint == amountsFingerprint else { throw DietaryCatalogError.invalidVersion }
        let byID = Dictionary(recipes.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var entries: [Int: Entry] = [:]
        var texts: [Int: RecipeText] = [:]
        for (key, entry) in document.recipes {
            guard let id = Int(key), String(id) == key, let recipe = byID[id],
                  Set(recipe.stuff).count < Recommender.catchAllMinStuff,
                  let text = textForRecipe(id), entry.valid(for: recipe, text: text) else { continue }
            entries[id] = entry
            texts[id] = text
        }
        let goals = document.enabledGoals.filter { goal in
            entries.values.filter { $0.assessment(for: goal)?.status == .pass }.count >= 10
        }
        let covered = entries.values.filter { entry in goals.contains { entry.assessment(for: $0)?.status == .pass } }.count
        availableGoals = covered >= 30 ? goals : []
        dataVersion = document.dataVersion
        self.entries = entries
        self.texts = texts
        rejectedEntryCount = document.recipes.count - entries.count
    }

    public func count(for goal: DietaryGoal) -> Int {
        entries.values.filter { $0.assessment(for: goal)?.status == .pass }.count
    }

    public func text(for recipeID: Int) -> RecipeText? { texts[recipeID] }

    public func entry(for recipe: Recipe, goal: DietaryGoal) -> Entry? {
        guard availableGoals.contains(goal), let entry = entries[recipe.id],
              entry.recipeFingerprint == Self.fingerprint(recipe), entry.assessment(for: goal)?.status == .pass else { return nil }
        return entry
    }

    /// Existing match rules retain their priority; the goal only narrows the eligible pool.
    public func recommend(_ options: RecommendationOptions, goal: DietaryGoal, using recommender: Recommender) -> [RecipeMatch] {
        recommender.recommend(options).filter { eligible($0.recipe, goal: goal, options: options, normalizer: recommender.normalizer) }
            .sorted { a, b in
                if a.missingStuff.count != b.missingStuff.count { return a.missingStuff.count < b.missingStuff.count }
                if a.matchedStuff.count != b.matchedStuff.count { return a.matchedStuff.count > b.matchedStuff.count }
                return precedes(a.recipe, b.recipe, goal: goal, aIndex: a.sourceIndex, bIndex: b.sourceIndex)
            }
    }

    /// Browsing a goal does not imply owning its ingredients; ingredient modes apply after selection.
    public func candidates(_ options: RecommendationOptions, goal: DietaryGoal, using recommender: Recommender) -> [Recipe] {
        let keyword = (options.keyword ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let candidates = recommender.candidates(options).filter {
            (keyword.isEmpty || $0.name.contains(keyword)) && eligible($0, goal: goal, options: options, normalizer: recommender.normalizer)
        }
        return candidates.enumerated().sorted {
            precedes($0.element, $1.element, goal: goal, aIndex: $0.offset, bIndex: $1.offset)
        }.map(\.element)
    }

    private func eligible(_ recipe: Recipe, goal: DietaryGoal, options: RecommendationOptions, normalizer: StuffNormalizer) -> Bool {
        guard let entry = entry(for: recipe, goal: goal) else { return false }
        // Legacy tags omit some preparation-only chili; use the reviewed ingredients as well.
        let avoided = Set(normalizer.unique((options.avoid ?? []) + (options.noSpicy == true ? ["辣椒"] : [])))
        guard !avoided.isEmpty else { return true }
        return entry.unresolvedIngredients.isEmpty && avoided.isDisjoint(with: normalizer.unique(entry.avoidIngredients))
    }

    private func precedes(_ a: Recipe, _ b: Recipe, goal: DietaryGoal, aIndex: Int, bIndex: Int) -> Bool {
        if goal == .weightAware, let first = entries[a.id]?.portionOil, let second = entries[b.id]?.portionOil {
            if first.perServingUpper != second.perServingUpper { return first.perServingUpper < second.perServingUpper }
            if first.perServingLower != second.perServingLower { return first.perServingLower < second.perServingLower }
        }
        let firstScore = a.score ?? Recommender.defaultScore, secondScore = b.score ?? Recommender.defaultScore
        return firstScore == secondScore ? aIndex < bIndex : firstScore > secondScore
    }

    /// Match the build-time UTF-8, NUL-separated FNV-1a fingerprint, excluding display scores.
    public static func fingerprint(_ recipe: Recipe) -> String {
        var tokens = [String(recipe.id), recipe.name, recipe.link ?? "", recipe.bv ?? "", recipe.difficulty ?? ""]
        for values in [recipe.stuff, recipe.tools, recipe.methods, recipe.tags] { tokens += [String(values.count)] + values }
        var value: UInt64 = 14695981039346656037
        for byte in tokens.joined(separator: "\0").utf8 { value = (value ^ UInt64(byte)) &* 1099511628211 }
        return String(format: "%016llx", value)
    }

    static func amountsFingerprint(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
