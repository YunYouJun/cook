import CookCore
import Foundation
import XCTest

final class PrivateRecipeTests: XCTestCase {
    private func fixture() throws -> PrivateRecipeFile {
        let url = SharedFixtureTests.dataDirectory.appendingPathComponent("fixtures/private-recipes.json")
        return try PrivateRecipeFile.parse(String(contentsOf: url, encoding: .utf8))
    }

    func testSharedWebFixtureRoundTripsEveryField() throws {
        let file = try fixture()
        XCTAssertEqual(try PrivateRecipeFile.parse(file.export()), file)
        XCTAssertEqual(file.recipes[0].customId, "custom-legacy-a")
        XCTAssertTrue(file.recipes[0].note!.contains("\n"))
    }

    func testActualWebToSwiftToWebRoundtrip() throws {
        let env = ProcessInfo.processInfo.environment
        guard let input = env["COOK_PRIVATE_ROUNDTRIP_INPUT"], let output = env["COOK_PRIVATE_ROUNDTRIP_OUTPUT"] else {
            throw XCTSkip("Use scripts/test-private-recipe-roundtrip.sh for the live encoder bridge")
        }
        let file = try PrivateRecipeFile.parse(String(contentsOfFile: input, encoding: .utf8))
        XCTAssertEqual(file, try fixture())
        try file.export().write(toFile: output, atomically: true, encoding: .utf8)
    }

    func testBadFileIsRejectedAsAWhole() throws {
        let file = try fixture()
        var object = try JSONSerialization.jsonObject(with: Data(file.export().utf8)) as! [String: Any]
        var records = object["recipes"] as! [[String: Any]]
        records[0]["futureField"] = "must not be dropped"
        var unknown = object
        unknown["recipes"] = records
        XCTAssertThrowsError(try PrivateRecipeFile.parse(String(decoding: JSONSerialization.data(withJSONObject: unknown), as: UTF8.self)))
        records[0].removeValue(forKey: "futureField")
        records[0]["note"] = NSNull()
        unknown["recipes"] = records
        XCTAssertThrowsError(try PrivateRecipeFile.parse(String(decoding: JSONSerialization.data(withJSONObject: unknown), as: UTF8.self)))
        object["version"] = 2
        XCTAssertThrowsError(try PrivateRecipeFile.parse(String(decoding: JSONSerialization.data(withJSONObject: object), as: UTF8.self)))
        XCTAssertThrowsError(try PrivateRecipeFile.parse("{"))
        var bad = file
        bad.recipes[1].link = "javascript:alert(1)"
        XCTAssertThrowsError(try bad.export())
        bad = file
        bad.recipes[1].stuff = []
        XCTAssertThrowsError(try bad.export())
        XCTAssertFalse(PrivateRecipeFile.isSafeURL("https://user:pass@example.com"))
        XCTAssertEqual(file.recipes[0].customId, "custom-legacy-a")
    }

    func testPreviewPreservesLocalConflictAndCopiesAreIdempotent() throws {
        let incoming = try fixture().recipes[0]
        var local = incoming
        local.name = "本机版本"
        let plan = PrivateRecipeImport(current: [local], incoming: [incoming])
        XCTAssertEqual(plan.conflicts.count, 1)
        XCTAssertEqual(try plan.recordsToInsert(keepCopies: false, currentIDs: [local.customId]), [])
        let copies = try plan.recordsToInsert(keepCopies: true, currentIDs: [local.customId], createID: { "custom-copy" })
        XCTAssertEqual(copies[0].copiedFrom, incoming.customId)
        let again = PrivateRecipeImport(current: [local] + copies, incoming: [incoming])
        XCTAssertEqual(again.duplicates.count, 1)
        XCTAssertEqual(again.conflicts.count, 0)
        XCTAssertEqual(local.name, "本机版本")
    }

    func testDeletedIdentityBecomesConflictAndNamesAreNotIDs() throws {
        let recipe = try fixture().recipes[0]
        XCTAssertEqual(PrivateRecipeImport(current: [], incoming: [recipe], deletedIDs: [recipe.customId]).conflicts.count, 1)
        var another = recipe
        another.customId = "custom-another"
        XCTAssertEqual(PrivateRecipeImport(current: [recipe], incoming: [another]).additions.count, 1)
    }
}
