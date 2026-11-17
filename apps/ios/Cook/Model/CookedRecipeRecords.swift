import Foundation
import Observation

/// Explicit cooking records on this device, independent of visits and favorites.
@MainActor
@Observable
final class CookedRecipeRecords {
    enum RecipeID {
        case catalog(Int)
        case personal(String)

        fileprivate var storageID: String {
            switch self {
            case let .catalog(id): "catalog:\(id)"
            case let .personal(id): "personal:\(id)"
            }
        }
    }

    private static let storageKey = "cook.cookedRecipes.v1"
    @ObservationIgnored private let defaults: UserDefaults?
    private var dates: [String: Date]

    init(defaults: UserDefaults? = nil) {
        self.defaults = defaults
        dates = defaults?.dictionary(forKey: Self.storageKey)?.compactMapValues { $0 as? Date } ?? [:]
    }

    func recordedAt(for recipeID: RecipeID) -> Date? {
        dates[recipeID.storageID]
    }

    func setCooked(_ cooked: Bool, for recipeID: RecipeID, at date: Date = .now) {
        let key = recipeID.storageID
        guard cooked != (dates[key] != nil) else { return }
        dates[key] = cooked ? date : nil
        defaults?.set(dates, forKey: Self.storageKey)
    }
}
