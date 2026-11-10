import CookCore
import Foundation

/// 估算日期始终是食用计划，不能由照片判断食物是否安全。
enum FridgeExpiryBasis: String, CaseIterable, Identifiable, Sendable {
    case estimated, package, calculated, manual

    var id: String { rawValue }
    var isSuggested: Bool { self == .estimated || self == .manual }
    var title: String {
        switch self {
        case .estimated: return "食用时间建议"
        case .package: return "包装标注日期"
        case .calculated: return "生产日期与保质期推算"
        case .manual: return "自己设定的食用计划"
        }
    }
}

struct ProduceStorageAdvice: Equatable {
    let days: Int
    let condition: String
    let source: URL

    static let source = URL(string: "https://extension.umaine.edu/food-health/2025/05/29/storing-and-washing-fresh-fruits-and-vegetables/")!
    static let fruitNames = ["草莓", "蓝莓", "树莓", "黑莓", "桃", "油桃", "梨", "苹果"]
    static let names = fruitNames + ["西兰花", "胡萝卜", "白萝卜", "玉米", "黄瓜", "茄子", "生菜", "菠菜", "青菜", "青椒", "西葫芦", "豆角", "荷兰豆"]

    /// 取公开储存建议区间的较短端，完整蔬果与切开蔬果分别计算。
    /// 不覆盖既有通用 shelf-life 数据；没有已核对条目的种类返回 nil。
    static func recommendation(for name: String, cut: Bool = false) -> Self? {
        if cut {
            guard names.contains(name) || ["番茄", "香蕉", "葡萄", "橙子", "菠萝", "西瓜", "土豆", "洋葱", "菌菇"].contains(name) else { return nil }
            // 切开不会延长完整食材的建议时间，例如蓝莓原本只建议 2 天。
            let days = min(name == "番茄" ? 2 : 3, recommendation(for: name)?.days ?? 3)
            return Self(days: days, condition: "切开后及时密封冷藏，保持 4℃ 或以下；从切开当天起算。", source: source)
        }
        let days: Int
        switch name {
        case "草莓", "蓝莓", "树莓", "黑莓": days = 2
        case "桃", "油桃", "梨": days = 5
        case "西兰花", "豆角": days = 3
        case "胡萝卜", "白萝卜": days = 7
        case "玉米", "茄子", "菠菜", "青菜": days = 1
        case "生菜": days = 5
        case "黄瓜": days = 7
        case "青椒": days = 14
        case "西葫芦", "荷兰豆": days = 2
        case "苹果":
            return Self(
                days: 28, condition: "完整苹果冷藏，保持 4℃ 或以下；从购买当天起算。",
                source: URL(string: "https://www.fda.gov/food/consumers/how-cut-food-waste-and-maintain-food-safety")!)
        default: return nil
        }
        let ripeness = ["桃", "油桃", "梨"].contains(name) ? "已成熟、完整" : "完整"
        return Self(days: days, condition: "\(ripeness)蔬果冷藏，保持 4℃ 或以下；从购买当天起算。", source: source)
    }

    func date(from day: Date, calendar: Calendar = .current) -> Date {
        calendar.date(byAdding: .day, value: days, to: calendar.startOfDay(for: day)) ?? day
    }
}

struct FridgeLabelScan: Identifiable, Sendable {
    let id = UUID()
    var text: String
    var names: [String]
    var productionDay: Date?
    var expiryDay: Date?
    var basis: FridgeExpiryBasis = .estimated
    var dateCandidates: [Date] = []
    var messages: [String] = []
}

/// 只接受带完整年份的日期。无标签、多个相互冲突的日期或非法日期都留给用户确认。
enum FridgeLabelParser {
    private static let datePattern =
        #"(?<![0-9])((?:19|20|21)[0-9]{2})(?:\s*[-./年]\s*([0-9]{1,2})\s*[-./月]\s*([0-9]{1,2})\s*日?|([0-9]{2})([0-9]{2}))(?![0-9])"#
    private static let productionLabels = #"生产日期|生产时间|制造日期|(?i:\bMFG\b|\bMFD\b|\bPROD\b)"#
    private static let expiryLabels = #"保质期至|有效期至|有效日期|到期日期|到期日|限期使用日期|食用期限|此日期前食用|(?i:\bEXP\b|\bUSE\s+BY\b|\bBEST\s+BEFORE\b)"#

    static func parse(_ text: String, names: [String] = [], now: Date = Date(), calendar: Calendar = .current) -> FridgeLabelScan {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        return parseGregorian(text, names: names, now: now, calendar: gregorian)
    }

    static func calculatedExpiry(productionDay: Date, labelText: String, calendar: Calendar = .current) -> Date? {
        guard let period = shelfLife(in: labelText) else { return nil }
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        return gregorian.date(byAdding: period.component, value: period.value, to: productionDay)
    }

    private static func parseGregorian(_ text: String, names: [String], now: Date, calendar: Calendar) -> FridgeLabelScan {
        var result = FridgeLabelScan(text: text, names: names)
        let allDates = matches(datePattern, in: text).compactMap { date($0, in: text, calendar: calendar) }
        result.dateCandidates = Array(Set(allDates)).sorted()
        let production = labeledDates(productionLabels, in: text, calendar: calendar)
        let expiry = labeledDates(expiryLabels, in: text, calendar: calendar)
        if production.count == 1, let day = production.first, day <= calendar.startOfDay(for: now) {
            result.productionDay = day
        } else if !production.isEmpty {
            result.messages.append("生产日期存在冲突或晚于今天，请核对包装。")
        }
        if expiry.count == 1, let day = expiry.first {
            if let productionDay = result.productionDay, day < productionDay {
                result.messages.append("到期日早于生产日期，请重新核对。")
            } else {
                result.expiryDay = day
                result.basis = .package
            }
        } else if expiry.count > 1 {
            result.messages.append("识别到多个到期日，请选择正确日期。")
        }
        if result.expiryDay == nil, matches(expiryLabels, in: text).isEmpty, let productionDay = result.productionDay,
            let calculated = calculatedExpiry(productionDay: productionDay, labelText: text, calendar: calendar)
        {
            result.expiryDay = calculated
            result.basis = .calculated
            result.messages.append("按生产日期加包装保质期推算，请核对起算方式及包装上的储存条件。")
        }
        if result.expiryDay == nil {
            result.messages.append("没有确认到期日。可选择识别出的日期，或按食材生成食用计划。")
        }
        return result
    }

    private static func labeledDates(_ labels: String, in text: String, calendar: Calendar) -> Set<Date> {
        let pattern = "(?:\(labels))[：:\\s]*" + datePattern
        return Set(matches(pattern, in: text).compactMap { date($0, in: text, calendar: calendar) })
    }

    private static func date(_ match: NSTextCheckingResult, in text: String, calendar: Calendar) -> Date? {
        func number(_ index: Int) -> Int? {
            guard let range = Range(match.range(at: index), in: text) else { return nil }
            return Int(text[range])
        }
        guard let year = number(1), let month = number(2) ?? number(4), let day = number(3) ?? number(5),
            let value = calendar.date(from: DateComponents(year: year, month: month, day: day))
        else { return nil }
        let parts = calendar.dateComponents([.year, .month, .day], from: value)
        guard parts.year == year, parts.month == month, parts.day == day else { return nil }
        return value
    }

    private static func shelfLife(in text: String) -> (component: Calendar.Component, value: Int)? {
        let pattern = #"(?:保质期|(?i:\bSHELF\s+LIFE\b))[：:\s]*([0-9]{1,4})\s*(天|日|个月|月|年|(?i:days?|months?|years?))"#
        let found = matches(pattern, in: text)
        guard found.count == 1, let match = found.first,
            let valueRange = Range(match.range(at: 1), in: text), let value = Int(text[valueRange]), value > 0,
            let unitRange = Range(match.range(at: 2), in: text)
        else { return nil }
        let unit = text[unitRange].lowercased()
        if ["天", "日", "day", "days"].contains(unit), value <= 3660 { return (.day, value) }
        if ["月", "个月", "month", "months"].contains(unit), value <= 120 { return (.month, value) }
        if ["年", "year", "years"].contains(unit), value <= 10 { return (.year, value) }
        return nil
    }

    private static func matches(_ pattern: String, in text: String) -> [NSTextCheckingResult] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
    }
}
