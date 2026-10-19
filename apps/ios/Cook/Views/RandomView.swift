import CookCore
import SwiftUI

/// 今天吃什么：随机一道菜
struct RandomView: View {
    @Environment(RecipeStore.self) private var store
    @State private var recipe: Recipe?
    @State private var rolls = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer()
                if let recipe {
                    NavigationLink(value: RecipeRoute(recipe: recipe)) {
                        VStack(spacing: 12) {
                            Text(store.thumbnail(for: recipe))
                                .font(.system(size: 96))
                                .accessibilityHidden(true)
                            Text(recipe.name)
                                .font(.title.bold())
                                .multilineTextAlignment(.center)
                            Text(store.meta(for: recipe))
                                .foregroundStyle(.secondary)
                            // 卡片本身能点，用一行带箭头的提示说明这一点
                            Label("看做法", systemImage: "chevron.right")
                                .labelStyle(TrailingIconLabelStyle())
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.tint)
                                .padding(.top, 4)
                        }
                        .padding(32)
                        .frame(maxWidth: .infinity)
                        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                        .shadow(color: .black.opacity(0.08), radius: 16, y: 6)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("打开菜谱详情")
                }
                Spacer()
                Button(action: roll) {
                    Label("换一道", systemImage: "dice")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
            .padding()
            .frame(maxWidth: 600)
            .frame(maxWidth: .infinity)
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("今天吃什么")
            .recipeDestinations()
            .onAppear {
                if recipe == nil {
                    recipe = store.randomRecipe()
                }
            }
            .sensoryFeedback(.impact, trigger: rolls)
        }
    }

    private func roll() {
        recipe = store.randomRecipe(excluding: recipe?.id)
        rolls += 1
    }
}

/// 文字在前、图标在后，比如“看做法 >”
private struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.title
            configuration.icon
        }
    }
}
