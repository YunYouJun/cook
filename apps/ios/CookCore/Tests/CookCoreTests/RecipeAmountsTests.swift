import CookCore
import XCTest

final class RecipeAmountsTests: XCTestCase {
    private func tomatoRows() throws -> [RecipePresentation.Quantity] {
        let library = try XCTUnwrap(RecipeTextLibrary.load(from: SharedFixtureTests.dataDirectory))
        return RecipePresentation(text: try XCTUnwrap(library.text(for: 250))).allQuantities
    }

    func testTomatoServingsRoundEggsBeforeCalculatingOil() throws {
        let rows = try tomatoRows()
        for (servings, eggs, oil) in [(1, "2 个", "8 ml"), (2, "3 个", "12 ml"), (3, "5 个", "20 ml"), (20, "30 个", "120 ml")] {
            let amounts = RecipeAmounts(rows: rows, servings: servings)
            XCTAssertTrue(amounts.supportsServings)
            XCTAssertEqual(amounts.amount(for: "鸡蛋")?.text, eggs)
            XCTAssertEqual(amounts.amount(for: "食用油")?.text, oil)
            XCTAssertTrue(amounts.amount(for: "食用油")?.adjustsWithServings == true)
        }
        let three = RecipeAmounts(rows: rows, servings: 3)
        XCTAssertEqual(three.amount(for: "西红柿")?.text, "3 个（约 540 g）")
        XCTAssertEqual(three.amount(for: "盐")?.text, "4.5–6 g")
        XCTAssertEqual(three.amount(for: "糖（可选）")?.text, "0–6 g")
        XCTAssertEqual(three.amount(for: "葱花（可选）")?.text, "0–30 g")
        XCTAssertEqual(three.amount(for: "鸡蛋")?.note, "已向上取整")
    }

    func testDependenciesAreOrderIndependentAndKeepFixedAmounts() throws {
        let rows = try ["油 = 4ml * 鸡蛋/个", "鸡蛋 = 1.5 个 * 份数，向上取整", "辣椒量 = 50 克（无论多少人都放这些）", "面粉 = 份数 * 150 克", "淀粉 = 面粉 / 4 克"].map { try XCTUnwrap(RecipePresentation.Quantity($0)) }
        let result = RecipeAmounts(rows: rows, servings: 3)
        XCTAssertEqual(result.amount(for: "油")?.text, "20 ml")
        XCTAssertEqual(result.amount(for: "辣椒")?.text, "50 g（无论多少人都放这些）")
        XCTAssertEqual(result.amount(for: "辣椒")?.adjustsWithServings, false)
        XCTAssertEqual(result.amount(for: "淀粉")?.text, "112.5 g")
        XCTAssertEqual(RecipeAmounts(rows: Array(rows.reversed()), servings: 3).amount(for: "油"), result.amount(for: "油"))
    }

    func testFractionsPerPersonRulesAndDecimals() throws {
        let rows = try ["洋葱量 = 份数 * 1/12 个", "葱的数量 = 2 根/三人。", "盐量 = 份数 * 0.1 g。", "虾 = 份数 × 3–5 只"].map { try XCTUnwrap(RecipePresentation.Quantity($0)) }
        let result = RecipeAmounts(rows: rows, servings: 3)
        XCTAssertEqual(result.amount(for: "洋葱")?.text, "0.25 个")
        XCTAssertEqual(result.amount(for: "葱")?.text, "2 根")
        XCTAssertEqual(result.amount(for: "盐")?.text, "0.3 g")
        XCTAssertEqual(result.amount(for: "虾")?.text, "9–15 只")
    }

    func testUnknownInvalidAndCyclicFormulasNeverInventAmounts() throws {
        let input = ["A = 2g * B", "B = A/个", "C = 份数 * 正常男子手抓半把", "D = 未记录原料 * 4g", "E = 1/0 g * 份数", "F = 2-1g * 份数", "G = 1g + 2g", "H = 4ml * 鸡蛋/斤", "I = 1 个 (份数 + 1)", "鸡蛋 = 1.5 个 * 份数，向上取整"]
        let rows = try input.map { try XCTUnwrap(RecipePresentation.Quantity($0)) }
        let result = RecipeAmounts(rows: rows, servings: 3)
        for row in rows.dropLast() {
            XCTAssertFalse(result.amount(for: row).isResolved, row.name)
            XCTAssertEqual(result.amount(for: row).text, row.amount, row.name)
            XCTAssertEqual(result.amount(for: row).note, "需按原文确认")
        }
        XCTAssertEqual(result.amount(for: "鸡蛋")?.text, "5 个")
        XCTAssertNil(result.amount(for: "未知"))
    }

    func testDuplicateNamesAndServingBounds() throws {
        let duplicate = try ["鸡蛋 = 2 个 * 份数", "鸡蛋 = 4 个 * 份数", "食用油 = 4ml * 鸡蛋/个"].map { try XCTUnwrap(RecipePresentation.Quantity($0)) }
        let result = RecipeAmounts(rows: duplicate, servings: 2)
        XCTAssertNil(result.amount(for: "鸡蛋"))
        XCTAssertEqual(result.amount(for: "食用油")?.isResolved, false)
        let rows = try tomatoRows()
        XCTAssertEqual(RecipeAmounts(rows: rows, servings: 0).amount(for: "鸡蛋")?.text, "2 个")
        XCTAssertEqual(RecipeAmounts(rows: rows, servings: Int.max).amount(for: "鸡蛋")?.text, "30 个")
    }
}
