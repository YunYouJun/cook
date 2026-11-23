import Foundation

/// 和网页版（食用手册）互通收藏和浏览记录：读写网页版「设置 → 收藏和历史」导出的 JSON。
/// 格式见 apps/web/app/utils/userData.ts：收藏按菜谱 id，浏览记录按菜名；时间都是毫秒。
/// 在云乐坊账号同步做好之前，用它把网页版的数据带到 App 里，或者反过来。
struct WebUserData: Equatable {
    static let app = "cook"
    static let version = 1

    struct FavoriteItem: Equatable {
        let recipeId: Int
        let timeMillis: Double
        var time: Date { Date(timeIntervalSince1970: timeMillis / 1000) }
    }

    struct VisitItem: Hashable {
        let name: String
        let timeMillis: Double
        var time: Date { Date(timeIntervalSince1970: timeMillis / 1000) }
    }

    /// 生效的收藏（已经去掉被取消的），按收藏时间从早到晚
    var favorites: [FavoriteItem]
    /// 生效的浏览记录，按菜名和时间去重，从早到晚。
    var visits: [VisitItem]

    /// SwiftData 本机库每道菜只保留最近一次浏览。
    var latestVisits: [VisitItem] {
        var seen = Set<String>()
        return Array(visits.reversed().filter { seen.insert($0.name).inserted }.reversed())
    }

    /// 解析网页版导出的文本；不是食用手册导出的数据时返回 nil，坏掉的条目直接跳过
    static func parse(_ text: String) -> WebUserData? {
        guard let data = text.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["app"] as? String == app,
              (object["version"] as? NSNumber)?.intValue == version
        else {
            return nil
        }

        // 同一道菜以最后一次操作为准，取消收藏和收藏同一时刻时算取消（和网页版的合并规则一致）
        var added: [Int: Double] = [:]
        for item in list(object["favorites"]) {
            if let id = recipeId(item["id"]), let time = time(item["time"]), time > (added[id] ?? -1) {
                added[id] = time
            }
        }
        var removed: [Int: Double] = [:]
        for item in list(object["favoriteRemovals"]) {
            if let id = recipeId(item["id"]), let time = time(item["time"]), time > (removed[id] ?? -1) {
                removed[id] = time
            }
        }
        let favorites = added
            .filter { id, time in (removed[id] ?? -1) < time }
            .map { FavoriteItem(recipeId: $0.key, timeMillis: $0.value) }
            .sorted { ($0.timeMillis, $0.recipeId) < ($1.timeMillis, $1.recipeId) }

        let clearedAt = time(object["historyClearedAt"]) ?? 0
        let removedVisits = Set(list(object["historyRemovals"]).compactMap { item -> VisitItem? in
            guard let name = name(item["name"]), let time = time(item["time"]) else {
                return nil
            }
            return VisitItem(name: name, timeMillis: time)
        })
        let visits = Set(list(object["histories"]).compactMap { item -> VisitItem? in
            guard let recipe = item["recipe"] as? [String: Any], let name = name(recipe["name"]), let time = time(item["time"]),
                  time > clearedAt
            else {
                return nil
            }
            return VisitItem(name: name, timeMillis: time)
        })
            .subtracting(removedVisits)
            .sorted { ($0.timeMillis, $0.name) < ($1.timeMillis, $1.name) }

        return WebUserData(favorites: favorites, visits: visits)
    }

    /// 生成网页版能导入的 JSON。recipes 用来补全浏览记录里的菜谱信息（网页版按菜名认浏览记录）
    static func export(
        favorites: [(recipeId: Int, time: Date)],
        favoriteRemovals: [(recipeId: Int, time: Date)],
        visits: [(recipe: [String: Any], time: Date)],
        visitRemovals: [(name: String, time: Date)],
        now: Date = Date()
    ) -> String {
        let object: [String: Any] = [
            "app": app,
            "version": version,
            "exportedAt": milliseconds(now),
            "favorites": favorites.map { ["id": $0.recipeId, "time": milliseconds($0.time)] },
            "favoriteRemovals": favoriteRemovals.map { ["id": $0.recipeId, "time": milliseconds($0.time)] },
            "histories": visits.map { ["recipe": $0.recipe, "time": milliseconds($0.time)] },
            "historyRemovals": visitRemovals.map { ["name": $0.name, "time": milliseconds($0.time)] },
            "historyClearedAt": 0,
        ]
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    static func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private static func list(_ value: Any?) -> [[String: Any]] {
        (value as? [Any])?.compactMap { $0 as? [String: Any] } ?? []
    }

    private static func recipeId(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber else {
            return nil
        }
        let double = number.doubleValue
        guard double >= 0, double == double.rounded(), double <= Double(Int.max) else {
            return nil
        }
        return Int(double)
    }

    private static func time(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber else {
            return nil
        }
        let double = number.doubleValue
        guard double.isFinite, double >= 0 else {
            return nil
        }
        return double
    }

    private static func name(_ value: Any?) -> String? {
        guard let name = value as? String, !name.isEmpty, name.utf16.count <= 200 else {
            return nil
        }
        return name
    }
}
