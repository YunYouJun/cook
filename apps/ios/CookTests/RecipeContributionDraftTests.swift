import XCTest
@testable import Cook

final class RecipeContributionDraftTests: XCTestCase {
    func testRequiresNameAndWebSource() {
        var draft = RecipeContributionDraft(name: " \n ", source: "https://www.bilibili.com/video/BV123")
        XCTAssertFalse(draft.canSubmit(maxLength: 500))
        draft.name = "番茄炒蛋"
        for source in ["", "bilibili.com/video/BV123", "https://", "file:///recipe", "javascript:alert(1)", "https://example.com 做法"] {
            draft.source = source
            XCTAssertFalse(draft.canSubmit(maxLength: 500), source)
        }
        for source in [" https://www.bilibili.com/video/BV123\n", "http://example.com/recipe", "https://example.com/菜谱"] {
            draft.source = source
            XCTAssertTrue(draft.canSubmit(maxLength: 500), source)
        }
    }

    func testStructuredContentKeepsSourceAndTrimsFields() {
        let draft = RecipeContributionDraft(name: " 番茄炒蛋\n", source: " https://example.com/recipe?a=1&b=2 ", details: " 推荐给新手 \n")
        XCTAssertEqual(draft.note, "菜谱投稿\n菜名：番茄炒蛋\n做法链接：https://example.com/recipe?a=1&b=2\n补充说明：推荐给新手")
        XCTAssertFalse(RecipeContributionDraft(name: "菜名", source: "https://example.com").note.contains("补充说明"))
    }

    func testFullPayloadLimitNeverTruncatesSourceOrDetails() {
        var draft = RecipeContributionDraft(name: "番茄炒蛋", source: "https://example.com/recipe", details: "好")
        let padding = 500 - draft.note.count
        draft.details += String(repeating: "好", count: padding)
        XCTAssertEqual(draft.note.count, 500)
        XCTAssertTrue(draft.canSubmit(maxLength: 500))
        draft.details += "吃"
        XCTAssertFalse(draft.canSubmit(maxLength: 500))
        XCTAssertTrue(draft.note.hasSuffix("吃"))
        XCTAssertTrue(draft.note.contains(draft.source))
    }
}
