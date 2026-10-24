import CookCore
import Foundation
import Observation
import UserNotifications

enum KitchenIngredients {
    /// 只读取正文明确标记的原料章节，保留原文中的用量和“可选”，不从步骤猜材料。
    static func list(text: RecipeText?, fallback: [String]) -> [String] {
        var inIngredients = false
        var items: [String] = []
        for block in text?.blocks ?? [] {
            if case let .heading(title) = block {
                if inIngredients { break }
                inIngredients = title.trimmingCharacters(in: .whitespacesAndNewlines) == "必备原料和工具"
            } else if inIngredients, case let .list(_, _, entries) = block {
                items.append(contentsOf: entries)
            }
        }
        var seen: Set<String> = []
        return (items.isEmpty ? fallback : items).compactMap { item in
            let value = item.trimmingCharacters(in: .whitespacesAndNewlines)
            return !value.isEmpty && seen.insert(value).inserted ? value : nil
        }
    }
}

/// 只在操作时保存结束时间，不靠后台每秒执行来计时。
struct KitchenTimer: Codable, Equatable {
    var duration: TimeInterval = 300
    private(set) var pausedRemaining: TimeInterval = 300
    private(set) var deadline: Date?
    private(set) var hasStarted = false

    enum Phase { case ready, running, paused, finished }

    func remaining(at now: Date) -> TimeInterval {
        max(0, deadline.map { $0.timeIntervalSince(now) } ?? pausedRemaining)
    }

    func phase(at now: Date) -> Phase {
        guard hasStarted else { return .ready }
        guard remaining(at: now) > 0 else { return .finished }
        return deadline == nil ? .paused : .running
    }

    func display(at now: Date) -> String {
        let seconds = Int(ceil(remaining(at: now)))
        if seconds >= 3600 {
            return String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        }
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    mutating func configure(seconds: TimeInterval) {
        guard seconds.isFinite, (1 ... 86400).contains(seconds) else { return }
        duration = seconds
        reset()
    }

    mutating func start(at now: Date) {
        pausedRemaining = duration
        deadline = now.addingTimeInterval(duration)
        hasStarted = true
    }

    mutating func pause(at now: Date) {
        guard phase(at: now) == .running else { return }
        pausedRemaining = remaining(at: now)
        deadline = nil
    }

    mutating func resume(at now: Date) {
        guard phase(at: now) == .paused else { return }
        deadline = now.addingTimeInterval(pausedRemaining)
    }

    mutating func reset() {
        pausedRemaining = duration
        deadline = nil
        hasStarted = false
    }
}

struct KitchenProgress: Codable, Equatable {
    var prepared: Set<String> = []
    var timer = KitchenTimer()
}

/// 备料与推荐选择相互独立，不把“家里有”当成“已备好”。不参与 iCloud 同步。
@MainActor
@Observable
final class KitchenWorkbench {
    static let storageKey = "kitchen.workbench.v1"
    private(set) var progress: [String: KitchenProgress]
    private(set) var reminderUnavailable: Set<Int> = []
    @ObservationIgnored private let defaults: UserDefaults?
    @ObservationIgnored private let notifications: KitchenTimerNotifications
    @ObservationIgnored private var notificationTasks: [Int: Task<Void, Never>] = [:]

    init(defaults: UserDefaults? = .standard, notifications: KitchenTimerNotifications = .live) {
        self.defaults = defaults
        self.notifications = notifications
        progress = defaults?.data(forKey: Self.storageKey)
            .flatMap { try? JSONDecoder().decode([String: KitchenProgress].self, from: $0) } ?? [:]
    }

    func state(for recipeID: Int) -> KitchenProgress {
        progress[String(recipeID)] ?? KitchenProgress()
    }

    func togglePrepared(_ ingredient: String, recipeID: Int) {
        var state = state(for: recipeID)
        if !state.prepared.insert(ingredient).inserted { state.prepared.remove(ingredient) }
        save(state, recipeID: recipeID)
    }

    func clearPrepared(recipeID: Int) {
        var state = state(for: recipeID)
        state.prepared = []
        save(state, recipeID: recipeID)
    }

    func updateTimer(recipeID: Int, recipeName: String, _ update: (inout KitchenTimer) -> Void) {
        var state = state(for: recipeID)
        update(&state.timer)
        save(state, recipeID: recipeID)
        scheduleReminder(recipeID: recipeID, recipeName: recipeName, deadline: state.timer.deadline)
    }

    private func save(_ state: KitchenProgress, recipeID: Int) {
        progress[String(recipeID)] = state
        if let data = try? JSONEncoder().encode(progress) {
            defaults?.set(data, forKey: Self.storageKey)
        }
    }

    /// 先等旧请求退出再移除/添加，防止快速暂停、继续后留下过时的提醒。
    private func scheduleReminder(recipeID: Int, recipeName: String, deadline: Date?) {
        let previous = notificationTasks[recipeID]
        previous?.cancel()
        reminderUnavailable.remove(recipeID)
        notificationTasks[recipeID] = Task {
            await previous?.value
            guard !Task.isCancelled else { return }
            let available = await notifications.replace(recipeID, recipeName, deadline)
            guard !Task.isCancelled else { return }
            if !available { reminderUnavailable.insert(recipeID) }
            notificationTasks[recipeID] = nil
        }
    }
}

struct KitchenTimerNotifications {
    var replace: @MainActor (Int, String, Date?) async -> Bool

    /// 测试与预览不弹通知授权，也不影响真实提醒。
    static let disabled = Self { _, _, _ in true }

    static let live = Self { recipeID, recipeName, deadline in
        let center = UNUserNotificationCenter.current()
        let identifier = "kitchen-timer-\(recipeID)"
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
        guard let deadline, deadline > Date() else { return true }
        guard await FridgeReminder.requestAuthorization() else { return false }
        guard !Task.isCancelled else { return true }
        let content = UNMutableNotificationContent()
        content.title = "计时结束"
        content.body = "\(recipeName)的计时器响了，看看锅里吧。"
        content.sound = .default
        content.threadIdentifier = "kitchen"
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, deadline.timeIntervalSinceNow), repeats: false)
        do {
            try await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
            return true
        } catch {
            return false
        }
    }
}
