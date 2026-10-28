import CookCore
import SwiftUI
import UIKit

/// A shared visual identity for goal selection, entry points and reviewed recipes.
struct DietaryGoalAppearance {
    let goal: DietaryGoal?

    var symbol: String {
        switch goal {
        case .lessAddedSugar?: return "cube"
        case .balancedPairing?: return "fork.knife.circle"
        case .weightAware?: return "scalemass"
        case nil: return "slider.horizontal.3"
        }
    }

    var color: Color {
        let systemColor: UIColor
        switch goal {
        case .lessAddedSugar?: systemColor = .systemPurple
        case .balancedPairing?: systemColor = .systemTeal
        case .weightAware?: systemColor = .systemOrange
        case nil: systemColor = .secondaryLabel
        }
        // Small symbols use the system's higher-contrast variants in both appearances.
        return Color(uiColor: UIColor { traits in
            systemColor.resolvedColor(with: UITraitCollection(traitsFrom: [
                traits, UITraitCollection(accessibilityContrast: .high),
            ]))
        })
    }
}

struct DietaryGoalIcon: View {
    let goal: DietaryGoal?

    var body: some View {
        let appearance = DietaryGoalAppearance(goal: goal)
        Image(systemName: appearance.symbol)
            .symbolRenderingMode(.hierarchical)
            .foregroundStyle(appearance.color)
            .accessibilityHidden(true)
    }
}
