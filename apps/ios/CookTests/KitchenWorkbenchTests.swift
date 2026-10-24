import CookCore
import XCTest
@testable import Cook

@MainActor
final class KitchenWorkbenchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1000)

    func testChecklistKeepsExplicitIngredientsAndOptionalNotesWithoutReadingSteps() {
        let text = RecipeText(blocks: [
            .paragraph("先准备好食材"), .heading("必备原料和工具"),
            .list(ordered: false, start: 1, items: ["鸡蛋 2 个", "糖（可选）", "鸡蛋 2 个"]),
            .heading("操作"), .list(ordered: true, start: 1, items: ["热锅，倒入鸡蛋"]),
        ])
        XCTAssertEqual(KitchenIngredients.list(text: text, fallback: ["鸡蛋"]), ["鸡蛋 2 个", "糖（可选）"])
        XCTAssertEqual(KitchenIngredients.list(text: RecipeText(blocks: [.paragraph("做法")]), fallback: ["鸡蛋", "鸡蛋"]), ["鸡蛋"])
        XCTAssertEqual(KitchenIngredients.list(text: nil, fallback: []), [])
    }

    func testTimerKeepsElapsedTimeAcrossPauseAndBackground() throws {
        var timer = KitchenTimer()
        timer.configure(seconds: 90)
        timer.start(at: now)
        XCTAssertEqual(timer.display(at: now.addingTimeInterval(10.2)), "01:20")
        timer.pause(at: now.addingTimeInterval(25))
        XCTAssertEqual(timer.remaining(at: now.addingTimeInterval(200)), 65)
        XCTAssertEqual(timer.phase(at: now.addingTimeInterval(200)), .paused)
        timer.resume(at: now.addingTimeInterval(200))

        // 序列化模拟进程退出，重开后按结束时间恢复，无需补跑后台 tick。
        let restored = try JSONDecoder().decode(KitchenTimer.self, from: JSONEncoder().encode(timer))
        XCTAssertEqual(restored.remaining(at: now.addingTimeInterval(250)), 15)
        XCTAssertEqual(restored.phase(at: now.addingTimeInterval(300)), .finished)
        XCTAssertEqual(restored.display(at: now.addingTimeInterval(300)), "00:00")
    }

    func testResetAndRestartDoNotReuseAnExpiredDeadline() {
        var timer = KitchenTimer()
        timer.configure(seconds: 3601)
        XCTAssertEqual(timer.display(at: now), "1:00:01")
        timer.start(at: now)
        timer.reset()
        XCTAssertEqual(timer.phase(at: now.addingTimeInterval(4000)), .ready)
        XCTAssertNil(timer.deadline)
        XCTAssertEqual(timer.remaining(at: now), 3601)
        timer.start(at: now.addingTimeInterval(5000))
        XCTAssertEqual(timer.deadline, now.addingTimeInterval(8601))
        timer.configure(seconds: 0)
        timer.configure(seconds: .infinity)
        XCTAssertEqual(timer.duration, 3601)
    }

    func testExpiredTimerCannotBeResumedOrPausedBackToLife() {
        var timer = KitchenTimer()
        timer.configure(seconds: 1)
        timer.start(at: now)
        let later = now.addingTimeInterval(2)
        timer.pause(at: later)
        timer.resume(at: later)
        XCTAssertEqual(timer.phase(at: later), .finished)
        timer.start(at: later)
        XCTAssertEqual(timer.phase(at: later), .running)
        XCTAssertEqual(timer.remaining(at: later), 1)
    }

    func testPreparationAndTimerSurviveReopeningWithoutMixingRecipes() throws {
        let suite = "cook.kitchen.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let workbench = KitchenWorkbench(defaults: defaults, notifications: .disabled)
        workbench.togglePrepared("番茄", recipeID: 250)
        workbench.togglePrepared("鸡蛋", recipeID: 250)
        workbench.togglePrepared("鸡蛋", recipeID: 250)
        workbench.updateTimer(recipeID: 250, recipeName: "番茄炒蛋") { $0.start(at: now) }
        let restored = KitchenWorkbench(defaults: defaults, notifications: .disabled)
        XCTAssertEqual(restored.state(for: 250).prepared, ["番茄"])
        XCTAssertTrue(restored.state(for: 333).prepared.isEmpty)
        XCTAssertEqual(restored.state(for: 250).timer.remaining(at: now.addingTimeInterval(60)), 240)
        restored.clearPrepared(recipeID: 250)
        XCTAssertTrue(restored.state(for: 250).prepared.isEmpty)
        XCTAssertEqual(restored.state(for: 250).timer.deadline, now.addingTimeInterval(300))
    }

    func testBrokenStoredStateDoesNotPreventOpening() throws {
        let suite = "cook.kitchen.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(Data("broken".utf8), forKey: KitchenWorkbench.storageKey)
        let workbench = KitchenWorkbench(defaults: defaults, notifications: .disabled)
        XCTAssertEqual(workbench.state(for: 1), KitchenProgress())
    }

    func testRapidPauseAndResumeLeaveOnlyLatestNotification() async {
        let firstStarted = expectation(description: "first notification request")
        let latestApplied = expectation(description: "latest notification request")
        var releaseFirst: CheckedContinuation<Bool, Never>?
        var deadlines: [Date?] = []
        let client = KitchenTimerNotifications { _, _, deadline in
            deadlines.append(deadline)
            if deadlines.count == 1 {
                return await withCheckedContinuation { continuation in
                    releaseFirst = continuation
                    firstStarted.fulfill()
                }
            }
            latestApplied.fulfill()
            return true
        }
        let workbench = KitchenWorkbench(defaults: nil, notifications: client)
        workbench.updateTimer(recipeID: 1, recipeName: "番茄炒蛋") { $0.start(at: now) }
        await fulfillment(of: [firstStarted], timeout: 2)
        workbench.updateTimer(recipeID: 1, recipeName: "番茄炒蛋") { $0.pause(at: now.addingTimeInterval(10)) }
        workbench.updateTimer(recipeID: 1, recipeName: "番茄炒蛋") { $0.resume(at: now.addingTimeInterval(20)) }
        releaseFirst?.resume(returning: false)
        await fulfillment(of: [latestApplied], timeout: 2)
        XCTAssertEqual(deadlines, [now.addingTimeInterval(300), now.addingTimeInterval(310)])
        XCTAssertFalse(workbench.reminderUnavailable.contains(1), "被取消的旧请求不能覆盖新提醒的状态")
    }
}
