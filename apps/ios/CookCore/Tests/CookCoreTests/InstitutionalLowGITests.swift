import Foundation
import XCTest
@testable import CookCore

final class InstitutionalLowGITests: XCTestCase {
    private func catalog(recipes: [Recipe]? = nil, edit: (inout [String: Any]) -> Void = { _ in }) throws -> InstitutionalLowGICatalog {
        let url = SharedFixtureTests.dataDirectory.appendingPathComponent(InstitutionalLowGICatalog.fileName)
        var document = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        edit(&document)
        return try InstitutionalLowGICatalog(data: JSONSerialization.data(withJSONObject: document), recipes: recipes ?? SharedFixtureTests.loadData().recipes)
    }

    func testReviewedEditorialRecipesKeepSourceIdentityAndSpecificPreparationRequirements() throws {
        let catalog = try catalog()
        XCTAssertTrue(catalog.isAvailable)
        XCTAssertEqual(catalog.entries.count, 10)
        XCTAssertTrue(try XCTUnwrap(catalog.entries[881]).requirements.contains("Basmati"))
        XCTAssertTrue(try XCTUnwrap(catalog.entries[887]).requirements.contains("al dente"))
        XCTAssertTrue(InstitutionalLowGICatalog.explanation.contains("未提供整道菜的实验室实测 GI 值"))
        XCTAssertEqual(catalog.chineseReferences, InstitutionalLowGICatalog.chineseReferences)
        XCTAssertEqual(catalog.chineseReferences.map(\.publisher), ["国家卫生健康委", "国家卫生健康委"])
    }

    func testOneStaleRecipeClosesTheFilterInsteadOfPromotingAnUnreviewedTag() throws {
        var recipes = try SharedFixtureTests.loadData().recipes
        let index = try XCTUnwrap(recipes.firstIndex { $0.id == 881 })
        recipes[index].tools = ["电饭煲"]
        let catalog = try catalog(recipes: recipes)
        XCTAssertEqual(catalog.entries.count, 9)
        XCTAssertFalse(catalog.isAvailable)
        XCTAssertNil(catalog.entry(for: try XCTUnwrap(recipes.first { $0.id == 887 })))
        var fake = try XCTUnwrap(recipes.first { $0.id == 333 })
        fake.tags.append(InstitutionalLowGICatalog.tag)
        XCTAssertNil(catalog.entry(for: fake))
    }

    func testUntrustedPublisherLookalikeHostAndDuplicateSourceSectionsCannotOpenTheFilter() throws {
        XCTAssertThrowsError(try catalog { $0["publisher"] = "Unknown blog" })
        XCTAssertThrowsError(try catalog { $0["claimKind"] = "measured-gi" })
        XCTAssertThrowsError(try catalog { document in
            var references = document["chineseReferences"] as! [[String: Any]]
            references[0]["url"] = "https://example.com/guide.pdf"
            document["chineseReferences"] = references
        })
        let lookalike = try catalog { document in
            var entries = document["recipes"] as! [String: [String: Any]]
            entries["881"]!["sourceURL"] = "https://glycemicindex.com.example.org/2008/05/low-gi-recipes-of-the-month-14/"
            document["recipes"] = entries
        }
        XCTAssertFalse(lookalike.isAvailable)
        XCTAssertThrowsError(try catalog { document in
            var entries = document["recipes"] as! [String: [String: Any]]
            entries["885"]!["sourceRecipeTitle"] = entries["884"]!["sourceRecipeTitle"]
            document["recipes"] = entries
        })
    }

    func testFullSeasoningsAndCompoundUnknownsNarrowTheSameRecipePool() throws {
        let data = try SharedFixtureTests.loadData()
        let catalog = try catalog()
        let pasta = try XCTUnwrap(data.recipes.first { $0.id == 887 })
        let prawns = try XCTUnwrap(data.recipes.first { $0.id == 886 })
        let fish = try XCTUnwrap(data.recipes.first { $0.id == 880 })
        XCTAssertTrue(catalog.permits(pasta, options: RecommendationOptions(stuff: []), normalizer: data.normalizer))
        XCTAssertFalse(catalog.permits(pasta, options: RecommendationOptions(stuff: [], avoid: ["牛奶"]), normalizer: data.normalizer))
        XCTAssertTrue(catalog.permits(prawns, options: RecommendationOptions(stuff: [], noSpicy: true), normalizer: data.normalizer))
        XCTAssertFalse(catalog.permits(fish, options: RecommendationOptions(stuff: [], noSpicy: true), normalizer: data.normalizer))
        // The compound fish sauce is unknown; a request to avoid even an unrelated ingredient stays conservative.
        XCTAssertFalse(catalog.permits(fish, options: RecommendationOptions(stuff: [], avoid: ["牛奶"]), normalizer: data.normalizer))
    }
}
