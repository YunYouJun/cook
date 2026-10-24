import CookCore
import XCTest

final class RecipeInstructionAmountsTests: XCTestCase {
    private func presentation(_ id: Int) throws -> RecipePresentation {
        let library = try XCTUnwrap(RecipeTextLibrary.load(from: SharedFixtureTests.dataDirectory))
        return RecipePresentation(text: try XCTUnwrap(library.text(for: id)))
    }

    func testTomatoUsesStepSaltInsteadOfTotalAndKeepsNotesAndIdentity() throws {
        let recipe = try presentation(250)
        let step = recipe.steps[3]
        for servings in [1, 2, 3, 20] {
            let amounts = RecipeAmounts(rows: recipe.allQuantities, servings: servings)
            let result = amounts.instruction(step.text)
            XCTAssertEqual(result.text, step.text.replacingOccurrences(of: "1g * 份数", with: "\(servings) g"))
            XCTAssertTrue(result.hasAdjustedAmounts)
            XCTAssertFalse(result.hasUnresolvedFormula)
            XCTAssertTrue(result.text.contains("1ml 醋"), "没有份数依据的可选用量保持原文")
        }
        XCTAssertEqual(step, try presentation(250).steps[3], "换算不改来源文字、编号或持久化身份")
    }

    func testAllBundledStepServingFormulasResolveIncludingRecipesWithoutQuantityTables() throws {
        let examples = [
            (225, "（份数 * 5）g", "15 g"), (225, "（份数 * 3）g", "9 g"),
            (250, "1g * 份数", "3 g"), (686, "份数 * 13 毫升", "39 ml"),
            (688, "份数 * 9ml", "27 ml"), (763, "份数 * 8ml", "24 ml"),
            (859, "（2g * 份数）", "6 g"),
        ]
        for (id, expression, expected) in examples {
            let recipe = try presentation(id)
            let source = try XCTUnwrap(recipe.steps.first { $0.text.contains(expression) }).text
            let result = RecipeAmounts(rows: recipe.allQuantities, servings: 3).instruction(source)
            XCTAssertEqual(result.text, source.replacingOccurrences(of: expression, with: expected), "recipe \(id)")
            XCTAssertTrue(result.hasAdjustedAmounts)
            XCTAssertFalse(result.hasUnresolvedFormula)
        }
        let withoutTable = try presentation(859)
        XCTAssertTrue(withoutTable.quantities.isEmpty, "原文没有等式表，补充标注不改写原文表格")
        XCTAssertFalse(withoutTable.allQuantities.isEmpty, "独立标注现已补上明确数量")
        XCTAssertTrue(withoutTable.steps.contains { RecipeAmounts(rows: [], servings: 3).instruction($0.text).hasAdjustedAmounts })
    }

    func testMultipleAmountsRangesFractionsRoundingAndDependencies() throws {
        let rows = try presentation(250).allQuantities
        let amounts = RecipeAmounts(rows: rows, servings: 3)
        let source = "加入 1.5–2g × 份数 的盐、份数 * 1/2 茶匙 糖、`4ml * 鸡蛋/个` 油和 `1.5个 * 份数，向上取整` 鸡蛋。"
        XCTAssertEqual(amounts.instruction(source).text, "加入 4.5–6 g 的盐、1.5 茶匙 糖、`20 ml` 油和 `5 个（已向上取整）` 鸡蛋。")
        XCTAssertEqual(amounts.instruction("加入 1.5个 * 份数（向上取整） 鸡蛋").text, "加入 5 个（已向上取整） 鸡蛋")
    }

    func testUnrelatedNumbersMarkdownAndLinksAreUntouched() throws {
        let amounts = RecipeAmounts(rows: try presentation(250).allQuantities, servings: 20)
        let source = "将 `鸡蛋` 与 **糖** 混合，切 4cm 小块，加入 10ml 水，180℃ 加热 20 秒，收至 1/4。\n• 可选：1/2 茶匙盐。根据份数调整口味。\n[配方](https://example.com/份数*2g)"
        let result = amounts.instruction(source)
        XCTAssertEqual(result.text, source)
        XCTAssertFalse(result.hasAdjustedAmounts)
        XCTAssertFalse(result.hasUnresolvedFormula)
    }

    func testUnsupportedExpressionsNeverPartiallyConvert() {
        let amounts = RecipeAmounts(rows: [], servings: 3)
        for expression in ["1g * 份数 + 2g", "未知数量 * 2g * 份数", "份数 * 2g / 未知数量", "1g * (份数 + 1)", "((份数 * 2g))", "份数 * 10mm", "份数 * 一把半", "-1g * 份数", "份数 * 2g - 份数 * 6g", "`份数 * 2g + 未知数量`", "`份数 * 2g` + 1g", "份数 * 1.5个，向下取整", "`份数 * 1.5个`，向上取整", "（份数 * 1.5个），向下取整"] {
            let source = "加入 \(expression) 的原料。"
            let result = amounts.instruction(source)
            XCTAssertEqual(result.text, source, expression)
            XCTAssertFalse(result.hasAdjustedAmounts, expression)
            XCTAssertTrue(result.hasUnresolvedFormula, expression)
        }
    }

    func testRecognizedAndUnknownAmountsCanCoexistWithoutLosingText() {
        let result = RecipeAmounts(rows: [], servings: 3).instruction("加 1g * 份数 的盐，再加 `份数 * 一把半` 糖。\n• 保留可选说明")
        XCTAssertEqual(result.text, "加 3 g 的盐，再加 `份数 * 一把半` 糖。\n• 保留可选说明")
        XCTAssertTrue(result.hasAdjustedAmounts)
        XCTAssertTrue(result.hasUnresolvedFormula)
    }
}
