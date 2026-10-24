@testable import CookCore
import XCTest

final class RecipeAmountAllocationTests: XCTestCase {
    private func source(_ id: Int) throws -> RecipeText {
        let library = try XCTUnwrap(RecipeTextLibrary.load(from: SharedFixtureTests.dataDirectory))
        let text = try XCTUnwrap(library.text(for: id))
        XCTAssertNotNil(RecipePresentation(text: text).amountAnnotations)
        return text
    }

    private func rendered(_ id: Int, _ block: Int, _ item: Int, _ servings: Int = 3) throws -> RecipeInstructionAmounts {
        let presentation = RecipePresentation(text: try source(id))
        let amounts = RecipeAmounts(rows: presentation.allQuantities, servings: servings)
        let plan = RecipeStepUsagePlan(steps: presentation.steps, amounts: amounts, annotations: presentation.amountAnnotations)
        let step = try XCTUnwrap(presentation.steps.first { $0.sourceBlock == block && $0.sourceItem == item })
        return try XCTUnwrap(plan.instruction(for: step))
    }

    func testFixedBatchHalvesNeverBecomePerPersonAmounts() throws {
        for servings in [1, 2, 3, 20] {
            let first = try rendered(598, 9, 3, servings).text
            let last = try rendered(598, 9, 9, servings).text
            XCTAssertTrue(first.contains("本步 食盐 1.5 g"), first)
            XCTAssertTrue(first.contains("本步 酱油 5 g"), first)
            XCTAssertTrue(last.contains("剩下的食盐（1.5 g）"), last)
            XCTAssertTrue(last.contains("味极鲜酱油酱油（5 g）"), last)
        }
    }

    func testFlourStarchAndSaltAllocationsScaleAndKeepOriginalNumbers() throws {
        let flour = try rendered(680, 14, 0).text
        XCTAssertTrue(flour.contains("40g 低筋面粉（本步 低筋面粉 120 g）"), flour)
        let flourRest = try rendered(680, 14, 1).text
        XCTAssertTrue(flourRest.contains("剩余 80g 低筋面粉（240 g）"), flourRest)
        let starch = try rendered(727, 9, 2).text
        XCTAssertTrue(starch.contains("本步 淀粉 60 g"), starch)
        let starchRest = try rendered(727, 9, 4).text
        XCTAssertTrue(starchRest.contains("剩余的 60 g 淀粉（180 g）"), starchRest)
        let soup = try rendered(489, 15, 2).text
        XCTAssertTrue(soup.contains("剩余 15 g 盐（45 g）"), soup)
    }

    func testOilTransfersDoNotDebitTheSameOilAgain() throws {
        for servings in [1, 2, 3, 20] {
            let first = try rendered(710, 10, 1, servings).text
            let rest = try rendered(710, 10, 7, servings).text
            XCTAssertTrue(first.contains("本步 油 \(5 * servings) ml"), first)
            XCTAssertTrue(rest.contains("剩余的油（\(30 * servings) ml）"), rest)
            XCTAssertEqual(try rendered(710, 10, 8, servings).text, "将热油淋入葱姜碗中")
            XCTAssertEqual(try rendered(710, 10, 10, servings).text, "姜葱姜油淋在鸡丝上，搅拌均匀即可")
        }
    }

    func testExplicitRangeHalvesUseProportionsWithoutChoosingActualPriorUse() throws {
        let first = try rendered(754, 9, 3).text
        let last = try rendered(754, 9, 5).text
        XCTAssertTrue(first.contains("本步 冰糖 3–6 粒"), first)
        XCTAssertTrue(last.contains("本步 冰糖 3–6 粒"), last)
        XCTAssertTrue(last.contains("本步 姜 15 g"), last)
        XCTAssertTrue(last.contains("本步 料酒或者黄酒 45–60 ml"), last)
        let presentation = RecipePresentation(text: try source(754))
        let amounts = RecipeAmounts(rows: presentation.allQuantities, servings: 3)
        XCTAssertNil(amounts.amount(for: "料酒或者黄酒")?.measurement)
    }

    func testTwoServingFishRecipeSeparatesPerBagAndWholeStepQuantities() throws {
        let first = try rendered(781, 14, 1).text
        let last = try rendered(781, 14, 6).text
        XCTAssertTrue(first.contains("本步 姜 15 g"), first)
        XCTAssertTrue(first.contains("本步 青葱 37.5 g"), first)
        XCTAssertTrue(last.contains("本步 姜 4.5 g"), last)
        XCTAssertTrue(last.contains("本步 青葱 15 g"), last)
        let wine = try rendered(781, 14, 2).text
        XCTAssertTrue(wine.contains("每个袋子倒入 2.5mL 料酒（本步 料酒 7.5 ml）"), wine)
        XCTAssertTrue(try rendered(781, 14, 7).text.contains("190℃"))
    }

    func testPreparedChickenAndFlowerLayerAreNotAdditionalIngredients() throws {
        XCTAssertFalse(try rendered(701, 9, 2).hasUnresolvedFormula)
        let layer = try rendered(701, 9, 3)
        XCTAssertFalse(layer.hasUnresolvedFormula)
        XCTAssertEqual(layer.text, "把腌好的鸡肉铺在虫草花上面")
    }

    func testMarinadeAndSauceNeverReceiveWholeCombinedTotal() throws {
        let marinade = try rendered(775, 13, 0).text
        let sauce = try rendered(775, 19, 2).text
        XCTAssertTrue(marinade.contains("本步 盐 9 g"), marinade)
        XCTAssertTrue(sauce.contains("本步 盐 15 g"), sauce)
        XCTAssertTrue(marinade.contains("本步 生姜 30 g"), marinade)
        XCTAssertTrue(try rendered(775, 17, 0).text.contains("本步 生姜 30 g"))
        let pancake = try rendered(810, 10, 0).text
        let batter = try rendered(810, 10, 1).text
        for text in [pancake, batter] {
            XCTAssertTrue(text.contains("本步 盐 4.5 g"), text)
            XCTAssertTrue(text.contains("本步 糖 7.5 g"), text)
            XCTAssertTrue(text.contains("本步 酱油 7.5 g"), text)
        }
    }

    func testRecoveredAmountsRetainApproximationBranchesAndFixedBatchScope() throws {
        let ginger = try rendered(799, 10, 2).text
        XCTAssertTrue(ginger.contains("剩余大约 10g 姜片（本步 生姜 30 g）"), ginger)
        XCTAssertTrue(try rendered(799, 10, 4).hasUnresolvedFormula, "葱结/葱末分配仍未知")
        let presentation = RecipePresentation(text: try source(817))
        for servings in [1, 20] {
            let amounts = RecipeAmounts(rows: presentation.allQuantities, servings: servings)
            XCTAssertEqual(amounts.amount(for: "酱油")?.measurement?.text, "30 g")
            XCTAssertEqual(amounts.amount(for: "耗油")?.measurement?.text, "20 g")
            XCTAssertNil(amounts.amount(for: "蚝油"), "不擅改原文耗油的身份")
            XCTAssertNil(amounts.amount(for: "味淋")?.measurement)
            XCTAssertNil(amounts.amount(for: "料酒")?.measurement)
            XCTAssertNil(amounts.amount(for: "老抽")?.measurement)
        }
        let pepper = RecipePresentation(text: try source(731))
        XCTAssertEqual(RecipeAmounts(rows: pepper.allQuantities, servings: 3).amount(for: "黑胡椒")?.measurement?.text, "12 g")
        XCTAssertTrue(try rendered(731, 11, 0).text.contains("本步 黑胡椒 6 g"))
        XCTAssertTrue(try rendered(731, 11, 6).text.contains("本步 黑胡椒 6 g"))
    }
}
