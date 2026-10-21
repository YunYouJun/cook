import Foundation
import Observation
import UserNotifications

/// 冰箱里的日期只记年月日：存成那天的 UTC 0 点，用的时候换成当地那天的 0 点。
/// 直接存当地 0 点的话，同步到别的时区（或者人出了国）会差一天
enum FridgeDay {
    private static let utc: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    /// 当地的某一天 → 存储用的日期
    static func stored(_ day: Date, calendar: Calendar = .current) -> Date {
        utc.date(from: gregorian(calendar).dateComponents([.year, .month, .day], from: day)) ?? day
    }

    /// 存储的日期 → 当地那天的 0 点
    static func local(_ stored: Date, calendar: Calendar = .current) -> Date {
        gregorian(calendar).date(from: utc.dateComponents([.year, .month, .day], from: stored)) ?? stored
    }

    /// 用户的日历可能是和历、佛历，年月日统一按公历换算，只取它的时区
    private static func gregorian(_ calendar: Calendar) -> Calendar {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        return gregorian
    }
}

/// 离到期还有几天，按自然日算：0 是今天到期，负数是过期了几天
enum Freshness: Equatable {
    case expired(days: Int)
    case today
    case soon(days: Int)
    case fresh(days: Int)

    /// 今天、明天、后天到期的算「快过期」
    static let soonDays = 2

    init(expiresAt: Date, now: Date = Date(), calendar: Calendar = .current) {
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: now), to: calendar.startOfDay(for: expiresAt)).day ?? 0
        if days < 0 {
            self = .expired(days: -days)
        } else if days == 0 {
            self = .today
        } else if days <= Self.soonDays {
            self = .soon(days: days)
        } else {
            self = .fresh(days: days)
        }
    }

    var label: String {
        switch self {
        case let .expired(days): return "过期 \(days) 天"
        case .today: return "今天到期"
        case .soon(1): return "明天到期"
        case .soon(2): return "后天到期"
        case let .soon(days), let .fresh(days): return "还剩 \(days) 天"
        }
    }

    var isExpired: Bool {
        if case .expired = self {
            return true
        }
        return false
    }

    /// 今天到期或者快过期，要先用掉
    var needsAttention: Bool {
        switch self {
        case .today, .soon: return true
        default: return false
        }
    }
}

/// 临期提醒：每天最多一条本地通知，汇总今天和明天到期的食材。全在本机排程，不经过服务器
enum FridgeReminder {
    static let enabledKey = "fridge.reminder.enabled"
    /// 每天几点提醒，存从 0 点起的分钟数
    static let timeKey = "fridge.reminder.minutes"
    static let defaultMinutes = 9 * 60
    /// 冰箱页「开启临期提醒」的卡片关掉后不再出现
    static let promptDismissedKey = "fridge.reminder.promptDismissed"
    static let identifierPrefix = "fridge-expiry-"
    /// 系统最多给一个 App 排 64 条，留足余量
    static let limit = 30

    struct Entry: Equatable {
        var name: String
        var expiresAt: Date
    }

    struct Plan: Equatable {
        var identifier: String
        var fireDate: Date
        var title: String
        var body: String
    }

    /// 每样食材在到期前一天和到期当天各提醒一次；同一天的合成一条，已经过了提醒时间的跳过
    static func plan(_ entries: [Entry], now: Date, minutes: Int, calendar: Calendar = .current, limit: Int = limit) -> [Plan] {
        var byExpiryDay: [Date: [String]] = [:]
        for entry in entries {
            byExpiryDay[calendar.startOfDay(for: entry.expiresAt), default: []].append(entry.name)
        }
        var days = Set<Date>()
        for expiryDay in byExpiryDay.keys {
            days.insert(expiryDay)
            // 加减一天后再取当天 0 点：零点切夏令时的地区（比如智利）那天是从 1 点开始的
            if let dayBefore = calendar.date(byAdding: .day, value: -1, to: expiryDay) {
                days.insert(calendar.startOfDay(for: dayBefore))
            }
        }

        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyyMMdd"

        var plans: [Plan] = []
        for day in days.sorted() {
            // 按当天的时和分算，不是 0 点加几分钟：夏令时切换那天两者差一小时
            guard let fireDate = calendar.date(bySettingHour: minutes / 60, minute: minutes % 60, second: 0, of: day), fireDate > now else {
                continue
            }
            let today = uniqueStrings(byExpiryDay[day] ?? [])
            let tomorrow = calendar.date(byAdding: .day, value: 1, to: day).flatMap { byExpiryDay[calendar.startOfDay(for: $0)] }.map(uniqueStrings) ?? []
            var parts: [String] = []
            if !today.isEmpty {
                parts.append("\(names(today))今天到期")
            }
            if !tomorrow.isEmpty {
                parts.append("\(names(tomorrow))明天到期")
            }
            plans.append(Plan(
                identifier: identifierPrefix + formatter.string(from: day),
                fireDate: fireDate,
                title: "冰箱里有食材快到期了",
                body: parts.joined(separator: "，") + "，看看能做什么吧。"
            ))
            if plans.count == limit {
                break
            }
        }
        return plans
    }

    /// 最多写出三样，多了用「等 N 样」
    static func names(_ list: [String]) -> String {
        list.count > 3 ? list.prefix(3).joined(separator: "、") + "等 \(list.count) 样" : list.joined(separator: "、")
    }

    /// 弹系统的通知授权；已经拒绝过时直接返回 false，需要用户去设置里打开
    static func requestAuthorization() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return true
        case .denied:
            return false
        default:
            return (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        }
    }

    /// 正在进行的重排；新的一次要等它停下再开始，免得旧的在新的清空之后又把过时的提醒加回去
    @MainActor private static var running: Task<Void, Never>?

    /// 冰箱里的食材或提醒设置变了就整份重排：先清掉之前排的，再按现在的食材排。
    /// 连续触发时取消上一次、排队执行，最后留下的总是最新的一份
    @MainActor
    static func reschedule(_ entries: [Entry], defaults: UserDefaults = .standard) {
        let previous = running
        previous?.cancel()
        running = Task {
            await previous?.value
            guard !Task.isCancelled else {
                return
            }
            await apply(entries, defaults: defaults, now: Date())
        }
    }

    private static func apply(_ entries: [Entry], defaults: UserDefaults, now: Date) async {
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests()
        center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier).filter { $0.hasPrefix(identifierPrefix) })
        guard defaults.bool(forKey: enabledKey) else {
            return
        }
        let status = await center.notificationSettings().authorizationStatus
        guard status == .authorized || status == .provisional || status == .ephemeral, !Task.isCancelled else {
            return
        }
        let minutes = defaults.object(forKey: timeKey) as? Int ?? defaultMinutes
        let calendar = Calendar.current
        for plan in plan(entries, now: now, minutes: minutes, calendar: calendar) {
            guard !Task.isCancelled else {
                return
            }
            let content = UNMutableNotificationContent()
            content.title = plan.title
            content.body = plan.body
            content.sound = .default
            content.threadIdentifier = "fridge"
            content.userInfo = ["tab": "fridge"]
            let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: plan.fireDate)
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: plan.identifier, content: content, trigger: trigger))
        }
    }
}

extension Notification.Name {
    /// 点开了临期通知，各个窗口切到冰箱首页
    static let showFridgeHome = Notification.Name("fridge.showHome")
}

/// 点通知冷启动时，回调可能比窗口先到，广播没人收；先记下来，窗口出现时再取
@MainActor
enum PendingFridgeHome {
    static var requested = false

    /// 有没处理的请求就返回 true，并清掉
    static func take() -> Bool {
        defer { requested = false }
        return requested
    }
}

/// 一个窗口当前在哪个 Tab；iPad 上每个窗口各有一份。点开临期通知时切到冰箱，并退回冰箱首页
@Observable
final class AppRouter {
    var tab: AppTab = .cook
    /// 每次要显示冰箱首页时加一，冰箱页看到变化就清掉已经点进去的页面和 sheet
    private(set) var fridgeHomeRequests = 0

    func showFridgeHome() {
        tab = .fridge
        fridgeHomeRequests += 1
    }
}

/// App 在前台时也显示临期通知，点开通知进冰箱页
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_: UNUserNotificationCenter, willPresent _: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    func userNotificationCenter(_: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.notification.request.identifier.hasPrefix(FridgeReminder.identifierPrefix) else {
            return
        }
        await MainActor.run {
            PendingFridgeHome.requested = true
            NotificationCenter.default.post(name: .showFridgeHome, object: nil)
        }
    }
}
