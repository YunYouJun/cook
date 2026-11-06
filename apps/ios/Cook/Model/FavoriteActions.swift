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
        var accountID: String? = nil

        var title: String { isFavorite ? "收藏未能保存" : "未能取消收藏" }
    }

    var failure: Failure?
    var accountData: AccountUserDataStore?
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
            if let accountData, accountData.usesAccountLibrary {
                try accountData.setFavorite(recipeId, enabled: isFavorite)
            } else {
                try context.setFavorite(recipeId, isFavorite: isFavorite, save: save)
            }
            failure = nil
            savedCount += 1
        } catch {
            logger.error("保存收藏失败：\(String(describing: error), privacy: .private)")
            failure = Failure(recipeId: recipeId, isFavorite: isFavorite,
                              accountID: accountData?.usesAccountLibrary == true ? accountData?.ownerID : nil)
        }
    }

    func retry(_ failure: Failure) {
        let currentID = accountData?.usesAccountLibrary == true ? accountData?.ownerID : nil
        guard currentID == failure.accountID else { self.failure = nil; return }
        set(failure.recipeId, isFavorite: failure.isFavorite)
    }
}
