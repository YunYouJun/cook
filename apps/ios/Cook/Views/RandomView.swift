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
                        }
                        .padding(32)
                        .frame(maxWidth: .infinity)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
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
