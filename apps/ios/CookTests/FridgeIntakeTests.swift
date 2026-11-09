import CookCore
import SwiftData
import UIKit
import XCTest

@testable import Cook

final class FridgeIntakeTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return calendar
    }

    private func day(_ month: Int, _ day: Int, year: Int = 2026) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    private func scan(_ text: String) -> FridgeLabelScan {
        FridgeLabelParser.parse(text, now: day(11, 10), calendar: calendar)
    }

    func testPackageExpiryTakesPrecedenceOverCalculation() {
        let result = scan("生产日期：2026年10月01日\n保质期：30天\n有效期至：2026-10-29")
        XCTAssertEqual(result.productionDay, day(10, 1))
        XCTAssertEqual(result.expiryDay, day(10, 29))
        XCTAssertEqual(result.basis, .package)
    }

    func testCompactAndMultilineDatesCalculateCalendarMonths() {
        let result = scan("生产日期\n20260131\n保质期：1个月")
        XCTAssertEqual(result.productionDay, day(1, 31))
        XCTAssertEqual(result.expiryDay, day(2, 28))
        XCTAssertEqual(result.basis, .calculated)
        XCTAssertEqual(scan("MFG: 2024/02/29\nShelf life: 1 year").expiryDay, day(2, 28, year: 2025))
    }

    func testAmbiguousUnlabeledDateDoesNotBecomeProductionOrExpiry() {
        let result = scan("2026.10.01\n保质期：30天")
        XCTAssertNil(result.productionDay)
        XCTAssertNil(result.expiryDay)
        XCTAssertEqual(result.dateCandidates, [day(10, 1)])
        XCTAssertNil(scan("11/10/2026").expiryDay)
    }

    func testInvalidFutureAndConflictingDatesRequireReview() {
        for text in [
            "生产日期：2026-11-11\n保质期：30天", "生产日期：2026-02-30\n保质期：30天",
            "生产日期：2026-10-01\n生产日期：2026-10-02\n保质期：30天",
            "生产日期：2026-10-01\nEXP: 2026-10-09\nEXP: 2026-10-10",
            "生产日期：2026-10-01\nEXP: 2026-02-30\n保质期：30天",
            "生产日期：2026-10-01\n有效期至：2026-09-30",
        ] {
            XCTAssertNil(scan(text).expiryDay, text)
        }
        XCTAssertTrue(scan("EXP: 2026-02-30").dateCandidates.isEmpty)
        XCTAssertNil(scan("批次 1202610019").expiryDay)
    }

    func testMissingProductionLabelAndMultipleDurationsDoNotGuess() {
        for text in [
            "生产日期：见包装喷码\n2026-10-01\n保质期：30天",
            "生产日期：2026-10-01\n保质期：30天\n保质期：7天",
            "生产日期：2026-10-01\n保质期：0天",
            "生产日期：2026-10-01\n保质期：9999天",
        ] {
            XCTAssertNil(scan(text).expiryDay, text)
        }
    }

    func testDateParsingUsesGregorianAndCallerTimezone() {
        var buddhist = Calendar(identifier: .buddhist)
        buddhist.timeZone = calendar.timeZone
        XCTAssertEqual(FridgeLabelParser.parse("EXP: 2026-11-20", calendar: buddhist).expiryDay, day(11, 20))
        var losAngeles = Calendar(identifier: .gregorian)
        losAngeles.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        let result = FridgeLabelParser.parse("EXP: 2026-11-20", calendar: losAngeles)
        XCTAssertEqual(losAngeles.component(.day, from: result.expiryDay!), 20)
        XCTAssertEqual(losAngeles.component(.hour, from: result.expiryDay!), 0)
    }

    func testProduceSuggestionsDependOnTypeAndCutState() {
        XCTAssertEqual(ProduceStorageAdvice.recommendation(for: "草莓")?.days, 2)
        XCTAssertEqual(ProduceStorageAdvice.recommendation(for: "梨")?.days, 5)
        XCTAssertEqual(ProduceStorageAdvice.recommendation(for: "苹果")?.days, 28)
        XCTAssertEqual(ProduceStorageAdvice.recommendation(for: "蓝莓")?.days, 2)
        XCTAssertLessThanOrEqual(
            ProduceStorageAdvice.recommendation(for: "蓝莓", cut: true)!.days,
            ProduceStorageAdvice.recommendation(for: "蓝莓")!.days)
        XCTAssertNil(ProduceStorageAdvice.recommendation(for: "西瓜"), "完整西瓜没有已核对的固定天数，不套用切开建议")
        XCTAssertEqual(ProduceStorageAdvice.recommendation(for: "西瓜", cut: true)?.days, 3)
        let cutApple = ProduceStorageAdvice.recommendation(for: "苹果", cut: true)!
        XCTAssertEqual(cutApple.days, 3)
        XCTAssertEqual(cutApple.date(from: day(10, 31), calendar: calendar), day(11, 3))
        XCTAssertNil(ProduceStorageAdvice.recommendation(for: "牛奶", cut: true))
        XCTAssertNil(ProduceStorageAdvice.recommendation(for: "未知食材"))
        XCTAssertTrue(cutApple.condition.contains("4℃"))
    }

    func testGenericAndLowConfidenceImageLabelsDoNotInventProduce() {
        XCTAssertEqual(FridgePhotoRecognition.imageNames([("fruit", 0.99), ("vegetable", 0.9), ("apple", 0.1)]), [])
        XCTAssertEqual(FridgePhotoRecognition.imageNames([("apple", 0.9), ("banana", 0.4), ("fruit", 0.99)]), ["苹果", "香蕉"])
    }

    @MainActor
    func testConfirmedMetadataPersistsAndOldItemsRemainSuggested() throws {
        let container = Persistence.makeContainer(inMemory: true)
        let context = container.mainContext
        let old = context.addToFridge("苹果", days: 5, at: day(11, 1), calendar: calendar)
        XCTAssertEqual(old.expiryBasis, .estimated)
        let item = FridgeItem(name: "牛奶", addedAt: day(11, 10), expiresAt: FridgeDay.stored(day(11, 20), calendar: calendar))
        item.productionDate = FridgeDay.stored(day(11, 1), calendar: calendar)
        item.expiryBasisRaw = FridgeExpiryBasis.calculated.rawValue
        item.storageAdvice = "按包装要求冷藏"
        context.insert(item)
        try context.save()
        let reopened = ModelContext(container)
        let saved = try XCTUnwrap(reopened.fetch(FetchDescriptor<FridgeItem>()).first { $0.name == "牛奶" })
        XCTAssertEqual(saved.expiryBasis, .calculated)
        XCTAssertEqual(saved.productionDate, item.productionDate)
        XCTAssertEqual(saved.storageAdvice, "按包装要求冷藏")
    }

    @MainActor
    func testExistingFridgeStoreMigratesWithoutLosingItems() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("fridge.store")
        try autoreleasepool {
            let schema = Schema([
                Favorite.self, HistoryEntry.self, Preferences.self, LegacyFridge.FridgeItem.self, PrivateRecipeRecord.self,
            ])
            let container = try ModelContainer(
                for: schema, configurations: ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none))
            let old = LegacyFridge.FridgeItem()
            old.name = "鸡蛋"
            old.expiresAt = FridgeDay.stored(day(11, 20), calendar: calendar)
            container.mainContext.insert(old)
            try container.mainContext.save()
        }
        try autoreleasepool {
            let schema = Schema(Persistence.models)
            let container = try ModelContainer(
                for: schema, configurations: ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none))
            let saved = try XCTUnwrap(container.mainContext.fetch(FetchDescriptor<FridgeItem>()).first)
            XCTAssertEqual(saved.name, "鸡蛋")
            XCTAssertEqual(saved.expiryDay(in: calendar), day(11, 20))
            XCTAssertEqual(saved.expiryBasis, .estimated)
            XCTAssertNil(saved.productionDate)
            saved.productionDate = FridgeDay.stored(day(11, 1), calendar: calendar)
            saved.expiryBasisRaw = FridgeExpiryBasis.package.rawValue
            try container.mainContext.save()
        }
        let schema = Schema(Persistence.models)
        let container = try ModelContainer(
            for: schema, configurations: ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none))
        let saved = try XCTUnwrap(container.mainContext.fetch(FetchDescriptor<FridgeItem>()).first)
        XCTAssertEqual(saved.expiryBasis, .package)
        XCTAssertEqual(saved.productionDate, FridgeDay.stored(day(11, 1), calendar: calendar))
    }

    func testRemindersSeparateSuggestionsFromPackageDates() {
        let entries = [
            FridgeReminder.Entry(name: "草莓", expiresAt: day(11, 11), isSuggested: true),
            FridgeReminder.Entry(name: "牛奶", expiresAt: day(11, 11)),
        ]
        let plans = FridgeReminder.plan(entries, now: day(11, 10), minutes: 540, calendar: calendar)
        XCTAssertEqual(plans[0].body, "牛奶明天到期，草莓建议明天吃完，看看能做什么吧。")
        XCTAssertEqual(plans[1].body, "牛奶今天到期，草莓建议今天吃完，看看能做什么吧。")
        XCTAssertEqual(Freshness.today.label(isSuggested: true), "建议今天吃完")
        XCTAssertEqual(Freshness.expired(days: 2).label(isSuggested: true), "已超建议 2 天")
    }

    @MainActor
    func testVisionReadsARealLabelImage() async throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 650)).image { renderer in
            UIColor.white.setFill()
            renderer.fill(CGRect(x: 0, y: 0, width: 1200, height: 650))
            ("MFG: 2026-10-01\nSHELF LIFE: 30 DAYS\nEXP: 2026-10-31" as NSString).draw(
                in: CGRect(x: 60, y: 60, width: 1080, height: 530),
                withAttributes: [.font: UIFont.systemFont(ofSize: 64), .foregroundColor: UIColor.black]
            )
        }
        let result = try await FridgePhotoRecognition.recognize(XCTUnwrap(image.pngData()), knownNames: [])
        XCTAssertEqual(result.productionDay, day(10, 1))
        XCTAssertEqual(result.expiryDay, day(10, 31), result.text)
        XCTAssertEqual(result.basis, .package)
    }

    @MainActor
    func testVisionReadsChineseProductionDateAndShelfLife() async throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1400, height: 650)).image { renderer in
            UIColor.white.setFill()
            renderer.fill(CGRect(x: 0, y: 0, width: 1400, height: 650))
            ("鲜牛奶\n生产日期：2026年10月01日\n保质期：30天" as NSString).draw(
                in: CGRect(x: 60, y: 60, width: 1280, height: 530),
                withAttributes: [.font: UIFont.systemFont(ofSize: 64), .foregroundColor: UIColor.black])
        }
        let result = try await FridgePhotoRecognition.recognize(XCTUnwrap(image.pngData()), knownNames: ["牛奶"])
        XCTAssertEqual(result.names, ["牛奶"])
        XCTAssertEqual(result.productionDay, day(10, 1), result.text)
        XCTAssertEqual(result.expiryDay, day(10, 31), result.text)
        XCTAssertEqual(result.basis, .calculated)
    }

    func testUnreadableImageFailsWithoutReturningGuessedDates() async {
        do {
            _ = try await FridgePhotoRecognition.recognize(Data("invalid".utf8), knownNames: [])
            XCTFail("Invalid image should fail")
        } catch {
            XCTAssertTrue(error is FridgePhotoRecognition.Failure)
        }
    }
}

private enum LegacyFridge {
    @Model
    final class FridgeItem {
        var name: String = ""
        var addedAt: Date = Date()
        var expiresAt: Date = Date()
        var createdAt: Date = Date()
        var updatedAt: Date = Date()
        var deletedAt: Date?

        init() {}
    }
}
