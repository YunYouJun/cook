import UIKit
import XCTest

/// 首屏优先选食材；场景在筛选里，灵感和随机操作放在选择区之后。
final class CookDiscoveryUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testDefaultCatalogCountMatchesFilterPreviewWithoutIngredients() {
        let app = launch()
        let results = app.buttons["cook.showResults"]
        reveal(results, in: app)
        let catalogCount = results.label.filter { $0.isASCII && $0.isNumber }
        XCTAssertGreaterThan(Int(catalogCount) ?? 0, 0, "默认页面应可浏览真实菜谱目录")

        app.buttons["筛选"].tap()
        let count = app.descendants(matching: .any).matching(identifier: "filter.resultCount").firstMatch
        wait(count, "exists == true")
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "filter.selectionHint").firstMatch.exists)
        XCTAssertEqual(count.label.filter { $0.isASCII && $0.isNumber }, catalogCount,
                       "未选食材时，筛选预览也应使用页面正在浏览的目录数量")
        capture(app, "find-recipes-default-filter-count")
        app.buttons["完成"].tap()
        wait(count, "exists == false")
        XCTAssertEqual(results.label.filter { $0.isASCII && $0.isNumber }, catalogCount)
    }

    @MainActor
    func testIngredientAliasSuggestionClearsPreviousRecipeNameSearch() {
        let app = launch()
        submit("蒸蛋", in: app)
        let clearKeyword = app.buttons["cook.clearKeyword"]
        reveal(clearKeyword, in: app)
        XCTAssertTrue(clearKeyword.exists)

        let query = app.textFields["cook.query"]
        reveal(query, in: app, towardTop: true)
        query.tap()
        query.typeText("西红柿")
        let suggestion = app.buttons["添加番茄"]
        wait(suggestion, "exists == true AND isHittable == true")
        suggestion.tap()
        wait(clearKeyword, "exists == false")

        let context = app.staticTexts["discovery.context"]
        reveal(context, in: app)
        wait(context, "label CONTAINS '已有食材：番茄'")
        XCTAssertFalse(context.label.contains("菜名："), "选择食材联想应清除上次菜名限制")
        capture(app, "find-recipes-alias-suggestion-replaces-keyword")

        let recipe = recipeLinks(in: app).firstMatch
        reveal(recipe, in: app)
        recipe.tap()
        wait(favoriteButton(in: app), "isHittable == true")
        let owned = app.staticTexts["已有"]
        reveal(owned, in: app, page: .recipe)
        XCTAssertTrue(owned.exists)
        XCTAssertTrue(app.staticTexts["番茄"].exists, "新候选应带入番茄的真实食材匹配")
        capture(app, "find-recipes-alias-suggestion-matched-detail")
    }

    @MainActor
    func testDefaultThreeRecipesShuffleAndDecideWithoutExtraModesOrRandomTab() {
        let app = launch()
        XCTAssertFalse(app.buttons["随机"].exists)
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "discovery.mode").firstMatch.exists)
        XCTAssertFalse(app.buttons["cook.discovery"].exists)
        XCTAssertFalse(app.buttons["discovery.scene.easy"].exists, "场景应收进筛选，不占用食材首屏")
        XCTAssertTrue(app.textFields["cook.query"].isHittable)
        let potato = app.buttons.matching(NSPredicate(format: "label == %@", "土豆")).firstMatch
        XCTAssertTrue(potato.isHittable, "常用食材必须无需滚动就能点选")
        XCTAssertTrue(app.staticTexts["蔬菜"].isHittable, "蔬菜快选应回到首屏")
        XCTAssertFalse(app.buttons["discovery.shuffle"].isHittable, "随机操作不应挤在食材前面")
        XCTAssertFalse(app.buttons["discovery.decide"].isHittable, "随机操作不应遮挡首屏快选")
        capture(app, "find-recipes-compact-ingredient-first")
        let recipes = recipeLinks(in: app)
        reveal(recipes.firstMatch, in: app)
        waitForThreeRecipes(in: app)
        let previous = Set(recipes.allElementsBoundByIndex.map(\.identifier))
        XCTAssertEqual(previous.count, 3)
        capture(app, "find-recipes-default-three")

        let shuffle = app.buttons["discovery.shuffle"]
        reveal(shuffle, in: app)
        XCTAssertTrue(shuffle.isEnabled)
        shuffle.tap()
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let updated = Set(recipes.allElementsBoundByIndex.map(\.identifier))
            return updated.count == 3 && updated.isDisjoint(with: previous)
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 10), .completed)
        capture(app, "find-recipes-shuffled-three")

        let decide = app.buttons["discovery.decide"]
        reveal(decide, in: app)
        decide.tap()
        wait(favoriteButton(in: app), "isHittable == true")
        XCTAssertEqual(app.webViews.count, 0)
        capture(app, "find-recipes-decide-real-detail")
    }

    @MainActor
    func testSceneFavoritesAndKitchenUseTheSameFindRecipesFlow() {
        let app = launch()
        chooseScene("soup", in: app)
        XCTAssertTrue(app.buttons["cook.clearScene"].exists)
        let recipe = recipeLinks(in: app).firstMatch
        reveal(recipe, in: app)
        recipe.press(forDuration: 1)
        let favorite = app.buttons["收藏"].firstMatch
        XCTAssertTrue(favorite.waitForExistence(timeout: 10))
        favorite.tap()
        wait(app.buttons["cook.presentation"], "isHittable == true")
        reveal(recipe, in: app)
        recipe.tap()
        wait(app.buttons["取消收藏"], "isHittable == true")
        capture(app, "find-recipes-shared-favorite")
        goBack(in: app)

        app.selectCookingPresentation("云厨房")
        wait(app.buttons["kitchen.cook"], "exists == true")
        XCTAssertFalse(app.buttons["cook.discovery"].exists)
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "discovery.mode").firstMatch.exists)
        XCTAssertTrue(app.textFields["cook.query"].exists)
        openFilters(in: app)
        XCTAssertTrue(app.buttons["discovery.scene.soup"].isSelected)
        closeFilters(in: app)
        reveal(recipeLinks(in: app).firstMatch, in: app)
        recipeLinks(in: app).firstMatch.tap()
        wait(favoriteButton(in: app), "isHittable == true")
        XCTAssertEqual(app.webViews.count, 0)
        capture(app, "find-recipes-kitchen-real-detail")
    }

    @MainActor
    func testRecipeNameSearchCanBeClearedWithoutChangingSceneOrIngredients() {
        let app = launch()
        submit("番茄炒蛋", in: app)
        let recipes = recipeLinks(in: app)
        reveal(recipes.firstMatch, in: app)
        XCTAssertGreaterThan(recipes.count, 0)
        XCTAssertTrue(recipes.allElementsBoundByIndex.allSatisfy { $0.label.contains("番茄炒蛋") })
        XCTAssertTrue(app.staticTexts["discovery.context"].label.contains("番茄炒蛋"))
        capture(app, "find-recipes-name-search")
        let clear = app.buttons["cook.clearKeyword"]
        reveal(clear, in: app, towardTop: true)
        clear.tap()
        wait(clear, "exists == false")
        reveal(recipes.firstMatch, in: app)
        waitForThreeRecipes(in: app)
        XCTAssertFalse(app.buttons["cook.clearScene"].exists)
    }

    @MainActor
    func testOneSentenceAddsIngredientsAndAvoidanceAndKeepsMatchInDetails() {
        let app = launch()
        submit("土豆和鸡蛋，不要辣", in: app)
        let context = app.staticTexts["discovery.context"]
        reveal(context, in: app)
        wait(context, "label CONTAINS '土豆' AND label CONTAINS '鸡蛋' AND label CONTAINS '不吃辣'")
        XCTAssertTrue(context.label.contains("推荐方式：全部推荐"))
        let recipe = recipeLinks(in: app).firstMatch
        reveal(recipe, in: app)
        recipe.tap()
        wait(favoriteButton(in: app), "isHittable == true")
        let owned = app.staticTexts["已有"]
        reveal(owned, in: app, page: .recipe)
        XCTAssertTrue(owned.exists)
        capture(app, "find-recipes-sentence-ingredient-match")
    }

    @MainActor
    func testEmptySceneCanBeCancelledWithoutLosingIngredientsOrTool() {
        let app = launch()
        selectIngredient("土豆", in: app)
        selectIngredient("微波炉", in: app)
        chooseScene("home", in: app)
        let empty = app.staticTexts["discovery.empty"]
        reveal(empty, in: app)
        XCTAssertTrue(empty.waitForExistence(timeout: 10))
        let context = app.staticTexts["discovery.context"]
        XCTAssertTrue(context.label.contains("土豆"))
        XCTAssertTrue(context.label.contains("微波炉"))
        XCTAssertTrue(context.label.contains("推荐方式：全部推荐"))
        capture(app, "find-recipes-empty-scene")
        let reset = app.buttons["discovery.reset"]
        reveal(reset, in: app)
        reset.tap()
        wait(empty, "exists == false")
        XCTAssertFalse(app.buttons["cook.clearScene"].exists)
        reveal(recipeLinks(in: app).firstMatch, in: app)
        XCTAssertTrue(recipeLinks(in: app).firstMatch.exists)
        XCTAssertTrue(context.label.contains("土豆"))
        XCTAssertTrue(context.label.contains("微波炉"))
        for name in ["土豆", "微波炉"] {
            let item = app.buttons.matching(NSPredicate(format: "label == %@", name)).firstMatch
            reveal(item, in: app)
            XCTAssertTrue(item.isSelected)
        }
        capture(app, "find-recipes-restored-after-scene-reset")
    }

    @MainActor
    func testDietaryGoalIsKeptByDecideAndReviewedRecipeDetails() {
        let app = launch()
        openFilters(in: app)
        let goal = app.buttons["dietary.openPicker"].firstMatch
        reveal(goal, in: app, page: .filters)
        goal.tap()
        let sugar = app.buttons["dietary.goal.less-added-sugar"]
        XCTAssertTrue(sugar.waitForExistence(timeout: 10))
        sugar.tap()
        app.buttons["dietary.done"].tap()
        wait(sugar, "exists == false")
        closeFilters(in: app)
        let context = app.staticTexts["discovery.context"]
        reveal(context, in: app)
        XCTAssertTrue(context.label.contains("减少添加糖"))
        let decide = app.buttons["discovery.decide"]
        reveal(decide, in: app)
        decide.tap()
        let reviewed = app.buttons["dietary.openReviewedRecipe"]
        wait(favoriteButton(in: app), "isHittable == true")
        reveal(reviewed, in: app, page: .recipe)
        reviewed.tap()
        XCTAssertTrue(app.otherElements["dietary.reviewSummary"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.webViews.count, 0)
        capture(app, "find-recipes-dietary-reviewed-text")
        goBack(in: app, waitingFor: reviewed)
        goBack(in: app)
        openFilters(in: app)
        XCTAssertEqual(goal.value as? String, "减少添加糖")
        closeFilters(in: app)
    }

    @MainActor
    func testLargestTextCanChooseSceneSearchAndDecideOnOnePage() {
        let app = launch(contentSize: .accessibilityExtraExtraExtraLarge)
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "discovery.mode").firstMatch.exists)
        openFilters(in: app)
        let home = app.buttons["discovery.scene.home"]
        reveal(home, in: app, page: .filters)
        let window = app.windows.firstMatch
        XCTAssertGreaterThanOrEqual(home.frame.minX, window.frame.minX)
        XCTAssertLessThanOrEqual(home.frame.maxX, window.frame.maxX)
        home.tap()
        wait(home, "isSelected == true")
        capture(app, "find-recipes-largest-scene")
        // 在同一筛选弹层内再次点按撤销，不增加找菜模式。
        home.tap()
        wait(home, "isSelected == false")
        closeFilters(in: app)
        submit("番茄炒蛋", in: app)
        let recipe = recipeLinks(in: app).firstMatch
        reveal(recipe, in: app)
        XCTAssertTrue(recipe.label.contains("番茄炒蛋"))
        capture(app, "find-recipes-largest-search-result")
        let decide = app.buttons["discovery.decide"]
        reveal(decide, in: app)
        decide.tap()
        wait(favoriteButton(in: app), "isHittable == true")
        XCTAssertEqual(app.webViews.count, 0)
        capture(app, "find-recipes-largest-decided-detail")
    }

    @MainActor
    func testIPadIngredientSelectionUpdatesCompleteResultsBesideChooser() throws {
        let app = launch()
        guard app.windows.firstMatch.horizontalSizeClass == .regular else {
            throw XCTSkip("此用例检查 iPad 双栏布局")
        }
        let resultList = app.descendants(matching: .any).matching(identifier: "dietary.results").firstMatch
        wait(resultList, "exists == true AND isHittable == true")
        let catalogHeader = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "当前候选 · ")).firstMatch
        wait(catalogHeader, "exists == true")
        XCTAssertGreaterThan(Int(catalogHeader.label.filter { $0.isASCII && $0.isNumber }) ?? 0, 3,
                             "右栏应直接展示完整结果，而不是三道灵感的入口")
        let tomato = app.buttons.matching(NSPredicate(format: "label == %@", "番茄")).firstMatch
        XCTAssertTrue(tomato.isHittable, "左栏首屏应能直接选择常用食材")
        let decide = app.buttons["discovery.decide"]
        if decide.isHittable {
            // 大屏有空间同时显示后置灵感；验证相对顺序，不要求它一定离屏。
            let tools = app.staticTexts["厨具（可选）"]
            XCTAssertTrue(tools.exists)
            XCTAssertGreaterThan(decide.frame.minY, tools.frame.maxY,
                                 "随机操作应排在食材和厨具选择区之后")
        }
        capture(app, "find-recipes-ipad-live-catalog")

        tomato.tap()
        wait(tomato, "isSelected == true")
        let selection = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已选：番茄")).firstMatch
        wait(selection, "exists == true")
        let match = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "dietary.recipe.")).firstMatch
        wait(match, "exists == true AND isHittable == true")
        XCTAssertTrue(tomato.isHittable, "结果更新后左栏仍应停在食材选择区")
        XCTAssertGreaterThan(match.frame.midX, tomato.frame.maxX, "结果应在食材旁实时更新")
        capture(app, "find-recipes-ipad-live-ingredient-results")
    }

    @MainActor
    func testIPadCanReplaceRecipeDetailsFromTheVisibleInspirationList() throws {
        let app = launch()
        guard app.windows.firstMatch.horizontalSizeClass == .regular else {
            throw XCTSkip("此用例检查 iPad 左栏连续选菜与右栏详情更新")
        }
        let candidates = twoInspirationCandidatesForIPad(in: app)
        guard candidates.count >= 2 else { return }
        let first = app.buttons.matching(identifier: candidates[0].id).firstMatch
        let second = app.buttons.matching(identifier: candidates[1].id).firstMatch
        let firstLabel = candidates[0].label
        let secondLabel = candidates[1].label

        reveal(first, in: app, towardTop: true)
        first.tap()
        wait(favoriteButton(in: app), "isHittable == true")
        let firstTitle = visibleRecipeTitle(in: app)
        XCTAssertFalse(firstTitle.isEmpty)
        XCTAssertTrue(firstLabel.hasPrefix(firstTitle), "右栏应显示刚刚点选的第一道菜")

        reveal(second, in: app)
        second.tap()
        let replaced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let title = self.visibleRecipeTitle(in: app)
            return !title.isEmpty && title != firstTitle && secondLabel.hasPrefix(title)
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [replaced], timeout: 10), .completed,
                       "右栏已有详情时，左栏点选另一道菜也应立即更新")
        wait(favoriteButton(in: app), "isHittable == true")
        capture(app, "find-recipes-ipad-replaced-detail")

        let decide = app.buttons["discovery.decide"]
        reveal(decide, in: app)
        decide.tap()
        wait(favoriteButton(in: app), "isHittable == true")
        let decidedTitle = visibleRecipeTitle(in: app)
        XCTAssertFalse(decidedTitle.isEmpty)
        XCTAssertNotEqual(decidedTitle, "找到的菜")
        XCTAssertNotEqual(decidedTitle, "找菜")
        decide.tap()
        let decidedAgain = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let title = self.visibleRecipeTitle(in: app)
            return !title.isEmpty && title != decidedTitle && title != "找到的菜" && title != "找菜"
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [decidedAgain], timeout: 10), .completed,
                       "至少有两道候选时，连续决定应排除上次选择并更新右栏详情")
        wait(favoriteButton(in: app), "isHittable == true")
        XCTAssertEqual(app.webViews.count, 0)
        capture(app, "find-recipes-ipad-decide-with-detail-open")
    }

    @MainActor
    private func twoInspirationCandidatesForIPad(in app: XCUIApplication) -> [(id: String, label: String)] {
        let chooser = app.descendants(matching: .any).matching(identifier: "cook.chooser").firstMatch
        wait(chooser, "exists == true AND isHittable == true")
        var candidates: [(id: String, label: String)] = []
        // 左栏为懒加载列表，逐屏记录稳定 ID，不要求三道菜同时出现在无障碍树里。
        for _ in 0..<10 {
            for recipe in recipeLinks(in: app).allElementsBoundByIndex where recipe.exists {
                let id = recipe.identifier
                let label = recipe.label
                if !label.isEmpty && !candidates.contains(where: { $0.id == id }) {
                    candidates.append((id, label))
                }
            }
            if candidates.count >= 2 { return candidates }
            chooser.swipeUp()
        }
        XCTAssertGreaterThanOrEqual(candidates.count, 2, "应能在左栏滚动找到至少两道不同的灵感")
        return candidates
    }

    @MainActor
    private func visibleRecipeTitle(in app: XCUIApplication) -> String {
        let chooser = app.descendants(matching: .any).matching(identifier: "cook.chooser").firstMatch
        let bar = app.navigationBars.allElementsBoundByIndex.last(where: {
            $0.isHittable && $0.frame.midX > chooser.frame.maxX
        })
        return bar?.identifier ?? ""
    }

    @MainActor
    private func launch(contentSize: UIContentSizeCategory = .large) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        var arguments = XCUIApplication.standardCookingLaunchArguments
        if let index = arguments.firstIndex(of: "-UIPreferredContentSizeCategoryName") {
            arguments[index + 1] = contentSize.rawValue
        }
        app.launchArguments = arguments + ["-ui-testing-reset-consent"]
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        app.launchEnvironment["COOK_SKIP_CLOUDKIT_SCHEMA"] = "1"
        app.launch()
        let presentation = app.buttons["cook.presentation"]
        XCTAssertTrue(presentation.waitForExistence(timeout: 20))
        app.selectCookingPresentation("食材快选")
        return app
    }

    @MainActor
    private func recipeLinks(in app: XCUIApplication) -> XCUIElementQuery {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "discovery.recipe."))
    }

    @MainActor
    private func favoriteButton(in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label IN %@", ["收藏", "取消收藏"])).firstMatch
    }

    @MainActor
    private func waitForThreeRecipes(in app: XCUIApplication) {
        let recipes = recipeLinks(in: app)
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in recipes.count == 3 }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
    }

    @MainActor
    private func submit(_ text: String, in app: XCUIApplication) {
        let query = app.textFields["cook.query"]
        reveal(query, in: app, towardTop: true)
        query.tap()
        query.typeText(text + "\n")
    }

    @MainActor
    private func selectIngredient(_ name: String, in app: XCUIApplication) {
        let item = app.buttons.matching(NSPredicate(format: "label == %@", name)).firstMatch
        reveal(item, in: app)
        item.tap()
        wait(item, "isSelected == true")
    }

    @MainActor
    private func openFilters(in app: XCUIApplication) {
        let filters = app.buttons["筛选"].firstMatch
        wait(filters, "isHittable == true")
        filters.tap()
        wait(app.buttons["完成"], "isHittable == true")
    }

    @MainActor
    private func closeFilters(in app: XCUIApplication) {
        app.buttons["完成"].tap()
        wait(app.buttons["完成"], "exists == false")
    }

    @MainActor
    private func chooseScene(_ name: String, in app: XCUIApplication) {
        openFilters(in: app)
        let scene = app.buttons["discovery.scene.\(name)"]
        reveal(scene, in: app, page: .filters)
        scene.tap()
        wait(scene, "isSelected == true")
        closeFilters(in: app)
    }

    private enum ContentPage: Equatable { case findRecipes, recipe, filters }

    /// 读取当前页面的真实可滚动区域，不用屏幕外元素的 exists 代替可操作性。
    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, towardTop: Bool = false,
                        page: ContentPage = .findRecipes,
                        file: StaticString = #filePath, line: UInt = #line) {
        switch page {
        case .findRecipes: wait(app.buttons["cook.presentation"], "isHittable == true", file: file, line: line)
        case .recipe: wait(favoriteButton(in: app), "isHittable == true", file: file, line: line)
        case .filters: wait(app.buttons["完成"], "isHittable == true", file: file, line: line)
        }
        let window = app.windows.firstMatch
        var visibleTop = window.frame.minY
        var visibleBottom = window.frame.maxY
        for _ in 0..<30 {
            let frame = element.exists ? element.frame : CGRect.null
            // 固定底部结果按钮本身无需滚动，不能拿自己的上沿当遮挡边界。
            if element.isHittable && ["cook.showResults", "kitchen.cook"].contains(element.identifier) { return }
            let bars = app.navigationBars.allElementsBoundByIndex.filter(\.isHittable)
            let navigation = bars.first(where: { !frame.isNull && frame.midX >= $0.frame.minX && frame.midX <= $0.frame.maxX })
                ?? bars.first ?? app.navigationBars.firstMatch
            let navigationFrame = navigation.isHittable ? navigation.frame : CGRect.null
            let top = navigationFrame.isNull ? window.frame.minY : navigationFrame.maxY
            var bottom = window.frame.maxY
            if page == .findRecipes {
                let kitchen = app.buttons.matching(identifier: "kitchen.cook").firstMatch
                if kitchen.exists && kitchen.isHittable {
                    bottom = kitchen.frame.minY
                } else if window.horizontalSizeClass != .regular {
                    let results = app.buttons.matching(identifier: "cook.showResults").firstMatch
                    if results.exists && results.isHittable { bottom = results.frame.minY }
                }
            } else if page == .filters {
                // 整个固定汇总容器都会遮挡列表，不能仅按文字或窗口边界推算。
                let summary = app.descendants(matching: .any).matching(identifier: "filter.summary").firstMatch
                if summary.exists {
                    bottom = min(bottom, summary.frame.minY)
                } else {
                    let count = app.descendants(matching: .any).matching(identifier: "filter.resultCount").firstMatch
                    if count.exists { bottom = min(bottom, count.frame.minY - 12) }
                }
            }
            visibleTop = top
            visibleBottom = bottom
            let withinViewport = page == .filters
                ? frame.minY >= top && frame.maxY <= bottom
                : frame.midY >= top && frame.midY <= bottom
            if element.isHittable && !frame.isNull && withinViewport { return }
            let above = !frame.isNull && frame.minY < top
            let scrollBack = towardTop || above
            let x = navigationFrame.isNull ? window.frame.midX : navigationFrame.midX
            let start = top + (bottom - top) * (scrollBack ? 0.15 : 0.85)
            let end = top + (bottom - top) * (scrollBack ? 0.85 : 0.15)
            let origin = window.coordinate(withNormalizedOffset: .zero)
            origin.withOffset(CGVector(dx: x - window.frame.minX, dy: start - window.frame.minY))
                .press(forDuration: 0.1,
                       thenDragTo: origin.withOffset(CGVector(dx: x - window.frame.minX, dy: end - window.frame.minY)),
                       withVelocity: .slow, thenHoldForDuration: 0.1)
        }
        XCTAssertTrue(element.exists && element.isHittable, "目标应能滚动到可操作位置", file: file, line: line)
        if page == .filters, element.exists {
            XCTAssertGreaterThanOrEqual(element.frame.minY, visibleTop, "目标应完整露在导航栏下方", file: file, line: line)
            XCTAssertLessThanOrEqual(element.frame.maxY, visibleBottom, "目标应完整露在筛选汇总上方", file: file, line: line)
        }
    }

    @MainActor
    private func goBack(in app: XCUIApplication, waitingFor target: XCUIElement? = nil) {
        let bar = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable }) ?? app.navigationBars.firstMatch
        bar.buttons.element(boundBy: 0).tap()
        wait(target ?? app.buttons["cook.presentation"], "isHittable == true")
    }

    private func wait(_ element: XCUIElement, _ predicate: String,
                      file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: predicate), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 10), .completed, file: file, line: line)
    }

    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
