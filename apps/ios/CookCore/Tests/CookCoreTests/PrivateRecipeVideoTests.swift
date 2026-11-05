import Foundation
import XCTest
@testable import CookCore

final class PrivateRecipeVideoTests: XCTestCase {
    private func recipe() -> PrivateRecipe {
        var value = PrivateRecipe(customId: "custom-video", name: "蒸蛋", stuff: ["鸡蛋"], tools: [], createdAt: 1000, updatedAt: 2000)
        value.video = PrivateRecipeVideo(fileExtension: "mp4", originalName: "做法.mp4", byteCount: 100, location: .iCloud)
        return value
    }

    func testPortableV1StillOmitsAttachmentAndNativePayloadRestoresIt() throws {
        let original = recipe()
        let portable = try PrivateRecipeFile(recipes: [original]).export()
        XCTAssertFalse(portable.contains("videos"))
        XCTAssertNil(try PrivateRecipeFile.parse(portable).recipes[0].video)
        XCTAssertNil(try PrivateRecipeNativeFile.parse(portable)[0].video)
        let stored = try PrivateRecipeNativeFile.export(recipes: [original])
        XCTAssertEqual(try PrivateRecipeNativeFile.parse(stored), [original])
        XCTAssertEqual(try PrivateRecipeFile.parse(stored).recipes[0].name, original.name, "Older builds must still be able to read recipe text")
    }

    func testV2BackupRestoresReferencesAndRejectsUnknownRecipesOrBadPaths() throws {
        let original = recipe()
        let backup = try PrivateRecipeNativeFile.export(recipes: [original], backup: true)
        XCTAssertEqual(try PrivateRecipeNativeFile.parse(backup), [original])
        XCTAssertThrowsError(try PrivateRecipeFile.parse(backup), "Web v1 must reject unsupported version rather than silently lose references")
        XCTAssertFalse(backup.contains("file://"))
        var object = try JSONSerialization.jsonObject(with: Data(backup.utf8)) as! [String: Any]
        object["videos"] = ["other-recipe": try JSONSerialization.jsonObject(with: JSONEncoder().encode(original.video!))]
        func text() throws -> String { String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self) }
        XCTAssertThrowsError(try PrivateRecipeNativeFile.parse(text()))
        object["videos"] = [original.customId: ["id": "../escape", "fileExtension": "mp4", "originalName": "v.mp4", "byteCount": 10, "location": "iCloud"]]
        XCTAssertThrowsError(try PrivateRecipeNativeFile.parse(text()))
        object = try JSONSerialization.jsonObject(with: Data(backup.utf8)) as! [String: Any]
        object["version"] = 3
        XCTAssertThrowsError(try PrivateRecipeNativeFile.parse(text()))
        object["version"] = true
        XCTAssertThrowsError(try PrivateRecipeNativeFile.parse(text()))
    }

    func testAttachmentValidationEnforcesSizeFormatAndLocalOwnership() throws {
        var video = try XCTUnwrap(recipe().video)
        video.byteCount = PrivateRecipeVideo.byteLimit
        XCTAssertNoThrow(try video.validated())
        video.byteCount += 1
        XCTAssertThrowsError(try video.validated())
        video.byteCount = 0
        XCTAssertThrowsError(try video.validated())
        video.byteCount = 100
        video.location = .local
        XCTAssertThrowsError(try video.validated())
        video.deviceID = UUID().uuidString.lowercased()
        XCTAssertNoThrow(try video.validated())
        video.location = .iCloud
        XCTAssertThrowsError(try video.validated())
        video.deviceID = nil
        video.fileExtension = "mkv"
        XCTAssertThrowsError(try video.validated())
    }

    func testImportPreservesExistingVideoForTextBackupAndDetectsDifferentAssociation() throws {
        let current = recipe()
        var plain = current
        plain.video = nil
        XCTAssertEqual(PrivateRecipeImport(current: [current], incoming: [plain]).duplicates.count, 1)
        XCTAssertEqual(PrivateRecipeImport(current: [plain], incoming: [current]).conflicts.count, 1)
        var changed = current
        changed.video?.id = UUID().uuidString.lowercased()
        let plan = PrivateRecipeImport(current: [current], incoming: [changed])
        XCTAssertEqual(plan.conflicts.count, 1)
        let copy = try plan.recordsToInsert(keepCopies: true, currentIDs: [current.customId])[0]
        XCTAssertEqual(copy.video, changed.video)
        XCTAssertEqual(copy.copiedFrom, current.customId)
    }
}
