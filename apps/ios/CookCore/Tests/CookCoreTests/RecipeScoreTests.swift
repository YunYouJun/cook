import CookCore
import XCTest

/// 质量分只决定同组先后，和 packages/cook/src/utils/score.test.ts 的同名用例一致
final class RecipeScoreTests: XCTestCase {
    func testScoreOnlyBreaksTiesInsideTheSameGroup() {
        let recipes = [
            Recipe(id: 0, name: "缺一样但分高", stuff: ["土豆", "牛肉"], score: 100),
            Recipe(id: 1, name: "低分", stuff: ["土豆"], score: 10),
            Recipe(id: 2, name: "没分", stuff: ["土豆"]),
            Recipe(id: 3, name: "高分", stuff: ["土豆"], score: 90),
        ]
        let recommender = Recommender(recipes: recipes, normalizer: StuffNormalizer(aliases: [:]))
        let names = recommender.recommend(RecommendationOptions(stuff: ["土豆"])).map(\.recipe.name)
        XCTAssertEqual(names, ["高分", "没分", "低分", "缺一样但分高"])
        XCTAssertEqual(Recommender.defaultScore, 50)
    }
}
