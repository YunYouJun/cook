import Foundation

enum CookFeatures {
    /// Reserved for the future native WeChat entry, not Yunlefun web sign-in or link handling.
    /// Only an explicit YES enables it; missing or unresolved build settings stay disabled.
    static var weChatLoginEnabled: Bool {
        Bundle.main.object(forInfoDictionaryKey: "CookWeChatLoginEnabled") as? String == "YES"
    }
}
