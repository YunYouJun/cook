import CookCore
import Foundation
import XCTest

/// 读 packages/data/recipe-text.json：链接到 HowToCook 的菜都要有离线正文
final class RecipeTextTests: XCTestCase {
    func testEveryHowToCookRecipeHasText() throws {
        let data = try SharedFixtureTests.loadData()
        let library = try XCTUnwrap(RecipeTextLibrary.load(from: SharedFixtureTests.dataDirectory))
        let linked = data.recipes.filter { $0.link?.hasPrefix("https://github.com/Anduin2017/HowToCook/") == true }
        XCTAssertFalse(linked.isEmpty)
        XCTAssertEqual(library.count, linked.count)
        for recipe in linked {
            let text = try XCTUnwrap(library.text(for: recipe.id), "\(recipe.name) 没有正文")
            XCTAssertFalse(text.blocks.isEmpty, recipe.name)
        }
        XCTAssertNil(library.text(for: data.recipes.first { $0.link == nil }!.id))
    }

    func testDecodesBlocks() throws {
        let json = """
        {"blocks":[{"type":"heading","text":"操作"},{"type":"list","ordered":true,"start":3,"items":["切块"]},{"type":"paragraph","text":"出锅"},{"type":"table","rows":[]}],"images":2}
        """
        let text = try JSONDecoder().decode(RecipeText.self, from: Data(json.utf8))
        XCTAssertEqual(text.blocks, [
            .heading("操作"),
            .list(ordered: true, start: 3, items: ["切块"]),
            .paragraph("出锅"),
            .paragraph(""),
        ])
        XCTAssertEqual(text.images, 2)
    }
}
