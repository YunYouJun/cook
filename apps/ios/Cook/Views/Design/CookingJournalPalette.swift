import SwiftUI

enum CookingJournalPalette {
    static let warmInk = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.98, green: 0.77, blue: 0.48, alpha: 1)
            : UIColor(red: 0.48, green: 0.26, blue: 0.10, alpha: 1)
    })

    static let warmFill = warmInk.opacity(0.12)
}
