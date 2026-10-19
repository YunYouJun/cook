import Foundation

/// 食材名标准化：别名换成数据里的标准名（aliases.json），对应 TS 的 normalizeStuff / uniqueStuff。
public struct StuffNormalizer: Hashable, Sendable {
    /// 常见别名 → 标准名
    public var aliases: [String: String]

    public init(aliases: [String: String]) {
        self.aliases = aliases
    }

    /// 录入时可能用到的分隔符
    static let separators: Set<Character> = [",", "，", "、", ";", "；", "\n", "\r\n"]

    /// 去掉首尾空白并换成标准名，不拆分、不去重
    public func normalize(_ input: [String]) -> [String] {
        input.map { item in
            let trimmed = item.trimmingCharacters(in: .whitespacesAndNewlines)
            return aliases[trimmed] ?? trimmed
        }
    }

    /// 按分隔符拆开、标准化、去掉空项并去重，保留第一次出现的顺序
    public func unique(_ stuff: [String]) -> [String] {
        let parts = stuff.flatMap { item in
            item.split(omittingEmptySubsequences: false, whereSeparator: { Self.separators.contains($0) }).map(String.init)
        }
        var seen = Set<String>()
        var result: [String] = []
        for item in normalize(parts) where !item.isEmpty {
            if seen.insert(item).inserted {
                result.append(item)
            }
        }
        return result
    }
}
