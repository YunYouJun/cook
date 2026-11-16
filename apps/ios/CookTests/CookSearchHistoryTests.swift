import XCTest
@testable import Cook

final class CookSearchHistoryTests: XCTestCase {
    func testRecencyDeduplicationAndLimitSurviveReload() {
        let suite = "cook.search.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let history = CookSearchHistory(defaults: defaults)
        history.record("  \n ")
        XCTAssertTrue(history.entries.isEmpty)
        for index in 0..<12 { history.record("菜名\(index)") }
        history.record("  菜名5 \n")
        XCTAssertEqual(history.entries, ["菜名5", "菜名11", "菜名10", "菜名9", "菜名8", "菜名7", "菜名6", "菜名4", "菜名3", "菜名2"])
        XCTAssertEqual(CookSearchHistory(defaults: defaults).entries, history.entries)
        history.record("ABC")
        history.record("ａｂｃ")
        XCTAssertEqual(history.entries.filter { $0 == "ABC" || $0 == "ａｂｃ" }, ["ａｂｃ"])
    }

    func testDeleteAndClearArePersistentAndDoNotTouchOtherPreferences() {
        let suite = "cook.search.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(true, forKey: "unrelated.preference")
        let history = CookSearchHistory(defaults: defaults)
        history.record("蒸蛋")
        history.record("鸡蛋，不要辣")
        history.remove("蒸蛋")
        XCTAssertEqual(CookSearchHistory(defaults: defaults).entries, ["鸡蛋，不要辣"])
        history.clear()
        XCTAssertTrue(CookSearchHistory(defaults: defaults).entries.isEmpty)
        XCTAssertNil(defaults.object(forKey: CookSearchHistory.storageKey))
        XCTAssertTrue(defaults.bool(forKey: "unrelated.preference"))
    }

    func testLoadNormalizesStoredEntriesAndMemoryStoresAreIsolated() {
        let suite = "cook.search.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(["  蒸蛋 ", "", "蒸蛋", "土豆\n 鸡蛋"], forKey: CookSearchHistory.storageKey)
        XCTAssertEqual(CookSearchHistory(defaults: defaults).entries, ["蒸蛋", "土豆 鸡蛋"])
        let memory = CookSearchHistory()
        memory.record("番茄")
        XCTAssertTrue(CookSearchHistory().entries.isEmpty)
        XCTAssertEqual(CookSearchHistory(defaults: defaults).entries, ["蒸蛋", "土豆 鸡蛋"])
    }
}
