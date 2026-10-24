import CookCore
import XCTest
@testable import Cook

final class PersonalCatalogStoreTests: XCTestCase {
    func testFailedSavePreservesDiskAndState() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("catalog.json")
        let store = PersonalCatalogStore(url: url)
        try store.update { value in var next = value; next.items = [CatalogEntry(category: .tools, name: "塔吉锅")]; return next }
        let original = try Data(contentsOf: url)
        let failing = PersonalCatalogStore(url: url, write: { _, _ in throw CocoaError(.fileWriteOutOfSpace) })
        XCTAssertThrowsError(try failing.update { _ in PersonalCatalog() })
        XCTAssertEqual(failing.value, store.value)
        XCTAssertEqual(try Data(contentsOf: url), original)
        try Data("{broken".utf8).write(to: url)
        XCTAssertThrowsError(try store.update { _ in PersonalCatalog() })
        XCTAssertNotNil(store.loadError)
        XCTAssertEqual(try String(contentsOf: url), "{broken")
    }
}
