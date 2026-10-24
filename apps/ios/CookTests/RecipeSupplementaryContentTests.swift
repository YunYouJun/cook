import CookCore
import XCTest
@testable import Cook

@MainActor
final class RecipeSupplementaryContentTests: XCTestCase {
    private var steps: [RecipePresentation.Step] {
        RecipePresentation(text: RecipeText(blocks: [
            .heading("操作"), .list(ordered: true, start: 10, items: ["调味", "关火，盛盘"]),
        ])).steps
    }

    func testStandaloneReferenceFindsSourceStepIdentity() {
        for value in ["继续 第 10 步", "继续第10步", " 第 10 步。 "] {
            XCTAssertEqual(RecipeSupplementaryContent.destination(for: value, steps: steps)?.id, steps[0].id)
        }
        XCTAssertEqual(RecipeSupplementaryContent.destination(for: "继续第11步", steps: steps)?.id, steps[1].id)
    }

    func testMissingAmbiguousAndExistingMarkdownLinksRemainText() {
        for value in ["继续第0步", "继续第99步", "第99999999999999999999999步", "第10步和第11步", "第10步后再加盐", "[继续第10步](https://example.com)"] {
            XCTAssertNil(RecipeSupplementaryContent.destination(for: value, steps: steps))
        }
        let repeated = RecipePresentation(text: RecipeText(blocks: [
            .heading("操作"), .list(ordered: true, start: 10, items: ["调味"]),
            .heading("操作步骤"), .list(ordered: true, start: 10, items: ["盛盘"]),
        ])).steps
        XCTAssertNil(RecipeSupplementaryContent.destination(for: "继续第10步", steps: repeated))
    }
}
