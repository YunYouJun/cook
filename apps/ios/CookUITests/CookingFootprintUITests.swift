import XCTest
import UIKit

final class CookingFootprintUITests: XCTestCase {
    @MainActor
    private func launch(largeDark: Bool = false, showcaseSession: UUID? = nil) -> XCUIApplication {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = XCUIApplication.standardCookingLaunchArguments + [
            "-ui-testing-reset-consent", "-ui-testing-reset-kitchen",
        ]
        if largeDark {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName",
                                    UIContentSizeCategory.accessibilityExtraExtraExtraLarge.rawValue, "-ui-testing-dark"]
        }
        app.launchEnvironment["COOK_UI_TEST_DECLINE_CONSENT"] = "1"
        let fixtures = Bundle(for: Self.self)
        app.launchEnvironment["COOK_UI_TEST_SHOWCASE_IMAGE"] = fixtures.path(forResource: "tomato-egg", ofType: "webp")
        app.launchEnvironment["COOK_UI_TEST_SHOWCASE_MODEL"] = fixtures.path(forResource: "TomatoEggAchievement", ofType: "usdz")
        if let showcaseSession {
            app.launchEnvironment["COOK_UI_TEST_SHOWCASE_SESSION"] = showcaseSession.uuidString
            app.launchEnvironment["COOK_UI_TEST_SHOWCASE_LIVE"] = ProcessInfo.processInfo.environment["COOK_UI_TEST_SHOWCASE_LIVE"]
        }
        app.launch()
        terminateAppAfterTest(app)
        return app
    }

    @MainActor
    func testProgressiveDetailLearningAndIndependentTimelineDeletion() {
        let app = launch()
        openRecipe(app)
        XCTAssertFalse(app.buttons["recipe.markLearned"].exists)
        XCTAssertFalse(app.buttons["recipe.learnedStatus"].exists)
        app.buttons["recipe.cookedRecord"].tap()
        XCTAssertTrue(app.buttons["recipe.markLearned"].waitForExistence(timeout: 5))

        app.buttons["recipe.markLearned"].tap()
        XCTAssertTrue(app.buttons["cooking.celebration.undo"].waitForExistence(timeout: 5))
        let celebrationHeading = app.descendants(matching: .any).matching(identifier: "cooking.celebration.heading").firstMatch
        XCTAssertTrue(celebrationHeading.label.contains("获得菜肴徽章"))
        capture(app, "footprint-celebration")
        app.buttons["cooking.celebration.undo"].tap()
        XCTAssertTrue(app.buttons["recipe.markLearned"].waitForExistence(timeout: 5),
                      "Immediate undo must preserve the cook that existed before learning.")
        app.buttons.matching(identifier: "我的").firstMatch.tap()
        openLearnedDishes(app)
        XCTAssertTrue(app.staticTexts["还没有拿手菜"].exists)
        closeLearnedDishes(app)
        app.buttons.matching(identifier: "菜谱").firstMatch.tap()
        app.buttons["recipe.markLearned"].tap()
        app.buttons["cooking.celebration.done"].tap()
        let learned = app.buttons["recipe.learnedStatus"]
        XCTAssertTrue(learned.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["recipe.markLearned"].exists)
        learned.tap()
        app.buttons["在做菜足迹中查看"].tap()
        XCTAssertTrue(app.staticTexts["✨ 学会了"].waitForExistence(timeout: 5))
        capture(app, "footprint-learned-timeline")
        let row = app.descendants(matching: .any).matching(identifier: "footprint.entry").firstMatch
        row.swipeLeft()
        app.buttons["删除做过记录"].tap()
        XCTAssertTrue(app.staticTexts["✨ 学会了"].waitForExistence(timeout: 5),
                      "Removing the cook must leave its learned milestone reachable.")
        app.segmentedControls.buttons["已学会"].tap()
        XCTAssertTrue(app.staticTexts["✨ 学会了"].exists)
        app.buttons.matching(identifier: "我的").firstMatch.tap()
        openLearnedDishes(app)
        let achievement = assertBadge(app, contains: ["番茄炒蛋", "首次学会", "查看成就"])
        capture(app, "learned-dishes-catalog")
        achievement.tap()
        downloadShowcaseModel(app)
        let viewport = app.descendants(matching: .any).matching(identifier: "achievement.model").firstMatch
        let turn = app.buttons["右转"]
        XCTAssertTrue(turn.waitForExistence(timeout: 15))
        XCTAssertEqual(viewport.value as? String, "0 度")
        let reset = app.buttons["复位"]
        XCTAssertFalse(reset.isEnabled)
        turn.tap()
        XCTAssertEqual(viewport.value as? String, "30 度")
        XCTAssertTrue(reset.isEnabled)
        app.buttons["左转"].tap()
        XCTAssertEqual(viewport.value as? String, "0 度")
        viewport.swipeLeft()
        XCTAssertNotEqual(viewport.value as? String, "0 度")
        capture(app, "tomato-egg-achievement-rotated")
        reset.tap()
        XCTAssertEqual(viewport.value as? String, "0 度")
        XCTAssertFalse(reset.isEnabled)
        capture(app, "tomato-egg-achievement-reset")
        closeShowcaseModel(app)
        app.navigationBars["菜肴成就"].buttons["完成"].tap()
        closeLearnedDishes(app)
        app.buttons.matching(identifier: "菜谱").firstMatch.tap()
        row.tap()
        XCTAssertTrue(learned.waitForExistence(timeout: 5))
        learned.tap()
        app.buttons["撤销「学会」标记"].tap()
        XCTAssertTrue(app.buttons["recipe.cookedRecord"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["recipe.markLearned"].exists)
        app.buttons["recipe.cookedRecord"].tap()
        app.buttons["recipe.cookedRecord"].tap()
        app.buttons["我做过这道"].tap()
        let notice = app.staticTexts["cooking.notice"]
        XCTAssertTrue(notice.waitForExistence(timeout: 5))
        XCTAssertEqual(notice.label, "今天已记过做过这道啦")
        app.buttons.matching(identifier: "我的").firstMatch.tap()
        openLearnedDishes(app)
        XCTAssertTrue(app.staticTexts["还没有拿手菜"].exists,
                      "Regular undo must remove the badge even when the dish still has a cooking record.")
    }

    @MainActor
    func testWorkbenchCloseSkipAndExactImmediateUndo() {
        let app = launch()
        openRecipe(app)
        app.buttons["recipe.openKitchen"].tap()
        app.buttons["kitchen.close"].tap()
        XCTAssertTrue(app.buttons["recipe.openKitchen"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["kitchen.completion.learn"].exists)
        XCTAssertFalse(app.buttons["recipe.markLearned"].exists)

        openCompletion(app)
        app.buttons["kitchen.completion.skip"].tap()
        XCTAssertTrue(app.buttons["kitchen.close"].waitForExistence(timeout: 5))
        app.buttons["kitchen.close"].tap()
        XCTAssertFalse(app.buttons["recipe.markLearned"].exists)

        openCompletion(app)
        app.buttons["kitchen.completion.learn"].tap()
        XCTAssertTrue(app.buttons["cooking.celebration.undo"].waitForExistence(timeout: 5))
        app.buttons["cooking.celebration.undo"].tap()
        XCTAssertTrue(app.buttons["kitchen.close"].waitForExistence(timeout: 5))
        app.buttons["kitchen.close"].tap()
        XCTAssertTrue(app.buttons["recipe.cookedRecord"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["recipe.markLearned"].exists,
                       "Immediate undo must remove the cook generated by this confirmation.")

        openCompletion(app)
        app.buttons["kitchen.completion.learn"].tap()
        app.buttons["cooking.celebration.done"].tap()
        XCTAssertTrue(app.buttons["kitchen.close"].waitForExistence(timeout: 5))
        app.buttons["kitchen.close"].tap()
        openCompletion(app)
        XCTAssertFalse(app.buttons["kitchen.completion.learn"].exists)
        capture(app, "footprint-already-learned-completion")
        app.buttons["kitchen.completion.cook"].tap()
        XCTAssertTrue(app.staticTexts["今天已记过做过这道啦"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testCookOnlyAndDeletedPrivateRecipeFallback() {
        let app = launch()
        openRecipe(app)
        openCompletion(app)
        app.buttons["kitchen.completion.cook"].tap()
        XCTAssertTrue(app.buttons["kitchen.close"].waitForExistence(timeout: 5))
        app.buttons["kitchen.close"].tap()
        XCTAssertTrue(app.buttons["recipe.markLearned"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["recipe.learnedStatus"].exists)

        app.buttons.matching(identifier: "我的").firstMatch.tap()
        openLearnedDishes(app)
        XCTAssertTrue(app.staticTexts["还没有拿手菜"].exists)
        closeLearnedDishes(app)
        app.buttons["我的菜谱"].tap()
        app.buttons["新建菜谱"].tap()
        let name = app.textFields["菜名（最多 30 字）"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        name.tap()
        name.typeText("足迹测试蒸蛋")
        app.textFields["鸡蛋、丝瓜"].tap()
        app.textFields["鸡蛋、丝瓜"].typeText("鸡蛋")
        app.buttons["保存"].tap()
        let recipe = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "足迹测试蒸蛋")).firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 5))
        recipe.tap()
        app.buttons["recipe.cookedRecord"].tap()
        app.buttons["recipe.markLearned"].tap()
        app.buttons["cooking.celebration.done"].tap()
        XCTAssertTrue(app.buttons["删除菜谱"].waitForExistence(timeout: 5))
        app.buttons["删除菜谱"].tap()
        let dialog = app.sheets["删除这道私人菜谱？"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 5))
        dialog.buttons["删除"].tap()
        XCTAssertTrue(app.navigationBars["我的菜谱"].waitForExistence(timeout: 5))
        app.navigationBars["我的菜谱"].buttons.element(boundBy: 0).tap()
        app.buttons["settings.cookingFootprint"].tap()
        XCTAssertTrue(app.staticTexts["私人菜谱已删除或暂不可用"].waitForExistence(timeout: 5))
        capture(app, "footprint-deleted-private-recipe")
        let unavailable = app.staticTexts["私人菜谱已删除或暂不可用"]
        unavailable.swipeLeft()
        app.buttons["删除做过记录"].tap()
        XCTAssertTrue(unavailable.waitForExistence(timeout: 5))
        app.buttons["footprint.learnedDishes"].tap()
        assertBadge(app, contains: ["足迹测试蒸蛋", "首次学会", "私人菜谱已删除或暂不可用"])
        capture(app, "learned-dishes-deleted-private-recipe")
    }

    @MainActor
    func testLearnedBadgeFitsLargestTextInDarkAppearance() {
        let app = launch(largeDark: true)
        openRecipe(app)
        app.buttons["recipe.cookedRecord"].tap()
        app.buttons["recipe.markLearned"].tap()
        let done = app.buttons["cooking.celebration.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        for _ in 0..<4 where !done.isHittable { app.swipeUp() }
        done.tap()
        app.buttons.matching(identifier: "我的").firstMatch.tap()
        openLearnedDishes(app)
        let badge = assertBadge(app, contains: ["番茄炒蛋", "首次学会", "年", "月", "日"])
        XCTAssertTrue(app.windows.firstMatch.frame.contains(badge.frame))
        capture(app, "learned-dishes-largest-dark")
        badge.tap()
        downloadShowcaseModel(app)
        let turn = app.buttons["右转"]
        XCTAssertTrue(turn.waitForExistence(timeout: 15))
        for _ in 0..<4 where !turn.isHittable { app.swipeUp() }
        turn.tap()
        let reset = app.buttons["复位"]
        XCTAssertTrue(reset.isEnabled)
        capture(app, "tomato-egg-achievement-dark-rotated")
        reset.tap()
        XCTAssertFalse(reset.isEnabled)
    }

    @MainActor
    func testShowcasePacksDownloadIndependentlyReopenOfflineAndDelete() {
        let app = launch(showcaseSession: UUID())
        openRecipe(app)
        app.buttons["recipe.cookedRecord"].tap()
        app.buttons["recipe.markLearned"].tap()
        app.buttons["cooking.celebration.done"].tap()

        openShowcase(app)
        let badge = app.descendants(matching: .any).matching(identifier: "showcase.preview.badge").firstMatch
        let image = app.images["showcase.preview.image"]
        let learnedAt = app.staticTexts["showcase.learnedAt"]
        XCTAssertTrue(learnedAt.exists)
        let learnedDate = learnedAt.label
        let getImage = app.buttons["showcase.image.download"]
        let getModel = app.buttons["showcase.model.download"]
        let openModel = app.buttons["showcase.model.open"]
        XCTAssertTrue(badge.waitForExistence(timeout: 5))
        XCTAssertFalse(image.exists)
        XCTAssertFalse(openModel.exists)
        tapShowcaseAction(getImage, in: app)
        XCTAssertTrue(image.waitForExistence(timeout: 30))
        XCTAssertFalse(openModel.exists)
        capture(app, "showcase-image-only")

        downloadShowcaseModel(app)
        XCTAssertTrue(app.buttons["右转"].waitForExistence(timeout: 15))
        closeShowcaseModel(app)
        tapShowcaseAction(app.buttons["showcase.image.delete"], in: app)
        XCTAssertTrue(badge.waitForExistence(timeout: 5))
        XCTAssertFalse(image.exists)
        XCTAssertTrue(openModel.exists)
        capture(app, "showcase-model-only")
        tapShowcaseAction(getImage, in: app)
        XCTAssertTrue(image.waitForExistence(timeout: 30))

        // Relaunch with a transport that always fails: installed assets must still open.
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "COOK_UI_TEST_SHOWCASE_IMAGE")
        app.launchEnvironment.removeValue(forKey: "COOK_UI_TEST_SHOWCASE_MODEL")
        app.launchEnvironment.removeValue(forKey: "COOK_UI_TEST_SHOWCASE_LIVE")
        app.launch()
        openShowcase(app)
        XCTAssertTrue(image.waitForExistence(timeout: 10))
        tapShowcaseAction(openModel, in: app)
        let turn = app.buttons["右转"]
        XCTAssertTrue(turn.waitForExistence(timeout: 15))
        turn.tap()
        let model = app.descendants(matching: .any).matching(identifier: "achievement.model").firstMatch
        XCTAssertEqual(model.value as? String, "30 度")
        capture(app, "showcase-offline-model")
        closeShowcaseModel(app)

        tapShowcaseAction(app.buttons["showcase.model.delete"], in: app)
        XCTAssertFalse(openModel.exists)
        XCTAssertTrue(image.exists, "Removing the model must keep the downloaded photo.")
        tapShowcaseAction(getModel, in: app)
        XCTAssertTrue(app.staticTexts["下载失败，请重试。"].waitForExistence(timeout: 5))
        XCTAssertFalse(openModel.exists)
        tapShowcaseAction(app.buttons["showcase.image.delete"], in: app)
        XCTAssertTrue(badge.waitForExistence(timeout: 5))
        XCTAssertEqual(learnedAt.label, learnedDate)
        capture(app, "showcase-badge-after-deletion")
    }

    @MainActor
    private func openShowcase(_ app: XCUIApplication) {
        let me = app.buttons.matching(identifier: "我的").firstMatch
        XCTAssertTrue(me.waitForExistence(timeout: 10))
        me.tap()
        openLearnedDishes(app)
        assertBadge(app, contains: ["番茄炒蛋", "首次学会"]).tap()
        XCTAssertTrue(app.navigationBars["菜肴成就"].waitForExistence(timeout: 5))
    }

    @MainActor
    private func downloadShowcaseModel(_ app: XCUIApplication) {
        tapShowcaseAction(app.buttons["showcase.model.download"], in: app)
        let openModel = app.buttons["showcase.model.open"]
        XCTAssertTrue(openModel.waitForExistence(timeout: 30))
        tapShowcaseAction(openModel, in: app)
    }

    @MainActor
    private func closeShowcaseModel(_ app: XCUIApplication) {
        app.navigationBars["3D 模型"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["菜肴成就"].waitForExistence(timeout: 5))
    }

    @MainActor
    private func tapShowcaseAction(_ action: XCUIElement, in app: XCUIApplication) {
        XCTAssertTrue(action.waitForExistence(timeout: 10))
        let sheet = app.scrollViews["showcase.content"]
        for _ in 0..<5 where !action.isHittable { sheet.swipeUp() }
        for _ in 0..<5 where !action.isHittable { sheet.swipeDown() }
        XCTAssertTrue(action.isHittable)
        action.tap()
    }

    @MainActor
    private func openLearnedDishes(_ app: XCUIApplication) {
        let footprint = app.buttons["settings.cookingFootprint"]
        XCTAssertTrue(footprint.waitForExistence(timeout: 5))
        for _ in 0..<5 where !footprint.isHittable { app.swipeUp() }
        footprint.tap()
        let link = app.buttons["footprint.learnedDishes"]
        XCTAssertTrue(link.waitForExistence(timeout: 5))
        link.tap()
        XCTAssertTrue(app.navigationBars["我的拿手菜"].waitForExistence(timeout: 5))
    }

    @MainActor
    private func closeLearnedDishes(_ app: XCUIApplication) {
        app.navigationBars["我的拿手菜"].buttons.element(boundBy: 0).tap()
        app.navigationBars["做菜足迹"].buttons.element(boundBy: 0).tap()
    }

    @MainActor
    @discardableResult
    private func assertBadge(_ app: XCUIApplication, contains fragments: [String],
                             file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let badge = app.descendants(matching: .any).matching(identifier: "learnedDishes.badge").firstMatch
        XCTAssertTrue(badge.waitForExistence(timeout: 5), file: file, line: line)
        for fragment in fragments {
            XCTAssertTrue(badge.label.contains(fragment), badge.label, file: file, line: line)
        }
        return badge
    }

    @MainActor
    private func openRecipe(_ app: XCUIApplication) {
        let library = app.buttons.matching(identifier: "菜谱").firstMatch
        XCTAssertTrue(library.waitForExistence(timeout: 20))
        library.tap()
        let search = app.searchFields["搜索菜名或食材"]
        if !search.waitForExistence(timeout: 3), app.buttons["显示边栏"].exists {
            app.buttons["显示边栏"].tap()
        }
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("番茄炒蛋\n")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "番茄炒蛋")).firstMatch.tap()
        let close = app.buttons.matching(NSPredicate(format: "label IN %@", ["关闭", "取消"])).firstMatch
        if close.exists && close.isHittable { close.tap() }
        if app.buttons["隐藏边栏"].exists { app.buttons["隐藏边栏"].tap() }
        XCTAssertTrue(app.buttons["recipe.cookedRecord"].waitForExistence(timeout: 10))
    }

    @MainActor
    private func openCompletion(_ app: XCUIApplication) {
        app.buttons["recipe.openKitchen"].tap()
        let finish = app.buttons["kitchen.finishCooking"]
        let content = app.scrollViews["kitchen.instructions"]
        XCTAssertTrue(content.waitForExistence(timeout: 5))
        for _ in 0..<20 {
            if finish.isHittable { break }
            content.swipeUp(velocity: .fast)
        }
        XCTAssertTrue(finish.isHittable)
        finish.tap()
        XCTAssertTrue(app.buttons["kitchen.completion.skip"].waitForExistence(timeout: 5))
    }

    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
