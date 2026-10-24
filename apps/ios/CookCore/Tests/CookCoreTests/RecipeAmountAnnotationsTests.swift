@testable import CookCore
import XCTest

final class RecipeAmountAnnotationsTests: XCTestCase {
    private func library() throws -> RecipeTextLibrary {
        try XCTUnwrap(RecipeTextLibrary.load(from: SharedFixtureTests.dataDirectory))
    }

    private func annotated(_ quantities: [[String: Any]], steps: [String], usages: [[String: Any]] = []) throws -> RecipeText {
        var text = RecipeText(blocks: [.heading("计算"), .list(ordered: false, start: 1, items: ["原文数量"]), .heading("操作"), .list(ordered: true, start: 1, items: steps)])
        let evidence: [[String: Any]] = [["block": 1, "item": 0, "text": "原文数量"]]
        let rows = quantities.map { input -> [String: Any] in
            var row = input
            row["evidence"] = evidence
            if row["aliases"] == nil { row["aliases"] = [] as [String] }
            if row["conditional"] == nil { row["conditional"] = false }
            return row
        }
        let uses = usages.map { input -> [String: Any] in
            var use = input
            let index = use.removeValue(forKey: "step") as! Int
            let anchor: [String: Any] = ["block": 3, "item": index, "text": steps[index]]
            use["anchor"] = anchor
            use["evidence"] = [anchor]
            if use["conditional"] == nil { use["conditional"] = false }
            return use
        }
        let entry: [String: Any] = ["sourceFingerprint": RecipeAmountAnnotations.fingerprint(text), "servingEvidence": [], "quantities": rows, "stepSections": [], "usages": uses, "blockedIngredients": []]
        text.amountAnnotations = try JSONDecoder().decode(RecipeAmountAnnotations.Entry.self, from: JSONSerialization.data(withJSONObject: entry))
        return text
    }

    private func literal(_ lower: Double, _ unit: String, _ upper: Double? = nil) -> [String: Any] {
        var value: [String: Any] = ["op": "literal", "lower": lower, "unit": unit]
        if let upper { value["upper"] = upper }
        return value
    }

    private func plan(_ text: RecipeText, servings: Int) -> [RecipeInstructionAmounts] {
        let presentation = RecipePresentation(text: text)
        let amounts = RecipeAmounts(rows: presentation.allQuantities, servings: servings)
        let plan = RecipeStepUsagePlan(steps: presentation.steps, amounts: amounts, annotations: presentation.amountAnnotations)
        return presentation.steps.map { plan.instruction(for: $0)! }
    }

    func testAllEntriesLoadAndAllAnnotatedFormulasResolveForEverySupportedServing() throws {
        let data = try SharedFixtureTests.loadData()
        let library = try library()
        var count = 0
        for recipe in data.recipes {
            guard let source = library.text(for: recipe.id) else { continue }
            count += 1
            let presentation = RecipePresentation(text: source)
            XCTAssertNotNil(presentation.amountAnnotations, "\(recipe.id) \(recipe.name)")
            for servings in RecipeAmounts.servingRange {
                let amounts = RecipeAmounts(rows: presentation.allQuantities, servings: servings)
                for row in amounts.annotationRows {
                    XCTAssertTrue(amounts.amount(for: row).isResolved, "\(recipe.id) \(row.name) \(servings)")
                }
            }
        }
        XCTAssertEqual(count, library.count)
        XCTAssertEqual(count, 232)
    }

    func testRealRecipeAdditionBatchRangeFloorAndRoundedDependency() throws {
        let library = try library()
        func amount(_ id: Int, _ name: String, _ servings: Int = 3) throws -> RecipeAmounts.Amount {
            let presentation = RecipePresentation(text: try XCTUnwrap(library.text(for: id)))
            return try XCTUnwrap(RecipeAmounts(rows: presentation.allQuantities, servings: servings).amount(for: name))
        }
        XCTAssertEqual(try amount(668, "醋").measurement?.text, "19.5 ml")
        XCTAssertEqual(try amount(668, "盐").measurement?.text, "1 g")
        XCTAssertEqual(try amount(668, "蚝油").measurement?.text, "5 ml")
        XCTAssertEqual(try amount(401, "食用油").measurement?.text, "100 ml", "固定酱汁批次不能乘面条份数")
        XCTAssertEqual(try amount(401, "干面条").measurement?.text, "240 g")
        XCTAssertEqual(try amount(259, "食用油").measurement?.text, "30–45 ml")
        XCTAssertEqual(try amount(225, "酱油").measurement?.text, "15 g")
        XCTAssertEqual(try amount(225, "酱油").note, "已向上取整")
        XCTAssertEqual(try amount(834, "鸡蛋").measurement?.text, "4 个")
        XCTAssertEqual(try amount(834, "鸡蛋", 1).measurement?.text, "1 个")
        XCTAssertEqual(try amount(775, "盐").measurement?.text, "24 g")
        XCTAssertEqual(try amount(705, "盐").measurement?.text, "3.75 ml", "仅用本菜谱量匙体积定义，不转成克")
        XCTAssertNil(try amount(812, "冬笋").measurement, "可选食材不可作为无条件消耗")
    }

    func testExplicitHalfGarlicAndFlourDependentStepKeepAllSourceWords() throws {
        let library = try library()
        let garlic = try XCTUnwrap(library.text(for: 259))
        let result = plan(garlic, servings: 3)
        XCTAssertTrue(result[3].text.contains("一半的蒜末（本步 大蒜 6 瓣）"))
        XCTAssertTrue(result[5].text.contains("剩下的一半蒜末（6 瓣）"))
        let eggplant = plan(try XCTUnwrap(library.text(for: 225)), servings: 3)
        XCTAssertTrue(eggplant[5].text.contains("（面粉 / 20）克（本步 盐 15.75 g）"), eggplant[5].text)
        XCTAssertEqual(garlic.blocks, library.text(for: 259)?.blocks)
    }

    func testReviewedSingleUsesAndAmbiguousSauceBranch() throws {
        let library = try library()
        let chicken = plan(try XCTUnwrap(library.text(for: 701)), servings: 3)
        XCTAssertTrue(chicken[0].text.contains("生抽（45 ml）"), chicken[0].text)
        XCTAssertTrue(chicken[0].text.contains("食用油（30 ml）"), chicken[0].text)
        XCTAssertTrue(chicken[1].text.contains("冬瓜（900 g）"), chicken[1].text)
        XCTAssertTrue(chicken[0].text.contains("20 分钟"), "时长不随份数变化")
        let shrimp = plan(try XCTUnwrap(library.text(for: 789)), servings: 3)
        XCTAssertTrue(shrimp[0].text.contains("1 cm"), "切口尺寸不随份数变化")
        XCTAssertTrue(shrimp[1].text.contains("蒜蓉酱（150 g）"), shrimp[1].text)
        XCTAssertTrue(shrimp[3].text.contains("生抽（15 ml）"), shrimp[3].text)
        let eggs = plan(try XCTUnwrap(library.text(for: 853)), servings: 3)
        XCTAssertTrue(eggs[0].text.contains("鸡蛋（9 个）"), eggs[0].text)
        let riceSource = try XCTUnwrap(library.text(for: 828))
        let rice = plan(riceSource, servings: 3)
        XCTAssertEqual(rice[0].text, "碗底铺猪油")
        XCTAssertEqual(rice[2].text, "淋上酱油与蚝油")
        XCTAssertTrue(rice[0].hasUnresolvedFormula)
        XCTAssertTrue(rice[2].hasUnresolvedFormula)
    }

    func testUnknownRepeatedAndOptionalUsesNeverInventRemainders() throws {
        for (conditional, initial) in [(false, literal(1, "g", 2)), (false, literal(1, "茶匙")), (false, literal(7, "g")), (true, literal(1, "g"))] {
            let text = try annotated([["name": "盐", "formula": literal(5, "g")]], steps: ["按原文加入第一份盐", "加入余下的盐"], usages: [
                ["step": 0, "ingredient": "盐", "kind": "amount", "target": "第一份盐", "formula": initial, "conditional": conditional],
                ["step": 1, "ingredient": "盐", "kind": "remaining", "target": "余下的盐"],
            ])
            XCTAssertEqual(plan(text, servings: 3)[1].text, "加入余下的盐")
            XCTAssertTrue(plan(text, servings: 3)[1].hasUnresolvedFormula)
        }
        let unknown = try annotated([["name": "盐", "formula": literal(5, "g")]], steps: ["分两次加入盐", "加入余下的盐"], usages: [
            ["step": 0, "ingredient": "盐", "kind": "unknown"],
            ["step": 1, "ingredient": "盐", "kind": "remaining", "target": "余下的盐"],
        ])
        XCTAssertEqual(plan(unknown, servings: 3).map(\.text), ["分两次加入盐", "加入余下的盐"])
    }

    func testPartialLedgerCannotHideAnUnannotatedUse() throws {
        for steps in [["加入第一份盐", "再加入少许盐", "加入余下的盐"],
                      ["加入第一份盐，再加入少许盐", "加入余下的盐"]] {
            let text = try annotated([["name": "盐", "formula": literal(5, "g")]], steps: steps, usages: [
                ["step": 0, "ingredient": "盐", "kind": "amount", "target": "第一份盐", "formula": literal(1, "g")],
                ["step": steps.count - 1, "ingredient": "盐", "kind": "remaining", "target": "余下的盐"],
            ])
            XCTAssertEqual(plan(text, servings: 3).last?.text, "加入余下的盐")
            XCTAssertEqual(plan(text, servings: 3).last?.hasUnresolvedFormula, true)
        }
    }

    func testPreparationReferenceDoesNotConsumeIngredientTwice() throws {
        let text = try annotated([["name": "大蒜", "formula": literal(4, "瓣")]],
            steps: ["大蒜切末，分为两等份备用", "加入一半大蒜", "加入剩余大蒜"], usages: [
                ["step": 0, "ingredient": "大蒜", "kind": "reference", "target": "大蒜"],
                ["step": 1, "ingredient": "大蒜", "kind": "amount", "target": "一半大蒜", "formula": literal(2, "瓣")],
                ["step": 2, "ingredient": "大蒜", "kind": "remaining", "target": "剩余大蒜"],
            ])
        XCTAssertEqual(plan(text, servings: 3).last?.text, "加入剩余大蒜（2 瓣）")
    }

    func testSingleServingUsesMatchPreparationForEverySupportedServing() throws {
        let library = try library()
        for servings in RecipeAmounts.servingRange {
            let broccoli = plan(try XCTUnwrap(library.text(for: 237)), servings: servings)
            let sauce = broccoli[6].text
            for (name, amount, unit) in [("生抽", 10, "ml"), ("蚝油", 5, "ml"), ("白糖", 2, "g")] {
                XCTAssertTrue(sauce.contains("本步 \(name) \(amount * servings) \(unit)"), sauce)
            }
            XCTAssertTrue(sauce.contains("30 ml 饮用水"), "未给份数依据的水量不猜测")
            let noodles = plan(try XCTUnwrap(library.text(for: 401)), servings: servings)
            XCTAssertTrue(noodles.contains { $0.text.contains("本步 干面条 \(80 * servings) g") })
            let potato = plan(try XCTUnwrap(library.text(for: 259)), servings: servings)
            XCTAssertTrue(potato[5].text.contains("本步 盐 \(2 * servings) g"), potato[5].text)
            XCTAssertTrue(potato[5].text.contains("剩下的一半蒜末（\(2 * servings) 瓣）"), potato[5].text)
        }
    }

    func testStructuredRemainderConvertsCompatibleUnitsAndRetainsTotalRange() throws {
        let text = try annotated([["name": "水", "formula": literal(1, "L", 2)]], steps: ["倒入第一份水", "倒入剩余水"], usages: [
            ["step": 0, "ingredient": "水", "kind": "amount", "target": "第一份水", "formula": literal(250, "ml")],
            ["step": 1, "ingredient": "水", "kind": "remaining", "target": "剩余水"],
        ])
        XCTAssertEqual(plan(text, servings: 3)[1].text, "倒入剩余水（0.75–1.75 L）")
    }

    func testSourceAnchorAndSemanticallyInvalidAnnotationsFallBackAsOneRecipe() throws {
        var text = try annotated([["name": "盐", "formula": literal(5, "g")]], steps: ["加入盐"])
        text.blocks[1] = .list(ordered: false, start: 1, items: ["原文发生变化"])
        XCTAssertNil(RecipePresentation(text: text).amountAnnotations)
        XCTAssertEqual(plan(text, servings: 3)[0].text, "加入盐")
        for formula in [
            ["op": "add", "args": [literal(1, "g"), literal(1, "ml")]],
            ["op": "multiply", "args": [literal(1, "g"), literal(1, "g")]],
            ["op": "divide", "args": [literal(1, "g"), ["op": "scalar", "lower": 0]]],
            ["op": "reference", "name": "盐"],
            ["op": "reference", "name": "未记录材料"],
            literal(-1, "g"),
        ] as [[String: Any]] {
            let source = try annotated([["name": "盐", "formula": formula]], steps: ["加入盐"])
            XCTAssertNil(RecipePresentation(text: source).amountAnnotations, "\(formula)")
            XCTAssertEqual(plan(source, servings: 3)[0].text, "加入盐")
        }
        let repeated = try annotated([["name": "盐", "formula": literal(5, "g")]], steps: ["盐与盐"], usages: [["step": 0, "ingredient": "盐", "kind": "total", "target": "盐"]])
        XCTAssertNil(RecipePresentation(text: repeated).amountAnnotations)
    }

    func testConditionalDependenciesRemainConditionalThroughArithmeticAndUsage() throws {
        let dependent: [String: Any] = ["op": "multiply", "args": [["op": "count", "name": "肉", "unit": "g"], literal(0.01, "g")]]
        let text = try annotated([
            ["name": "肉", "formula": literal(100, "g"), "conditional": true],
            ["name": "盐", "formula": literal(5, "g")],
            ["name": "腌料", "formula": dependent],
            ["name": "稀释腌料", "formula": ["op": "divide", "args": [dependent, ["op": "scalar", "lower": 2]]]],
            ["name": "加盐腌料", "formula": ["op": "add", "args": [dependent, literal(1, "g")]]],
        ], steps: ["若选用肉，加入第一份盐", "加入余下的盐"], usages: [
            ["step": 0, "ingredient": "盐", "kind": "amount", "target": "第一份盐", "formula": dependent],
            ["step": 1, "ingredient": "盐", "kind": "remaining", "target": "余下的盐"],
        ])
        let presentation = RecipePresentation(text: text)
        let amounts = RecipeAmounts(rows: presentation.allQuantities, servings: 3)
        XCTAssertTrue(try XCTUnwrap(amounts.amount(for: "腌料")).isResolved)
        for name in ["腌料", "稀释腌料", "加盐腌料"] {
            XCTAssertTrue(try XCTUnwrap(amounts.amount(for: name)).isResolved)
            XCTAssertNil(amounts.amount(for: name)?.measurement)
        }
        XCTAssertTrue(amounts.isConditional(try JSONDecoder().decode(RecipeAmountAnnotations.Formula.self, from: JSONSerialization.data(withJSONObject: dependent))))
        XCTAssertEqual(plan(text, servings: 3)[1].text, "加入余下的盐")
        XCTAssertTrue(plan(text, servings: 3)[1].hasUnresolvedFormula)
    }

    func testIdenticalStepTextUsesItsBlockAndItemAnchorOnlyOnce() throws {
        let text = try annotated([["name": "盐", "formula": literal(10, "g")]], steps: ["加入盐", "加入盐", "加入剩余盐"], usages: [
            ["step": 0, "ingredient": "盐", "kind": "amount", "target": "盐", "formula": literal(1, "g")],
            ["step": 1, "ingredient": "盐", "kind": "amount", "target": "盐", "formula": literal(2, "g")],
            ["step": 2, "ingredient": "盐", "kind": "remaining", "target": "剩余盐"],
        ])
        let result = plan(text, servings: 3)
        XCTAssertEqual(result[0].text, "加入盐（本步 盐 1 g）")
        XCTAssertEqual(result[1].text, "加入盐（本步 盐 2 g）")
        XCTAssertEqual(result[2].text, "加入剩余盐（7 g）")
        XCTAssertEqual(Set(RecipePresentation(text: text).steps.map(\.id)).count, 3)
    }

    func testMissingCorruptAndFutureSchemaAnnotationFilesFallBack() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("cook-amount-tests-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        XCTAssertNil(RecipeAmountAnnotations.load(from: directory))
        for bytes in ["{invalid", #"{"schemaVersion":2,"recipes":{}}"#] {
            try Data(bytes.utf8).write(to: directory.appendingPathComponent(RecipeAmountAnnotations.fileName))
            XCTAssertNil(RecipeAmountAnnotations.load(from: directory))
        }
    }

    func testAllExistingStepIdentitiesSurviveAddedSectionsAndServingChanges() throws {
        let library = try library()
        let data = try SharedFixtureTests.loadData()
        for recipe in data.recipes {
            guard var source = library.text(for: recipe.id) else { continue }
            let current = RecipePresentation(text: source)
            source.amountAnnotations = nil
            let original = RecipePresentation(text: source)
            for (block, oldSteps) in original.stepsByBlock {
                XCTAssertEqual(oldSteps.map(\.id), current.stepsByBlock[block]?.map(\.id), "\(recipe.id) block \(block)")
            }
            let savedCompleted = Set(current.steps.prefix(2).map(\.id))
            for servings in [1, 3, 20] {
                let amounts = RecipeAmounts(rows: current.allQuantities, servings: servings)
                _ = RecipeStepUsagePlan(steps: current.steps, amounts: amounts, annotations: current.amountAnnotations)
                XCTAssertEqual(savedCompleted, Set(current.steps.prefix(2).map(\.id)))
            }
        }
    }
}
