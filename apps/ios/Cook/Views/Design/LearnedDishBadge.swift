import SwiftUI

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
            if let artwork {
                Image(artwork)
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

    private var artwork: String? {
        switch catalogRecipeID {
        case 250: "cooking-badge-tomato-egg" // 番茄炒蛋
        case 588: "cooking-badge-steamed-egg" // 蒸蛋羹
        case 497: "cooking-badge-smashed-cucumber" // 拍黄瓜
        default: nil
        }
    }
}
