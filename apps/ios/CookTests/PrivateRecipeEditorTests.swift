import CookCore
import SwiftData
import XCTest
@testable import Cook

@MainActor
final class PrivateRecipeEditorTests: XCTestCase {
    func testNoteOnlyEditPreservesImportedIngredientAndToolArrays() throws {
        let imported = PrivateRecipe(
            customId: "custom-editor-preservation",
            name: "合成测试菜谱",
            stuff: ["盐（可选，少量）", "葱,蒜", "油、盐", "第一行\n第二行", "鸡蛋", "鸡蛋"],
            tools: ["锅、铲套装", "平底锅,带盖", "电锅（小号，带蒸架）", "锅\n盖", "碗", "碗"],
            note: "原备注",
            createdAt: 1000,
            updatedAt: 1000
        )
        let incoming = try PrivateRecipeFile.parse(PrivateRecipeFile(recipes: [imported]).export()).recipes
        let container = Persistence.makeContainer(inMemory: true)
        XCTAssertEqual(try PrivateRecipePersistence.importRecipes(incoming, keepCopies: false, in: container), 1)
        let original = try XCTUnwrap(PrivateRecipePersistence.snapshot(in: ModelContext(container)).recipes.first)

        var edited = original
        edited.stuff = PrivateRecipeEditorInput.list(original.stuff.joined(separator: "、"), preserving: original.stuff)
        edited.tools = PrivateRecipeEditorInput.list(original.tools.joined(separator: "、"), preserving: original.tools)
        edited.note = "只修改备注"
        edited.updatedAt += 1
        try PrivateRecipePersistence.save(edited, replacing: original, in: container)

        let saved = try PrivateRecipePersistence.snapshot(in: ModelContext(container)).recipes
        let exported = try XCTUnwrap(PrivateRecipeFile.parse(PrivateRecipeFile(recipes: saved).export()).recipes.first)
        XCTAssertEqual(exported.stuff, imported.stuff, "Editing only the note must not split or deduplicate the imported ingredient array")
        XCTAssertEqual(exported.tools, imported.tools, "Editing only the note must not split or deduplicate the imported tool array")
        XCTAssertEqual(exported.note, "只修改备注")
        XCTAssertEqual(exported.customId, imported.customId)
    }

    func testChangedListInputStillSplitsTrimsAndDeduplicates() {
        XCTAssertEqual(
            PrivateRecipeEditorInput.list(" 鸡蛋、盐,鸡蛋，\n 葱 \n盐", preserving: ["原有食材"]),
            ["鸡蛋", "盐", "葱"]
        )
        XCTAssertEqual(
            PrivateRecipeEditorInput.list(" 蒸锅,碗、蒸锅 ", preserving: ["原有厨具"]),
            ["蒸锅", "碗"]
        )
    }

    func testOptionalToolsCanStayOrBecomeEmpty() {
        XCTAssertEqual(PrivateRecipeEditorInput.list("", preserving: []), [])
        XCTAssertEqual(PrivateRecipeEditorInput.list("", preserving: nil), [])
        XCTAssertEqual(PrivateRecipeEditorInput.list(" \n，、, ", preserving: ["蒸锅"]), [])
    }
}
