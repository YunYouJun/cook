import SwiftUI
import UIKit

/// An illustrated medallion for an available catalog recipe, or a shared plate for other recipes.
struct LearnedDishBadge: View {
    let catalogRecipeID: Int?
    var size: CGFloat = 84

    var body: some View {
        ZStack {
            Circle().fill(.background)
            Circle().fill(.linearGradient(colors: [CookingJournalPalette.warmFill, CookingJournalPalette.warmInk.opacity(0.24)],
                                          startPoint: .topLeading, endPoint: .bottomTrailing))
            Circle().strokeBorder(CookingJournalPalette.warmInk.opacity(0.44), lineWidth: 2)
            Circle().strokeBorder(CookingJournalPalette.warmInk.opacity(0.20), lineWidth: 1)
                .padding(5)
            if let catalogRecipeID, let artwork = Self.artwork[catalogRecipeID] {
                Image(uiImage: artwork)
                    .resizable()
                    .scaledToFit()
                    .frame(width: size * 0.76, height: size * 0.76)
            } else {
                Image(systemName: "fork.knife.circle")
                    .font(.system(size: size * 0.47, weight: .medium))
                    .foregroundStyle(CookingJournalPalette.warmInk)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    private static let artwork: [Int: UIImage] = [
        250: "cooking-badge-tomato-egg",
        588: "cooking-badge-steamed-egg",
        497: "cooking-badge-smashed-cucumber",
    ].compactMapValues { name in
        guard let path = Bundle.main.path(forResource: name, ofType: "webp") else { return nil }
        return UIImage(contentsOfFile: path)
    }
}
