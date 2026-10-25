import CookCore
import XCTest

final class RecipePresentationTests: XCTestCase {
    func testTomatoRecipeKeepsFormulasOptionalNotesAndSubsteps() throws {
        let library = try XCTUnwrap(RecipeTextLibrary.load(from: SharedFixtureTests.dataDirectory))
        let text = try XCTUnwrap(library.text(for: 250))
        let presentation = RecipePresentation(text: text)
        XCTAssertEqual(presentation.difficulty, 2)
        XCTAssertEqual(presentation.calories, "252 大卡")
        XCTAssertEqual(presentation.ingredients.count, 6)
        XCTAssertEqual(presentation.amount(for: "糖（可选）"), "0-2g * 份数")
        XCTAssertEqual(presentation.amount(for: "鸡蛋"), "1.5 个 * 份数，向上取整")
        XCTAssertEqual(presentation.amount(for: "食用油"), "4ml * 鸡蛋/个")
        XCTAssertNil(presentation.amount(for: "鸡"))
        XCTAssertEqual(presentation.steps.count, 11)
        XCTAssertTrue(presentation.steps[1].text.contains("\n• 开水烫表皮"))
        XCTAssertFalse(presentation.steps.contains { $0.text.contains("快速做法") })
        XCTAssertEqual(presentation.steps.map(\.number), Array(1 ... 11))
    }

    func testUnknownSectionsAndMixedQuantitiesRemainOriginalText() {
        let text = RecipeText(blocks: [
            .paragraph("预估烹饪难度：★★，请小心操作"),
            .heading("必备原料和工具"), .paragraph("请使用耐热容器"),
            .list(ordered: false, start: 1, items: ["鸡蛋", "碗"]),
            .heading("计算"), .list(ordered: false, start: 1, items: ["鸡蛋 = 2 个", "盐适量"]),
            .heading("操作"), .list(ordered: false, start: 1, items: ["搅拌", "搅拌"]),
            .image(src: "egg.webp", width: 100, height: 100, alt: nil),
            .paragraph("蒸熟后取出"),
            .heading("附加内容"), .list(ordered: true, start: 1, items: ["也可以煎"]),
        ])
        let presentation = RecipePresentation(text: text)
        XCTAssertNil(presentation.difficulty)
        XCTAssertTrue(presentation.metadataBlocks.isEmpty)
        XCTAssertFalse(presentation.ingredientBlocks.contains(1), "含备注的原料章节保留标题和备注")
        XCTAssertTrue(presentation.quantities.isEmpty, "不能识别整个列表时不隐藏原始内容")
        XCTAssertEqual(presentation.steps.count, 3)
        XCTAssertEqual(Set(presentation.steps.map(\.id)).count, 3, "同名步骤可独立勾选")
        let prefixed = RecipePresentation(text: RecipeText(blocks: [.paragraph("新增简介")] + text.blocks))
        XCTAssertEqual(presentation.steps.map(\.id), prefixed.steps.map(\.id), "编辑简介不影响操作记录")
        XCTAssertEqual(presentation.steps.last?.text, "蒸熟后取出")
    }

    func testEveryConvertedBlockRetainsItsSourceText() throws {
        let data = try SharedFixtureTests.loadData()
        let library = try XCTUnwrap(RecipeTextLibrary.load(from: SharedFixtureTests.dataDirectory))
        for recipe in data.recipes {
            guard let text = library.text(for: recipe.id) else { continue }
            let presentation = RecipePresentation(text: text)
            for (offset, steps) in presentation.stepsByBlock {
                switch text.blocks[offset] {
                case let .list(ordered, start, items):
                    XCTAssertEqual(steps.map(\.text), items, recipe.name)
                    if ordered { XCTAssertEqual(steps.map(\.number), Array(start ..< start + items.count), recipe.name) }
                case let .paragraph(value): XCTAssertEqual(steps.map(\.text), [value.trimmingCharacters(in: .whitespacesAndNewlines)], recipe.name)
                default: XCTFail("图片或标题不能被替换为步骤")
                }
            }
            for (offset, rows) in presentation.quantities {
                guard case let .list(_, _, items) = text.blocks[offset] else { return XCTFail("用量必须来自原列表") }
                XCTAssertEqual(rows.count, items.count, recipe.name)
                for (row, item) in zip(rows, items) {
                    XCTAssertTrue(item.contains(row.name), recipe.name)
                    XCTAssertTrue(item.contains(row.amount), recipe.name)
                }
            }
        }
    }
}
