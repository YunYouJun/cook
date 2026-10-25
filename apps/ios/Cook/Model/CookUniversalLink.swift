import Foundation

enum CookUniversalLink {
    /// The registered WeChat link namespace only opens the account area for now.
    /// SDK callbacks must later be verified by OpenSDK and the server before changing a session.
    static func isWeChatLink(_ url: URL) -> Bool {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https",
              components.host?.lowercased() == "cook.yunyoujun.cn",
              components.user == nil, components.password == nil,
              components.port == nil || components.port == 443,
              components.percentEncodedPath.hasPrefix("/auth/wechat/")
        else { return false }

        // Do not interpret encoded separators or dot segments as a different route.
        let path = components.percentEncodedPath
        return !path.contains("%") && !path.contains("\\")
            && !path.split(separator: "/", omittingEmptySubsequences: false).contains(where: { $0 == "." || $0 == ".." })
    }
}

extension AppRouter {
    @discardableResult
    func openUniversalLink(_ url: URL) -> Bool {
        guard CookUniversalLink.isWeChatLink(url) else { return false }
        tab = .settings
        return true
    }
}
