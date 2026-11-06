import Foundation

/// Web、云函数与原生端共用的 v1 文档。所有时间均为 Unix 毫秒。
public struct UserDataDocument: Codable, Equatable, Sendable {
    public struct Favorite: Codable, Equatable, Sendable {
        public var id: Int
        public var time: Double
        public init(id: Int, time: Double) { self.id = id; self.time = time }
    }

    public struct RecipeSnapshot: Codable, Equatable, Sendable {
        public var name: String
        public var stuff: [String]
        public var tools: [String]
        public var link: String?
        public var bv: String?
        public var difficulty: String?
        public var emojis: [String]?
        public var tags: [String]?
        public var methods: [String]?

        public init(recipe: Recipe) {
            name = recipe.name; stuff = recipe.stuff; tools = recipe.tools
            link = recipe.link; bv = recipe.bv; difficulty = recipe.difficulty
            emojis = recipe.emojis; tags = recipe.tags; methods = recipe.methods
        }

        fileprivate var orderingKey: String {
            let fields: [Any] = [name, stuff, tools, link as Any? ?? NSNull(), bv as Any? ?? NSNull(),
                                 difficulty as Any? ?? NSNull(), emojis as Any? ?? NSNull(),
                                 tags as Any? ?? NSNull(), methods as Any? ?? NSNull()]
            return String(decoding: (try? JSONSerialization.data(withJSONObject: fields, options: .withoutEscapingSlashes)) ?? Data(), as: UTF8.self)
        }
    }

    public struct Visit: Codable, Equatable, Sendable {
        public var recipe: RecipeSnapshot
        public var time: Double
        public init(recipe: RecipeSnapshot, time: Double) { self.recipe = recipe; self.time = time }
    }

    public struct Removal: Codable, Equatable, Sendable {
        public var name: String
        public var time: Double
        public init(name: String, time: Double) { self.name = name; self.time = time }
    }

    public var version = 1
    public var favorites: [Favorite] = []
    public var favoriteRemovals: [Favorite] = []
    public var histories: [Visit] = []
    public var historyRemovals: [Removal] = []
    public var historyClearedAt: Double = 0
    public init() {}

    public enum Failure: Error { case invalidDocument }

    public static func decode(_ data: Data) throws -> Self {
        guard data.count <= 256 * 1024 else { throw Failure.invalidDocument }
        let value = try JSONDecoder().decode(Self.self, from: data)
        try value.validate()
        return value
    }

    public func validate() throws {
        let validTime: (Double) -> Bool = { $0.isFinite && $0 >= 0 }
        let validName: (String) -> Bool = { !$0.isEmpty && $0.utf16.count <= 200 }
        guard version == 1, favorites.count <= 5000, favoriteRemovals.count <= 1000,
              histories.count <= 500, historyRemovals.count <= 1000,
              validTime(historyClearedAt),
              (favorites + favoriteRemovals).allSatisfy({ $0.id >= 0 && validTime($0.time) }),
              histories.allSatisfy({ validName($0.recipe.name) && validTime($0.time) }),
              historyRemovals.allSatisfy({ validName($0.name) && validTime($0.time) }),
              try JSONEncoder().encode(self).count <= 256 * 1024 else { throw Failure.invalidDocument }
    }

    /// Same last-operation and tombstone rules as apps/web/app/utils/userData.ts.
    public func merging(_ other: Self, now: Double = Date().timeIntervalSince1970 * 1000) -> Self {
        var result = Self()
        result.historyClearedAt = max(historyClearedAt, other.historyClearedAt)
        let adds = Self.latest(favorites + other.favorites)
        let removes = Self.latest(favoriteRemovals + other.favoriteRemovals)
        result.favorites = adds.values.filter { (removes[$0.id]?.time ?? -1) < $0.time }
            .sorted { ($0.time, $0.id) < ($1.time, $1.id) }
        result.favoriteRemovals = Self.prune(Array(removes.values.filter { (adds[$0.id]?.time ?? -1) <= $0.time }), now: now, time: \.time, breakTie: { $0.id < $1.id })
            .sorted { ($0.time, $0.id) < ($1.time, $1.id) }

        struct VisitKey: Hashable { let name: String; let time: Double }
        var removalKeys = Set<VisitKey>()
        let removals = (historyRemovals + other.historyRemovals).filter {
            $0.time > result.historyClearedAt && removalKeys.insert(VisitKey(name: $0.name, time: $0.time)).inserted
        }
        result.historyRemovals = Self.prune(removals, now: now, time: \.time, breakTie: { $0.name.utf16.lexicographicallyPrecedes($1.name.utf16) }).sorted {
            $0.time == $1.time ? $0.name.utf16.lexicographicallyPrecedes($1.name.utf16) : $0.time < $1.time
        }
        let removed = Set(result.historyRemovals.map { VisitKey(name: $0.name, time: $0.time) })
        var selected: [VisitKey: Visit] = [:]
        for entry in histories + other.histories where entry.time > result.historyClearedAt {
            let key = VisitKey(name: entry.recipe.name, time: entry.time)
            guard !removed.contains(key) else { continue }
            if let previous = selected[key], !entry.recipe.orderingKey.utf16.lexicographicallyPrecedes(previous.recipe.orderingKey.utf16) { continue }
            selected[key] = entry
        }
        result.histories = Array(selected.values.sorted {
            $0.time == $1.time ? $0.recipe.name.utf16.lexicographicallyPrecedes($1.recipe.name.utf16) : $0.time < $1.time
        }.suffix(200))
        return result
    }

    private static func latest(_ values: [Favorite]) -> [Int: Favorite] {
        values.reduce(into: [:]) { result, item in
            if item.time > result[item.id]?.time ?? -1 { result[item.id] = item }
        }
    }

    private static func prune<T>(_ values: [T], now: Double, time: KeyPath<T, Double>, breakTie: (T, T) -> Bool) -> [T] {
        Array(values.filter { $0[keyPath: time] >= now - 180 * 24 * 60 * 60 * 1000 }
            .sorted { $0[keyPath: time] == $1[keyPath: time] ? breakTie($0, $1) : $0[keyPath: time] > $1[keyPath: time] }.prefix(500))
    }
}
