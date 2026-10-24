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

    func testCatalogAppendPreservesImportedListElementsWhenSavingAndExporting() throws {
        let imported = PrivateRecipe(
            customId: "custom-editor-catalog-append",
            name: "合成菜单追加菜谱",
            stuff: ["葱,蒜", "盐（可选，少量）", "第一行\n第二行", "西红柿", "葱,蒜"],
            tools: ["锅、铲套装", "平底锅,带盖", "锅、铲套装"],
            createdAt: 1000,
            updatedAt: 1000
        )
        let incoming = try PrivateRecipeFile.parse(PrivateRecipeFile(recipes: [imported]).export()).recipes
        let container = Persistence.makeContainer(inMemory: true)
        XCTAssertEqual(try PrivateRecipePersistence.importRecipes(incoming, keepCopies: false, in: container), 1)
        let original = try XCTUnwrap(PrivateRecipePersistence.snapshot(in: ModelContext(container)).recipes.first)

        var stuff = PrivateRecipeEditorList(preserving: original.stuff)
        var tools = PrivateRecipeEditorList(preserving: original.tools)
        let normalizer = StuffNormalizer(aliases: ["西红柿": "番茄", "马铃薯": "土豆"])
        stuff.append("鸡蛋", normalizer: normalizer)
        stuff.append("豌豆,蚕豆", normalizer: normalizer)
        stuff.append("豌豆,蚕豆", normalizer: normalizer)
        stuff.append("番茄", normalizer: normalizer)
        stuff.append("马铃薯", normalizer: normalizer)
        tools.append("蒸锅")
        tools.append("量杯、量勺")
        tools.append("量杯、量勺")
        var edited = original
        edited.stuff = stuff.values
        edited.tools = tools.values
        edited.updatedAt += 1
        try PrivateRecipePersistence.save(edited, replacing: original, in: container)

        let saved = try PrivateRecipePersistence.snapshot(in: ModelContext(container)).recipes
        let exported = try XCTUnwrap(PrivateRecipeFile.parse(PrivateRecipeFile(recipes: saved).export()).recipes.first)
        XCTAssertEqual(exported.stuff, ["葱,蒜", "盐（可选，少量）", "第一行\n第二行", "西红柿", "葱,蒜", "鸡蛋", "豌豆,蚕豆", "土豆"])
        XCTAssertEqual(exported.tools, ["锅、铲套装", "平底锅,带盖", "锅、铲套装", "蒸锅", "量杯、量勺"])
    }

    func testManualEditAfterCatalogAppendUsesEnteredList() {
        var stuff = PrivateRecipeEditorList(preserving: ["葱,蒜"])
        stuff.append("鸡蛋", normalizer: StuffNormalizer(aliases: [:]))
        stuff.text = " 鸡蛋、盐,鸡蛋，\n 葱 "
        XCTAssertEqual(stuff.values, ["鸡蛋", "盐", "葱"])
        stuff.append("蒜", normalizer: StuffNormalizer(aliases: [:]))
        XCTAssertEqual(stuff.values, ["鸡蛋", "盐", "葱", "蒜"])

        var tools = PrivateRecipeEditorList(preserving: ["锅、铲套装"])
        tools.append("蒸锅")
        tools.text = ""
        XCTAssertEqual(tools.values, [])
    }
}
