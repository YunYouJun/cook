import CookCore
import SwiftData
import SwiftUI

/// 菜谱详情：已有、还缺、常备分开列出，可以收藏、分享、去 B 站看做法
struct RecipeDetailView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(\.modelContext) private var context
    @Environment(\.openURL) private var openURL
    @Query private var favorites: [FavoriteRecipe]
    let route: RecipeRoute

    private var recipe: Recipe {
        route.recipe
    }

    private var isFavorite: Bool {
        favorites.contains { $0.recipeId == recipe.id }
    }

    private struct IngredientGroup {
        let label: String
        let items: [String]
    }

    private var groups: [IngredientGroup] {
        guard let match = route.match else {
            return [IngredientGroup(label: "食材", items: store.data.normalizer.unique(recipe.stuff))]
        }
        return [
            IngredientGroup(label: "已有", items: match.matchedStuff),
            IngredientGroup(label: "还缺", items: match.missingStuff),
            IngredientGroup(label: "常备调料", items: match.pantryStuff),
            IngredientGroup(label: "还能加", items: match.optionalStuff),
        ].filter { !$0.items.isEmpty }
    }

    /// 视频网页：优先用记录的链接，其次 B 站视频号
    private var webURL: URL? {
        if let link = recipe.link, !link.isEmpty {
            return URL(string: link)
        }
        return recipe.videoURL
    }

    private var feedbackURL: URL {
        var components = URLComponents(string: "https://github.com/YunYouJun/cook/issues/new")!
        components.queryItems = [
            URLQueryItem(name: "template", value: "recipe.yml"),
            URLQueryItem(name: "title", value: "[菜谱] \(recipe.name)"),
            URLQueryItem(name: "name", value: recipe.name),
            URLQueryItem(name: "video", value: webURL?.absoluteString ?? ""),
        ]
        return components.url!
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 8) {
                    Text(store.thumbnail(for: recipe))
                        .font(.system(size: 64))
                        .accessibilityHidden(true)
                    Text(recipe.name)
                        .font(.title2.bold())
                        .multilineTextAlignment(.center)
                    Text(store.meta(for: recipe))
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }

            ForEach(groups, id: \.label) { group in
                Section(group.label) {
                    Text(group.items.map { name in
                        store.emoji(forStuff: name).map { "\($0) \(name)" } ?? name
                    }.joined(separator: "、"))
                }
            }

            if !recipe.methods.isEmpty || !recipe.tags.isEmpty {
                Section("做法和标签") {
                    if !recipe.methods.isEmpty {
                        LabeledContent("做法", value: recipe.methods.joined(separator: "、"))
                    }
                    if !recipe.tags.isEmpty {
                        LabeledContent("标签", value: recipe.tags.joined(separator: "、"))
                    }
                }
            }

            Section {
                if webURL != nil {
                    Button(action: openVideo) {
                        Label(recipe.bv == nil ? "看做法" : "去 B 站看做法", systemImage: "play.rectangle")
                    }
                }
                Link(destination: feedbackURL) {
                    Label("反馈这道菜的问题", systemImage: "exclamationmark.bubble")
                }
            }
        }
        .navigationTitle(recipe.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    context.toggleFavorite(recipe.id)
                } label: {
                    Label(isFavorite ? "取消收藏" : "收藏", systemImage: isFavorite ? "heart.fill" : "heart")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if let webURL {
                    ShareLink(item: webURL, subject: Text(recipe.name)) {
                        Label("分享", systemImage: "square.and.arrow.up")
                    }
                }
            }
        }
        .sensoryFeedback(.success, trigger: isFavorite)
        .onAppear {
            context.recordVisit(recipe.id)
        }
    }

    /// 装了 B 站 App 就直接打开，否则用浏览器
    private func openVideo() {
        guard let webURL else {
            return
        }
        guard let bv = recipe.bv, !bv.isEmpty, recipe.link?.isEmpty ?? true,
              let appURL = URL(string: "bilibili://video/\(bv)")
        else {
            openURL(webURL)
            return
        }
        openURL(appURL) { accepted in
            if !accepted {
                openURL(webURL)
            }
        }
    }
}
