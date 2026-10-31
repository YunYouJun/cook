import SwiftUI

/// 仅在选过条件时显示，避免筛选收进子菜单后忘记当前限制。
struct CookActiveConditions: View {
    @Environment(CookSession.self) private var session

    var body: some View {
        if session.discoveryScene != nil || session.dietaryGoal != nil || !session.keyword.isEmpty {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { conditions }
                VStack(alignment: .leading, spacing: 4) { conditions }
            }
            .font(.subheadline)
            .buttonStyle(.plain)
            .foregroundStyle(.tint)
        }
    }

    @ViewBuilder
    private var conditions: some View {
        if let scene = session.discoveryScene {
            condition(scene.label) { session.discoveryScene = nil }
                .accessibilityLabel("取消场景：\(scene.label)")
                .accessibilityIdentifier("cook.clearScene")
        }
        if let goal = session.dietaryGoal {
            condition(goal.title) { session.selectDietaryGoal(nil) }
                .accessibilityLabel("取消饮食目标：\(goal.title)")
                .accessibilityIdentifier("cook.clearGoal")
        }
        if !session.keyword.isEmpty {
            condition(session.keyword) { session.keyword = "" }
                .accessibilityLabel("清除菜名：\(session.keyword)")
                .accessibilityIdentifier("cook.clearKeyword")
        }
    }

    private func condition(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: "xmark.circle")
                .fixedSize(horizontal: false, vertical: true)
                .frame(minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
        }
    }
}
