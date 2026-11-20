import Foundation

enum CookUniversalLink {
    // Keep recipe links on a host that older app versions don't claim.
    private static let recipeHost = "cook.yunle.fun"
    private static let recipePath = "/recipes/share/"

    static func recipeURL(id: Int) -> URL? {
        URL(string: "https://\(recipeHost)\(recipePath)?id=\(id)")
    }

    static func recipeID(_ url: URL) -> Int? {
        guard let components = cookComponents(url, host: recipeHost),
              [recipePath, String(recipePath.dropLast())].contains(components.percentEncodedPath),
              let ids = components.queryItems?.filter({ $0.name == "id" }), ids.count == 1,
              let value = ids.first?.value, let id = Int(value),
              (0...9_007_199_254_740_991).contains(id), String(id) == value
        else { return nil }
        return id
    }

    /// The registered WeChat link namespace is also used by the account-area fallback.
    /// A link without a matching OpenSDK response never changes a session.
    static func isWeChatLink(_ url: URL) -> Bool {
        guard let components = cookComponents(url, host: "cook.yunyoujun.cn"),
              components.percentEncodedPath.hasPrefix("/auth/wechat/")
        else { return false }

        // Do not interpret encoded separators or dot segments as a different route.
        let path = components.percentEncodedPath
        return !path.contains("%") && !path.contains("\\")
            && !path.split(separator: "/", omittingEmptySubsequences: false).contains(where: { $0 == "." || $0 == ".." })
    }

    private static func cookComponents(_ url: URL, host: String) -> URLComponents? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https",
              components.host?.lowercased() == host,
              components.user == nil, components.password == nil,
              components.port == nil || components.port == 443
        else { return nil }
        return components
    }
}

extension AppRouter {
    @discardableResult
    func openUniversalLink(_ url: URL) -> Bool {
        if let id = CookUniversalLink.recipeID(url) {
            showSharedRecipe(id)
            return true
        }
        guard CookUniversalLink.isWeChatLink(url) else { return false }
        tab = .settings
        return true
    }
}
