import CookCore
import XCTest

final class RecipeStepUsagePlanTests: XCTestCase {
    private func plan(_ quantities: [String], _ steps: [String], servings: Int = 1) -> [RecipeInstructionAmounts] {
        let presentation = RecipePresentation(text: RecipeText(blocks: [
            .heading("计算"), .list(ordered: false, start: 1, items: quantities),
            .heading("操作"), .list(ordered: true, start: 1, items: steps),
        ]))
        let plan = RecipeStepUsagePlan(steps: presentation.steps, amounts: RecipeAmounts(rows: presentation.allQuantities, servings: servings))
        return presentation.steps.compactMap { plan.instruction(for: $0) }
    }

    func testTomatoOilAndRemainingSaltFollowServingsWithoutEditingSource() throws {
        let library = try XCTUnwrap(RecipeTextLibrary.load(from: SharedFixtureTests.dataDirectory))
        let source = try XCTUnwrap(library.text(for: 250))
        let presentation = RecipePresentation(text: source)
        for (servings, oil, salt) in [(1, "8 ml", "0.5–1 g"), (2, "12 ml", "1–2 g"), (3, "20 ml", "1.5–3 g"), (20, "120 ml", "10–20 g")] {
            let amounts = RecipeAmounts(rows: presentation.allQuantities, servings: servings)
            let plan = RecipeStepUsagePlan(steps: presentation.steps, amounts: amounts)
            XCTAssertEqual(plan.instruction(for: presentation.steps[4])?.text, "热锅，加入食用油（\(oil)）")
            let remaining = try XCTUnwrap(plan.instruction(for: presentation.steps[9]))
            XCTAssertTrue(remaining.text.contains("剩余的盐（\(salt)）"), remaining.text)
            XCTAssertTrue(remaining.text.contains("糖（0–\(servings * 2) g）（可选，如果倾向于甜味版本）"), remaining.text)
            XCTAssertFalse(remaining.hasUnresolvedFormula)
            XCTAssertTrue(plan.instruction(for: presentation.steps[3])?.text.contains("\(servings) g") == true)
        }
        XCTAssertEqual(source, library.text(for: 250))
        XCTAssertEqual(presentation.steps, RecipePresentation(text: source).steps)
    }

    func testSeveralExplicitUsesAndUnitConversionAreSubtractedInOrder() {
        let results = plan(["盐 = 10g * 份数", "水 = 1 L * 份数"], [
            "加入 2g * 份数 的盐", "放入盐 1 g", "加入剩余的盐",
            "倒入 250 ml 水", "倒入剩下的水",
        ], servings: 2)
        XCTAssertEqual(results[2].text, "加入剩余的盐（15 g）")
        XCTAssertEqual(results[4].text, "倒入剩下的水（1.75 L）")
    }

    func testAmbiguousRepeatedAdditionsNeverReceiveTheWholeTotal() {
        for steps in [["加入食用油", "加入食用油"], ["加入少许食用油", "加入剩余的食用油"], ["加入少量油", "加入食用油"]] {
            let results = plan(["食用油 = 20 ml"], steps)
            XCTAssertEqual(results.map(\.text), steps)
            XCTAssertTrue(results.last?.hasUnresolvedFormula == true)
        }
    }

    func testUnknownConditionalRangeAndIncompatiblePriorAmountsBlockRemainders() {
        for first in ["加入少许盐", "用盐腌制", "如果喜欢咸味，加入 1g 盐", "加入 1–2g 盐", "加入 1 茶匙 盐", "加入 7 g 盐", "加入 `份数 * 一把半` 盐"] {
            let results = plan(["盐 = 5g"], [first, "加入剩余的盐"])
            XCTAssertEqual(results[1].text, "加入剩余的盐", first)
            XCTAssertTrue(results[1].hasUnresolvedFormula, first)
        }
        let missing = plan(["盐 = 5g"], ["加入剩余的盐"])
        XCTAssertEqual(missing[0].text, "加入剩余的盐")
        XCTAssertTrue(missing[0].hasUnresolvedFormula)
    }

    func testOptionalNotesMarkdownLinksAndNonUsageMentionsArePreserved() {
        let results = plan(["食用油 = 20ml", "盐 = 2g", "糖 = 0–2g"], [
            "热锅，加入 **食用油**，加热 20 秒", "油热后，倒入鸡蛋液",
            "加入盐、糖（可选，喜欢甜味时）", "[不要加入盐](https://example.com/盐)",
        ])
        XCTAssertEqual(results[0].text, "热锅，加入 **食用油**（20 ml），加热 20 秒")
        XCTAssertEqual(results[1].text, "油热后，倒入鸡蛋液")
        XCTAssertEqual(results[2].text, "加入盐（2 g）、糖（0–2 g）（可选，喜欢甜味时）")
        XCTAssertEqual(results[3].text, "[不要加入盐](https://example.com/盐)")
    }

    func testExplicitAmountsAreNotOverwrittenAndDuplicateIngredientsStayAmbiguous() {
        let fixed = plan(["盐 = 5g"], ["加入盐 2 g", "加入剩余 3g 盐"])
        XCTAssertEqual(fixed.map(\.text), ["加入盐 2 g", "加入剩余 3g 盐"])
        let duplicates = plan(["盐 = 2g", "盐 = 5g"], ["加入盐"])
        XCTAssertEqual(duplicates[0].text, "加入盐")
        XCTAssertTrue(duplicates[0].hasUnresolvedFormula)
        let forbidden = plan(["盐 = 5g"], ["不要加入盐", "加入剩余的盐"])
        XCTAssertEqual(forbidden.map(\.text), ["不要加入盐", "加入剩余的盐"])
    }
}
