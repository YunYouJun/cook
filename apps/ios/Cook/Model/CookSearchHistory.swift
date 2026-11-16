import Foundation
import Observation

/// Recent submitted searches on this device; independent of recipe visits and account sync.
@Observable
final class CookSearchHistory {
    static let storageKey = "cook.search.recent"
    static let limit = 10
    private(set) var entries: [String]
    @ObservationIgnored private let defaults: UserDefaults?

    init(defaults: UserDefaults? = nil) {
        self.defaults = defaults
        var seen: Set<String> = []
        entries = Array((defaults?.stringArray(forKey: Self.storageKey) ?? [])
            .map(Self.normalized)
            .filter { !$0.isEmpty && seen.insert(Self.identity($0)).inserted }
            .prefix(Self.limit))
    }

    func record(_ text: String) {
        let text = Self.normalized(text)
        guard !text.isEmpty else { return }
        entries.removeAll { Self.identity($0) == Self.identity(text) }
        entries.insert(text, at: 0)
        entries = Array(entries.prefix(Self.limit))
        persist()
    }

    func remove(_ text: String) {
        entries.removeAll { $0 == text }
        persist()
    }

    func clear() {
        entries = []
        defaults?.removeObject(forKey: Self.storageKey)
    }

    private func persist() {
        defaults?.set(entries, forKey: Self.storageKey)
    }

    private static func normalized(_ text: String) -> String {
        text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    private static func identity(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .widthInsensitive], locale: Locale(identifier: "en_US_POSIX"))
    }
}
