import CookCore
import Foundation
import SwiftData

// CloudKit 同步要求：属性都有默认值、不用唯一约束。所以同一道菜可能有多条记录，读取时按 recipeId 去重。
// 删除只打 deletedAt 标记（软删除），这样以后会员走 CloudBase 同步时也能把删除同步过去。

/// 收藏的菜谱
@Model
final class Favorite {
    var recipeId: Int = 0
    var createdAt: Date = Date()
    var updatedAt: Date = Date()
    var deletedAt: Date?

    init(recipeId: Int, at date: Date = Date()) {
        self.recipeId = recipeId
        createdAt = date
        updatedAt = date
    }
}

/// 浏览历史，每道菜只留最近一次
@Model
final class HistoryEntry {
    var recipeId: Int = 0
    var visitedAt: Date = Date()
    var updatedAt: Date = Date()
    var deletedAt: Date?

    init(recipeId: Int, at date: Date = Date()) {
        self.recipeId = recipeId
        visitedAt = date
        updatedAt = date
    }
}

/// 做菜偏好：家里常备、忌口、不吃辣和推荐模式。整个 App 只用一条
@Model
final class Preferences {
    var pantry: [String] = []
    var avoid: [String] = []
    var noSpicy: Bool = false
    var mode: String = "loose" // RecommendationMode.loose
    var createdAt: Date = Date()
    var updatedAt: Date = Date()

    init(pantry: [String]) {
        self.pantry = pantry
    }
}

/// 冰箱里的一样食材。同一种可以放好几份（比如前天和今天各买了一盒鸡蛋），各记各的到期日
@Model
final class FridgeItem {
    var name: String = ""
    /// 放进冰箱的那天
    var addedAt: Date = Date()
    /// 最后一天能吃，默认按 shelf-life.json 估。只代表日期，存成那天的 UTC 0 点（见 FridgeDay），
    /// 换了时区或同步到别的时区的设备也还是同一天；用的时候取 expiryDay(in:)
    var expiresAt: Date = Date()
    var createdAt: Date = Date()
    var updatedAt: Date = Date()
    var deletedAt: Date?

    init(name: String, addedAt: Date, expiresAt: Date) {
        self.name = name
        self.addedAt = addedAt
        self.expiresAt = expiresAt
        createdAt = addedAt
        updatedAt = addedAt
    }

    /// 到期那天在当前时区的 0 点，算剩几天、排提醒和选日期都用它
    func expiryDay(in calendar: Calendar = .current) -> Date {
        FridgeDay.local(expiresAt, calendar: calendar)
    }
}

enum Persistence {
    static let models: [any PersistentModel.Type] = [Favorite.self, HistoryEntry.self, Preferences.self, FridgeItem.self]

    /// 软删除的记录保留多久，过期后真正删掉
    static let tombstoneLifetime: TimeInterval = 30 * 24 * 60 * 60

    /// 有 iCloud 权限时通过 CloudKit 同步，否则只存在本机
    static func makeContainer(inMemory: Bool = false) -> ModelContainer {
        let schema = Schema(models)
        if !inMemory {
            // 首次启动时 Application Support 还不存在，先建好再让 SwiftData 建库
            try? FileManager.default.createDirectory(at: .applicationSupportDirectory, withIntermediateDirectories: true)
            let cloud = ModelConfiguration(schema: schema, cloudKitDatabase: .automatic)
            if let container = try? ModelContainer(for: schema, configurations: cloud) {
                return container
            }
        }
        let local = ModelConfiguration(schema: schema, isStoredInMemoryOnly: inMemory, cloudKitDatabase: .none)
        do {
            return try ModelContainer(for: schema, configurations: local)
        } catch {
            fatalError("无法创建本地数据库：\(error)")
        }
    }
}

extension ModelContext {
    func isFavorite(_ recipeId: Int) -> Bool {
        favorites(for: recipeId).contains { $0.deletedAt == nil }
    }

    func toggleFavorite(_ recipeId: Int, at date: Date = Date()) {
        let existing = favorites(for: recipeId)
        let active = existing.filter { $0.deletedAt == nil }
        if !active.isEmpty {
            for favorite in active {
                favorite.deletedAt = date
                favorite.updatedAt = date
            }
        } else if let revived = existing.first {
            revived.deletedAt = nil
            revived.createdAt = date
            revived.updatedAt = date
        } else {
            insert(Favorite(recipeId: recipeId, at: date))
        }
        try? save()
    }

    /// 记一次浏览，只保留最近 limit 道菜
    func recordVisit(_ recipeId: Int, at date: Date = Date(), limit: Int = 100) {
        let descriptor = FetchDescriptor<HistoryEntry>(predicate: #Predicate<HistoryEntry> { $0.recipeId == recipeId })
        let existing = (try? fetch(descriptor)) ?? []
        if let first = existing.first {
            first.visitedAt = date
            first.updatedAt = date
            first.deletedAt = nil
            existing.dropFirst().forEach { delete($0) }
        } else {
            insert(HistoryEntry(recipeId: recipeId, at: date))
        }

        let all = (try? fetch(FetchDescriptor<HistoryEntry>(sortBy: [SortDescriptor(\.visitedAt, order: .reverse)]))) ?? []
        let active = all.filter { $0.deletedAt == nil }
        for entry in active.dropFirst(limit) {
            entry.deletedAt = date
            entry.updatedAt = date
        }
        purgeTombstones(all, now: date)
        try? save()
    }

    /// 清空浏览记录
    func clearHistory(at date: Date = Date()) {
        let all = (try? fetch(FetchDescriptor<HistoryEntry>())) ?? []
        for entry in all where entry.deletedAt == nil {
            entry.deletedAt = date
            entry.updatedAt = date
        }
        try? save()
    }

    /// 取唯一的偏好记录；没有就按默认常备调料建一条。多台设备各建了一条时，留最早的那条，其余合并后删掉
    func preferences(defaultPantry: [String]) -> Preferences {
        let all = (try? fetch(FetchDescriptor<Preferences>(sortBy: [SortDescriptor(\.createdAt)]))) ?? []
        guard let first = all.first else {
            let created = Preferences(pantry: defaultPantry)
            insert(created)
            try? save()
            return created
        }
        let duplicates = all.dropFirst()
        if let newest = all.max(by: { $0.updatedAt < $1.updatedAt }), newest !== first {
            first.pantry = newest.pantry
            first.avoid = newest.avoid
            first.noSpicy = newest.noSpicy
            first.mode = newest.mode
            first.updatedAt = newest.updatedAt
        }
        if !duplicates.isEmpty {
            duplicates.forEach { delete($0) }
            try? save()
        }
        return first
    }

    /// 合并网页版导出的收藏和浏览记录，返回新加了几个收藏、几条浏览记录。
    /// 只加不减：App 里已有的不动；在 App 里取消收藏的时间比网页版收藏的晚时，以 App 为准
    @discardableResult
    func importWebData(_ data: WebUserData, recipeIdByName: [String: Int], validIds: Set<Int>, historyLimit: Int = 100) -> (favorites: Int, visits: Int) {
        var addedFavorites = 0
        for favorite in data.favorites where validIds.contains(favorite.recipeId) {
            let existing = favorites(for: favorite.recipeId)
            if existing.contains(where: { $0.deletedAt == nil }) {
                continue
            }
            if let removedAt = existing.compactMap(\.deletedAt).max(), removedAt >= favorite.time {
                continue
            }
            if let revived = existing.first {
                revived.deletedAt = nil
                revived.createdAt = favorite.time
                revived.updatedAt = Date()
            } else {
                let created = Favorite(recipeId: favorite.recipeId, at: favorite.time)
                created.updatedAt = Date()
                insert(created)
            }
            addedFavorites += 1
        }

        var addedVisits = 0
        for visit in data.visits {
            guard let recipeId = recipeIdByName[visit.name] else {
                continue
            }
            let descriptor = FetchDescriptor<HistoryEntry>(predicate: #Predicate<HistoryEntry> { $0.recipeId == recipeId })
            let existing = (try? fetch(descriptor)) ?? []
            if let entry = existing.first {
                // 在 App 里删过、或者 App 里看得更晚的，都不动
                if let deletedAt = entry.deletedAt, deletedAt >= visit.time {
                    continue
                }
                if entry.deletedAt == nil, entry.visitedAt >= visit.time {
                    continue
                }
                entry.visitedAt = visit.time
                entry.deletedAt = nil
                entry.updatedAt = Date()
            } else {
                insert(HistoryEntry(recipeId: recipeId, at: visit.time))
            }
            addedVisits += 1
        }

        if addedVisits > 0 {
            let all = (try? fetch(FetchDescriptor<HistoryEntry>(sortBy: [SortDescriptor(\.visitedAt, order: .reverse)]))) ?? []
            let now = Date()
            for entry in all.filter({ $0.deletedAt == nil }).dropFirst(historyLimit) {
                entry.deletedAt = now
                entry.updatedAt = now
            }
        }
        try? save()
        return (addedFavorites, addedVisits)
    }

    /// 放进冰箱，到期日按放入那天加上默认天数
    @discardableResult
    func addToFridge(_ name: String, days: Int, at date: Date = Date(), calendar: Calendar = .current) -> FridgeItem {
        let today = calendar.startOfDay(for: date)
        let expiryDay = calendar.date(byAdding: .day, value: days, to: today) ?? today
        let item = FridgeItem(name: name, addedAt: date, expiresAt: FridgeDay.stored(expiryDay, calendar: calendar))
        insert(item)
        try? save()
        return item
    }

    /// 吃完了或者扔了：软删除，其他设备同步后也会消失
    func removeFromFridge(_ items: [FridgeItem], at date: Date = Date()) {
        for item in items where item.deletedAt == nil {
            item.deletedAt = date
            item.updatedAt = date
        }
        purgeFridgeTombstones(now: date)
        try? save()
    }

    func setExpiry(_ item: FridgeItem, to date: Date, calendar: Calendar = .current) {
        item.expiresAt = FridgeDay.stored(date, calendar: calendar)
        item.updatedAt = Date()
        try? save()
    }

    private func purgeFridgeTombstones(now: Date) {
        let removed = (try? fetch(FetchDescriptor<FridgeItem>(predicate: #Predicate<FridgeItem> { $0.deletedAt != nil }))) ?? []
        for item in removed {
            if let deletedAt = item.deletedAt, now.timeIntervalSince(deletedAt) > Persistence.tombstoneLifetime {
                delete(item)
            }
        }
    }

    private func favorites(for recipeId: Int) -> [Favorite] {
        let descriptor = FetchDescriptor<Favorite>(predicate: #Predicate<Favorite> { $0.recipeId == recipeId })
        return (try? fetch(descriptor)) ?? []
    }

    private func purgeTombstones(_ entries: [HistoryEntry], now: Date) {
        for entry in entries {
            if let deletedAt = entry.deletedAt, now.timeIntervalSince(deletedAt) > Persistence.tombstoneLifetime {
                delete(entry)
            }
        }
    }
}

/// 按 recipeId 去重，保留第一次出现的顺序
func uniqueRecipeIds(_ ids: [Int]) -> [Int] {
    var seen = Set<Int>()
    return ids.filter { seen.insert($0).inserted }
}

extension WebUserData {
    /// 把 App 里的收藏和浏览记录（含软删除的）导出成网页版能导入的 JSON：
    /// 收藏按 id，浏览记录带上菜谱信息（网页版按菜名认）
    static func export(favorites: [Favorite], visits: [HistoryEntry], recipesById: [Int: Recipe], now: Date = Date()) -> String {
        var active: [Int: Date] = [:]
        var removed: [Int: Date] = [:]
        for favorite in favorites {
            if let deletedAt = favorite.deletedAt {
                removed[favorite.recipeId] = max(removed[favorite.recipeId] ?? .distantPast, deletedAt)
            } else {
                active[favorite.recipeId] = min(active[favorite.recipeId] ?? .distantFuture, favorite.createdAt)
            }
        }
        removed = removed.filter { active[$0.key] == nil }

        var exportedVisits: [(recipe: [String: Any], time: Date)] = []
        var visitRemovals: [(name: String, time: Date)] = []
        for entry in visits.sorted(by: { $0.visitedAt < $1.visitedAt }) {
            guard let recipe = recipesById[entry.recipeId] else {
                continue
            }
            if entry.deletedAt == nil {
                exportedVisits.append((recipe.webExport, entry.visitedAt))
            } else {
                visitRemovals.append((recipe.name, entry.visitedAt))
            }
        }

        return export(
            favorites: active.map { (recipeId: $0.key, time: $0.value) }.sorted { ($0.time, $0.recipeId) < ($1.time, $1.recipeId) },
            favoriteRemovals: removed.map { (recipeId: $0.key, time: $0.value) }.sorted { ($0.time, $0.recipeId) < ($1.time, $1.recipeId) },
            visits: exportedVisits,
            visitRemovals: visitRemovals,
            now: now
        )
    }
}

extension Recipe {
    /// 网页版浏览记录里存的菜谱字段（apps/web/app/utils/userData.ts 的 readRecipe）
    var webExport: [String: Any] {
        var object: [String: Any] = ["id": id, "name": name, "stuff": stuff, "tools": tools, "tags": tags, "methods": methods]
        if let link, !link.isEmpty {
            object["link"] = link
        }
        if let bv, !bv.isEmpty {
            object["bv"] = bv
        }
        if let difficulty, !difficulty.isEmpty {
            object["difficulty"] = difficulty
        }
        if let emojis {
            object["emojis"] = emojis
        }
        return object
    }
}

/// 去重，保留第一次出现的顺序
func uniqueStrings(_ names: [String]) -> [String] {
    var seen = Set<String>()
    return names.filter { seen.insert($0).inserted }
}
