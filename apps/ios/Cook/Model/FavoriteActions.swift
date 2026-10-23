import Observation
import os
import SwiftData

/// 详情、列表滑动和长按共用保存及重试逻辑，只在落盘成功后更新成功反馈。
@MainActor
@Observable
final class FavoriteActions {
    struct Failure {
        let recipeId: Int
        let isFavorite: Bool

        var title: String { isFavorite ? "收藏未能保存" : "未能取消收藏" }
    }

    var failure: Failure?
    private(set) var savedCount = 0
    private let context: ModelContext
    private let save: (ModelContext) throws -> Void
    private let logger = Logger(subsystem: "fun.yunle.cook", category: "Favorites")

    init(context: ModelContext, save: @escaping (ModelContext) throws -> Void = { try $0.save() }) {
        self.context = context
        self.save = save
    }

    func set(_ recipeId: Int, isFavorite: Bool) {
        do {
            try context.setFavorite(recipeId, isFavorite: isFavorite, save: save)
            failure = nil
            savedCount += 1
        } catch {
            logger.error("保存收藏失败：\(String(describing: error), privacy: .private)")
            failure = Failure(recipeId: recipeId, isFavorite: isFavorite)
        }
    }

    func retry(_ failure: Failure) {
        set(failure.recipeId, isFavorite: failure.isFavorite)
    }
}
