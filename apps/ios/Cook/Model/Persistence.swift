import Foundation
import SwiftData

// CloudKit 同步要求：属性都有默认值、不用唯一约束，所以重复记录在读取时按 recipeId 去重

/// 收藏的菜谱
@Model
final class FavoriteRecipe {
    var recipeId: Int = 0
    var createdAt: Date = Date()

    init(recipeId: Int, createdAt: Date = Date()) {
        self.recipeId = recipeId
        self.createdAt = createdAt
    }
}

/// 浏览历史，每道菜只留最近一次
@Model
final class RecipeVisit {
    var recipeId: Int = 0
    var visitedAt: Date = Date()

    init(recipeId: Int, visitedAt: Date = Date()) {
        self.recipeId = recipeId
        self.visitedAt = visitedAt
    }
}

enum Persistence {
    static let models: [any PersistentModel.Type] = [FavoriteRecipe.self, RecipeVisit.self]

    /// 有 iCloud 权限时通过 CloudKit 同步，否则只存在本机
    static func makeContainer(inMemory: Bool = false) -> ModelContainer {
        let schema = Schema(models)
        if !inMemory {
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
        !favorites(for: recipeId).isEmpty
    }

    func toggleFavorite(_ recipeId: Int) {
        let existing = favorites(for: recipeId)
        if existing.isEmpty {
            insert(FavoriteRecipe(recipeId: recipeId))
        } else {
            existing.forEach { delete($0) }
        }
        try? save()
    }

    /// 记一次浏览，只保留最近 limit 道菜
    func recordVisit(_ recipeId: Int, at date: Date = Date(), limit: Int = 100) {
        let descriptor = FetchDescriptor<RecipeVisit>(predicate: #Predicate<RecipeVisit> { $0.recipeId == recipeId })
        let existing = (try? fetch(descriptor)) ?? []
        if let first = existing.first {
            first.visitedAt = date
            existing.dropFirst().forEach { delete($0) }
        } else {
            insert(RecipeVisit(recipeId: recipeId, visitedAt: date))
        }
        try? save()

        let all = (try? fetch(FetchDescriptor<RecipeVisit>(sortBy: [SortDescriptor(\.visitedAt, order: .reverse)]))) ?? []
        if all.count > limit {
            all.dropFirst(limit).forEach { delete($0) }
            try? save()
        }
    }

    private func favorites(for recipeId: Int) -> [FavoriteRecipe] {
        let descriptor = FetchDescriptor<FavoriteRecipe>(predicate: #Predicate<FavoriteRecipe> { $0.recipeId == recipeId })
        return (try? fetch(descriptor)) ?? []
    }
}

/// 按 recipeId 去重，保留第一次出现的顺序
func uniqueRecipeIds(_ ids: [Int]) -> [Int] {
    var seen = Set<Int>()
    return ids.filter { seen.insert($0).inserted }
}
