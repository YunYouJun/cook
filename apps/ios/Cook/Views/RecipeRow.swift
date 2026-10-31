import CookCore
import SwiftUI

/// 列表里的一道菜
struct RecipeRow: View {
    @Environment(RecipeStore.self) private var store
    let recipe: Recipe
    var match: RecipeMatch? = nil
    var dietaryGoal: DietaryGoal? = nil

    var body: some View {
        HStack(spacing: 12) {
            Text(store.thumbnail(for: recipe))
                .font(.largeTitle)
                .frame(minWidth: 44)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(recipe.name)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
                Text(store.meta(for: recipe))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let dietaryGoal, let entry = store.dietaryCatalog?.entry(for: recipe, goal: dietaryGoal) {
                    Text(entry.reason(for: dietaryGoal))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let match, !match.missingStuff.isEmpty {
                    Text("还缺 \(match.missingStuff.joined(separator: "、"))")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

extension View {
    /// 菜谱行的快捷操作：左滑收藏，长按预览并收藏
    func recipeActions(_ recipe: Recipe, isFavorite: Bool, store: RecipeStore, toggleFavorite: @escaping () -> Void) -> some View {
        let label = Label(isFavorite ? "取消收藏" : "收藏", systemImage: isFavorite ? "heart.slash" : "heart")
        return swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button(action: toggleFavorite) { label }
                .tint(isFavorite ? .gray : .pink)
        }
        .contextMenu {
            Button(action: toggleFavorite) { label }
        } preview: {
            RecipePreviewCard(thumbnail: store.thumbnail(for: recipe), name: recipe.name, meta: store.meta(for: recipe), stuff: recipe.stuff)
        }
    }
}

/// 长按菜谱时的预览卡片。预览在单独的层级里渲染，拿不到环境里的 store，内容直接传进来
private struct RecipePreviewCard: View {
    let thumbnail: String
    let name: String
    let meta: String
    let stuff: [String]

    var body: some View {
        VStack(spacing: 10) {
            Text(thumbnail)
                .font(.system(size: 72))
            Text(name)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
            Text(meta)
                .foregroundStyle(.secondary)
            if !stuff.isEmpty {
                Text(stuff.joined(separator: "、"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
            }
        }
        .padding(28)
        .frame(width: 300)
    }
}
