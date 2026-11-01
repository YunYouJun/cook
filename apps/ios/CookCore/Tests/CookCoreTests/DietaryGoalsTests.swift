@testable import CookCore
import XCTest

final class DietaryGoalsTests: XCTestCase {
    private var directory: URL { SharedFixtureTests.dataDirectory }

    private func catalog(mutate: ((inout [String: Any]) -> Void)? = nil, changedTextID: Int? = nil, changedRecipeID: Int? = nil) throws -> DietaryCatalog {
        var recipes = try SharedFixtureTests.loadData().recipes
        if let id = changedRecipeID, let index = recipes.firstIndex(where: { $0.id == id }) { recipes[index].link = "https://example.com/changed" }
        let library = try XCTUnwrap(RecipeTextLibrary.load(from: directory))
        var document = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: directory.appendingPathComponent(DietaryCatalog.fileName))) as? [String: Any])
        mutate?(&document)
        let amounts = try Data(contentsOf: directory.appendingPathComponent(RecipeAmountAnnotations.fileName))
        return try DietaryCatalog(data: JSONSerialization.data(withJSONObject: document), recipes: recipes, amountsFingerprint: DietaryCatalog.amountsFingerprint(amounts)) { id in
            guard var source = library.text(for: id) else { return nil }
            if id == changedTextID { source.blocks.append(.paragraph("Changed source")) }
            return source
        }
    }

    func testBundledCatalogValidatesEveryEntryAndOpensWeightGoalAtTen() throws {
        let catalog = try catalog()
        XCTAssertEqual(catalog.entries.count, 36)
        XCTAssertEqual(catalog.rejectedEntryCount, 0)
        XCTAssertEqual(catalog.availableGoals, [.weightAware, .lessAddedSugar, .balancedPairing])
        XCTAssertEqual(catalog.count(for: .lessAddedSugar), 12)
        XCTAssertEqual(catalog.count(for: .weightAware), 10)
        XCTAssertEqual(catalog.entries[800]?.portionOil?.perServingUpper, 0)
    }

    func testSourceAndRecipeDriftExcludeOnlyMatchingAnnotations() throws {
        let changedText = try catalog(changedTextID: 333)
        XCTAssertNil(changedText.entries[333])
        XCTAssertEqual(changedText.rejectedEntryCount, 1)
        XCTAssertEqual(changedText.availableGoals.count, 2)
        XCTAssertFalse(changedText.availableGoals.contains(.weightAware))
        XCTAssertEqual(changedText.count(for: .weightAware), 9)
        XCTAssertNil(try catalog(changedRecipeID: 333).entries[333])
    }

    func testVersionAndAmountMismatchFailClosed() throws {
        XCTAssertThrowsError(try catalog { $0["schemaVersion"] = 99 })
        XCTAssertThrowsError(try catalog { $0["policyVersion"] = 99 })
        XCTAssertThrowsError(try catalog { $0["schemaVersion"] = 1 })
        XCTAssertThrowsError(try catalog { $0["amountsFingerprint"] = String(repeating: "0", count: 64) })
    }

    func testAmountDigestMatchesStandardSHA256Vectors() {
        XCTAssertEqual(DietaryCatalog.amountsFingerprint(Data()), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(DietaryCatalog.amountsFingerprint(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testStaleEntriesCannotBeUsedToMeetCoverageThreshold() throws {
        let catalog = try catalog { document in
            var entries = document["recipes"] as! [String: Any]
            for key in ["333", "630", "677", "701", "736", "756", "799"] { entries.removeValue(forKey: key) }
            document["recipes"] = entries
        }
        XCTAssertEqual(catalog.entries.count, 29)
        XCTAssertTrue(catalog.availableGoals.isEmpty)
    }

    func testGoalBrowsingSupportsKeywordAndExcludesUnknownOrAddedSugar() throws {
        let data = try SharedFixtureTests.loadData()
        let recommender = Recommender(recipes: data.recipes, normalizer: data.normalizer)
        let catalog = try catalog()
        let options = RecommendationOptions(stuff: [])
        let ids = catalog.candidates(options, goal: .lessAddedSugar, using: recommender).map(\.id)
        XCTAssertEqual(ids.count, 12)
        XCTAssertTrue(ids.contains(333))
        XCTAssertFalse(ids.contains(849), "Optional sugar cannot silently disappear from the original preparation")
        XCTAssertFalse(ids.contains(789), "Unverified sauce is unknown, rather than zero sugar")
        XCTAssertEqual(catalog.candidates(options, goal: .weightAware, using: recommender).count, 10)
        let keyword = RecommendationOptions(stuff: [], keyword: "菠菜")
        XCTAssertEqual(catalog.candidates(keyword, goal: .lessAddedSugar, using: recommender).map(\.id), [630])
    }

    func testFullIngredientsAndUnverifiedCompoundSeasoningsRespectAvoidPreferences() throws {
        let data = try SharedFixtureTests.loadData()
        let recommender = Recommender(recipes: data.recipes, normalizer: data.normalizer)
        let catalog = try catalog()
        let options = RecommendationOptions(stuff: [], avoid: ["猪肉"])
        XCTAssertFalse(catalog.candidates(options, goal: .lessAddedSugar, using: recommender).contains { $0.id == 689 }, "Pork fat is absent from the main-ingredient summary but present in the reviewed source")
        XCTAssertFalse(catalog.candidates(options, goal: .balancedPairing, using: recommender).contains { $0.id == 789 }, "Unverified compound sauce cannot be assumed to respect an avoidance")
    }

    func testIngredientMatchingPreservesFeasibilityAndRequiresReviewedIdentity() throws {
        let data = try SharedFixtureTests.loadData()
        let recommender = Recommender(recipes: data.recipes, normalizer: data.normalizer)
        let catalog = try catalog()
        let options = RecommendationOptions(stuff: ["鸡蛋"], pantry: ["油", "盐"])
        let matches = catalog.recommend(options, goal: .lessAddedSugar, using: recommender)
        let baseline = Set(recommender.recommend(options).map(\.recipe.id))
        XCTAssertFalse(matches.isEmpty)
        XCTAssertTrue(Set(matches.map(\.recipe.id)).isSubset(of: baseline))
        XCTAssertEqual(matches.map(\.missingStuff.count), matches.map(\.missingStuff.count).sorted())
        var fake = try XCTUnwrap(data.recipes.first { $0.id == 333 })
        fake.name = "A private recipe with a colliding ID"
        XCTAssertNil(catalog.entry(for: fake, goal: .lessAddedSugar))
    }

    func testNoSpicyUsesReviewedIngredientsWhenLegacyTagsAreMissing() throws {
        let data = try SharedFixtureTests.loadData()
        let recommender = Recommender(recipes: data.recipes, normalizer: data.normalizer)
        let catalog = try catalog()
        let plain = RecommendationOptions(stuff: [])
        XCTAssertTrue(catalog.candidates(plain, goal: .balancedPairing, using: recommender).contains { $0.id == 693 })
        let noSpicy = RecommendationOptions(stuff: [], noSpicy: true)
        let candidates = catalog.candidates(noSpicy, goal: .balancedPairing, using: recommender)
        XCTAssertFalse(candidates.contains { [670, 693, 777, 788, 861].contains($0.id) })
        XCTAssertTrue(candidates.contains { $0.id == 333 })
        let matching = RecommendationOptions(stuff: ["鸡蛋"], noSpicy: true)
        XCTAssertFalse(catalog.recommend(matching, goal: .balancedPairing, using: recommender).contains { $0.recipe.id == 693 })
    }

    func testAvoidPreferencesIncludeGarlicMushroomAndOptionalVegetableAliases() throws {
        let data = try SharedFixtureTests.loadData()
        let recommender = Recommender(recipes: data.recipes, normalizer: data.normalizer)
        let catalog = try catalog()
        for (name, excludedID) in [("蒜", 677), ("菌菇", 829), ("菠菜", 851), ("黄油", 829)] {
            let options = RecommendationOptions(stuff: [], avoid: [name])
            XCTAssertFalse(catalog.candidates(options, goal: .lessAddedSugar, using: recommender).contains { $0.id == excludedID }, "\(name) must also match preparation-only or optional ingredients")
        }
    }

    func testPairingHintsAndOilRangesDescribeSourceFacts() throws {
        let catalog = try catalog()
        XCTAssertEqual(catalog.entries[333]?.foodGroupSummary, "蛋白质来源、蔬菜")
        XCTAssertEqual(catalog.entries[333]?.pairingHint, "这一餐还可以搭配主食。")
        let oil = try JSONDecoder().decode(DietaryCatalog.PortionOil.self, from: Data(#"{"referenceServings":2,"batchOilGrams":{"lower":10,"upper":15},"servingEvidence":[],"oilEvidence":[]}"#.utf8))
        XCTAssertEqual(oil.perServingLower, 5)
        XCTAssertEqual(oil.perServingUpper, 7.5)
        XCTAssertEqual(oil.unit, .grams)
        XCTAssertTrue(oil.label.hasSuffix(" g"))
        let volume = try XCTUnwrap(catalog.entries[333]?.portionOil)
        XCTAssertEqual(volume.unit, .milliliters)
        XCTAssertEqual(volume.perServingLower, 5)
        XCTAssertEqual(volume.perServingUpper, 10)
        XCTAssertTrue(volume.label.hasSuffix(" ml"))
        XCTAssertTrue(volume.label.hasPrefix("每人添加油"))
        XCTAssertEqual(catalog.entries[630]?.portionOil?.perServingUpper, 15)
        XCTAssertEqual(catalog.entries[814]?.portionOil?.perServingUpper, 7.5)
    }

    func testMixedOilUnitsAreRejectedAndNeverComparedAsNutritionScores() throws {
        let invalid = #"{"referenceServings":2,"batchOilGrams":{"lower":10,"upper":15},"batchOilMilliliters":{"lower":10,"upper":15},"servingEvidence":[],"oilEvidence":[]}"#
        XCTAssertThrowsError(try JSONDecoder().decode(DietaryCatalog.PortionOil.self, from: Data(invalid.utf8)))
        let data = try SharedFixtureTests.loadData()
        let recommender = Recommender(recipes: data.recipes, normalizer: data.normalizer)
        let candidates = try catalog().candidates(RecommendationOptions(stuff: []), goal: .weightAware, using: recommender)
        let scores = candidates.map { $0.score ?? Recommender.defaultScore }
        XCTAssertEqual(scores, scores.sorted(by: >))
    }

    func testLegacyGramOnlyCatalogStillLoadsWithoutTreatingVolumeAsGrams() throws {
        let legacy = try catalog { document in
            document["schemaVersion"] = 1
            document["policyVersion"] = 1
            document["enabledGoals"] = ["less-added-sugar", "balanced-pairing"]
            var entries = document["recipes"] as! [String: [String: Any]]
            for key in ["333", "630", "677", "701", "736", "756", "799", "814"] {
                entries[key]?.removeValue(forKey: "portionOil")
                var assessments = entries[key]!["assessments"] as! [String: [String: Any]]
                assessments["weight-aware"]?["status"] = "unknown"
                assessments["weight-aware"]?["reason"] = "unverified-portion-oil"
                entries[key]?["assessments"] = assessments
            }
            document["recipes"] = entries
        }
        XCTAssertEqual(legacy.rejectedEntryCount, 0)
        XCTAssertEqual(legacy.availableGoals, [.lessAddedSugar, .balancedPairing])
        XCTAssertEqual(legacy.count(for: .weightAware), 2)
        XCTAssertEqual(legacy.entries[800]?.portionOil?.unit, .grams)
        XCTAssertEqual(legacy.entries[800]?.portionOil?.perServingUpper, 0)
    }
}
