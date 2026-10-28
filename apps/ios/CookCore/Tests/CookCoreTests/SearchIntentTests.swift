@testable import CookCore
import XCTest

final class SearchIntentTests: XCTestCase {
    private func data() throws -> CookData {
        func read<T: Decodable>(_ type: T.Type, _ name: String) throws -> T {
            try JSONDecoder().decode(type, from: Data(contentsOf: SharedFixtureTests.dataDirectory.appendingPathComponent(name)))
        }
        return try CookData(
            meta: RecipeMeta(schemaVersion: 1, dataVersion: "intent-test-data", chunked: false, total: 0, chunkSize: nil, chunks: nil),
            recipes: [], incompatibleRules: [], aliases: read([String: String].self, "aliases.json"),
            food: read(FoodCatalog.self, "food.json"), pantry: read(Pantry.self, "pantry.json"),
            query: read(QueryRules.self, "query.json")
        )
    }

    private func parser(goals: [DietaryGoal] = [.lessAddedSugar, .balancedPairing]) throws -> LocalSearchIntentParser {
        try LocalSearchIntentParser(data: data(), availableGoals: goals)
    }

    func testManualExpectedExampleKeepsGoalAndAllExplicitConditions() throws {
        let result = try parser().parse("有鸡蛋和西葫芦，不要辣，想减少添加糖")
        XCTAssertEqual(result.query.stuff, ["鸡蛋", "西葫芦"])
        XCTAssertTrue(result.query.noSpicy)
        XCTAssertEqual(result.goal, .lessAddedSugar)
        XCTAssertTrue(result.unresolved.isEmpty)
        XCTAssertTrue(result.query.unknown.isEmpty)
        XCTAssertEqual(result.source, .local)
        XCTAssertEqual(result.dataVersion, "intent-test-data")
    }

    func testAliasesNegationAndAbsentIngredientsKeepExistingParserSemantics() throws {
        let result = try parser().parse("西红柿和洋葱，洋葱不要，没有鸡蛋，用电饭锅做个简单的")
        XCTAssertEqual(result.query.stuff, ["番茄"])
        XCTAssertEqual(result.query.avoid, ["洋葱"])
        XCTAssertFalse(result.query.stuff.contains("鸡蛋"))
        XCTAssertEqual(result.query.tool, "电饭煲")
        XCTAssertEqual(result.query.difficulty, ["简单"])
        XCTAssertTrue(result.unresolved.isEmpty)
    }

    func testUnknownIngredientsAndUnverifiableConstraintsRemainPending() throws {
        for text in ["有火星菜", "鸡蛋，十分钟做好", "西葫芦，要清爽一点", "鸡蛋，给小朋友吃"] {
            let result = try parser().parse(text)
            XCTAssertFalse(result.unresolved.isEmpty, text)
            XCTAssertTrue(result.query.unknown.isEmpty, text)
            XCTAssertFalse(result.query.stuff.contains("火星菜"))
        }
        XCTAssertTrue(try parser().parse("十分钟做好").query.difficulty.isEmpty)
    }

    func testUnsupportedHealthRequestsNeverBecomeGoalsOrUnknownIngredients() throws {
        for text in ["控制体重", "生酮", "孕期饮食", "痘痘", "低糖", "糖尿病控糖", "不想减少添加糖", "减少添加糖不要"] {
            let result = try parser().parse(text)
            XCTAssertNil(result.goal, text)
            XCTAssertTrue(result.query.stuff.isEmpty, text)
            XCTAssertTrue(result.query.unknown.isEmpty, text)
            XCTAssertFalse(result.unresolved.isEmpty, text)
        }
    }

    func testNeedAndWantNegationsNeverActivateTheMentionedGoal() throws {
        for goal in [DietaryGoal.lessAddedSugar, .balancedPairing] {
            for negative in ["不需要", "不考虑", "不打算", "无需", "无须", "不必", "不希望", "不要求", "不准备", "不追求", "不选择", "不采用"] {
                for text in [negative + goal.title, goal.title + negative, negative + "再" + goal.title, goal.title + "这个目标目前" + negative] {
                    let result = try parser().parse(text)
                    XCTAssertNil(result.goal, text)
                    XCTAssertFalse(result.unresolved.isEmpty, text)
                }
            }
        }
        for text in ["减少添加糖和不需要减少添加糖", "减少添加糖，不需要减少添加糖", "不需要减少添加糖和均衡搭配", "减少添加糖和均衡搭配不需要"] {
            let result = try parser().parse(text)
            XCTAssertNil(result.goal, text)
            XCTAssertFalse(result.unresolved.isEmpty, text)
        }
        let separate = try parser().parse("不需要减少添加糖，而是均衡搭配")
        XCTAssertEqual(separate.goal, .balancedPairing)
    }

    func testUnavailableAndMultipleGoalsNeedConfirmation() throws {
        let unavailable = try parser(goals: []).parse("鸡蛋，减少添加糖")
        XCTAssertEqual(unavailable.query.stuff, ["鸡蛋"])
        XCTAssertNil(unavailable.goal)
        XCTAssertEqual(unavailable.unresolved, ["减少添加糖"])
        let combined = try parser().parse("减少添加糖和均衡搭配")
        XCTAssertNil(combined.goal)
        XCTAssertFalse(combined.unresolved.isEmpty)
    }

    func testNegatedMethodIsPendingRatherThanPositiveFilter() throws {
        let result = try parser().parse("有鸡蛋，不要炒，不用电饭煲")
        XCTAssertEqual(result.query.stuff, ["鸡蛋"])
        XCTAssertNil(result.query.tool)
        XCTAssertTrue(result.query.methodGroups.isEmpty)
        XCTAssertFalse(result.unresolved.isEmpty)
    }

    func testMultipleToolsRequireAnExplicitChoice() throws {
        for text in ["用炒锅和电饭煲", "用炒锅，用电饭煲"] {
            let result = try parser().parse(text)
            XCTAssertNil(result.query.tool)
            XCTAssertFalse(result.unresolved.isEmpty)
        }
    }

    func testLocallyProducedDraftPassesTheSameValidatorAsRemoteResults() throws {
        let validator = try SearchIntentValidator(data: data(), availableGoals: [.lessAddedSugar, .balancedPairing])
        for text in ["有鸡蛋和西葫芦，不要辣，想减少添加糖", "西红柿和洋葱，洋葱不要", "生酮", "有火星菜"] {
            let draft = try parser().parse(text)
            XCTAssertEqual(try validator.validate(draft, expectedSource: .local), draft)
            XCTAssertEqual(try validator.decode(JSONEncoder().encode(draft), expectedSource: .local), draft)
        }
    }

    func testWireContractRejectsRootAndNestedUnknownFieldsAndMissingRequiredFields() throws {
        let validator = try SearchIntentValidator(data: data(), availableGoals: [.lessAddedSugar])
        let good = try parser().parse("鸡蛋")
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(good)) as? [String: Any])
        for key in ["recipeIDs", "actions", "reason"] {
            var changed = json
            changed[key] = []
            XCTAssertThrowsError(try validator.decode(JSONSerialization.data(withJSONObject: changed)))
        }
        var changed = json
        var query = try XCTUnwrap(changed["query"] as? [String: Any])
        query["recipeIDs"] = [333]
        changed["query"] = query
        XCTAssertThrowsError(try validator.decode(JSONSerialization.data(withJSONObject: changed)))
        for key in ["source", "query", "unresolved", "dataVersion", "policyVersion", "schemaVersion"] {
            var missing = json
            missing.removeValue(forKey: key)
            XCTAssertThrowsError(try validator.decode(JSONSerialization.data(withJSONObject: missing)), key)
        }
        for key in ["stuff", "unknown", "avoid", "noSpicy", "difficulty", "methodGroups"] {
            var missing = json
            var nested = try XCTUnwrap(json["query"] as? [String: Any])
            nested.removeValue(forKey: key)
            missing["query"] = nested
            XCTAssertThrowsError(try validator.decode(JSONSerialization.data(withJSONObject: missing)), key)
        }
    }

    func testIllegalValuesVersionsAndSourcesFailClosed() throws {
        let validator = try SearchIntentValidator(data: data(), availableGoals: [.lessAddedSugar])
        let good = try parser().parse("鸡蛋")
        let edits: [(inout SearchIntent) -> Void] = [
            { $0.schemaVersion = 2 }, { $0.dataVersion = "stale" }, { $0.policyVersion = 2 },
            { $0.query.stuff = ["西红柿"] }, { $0.query.avoid = ["火星菜"] }, { $0.query.tool = "火星锅" },
            { $0.query.difficulty = ["五分钟"] }, { $0.query.methodGroups = ["焯"] },
            { $0.query.unknown = ["秋葵"] }, { $0.query.stuff = ["鸡蛋", "鸡蛋"] },
            { $0.query.avoid = ["鸡蛋"] }, { $0.goal = .weightAware },
            { $0.unresolved = [""] }, { $0.unresolved = [String(repeating: "字", count: 501)] },
            { $0.unresolved = ["待确认", "待确认"] },
        ]
        for edit in edits {
            var changed = good
            edit(&changed)
            XCTAssertThrowsError(try validator.validate(changed))
        }
        XCTAssertThrowsError(try validator.validate(good, expectedSource: .deepseek))
        XCTAssertThrowsError(try validator.decode(Data(repeating: 32, count: 32_769)))
    }

    func testInvalidJSONUnknownGoalAndWrongTypesAreRecoverable() throws {
        let validator = try SearchIntentValidator(data: data(), availableGoals: [.lessAddedSugar])
        for text in ["not JSON", "[]", "null", "{\"schemaVersion\":true}"] {
            XCTAssertThrowsError(try validator.decode(Data(text.utf8)))
        }
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(try parser().parse("鸡蛋"))) as? [String: Any])
        json["goal"] = "ketogenic"
        XCTAssertThrowsError(try validator.decode(JSONSerialization.data(withJSONObject: json)))
        json["goal"] = NSNull()
        var query = try XCTUnwrap(json["query"] as? [String: Any])
        query["noSpicy"] = "yes"
        json["query"] = query
        XCTAssertThrowsError(try validator.decode(JSONSerialization.data(withJSONObject: json)))
    }

    func testOverlongInputDoesNotProducePartialConditions() throws {
        let result = try parser().parse("鸡蛋" + String(repeating: "字", count: 1_000))
        XCTAssertTrue(result.isEmpty)
        XCTAssertEqual(result.unresolved.count, 1)
    }
}
