import SwiftData
import XCTest
@testable import Cook

@MainActor
final class FridgeTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return calendar
    }

    private func day(_ month: Int, _ day: Int, hour: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: month, day: day, hour: hour))!
    }

    func testFreshnessCountsCalendarDays() {
        let now = day(10, 20, hour: 23)
        XCTAssertEqual(Freshness(expiresAt: day(10, 19), now: now, calendar: calendar), .expired(days: 1))
        XCTAssertEqual(Freshness(expiresAt: day(10, 20), now: now, calendar: calendar), .today)
        XCTAssertEqual(Freshness(expiresAt: day(10, 21), now: now, calendar: calendar), .soon(days: 1))
        XCTAssertEqual(Freshness(expiresAt: day(10, 22), now: now, calendar: calendar), .soon(days: 2))
        XCTAssertEqual(Freshness(expiresAt: day(10, 23), now: now, calendar: calendar), .fresh(days: 3))
        XCTAssertEqual(Freshness.soon(days: 1).label, "明天到期")
        XCTAssertEqual(Freshness.soon(days: 2).label, "后天到期")
        XCTAssertEqual(Freshness.fresh(days: 3).label, "还剩 3 天")
        XCTAssertEqual(Freshness.expired(days: 2).label, "过期 2 天")
    }

    func testReminderPlanMergesSameDayAndSkipsPast() {
        // 10 月 20 日上午 10 点，已经过了今天 9 点的提醒
        let now = day(10, 20, hour: 10)
        let entries = [
            FridgeReminder.Entry(name: "菠菜", expiresAt: day(10, 21)),
            FridgeReminder.Entry(name: "鸡蛋", expiresAt: day(10, 21)),
            FridgeReminder.Entry(name: "番茄", expiresAt: day(10, 22)),
            FridgeReminder.Entry(name: "过期的豆腐", expiresAt: day(10, 18)),
        ]
        let plans = FridgeReminder.plan(entries, now: now, minutes: 9 * 60, calendar: calendar)

        XCTAssertEqual(plans.map(\.identifier), ["fridge-expiry-20261021", "fridge-expiry-20261022"])
        XCTAssertEqual(plans[0].fireDate, day(10, 21, hour: 9))
        XCTAssertEqual(plans[0].body, "菠菜、鸡蛋今天到期，番茄明天到期，看看能做什么吧。")
        XCTAssertEqual(plans[1].body, "番茄今天到期，看看能做什么吧。")
    }

    func testReminderPlanRespectsLimitAndShortensLongLists() {
        let now = day(10, 1)
        let entries = (2 ... 28).map { FridgeReminder.Entry(name: "第\($0)天", expiresAt: day(10, $0)) }
        XCTAssertEqual(FridgeReminder.plan(entries, now: now, minutes: 9 * 60, calendar: calendar, limit: 5).count, 5)
        XCTAssertEqual(FridgeReminder.names(["菠菜", "鸡蛋", "番茄", "豆腐"]), "菠菜、鸡蛋、番茄等 4 样")
    }

    func testAddAndRemoveFridgeItems() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let item = context.addToFridge("鸡蛋", days: 30, at: day(10, 20, hour: 18), calendar: calendar)
        XCTAssertEqual(item.expiresAt, day(11, 19))

        context.removeFromFridge([item], at: day(10, 21))
        let active = try context.fetch(FetchDescriptor<FridgeItem>(predicate: #Predicate { $0.deletedAt == nil }))
        XCTAssertTrue(active.isEmpty)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<FridgeItem>()), 1, "软删除，记录留着同步删除")
    }
}
