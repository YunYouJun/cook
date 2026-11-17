import SwiftUI

/// Shared dimensions for custom iOS controls. Keep system list and toolbar metrics native.
enum CookLayout {
    enum Spacing {
        static let xSmall: CGFloat = 4
        static let small: CGFloat = 8
        static let medium: CGFloat = 12
        static let large: CGFloat = 16
    }

    enum SearchField {
        static let leadingInset = Spacing.large
        static let trailingInset = Spacing.small
        static let actionWidth = CookLayout.minimumHitSize
    }

    static let minimumHitSize: CGFloat = 44
    static let searchFieldHeight: CGFloat = 48
    static let fieldCornerRadius: CGFloat = 24
}

struct CookSearchMagnifier: View {
    @ScaledMetric(relativeTo: .body) private var width = 20.0

    var body: some View {
        Image(systemName: "magnifyingglass")
            .font(.body)
            .foregroundStyle(.secondary)
            .frame(width: width)
            .accessibilityHidden(true)
    }
}

/// Semantic colors shared by search, ingredient selection, and the primary result action.
enum CookPalette {
    static let inputBackground = Color(uiColor: .secondarySystemGroupedBackground)
    static let primaryAction = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 155.0 / 255, green: 203.0 / 255, blue: 136.0 / 255, alpha: 1)
            : UIColor(red: 47.0 / 255, green: 107.0 / 255, blue: 69.0 / 255, alpha: 1)
    })
    static let onPrimaryAction = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 19.0 / 255, green: 32.0 / 255, blue: 16.0 / 255, alpha: 1)
            : .white
    })
}
