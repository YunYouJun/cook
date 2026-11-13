import Foundation

enum CookFeatures {
    static var membershipUIEnabled: Bool {
        #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-ui-testing-membership") { return true }
        #endif
        return Bundle.main.object(forInfoDictionaryKey: "CookMembershipUIEnabled") as? String == "YES"
    }
    static var membershipPurchasesEnabled: Bool {
        Bundle.main.object(forInfoDictionaryKey: "CookMembershipPurchasesEnabled") as? String == "YES"
    }
    /// Controls the native WeChat entry; the existing Yunlefun login remains available.
    /// Only an explicit YES enables it; missing or unresolved build settings stay disabled.
    static var weChatLoginEnabled: Bool {
        Bundle.main.object(forInfoDictionaryKey: "CookWeChatLoginEnabled") as? String == "YES"
    }
}
