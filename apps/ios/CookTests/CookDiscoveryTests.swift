import CookCore
import SwiftData
import XCTest
@testable import Cook

@MainActor
final class CookDiscoveryTests: XCTestCase {
    private lazy var container = Persistence.makeContainer(inMemory: true)

    private var recipes: [Recipe] {
        [
            Recipe(id: 1, name: "番茄蛋汤", stuff: ["番茄", "鸡蛋"], difficulty: "简单", tags: ["汤", "家常菜"], methods: ["煮"], tools: ["一口大锅"]),
            Recipe(id: 2, name: "辣椒蛋汤", stuff: ["鸡蛋", "辣椒"], difficulty: "简单", tags: ["汤", "辣"], methods: ["煮"], tools: ["一口大锅"]),
            Recipe(id: 3, name: "番茄炒蛋", stuff: ["番茄", "鸡蛋"], difficulty: "普通", tags: ["家常菜"], methods: ["炒"], tools: ["一口大锅"]),
            Recipe(id: 4, name: "蒸蛋", stuff: ["鸡蛋"], difficulty: "简单", methods: ["蒸"], tools: ["电饭煲"]),
            Recipe(id: 5, name: "蛋花汤", stuff: ["鸡蛋"], tags: ["汤"], methods: ["煮"], tools: ["一口大锅"]),
            Recipe(id: 6, name: "葱花蛋汤", stuff: ["鸡蛋", "洋葱"], difficulty: "简单", tags: ["汤"], methods: ["煮"], tools: ["一口大锅"]),
            Recipe(id: 7, name: "牛肉汤", stuff: ["牛肉"], difficulty: "简单", tags: ["汤"], methods: ["煮"], tools: ["一口大锅"]),
            Recipe(id: 8, name: "焖鸡", stuff: ["鸡肉"], difficulty: "普通", methods: ["炖/焖"], tools: ["一口大锅"]),
        ]
    }

    func testEasyAndHomeScenesUseRecordedDifficultyAndTags() {
        XCTAssertEqual(CookDiscovery.filter(recipes, using: CookDiscoveryScene.easy.filters).map(\.id), [1, 2, 4, 6, 7])
        XCTAssertEqual(CookDiscovery.filter(recipes, using: CookDiscoveryScene.home.filters).map(\.id), [1, 3])
        XCTAssertTrue(CookDiscoveryFilters().isEmpty)
        XCTAssertFalse(CookDiscoveryScene.easy.filters.isEmpty)
        XCTAssertFalse(CookDiscoveryScene.home.filters.isEmpty)
        XCTAssertEqual(CookDiscovery.reason(for: recipes[0], filters: CookDiscoveryScene.easy.filters), "难度标为「简单」")
        XCTAssertFalse(CookDiscovery.reason(for: recipes[4], filters: CookDiscoveryScene.easy.filters).contains("简单"))
    }

    func testSoupAndOtherMethodsUseSharedGroupsWithoutInferringFromNames() {
        XCTAssertEqual(CookDiscovery.filter(recipes, using: CookDiscoveryScene.soup.filters).map(\.id), [1, 2, 5, 6, 7])
        let nameOnly = Recipe(id: 20, name: "名字有汤但没记录汤标签", stuff: ["鸡蛋"], methods: ["煮"])
        XCTAssertTrue(CookDiscovery.filter([nameOnly], using: CookDiscoveryScene.soup.filters).isEmpty)
        XCTAssertTrue(CookDiscovery.filter(recipes, using: CookDiscoveryFilters(methodGroup: "炖煮")).contains { $0.id == 8 })
        XCTAssertTrue(CookDiscovery.filter(recipes, using: CookDiscoveryFilters(methodGroup: "未知分组")).isEmpty)
        XCTAssertEqual(CookDiscovery.reason(for: recipes[0], filters: CookDiscoveryScene.soup.filters), "标有「汤」标签")
        XCTAssertEqual(CookDiscovery.reason(for: recipes[7], filters: CookDiscoveryFilters(methodGroup: "炖煮")), "做法记录为「炖」")
    }

    func testTemporaryConditionsIntersectAndReasonsOnlyDescribeRecordedFields() {
        let filters = CookDiscoveryFilters(easyOnly: true, methodGroup: "汤羹", tag: "家常菜")
        XCTAssertEqual(CookDiscovery.filter(recipes, using: filters).map(\.id), [1])
        XCTAssertEqual(CookDiscovery.reason(for: recipes[0], filters: filters), "难度标为「简单」 · 标有「汤」标签 · 标有「家常菜」标签")
        let unknown = Recipe(id: 21, name: "资料未全", stuff: [])
        XCTAssertEqual(CookDiscovery.reason(for: unknown, filters: filters), "完整用料请查看做法")
    }

    func testNextBatchAvoidsPreviousDishesWhenEnoughRemain() {
        let first = CookDiscovery.sample(recipes)
        let next = CookDiscovery.sample(recipes, avoiding: first)
        XCTAssertEqual(first.count, 3)
        XCTAssertEqual(next.count, 3)
        XCTAssertTrue(Set(first.map(\.name)).isDisjoint(with: Set(next.map(\.name))))
        XCTAssertEqual(Set(next.map(\.name)).count, 3)
    }

    func testFewCandidatesAreKeptAndDuplicateNamesDoNotFillTwoPlaces() {
        let few = Array(recipes.prefix(2))
        let first = CookDiscovery.sample(few)
        XCTAssertEqual(Set(first.map(\.id)), Set([1, 2]))
        XCTAssertEqual(Set(CookDiscovery.sample(few, avoiding: first).map(\.id)), Set([1, 2]))
        var duplicate = recipes[0]
        duplicate.id = 99
        XCTAssertEqual(CookDiscovery.sample([recipes[0], duplicate, recipes[1]]).count, 2)
        let next = CookDiscovery.sample(Array(recipes.prefix(4)), avoiding: Array(recipes.prefix(3)))
        XCTAssertEqual(next.count, 3)
        XCTAssertEqual(next.first?.id, 4)
        XCTAssertEqual(Set(next.map(\.name)).count, 3)
        XCTAssertTrue(CookDiscovery.sample([], avoiding: first).isEmpty)
        XCTAssertTrue(CookDiscovery.sample(recipes, count: 0).isEmpty)
    }

    func testExplorationUsesReviewedSessionCandidatesWithoutChangingPreferencesOrSelections() throws {
        let store = try RecipeStore.loadBundled(inMemory: true)
        let defaultsName = "cook.discovery.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsName))
        defer { defaults.removePersistentDomain(forName: defaultsName) }
        let context = container.mainContext
        let session = CookSession(defaultPantry: store.data.pantry.defaults, context: context, dietaryDefaults: defaults)
        session.selectDietaryGoal(.balancedPairing)
        session.toggleAvoid("香菜")
        session.setNoSpicy(true)
        let reviewed = session.candidates(in: store)
        let anchor = try XCTUnwrap(reviewed.first(where: { !$0.stuff.isEmpty }))
        session.addStuff(try XCTUnwrap(anchor.stuff.first))
        let candidates = session.candidates(in: store)
        XCTAssertFalse(candidates.isEmpty)
        let before = session.options
        let preferenceCount = try context.fetchCount(FetchDescriptor<Preferences>())
        let pendingChanges = context.hasChanges

        for scene in CookDiscoveryScene.allCases {
            let filtered = CookDiscovery.filter(candidates, using: scene.filters)
            let first = CookDiscovery.sample(filtered)
            let next = CookDiscovery.sample(filtered, avoiding: first)
            XCTAssertTrue(Set(filtered.map(\.id)).isSubset(of: Set(candidates.map(\.id))))
            XCTAssertTrue((first + next).allSatisfy { recipe in
                store.dietaryCatalog?.entry(for: recipe, goal: .balancedPairing) != nil
            })
        }

        XCTAssertEqual(session.options, before)
        XCTAssertEqual(session.avoid, ["香菜"])
        XCTAssertEqual(session.dietaryGoal, .balancedPairing)
        XCTAssertTrue(session.hasCompletedDietaryIntroduction)
        XCTAssertEqual(CookSession(defaultPantry: [], context: context, dietaryDefaults: defaults).dietaryGoal, .balancedPairing)
        XCTAssertEqual(defaults.string(forKey: CookSession.dietaryGoalKey), DietaryGoal.balancedPairing.rawValue)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Preferences>()), preferenceCount)
        XCTAssertEqual(context.hasChanges, pendingChanges)
        XCTAssertEqual(session.candidates(in: store), candidates)
    }
}
