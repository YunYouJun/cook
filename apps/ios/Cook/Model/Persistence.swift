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

enum Persistence {
    static let models: [any PersistentModel.Type] = [Favorite.self, HistoryEntry.self, Preferences.self]

    /// 软删除的记录保留多久，过期后真正删掉
    static let tombstoneLifetime: TimeInterval = 30 * 24 * 60 * 60

    /// 有 iCloud 权限时通过 CloudKit 同步，否则只存在本机
    static func makeContainer(inMemory: Bool = false) -> ModelContainer {
        let schema = Schema(models)
        if !inMemory {
            // 首次启动时 Application Support 还不存在，先建好再让 SwiftData 建库
            try? FileManager.default.createDirectory(at: .applicationSupportDirectory, withIntermediateDirectories: true)
            let cloud = ModelConfiguration(schema: schema, cloudKitDatabase: .automatic)
            #if DEBUG
                CloudKitSchema.initializeIfNeeded(models: models, storeURL: cloud.url)
            #endif
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
