import Foundation

public enum CookProProductID {
    public static let proLifetime = "fun.yunle.cook.pro_lifetime"
    public static let aiMonth = "fun.yunle.cook.ai_month"
    public static let aiYear = "fun.yunle.cook.ai_year"

    public static let aiSubscriptionIDs: Set<String> = [aiMonth, aiYear]
    public static let allProductIDs: [String] = [proLifetime, aiMonth, aiYear]
}

public enum CookEntitlementPolicy {
    public static let freeFridgeInStockLimit = 12
    public static let freePrivateRecipeLimit = 3
    public static let freeSpecialShowcaseLimit = 1

    public static func canAddFridgeItem(currentInStockCount: Int, hasProAccess: Bool) -> Bool {
        hasProAccess || currentInStockCount < freeFridgeInStockLimit
    }

    public static func canAddPrivateRecipe(currentCount: Int, hasProAccess: Bool) -> Bool {
        hasProAccess || currentCount < freePrivateRecipeLimit
    }

    public static func canOpenSpecialShowcase(index: Int, hasProAccess: Bool) -> Bool {
        hasProAccess || index < freeSpecialShowcaseLimit
    }
}

public enum CookStorefrontPolicy {
    /// Missing or unresolved storefront conservatively resolves to Mainland China
    /// so regulated online AI features never activate before the storefront is known.
    public static func isMainlandChina(countryCode: String?) -> Bool {
        guard let normalized = countryCode?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
              !normalized.isEmpty else {
            return true
        }
        return normalized == "CHN"
    }

    public static func allowsOnlineAI(countryCode: String?, mainlandAIEnabled: Bool, overseasAIEnabled: Bool) -> Bool {
        if isMainlandChina(countryCode: countryCode) {
            return mainlandAIEnabled
        }
        return mainlandAIEnabled || overseasAIEnabled
    }

    public static func allowsAISubscriptionUI(countryCode: String?, mainlandAIEnabled: Bool, overseasAIEnabled: Bool) -> Bool {
        allowsOnlineAI(countryCode: countryCode, mainlandAIEnabled: mainlandAIEnabled, overseasAIEnabled: overseasAIEnabled)
    }
}

public struct CookProSnapshot: Equatable, Sendable {
    public var hasProLifetime: Bool
    public var aiSubscriptionExpiresAt: Date?
    public var aiSubscriptionProductID: String?
    public var storefrontCountryCode: String?

    public init(
        hasProLifetime: Bool = false,
        aiSubscriptionExpiresAt: Date? = nil,
        aiSubscriptionProductID: String? = nil,
        storefrontCountryCode: String? = nil
    ) {
        self.hasProLifetime = hasProLifetime
        self.aiSubscriptionExpiresAt = aiSubscriptionExpiresAt
        self.aiSubscriptionProductID = aiSubscriptionProductID
        self.storefrontCountryCode = storefrontCountryCode
    }

    public func hasActiveAISubscription(at now: Date = .now) -> Bool {
        guard let expiresAt = aiSubscriptionExpiresAt,
              let productID = aiSubscriptionProductID,
              CookProProductID.aiSubscriptionIDs.contains(productID) else {
            return false
        }
        return expiresAt > now
    }

    public func hasProAccess(at now: Date = .now) -> Bool {
        hasProLifetime || hasActiveAISubscription(at: now)
    }
}
