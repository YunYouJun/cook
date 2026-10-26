import CookCore
import SwiftData
import SwiftUI
import UIKit

/// 菜谱详情：已有、还缺、常备分开列出，可以收藏、分享、去 B 站或图文页看做法
struct RecipeDetailView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(FeedbackClient.self) private var feedback
    @Environment(FavoriteActions.self) private var favoriteActions
    @Environment(\.modelContext) private var context
    @Environment(\.openURL) private var openURL
    @State private var feedbackRecipe: Recipe?
    @State private var kitchenRecipe: Recipe?
    @Query(filter: #Predicate<Favorite> { $0.deletedAt == nil }) private var favorites: [Favorite]
    let route: RecipeRoute

    private var recipe: Recipe {
        route.recipe
    }

    private var dietaryReview: DietaryCatalog.Entry? {
        guard let goal = route.dietaryGoal else { return nil }
        return store.dietaryCatalog?.entry(for: recipe, goal: goal)
    }

    private var isFavorite: Bool {
        favorites.contains { $0.recipeId == recipe.id }
    }

    private struct IngredientGroup {
        let label: String
        let items: [String]
    }

    /// 从推荐结果进来时，按当前已选的食材和常备重新算一遍：宽屏时左栏还能接着改选，详情要跟着变。
    /// 不看推荐方式和菜名搜索，免得改了这些菜就从详情里「消失」；缺得太多不再推荐时只列食材
    private var match: RecipeMatch? {
        guard route.match != nil else {
            return nil
        }
        var options = session.options
        options.mode = .loose
        options.keyword = nil
        return store.recommender.recommend(options).first { $0.recipe.id == recipe.id }
    }

    private var groups: [IngredientGroup] {
        guard let match else {
            return [IngredientGroup(label: "食材", items: store.data.normalizer.unique(recipe.stuff))]
        }
        return [
            IngredientGroup(label: "已有", items: match.matchedStuff),
            IngredientGroup(label: "还缺", items: match.missingStuff),
            IngredientGroup(label: "常备调料", items: match.pantryStuff),
            IngredientGroup(label: "还能加", items: match.optionalStuff),
        ].filter { !$0.items.isEmpty }
    }

    /// 做法网页：优先用 B 站视频，没有视频时用图文做法链接
    private var webURL: URL? {
        recipe.videoURL ?? textURL
    }

    private var shareURL: URL? {
        dietaryReview == nil ? webURL : textURL
    }

    /// 图文做法链接（recipe.csv 的 link 列）
    private var textURL: URL? {
        guard let link = recipe.link, !link.isEmpty else { return nil }
        return URL(string: link)
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 8) {
                    Text(store.thumbnail(for: recipe))
                        .font(.system(size: 64))
                        .accessibilityHidden(true)
                    Text(store.meta(for: recipe))
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }

            Section {
                if let goal = route.dietaryGoal, let review = dietaryReview,
                   let text = store.dietaryCatalog?.text(for: recipe.id) {
                    NavigationLink {
                        RecipeTextView(recipe: recipe, text: text, dietaryGoal: goal)
                    } label: {
                        Label("查看已核对做法", systemImage: "doc.text.magnifyingglass")
                    }
                    .accessibilityIdentifier("dietary.openReviewedRecipe")
                    Text(review.reason(for: goal))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Button {
                    kitchenRecipe = recipe
                } label: {
                    Label("厨房工作台", systemImage: "checklist")
                }
                .accessibilityIdentifier("recipe.openKitchen")
            }

            ForEach(groups, id: \.label) { group in
                Section(group.label) {
                    Text(group.items.map { name in
                        store.emoji(forStuff: name).map { "\($0) \(name)" } ?? name
                    }.joined(separator: "、"))
                    // 读屏时不念 emoji 的名字，只念食材
                    .accessibilityLabel(group.items.joined(separator: "、"))
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
                if recipe.videoURL != nil {
                    Button(action: openVideo) {
                        Label(route.dietaryGoal == nil ? "去 B 站看做法" : "其他视频做法（未核对）", systemImage: "play.rectangle")
                    }
                }
                // 有内置正文时在 App 里看，不用等 GitHub 打开；正文页里也能打开原文
                if let text = store.text(for: recipe), dietaryReview == nil {
                    NavigationLink {
                        RecipeTextView(recipe: recipe, text: text)
                    } label: {
                        Label("看图文做法", systemImage: "doc.text")
                    }
                } else if dietaryReview == nil, let textURL {
                    Button {
                        openURL(textURL)
                    } label: {
                        Label("看图文做法", systemImage: "doc.text")
                    }
                }
                Button {
                    feedbackRecipe = recipe
                } label: {
                    Label("反馈这道菜的问题", systemImage: "exclamationmark.bubble")
                }
                .accessibilityIdentifier("recipe.feedback")
            }
        }
        .fullScreenCover(item: $kitchenRecipe) { recipe in
            KitchenWorkbenchView(recipe: recipe, dietaryGoal: dietaryReview == nil ? nil : route.dietaryGoal)
        }
        .sheet(item: $feedbackRecipe) { item in
            RecipeFeedbackView(recipe: item)
        }
        // 菜名只放在大标题里，滚动后收进导航栏，不在页面里再写一遍
        .navigationTitle(recipe.name)
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    favoriteActions.set(recipe.id, isFavorite: !isFavorite)
                } label: {
                    Label(isFavorite ? "取消收藏" : "收藏", systemImage: isFavorite ? "heart.fill" : "heart")
                        .contentTransition(.symbolEffect(.replace))
                        .symbolEffect(.bounce, value: isFavorite)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if let shareURL {
                    ShareLink(item: shareURL, subject: Text(recipe.name)) {
                        Label("分享", systemImage: "square.and.arrow.up")
                    }
                }
            }
        }
        // iPad 分栏里换一道菜时视图不会重建，按菜谱 id 记浏览
        .task(id: recipe.id) {
            context.recordVisit(recipe.id)
            // 从推荐结果点进来时，匿名记录这组食材下点开了第几个，用来评估排序
            if route.dietaryGoal == nil, let rank = route.rank {
                feedback.signalOpen(stuff: session.selectedStuff, tool: session.tool, mode: session.mode, recipeId: recipe.id, rank: rank)
            }
        }
    }

    /// 装了 B 站 App 就直接打开，否则用浏览器
    private func openVideo() {
        guard let webURL else {
            return
        }
        guard let bv = recipe.bv, !bv.isEmpty,
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
