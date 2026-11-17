@testable import CookCore
import Foundation
import XCTest

final class SharedCoreContractTests: XCTestCase {
    private struct RawBlock: Decodable {
        let type: String
        let text: String?
        let ordered: Bool?
        let start: Int?
        let items: [String]?
        let src: String?
        let width: Int?
        let height: Int?
        let alt: String?
    }

    private struct RecipeTextBlocksFixtures: Decodable {
        struct BlockDecodeCase: Decodable {
            let rawJson: String
            let expectedTypes: [String]
            let expectedOrderedListStart: Int
            let expectedImageCount: Int
        }

        struct UnknownSectionsCase: Decodable {
            let rawBlocks: [RecipeTextBlock]
            let expectedDifficultyNull: Bool
            let expectedQuantitiesCount: Int
            let expectedStepIds: [String]
            let expectedLastStepText: String
            let prefixParagraphText: String
        }

        struct BundledRecipe250: Decodable {
            let id: Int
            let expectedSourceFingerprint: String
            let expectedDifficulty: Int
            let expectedCalories: String
            let expectedIngredientsCount: Int
            let expectedStepCount: Int
            let expectedAmounts: [String: String]
            let expectedMissingAmountFor: String
        }

        let blockDecodeCase: BlockDecodeCase
        let unknownSectionsCase: UnknownSectionsCase
        let bundledRecipe250: BundledRecipe250
    }

    private struct RecipeAmountsScalingFixtures: Decodable {
        struct ServingsCase: Decodable {
            let servings: Int
            let eggs: String
            let oil: String
        }

        struct BundledTomato250: Decodable {
            let id: Int
            let servingsCases: [ServingsCase]
            let servings3Details: [String: String]
        }

        struct InstructionExample: Decodable {
            let id: Int
            let servings: Int
            let expression: String
            let expected: String
        }

        struct BundledAllocationCheck: Decodable {
            let id: Int
            let block: Int
            let item: Int
            let servings: Int
            let contains: [String]?
            let exactText: String?
            let expectUnresolved: Bool
        }

        struct SyntheticUsageCase: Decodable {
            let servings: Int
            let quantities: [String]
            let steps: [String]
            let expectedTexts: [String]
            let expectedUnresolvedLast: Bool
        }

        let bundledTomato250: BundledTomato250
        let bundledInstructionExamples: [InstructionExample]
        let bundledAllocationChecks: [BundledAllocationCheck]
        let syntheticUsageCases: [SyntheticUsageCase]
        let unsupportedInstructionExpressions: [String]
    }

    private struct DietaryContractsFixtures: Decodable {
        struct Sha256Vector: Decodable {
            let input: String
            let sha256: String
        }

        struct RecipeFingerprintCase: Decodable {
            let id: Int
            let expectedFingerprint: String
        }

        struct BundledDietarySummary: Decodable {
            let entryCount: Int
            let rejectedEntryCount: Int
            let availableGoals: [DietaryGoal]
            let lessAddedSugarCount: Int
            let weightAwareCount: Int
            let keywordSpinachLessAddedSugarIds: [Int]
            let noSpicyExcludedBalancedPairingIds: [Int]
            let avoidPorkExcludedLessAddedSugarIds: [Int]
            let avoidPorkExcludedBalancedPairingIds: [Int]
        }

        struct PermitsCase: Decodable {
            let id: Int
            let avoid: [String]
            let noSpicy: Bool
            let expected: Bool
        }

        struct BundledInstitutionalLowGI: Decodable {
            let entryCount: Int
            let expectedIds: [Int]
            let permitsCases: [PermitsCase]
        }

        let sha256Vectors: [Sha256Vector]
        let recipeFingerprintCases: [RecipeFingerprintCase]
        let bundledDietarySummary: BundledDietarySummary
        let bundledInstitutionalLowGI: BundledInstitutionalLowGI
    }

    func testRecipeTextBlocksFixtureMatches() throws {
        let fixtures = try SharedFixtureTests.fixture(RecipeTextBlocksFixtures.self, "recipe-text-blocks.json")
        let decoded = try JSONDecoder().decode(RecipeText.self, from: Data(fixtures.blockDecodeCase.rawJson.utf8))
        let types: [String] = decoded.blocks.map { block in
            switch block {
            case .heading: return "heading"
            case .list: return "list"
            case .image: return "image"
            case .paragraph: return "paragraph"
            }
        }
        XCTAssertEqual(types, fixtures.blockDecodeCase.expectedTypes)
        if case let .list(_, start, _) = decoded.blocks[1] {
            XCTAssertEqual(start, fixtures.blockDecodeCase.expectedOrderedListStart)
        } else {
            XCTFail("Expected list block at index 1")
        }
        XCTAssertEqual(decoded.imageCount, fixtures.blockDecodeCase.expectedImageCount)

        let unknownText = RecipeText(blocks: fixtures.unknownSectionsCase.rawBlocks)
        let unknownPres = RecipePresentation(text: unknownText)
        if fixtures.unknownSectionsCase.expectedDifficultyNull {
            XCTAssertNil(unknownPres.difficulty)
        }
        XCTAssertEqual(unknownPres.quantities.count, fixtures.unknownSectionsCase.expectedQuantitiesCount)
        XCTAssertEqual(unknownPres.steps.map(\.id), fixtures.unknownSectionsCase.expectedStepIds)
        XCTAssertEqual(unknownPres.steps.last?.text, fixtures.unknownSectionsCase.expectedLastStepText)
        let prefixed = RecipePresentation(text: RecipeText(blocks: [.paragraph(fixtures.unknownSectionsCase.prefixParagraphText)] + unknownText.blocks))
        XCTAssertEqual(unknownPres.steps.map(\.id), prefixed.steps.map(\.id))

        let library = try XCTUnwrap(RecipeTextLibrary.load(from: SharedFixtureTests.dataDirectory))
        let recipe250 = try XCTUnwrap(library.text(for: fixtures.bundledRecipe250.id))
        XCTAssertEqual(RecipeAmountAnnotations.fingerprint(recipe250), fixtures.bundledRecipe250.expectedSourceFingerprint)
        let pres250 = RecipePresentation(text: recipe250)
        XCTAssertEqual(pres250.difficulty, fixtures.bundledRecipe250.expectedDifficulty)
        XCTAssertEqual(pres250.calories, fixtures.bundledRecipe250.expectedCalories)
        XCTAssertEqual(pres250.ingredients.count, fixtures.bundledRecipe250.expectedIngredientsCount)
        XCTAssertEqual(pres250.steps.count, fixtures.bundledRecipe250.expectedStepCount)
        for (ingredient, expectedAmount) in fixtures.bundledRecipe250.expectedAmounts {
            XCTAssertEqual(pres250.amount(for: ingredient), expectedAmount)
        }
        XCTAssertNil(pres250.amount(for: fixtures.bundledRecipe250.expectedMissingAmountFor))
    }

    func testRecipeAmountsScalingFixtureMatches() throws {
        let fixtures = try SharedFixtureTests.fixture(RecipeAmountsScalingFixtures.self, "recipe-amounts-scaling.json")
        let library = try XCTUnwrap(RecipeTextLibrary.load(from: SharedFixtureTests.dataDirectory))
        let pres250 = RecipePresentation(text: try XCTUnwrap(library.text(for: fixtures.bundledTomato250.id)))

        for sc in fixtures.bundledTomato250.servingsCases {
            let amounts = RecipeAmounts(rows: pres250.allQuantities, servings: sc.servings)
            XCTAssertTrue(amounts.supportsServings)
            XCTAssertEqual(amounts.amount(for: "鸡蛋")?.text, sc.eggs)
            XCTAssertEqual(amounts.amount(for: "食用油")?.text, sc.oil)
        }
        let three = RecipeAmounts(rows: pres250.allQuantities, servings: 3)
        for (key, expected) in fixtures.bundledTomato250.servings3Details {
            if key == "eggNote" {
                XCTAssertEqual(three.amount(for: "鸡蛋")?.note, expected)
            } else {
                XCTAssertEqual(three.amount(for: key)?.text, expected)
            }
        }

        for ex in fixtures.bundledInstructionExamples {
            let pres = RecipePresentation(text: try XCTUnwrap(library.text(for: ex.id)))
            let source = try XCTUnwrap(pres.steps.first { $0.text.contains(ex.expression) }).text
            let result = RecipeAmounts(rows: pres.allQuantities, servings: ex.servings).instruction(source)
            XCTAssertEqual(result.text, source.replacingOccurrences(of: ex.expression, with: ex.expected))
            XCTAssertTrue(result.hasAdjustedAmounts)
            XCTAssertFalse(result.hasUnresolvedFormula)
        }

        for check in fixtures.bundledAllocationChecks {
            let pres = RecipePresentation(text: try XCTUnwrap(library.text(for: check.id)))
            let amounts = RecipeAmounts(rows: pres.allQuantities, servings: check.servings)
            let plan = RecipeStepUsagePlan(steps: pres.steps, amounts: amounts, annotations: pres.amountAnnotations)
            let step = try XCTUnwrap(pres.steps.first { $0.sourceBlock == check.block && $0.sourceItem == check.item })
            let result = try XCTUnwrap(plan.instruction(for: step))
            if let exact = check.exactText {
                XCTAssertEqual(result.text, exact)
            }
            for fragment in check.contains ?? [] {
                XCTAssertTrue(result.text.contains(fragment), result.text)
            }
            XCTAssertEqual(
                result.hasUnresolvedFormula,
                check.expectUnresolved,
                "Mismatch expectUnresolved for id=\(check.id) block=\(check.block) item=\(check.item): '\(result.text)'"
            )
        }

        for syn in fixtures.syntheticUsageCases {
            let pres = RecipePresentation(text: RecipeText(blocks: [
                .heading("计算"), .list(ordered: false, start: 1, items: syn.quantities),
                .heading("操作"), .list(ordered: true, start: 1, items: syn.steps),
            ]))
            let plan = RecipeStepUsagePlan(steps: pres.steps, amounts: RecipeAmounts(rows: pres.allQuantities, servings: syn.servings))
            let actual = pres.steps.compactMap { plan.instruction(for: $0) }
            XCTAssertEqual(actual.map(\.text), syn.expectedTexts)
            XCTAssertEqual(actual.last?.hasUnresolvedFormula, syn.expectedUnresolvedLast)
        }

        let empty = RecipeAmounts(rows: [], servings: 3)
        for expr in fixtures.unsupportedInstructionExpressions {
            let source = "加入 \(expr) 的原料。"
            let result = empty.instruction(source)
            XCTAssertEqual(result.text, source)
            XCTAssertFalse(result.hasAdjustedAmounts)
            XCTAssertTrue(result.hasUnresolvedFormula)
        }
    }

    func testDietaryContractsFixtureMatches() throws {
        let fixtures = try SharedFixtureTests.fixture(DietaryContractsFixtures.self, "dietary-contracts.json")
        for vec in fixtures.sha256Vectors {
            XCTAssertEqual(DietaryCatalog.amountsFingerprint(Data(vec.input.utf8)), vec.sha256)
        }

        let data = try SharedFixtureTests.loadData()
        for fpCase in fixtures.recipeFingerprintCases {
            let recipe = try XCTUnwrap(data.recipes.first { $0.id == fpCase.id })
            XCTAssertEqual(DietaryCatalog.fingerprint(recipe), fpCase.expectedFingerprint)
        }

        let dietary = try DietaryCatalog.load(from: SharedFixtureTests.dataDirectory, recipes: data.recipes)
        let summary = fixtures.bundledDietarySummary
        XCTAssertEqual(dietary.entries.count, summary.entryCount)
        XCTAssertEqual(dietary.rejectedEntryCount, summary.rejectedEntryCount)
        XCTAssertEqual(dietary.availableGoals, summary.availableGoals)
        XCTAssertEqual(dietary.count(for: .lessAddedSugar), summary.lessAddedSugarCount)
        XCTAssertEqual(dietary.count(for: .weightAware), summary.weightAwareCount)

        let recommender = Recommender(recipes: data.recipes, normalizer: data.normalizer)
        XCTAssertEqual(
            dietary.candidates(RecommendationOptions(stuff: [], keyword: "菠菜"), goal: .lessAddedSugar, using: recommender).map(\.id),
            summary.keywordSpinachLessAddedSugarIds
        )
        let noSpicyIDs = Set(dietary.candidates(RecommendationOptions(stuff: [], noSpicy: true), goal: .balancedPairing, using: recommender).map(\.id))
        for excluded in summary.noSpicyExcludedBalancedPairingIds {
            XCTAssertFalse(noSpicyIDs.contains(excluded))
        }
        let avoidPorkSugar = Set(dietary.candidates(RecommendationOptions(stuff: [], avoid: ["猪肉"]), goal: .lessAddedSugar, using: recommender).map(\.id))
        for excluded in summary.avoidPorkExcludedLessAddedSugarIds {
            XCTAssertFalse(avoidPorkSugar.contains(excluded))
        }
        let avoidPorkBalanced = Set(dietary.candidates(RecommendationOptions(stuff: [], avoid: ["猪肉"]), goal: .balancedPairing, using: recommender).map(\.id))
        for excluded in summary.avoidPorkExcludedBalancedPairingIds {
            XCTAssertFalse(avoidPorkBalanced.contains(excluded))
        }

        let lowGI = try InstitutionalLowGICatalog.load(from: SharedFixtureTests.dataDirectory, recipes: data.recipes)
        XCTAssertTrue(lowGI.isAvailable)
        XCTAssertEqual(lowGI.entries.count, fixtures.bundledInstitutionalLowGI.entryCount)
        XCTAssertEqual(lowGI.entries.keys.sorted(), fixtures.bundledInstitutionalLowGI.expectedIds)
        for pc in fixtures.bundledInstitutionalLowGI.permitsCases {
            let recipe = try XCTUnwrap(data.recipes.first { $0.id == pc.id })
            XCTAssertEqual(lowGI.permits(recipe, options: RecommendationOptions(stuff: [], avoid: pc.avoid, noSpicy: pc.noSpicy), normalizer: data.normalizer), pc.expected)
        }
    }
}
