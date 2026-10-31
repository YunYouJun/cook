import CookCore

extension RecommendationMode {
    static let displayModes: [RecommendationMode] = [.loose, .strict, .survival]

    var displayTitle: String {
        switch self {
        case .loose: return "全部推荐"
        case .strict: return "用上所有已选"
        case .survival: return "只看食材齐全"
        }
    }
}
