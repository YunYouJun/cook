import CookCore
import SwiftData
import SwiftUI

/// 推荐结果：按缺几样分组，可以切换推荐方式、按菜名搜，「再补一样」一点就加上；
/// 只选了厨具时列出用它能做的菜
struct ResultsView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(\.modelContext) private var context
    @Query(filter: #Predicate<Favorite> { $0.deletedAt == nil }) private var favorites: [Favorite]

    static let modes: [RecommendationMode] = [.loose, .strict, .survival]
    static func label(for mode: RecommendationMode) -> String {
        switch mode {
        case .loose: return "全部推荐"
        case .strict: return "用上所有已选"
        case .survival: return "只看食材齐全"
        }
    }

    static let groupLabels = ["齐全", "差一种", "差两种"]
    static let groupHints = ["食材都有", "补一种就能做", "补两种就能做"]

    private var filterSummary: [String] {
        var items: [String] = []
        if let tool = session.tool {
            items.append(store.toolLabel(tool))
        }
        items += session.methodGroups + session.difficulty
        if session.noSpicy {
            items.append("不吃辣")
        }
        return items
    }

    var body: some View {
        @Bindable var session = session
        let favoriteIds = Set(favorites.map(\.recipeId))

        Group {
            if session.selectedStuff.isEmpty, let tool = session.tool {
                toolList(tool, favoriteIds: favoriteIds)
            } else {
                recommendList(favoriteIds: favoriteIds)
            }
        }
        .navigationTitle("能做的菜")
        .searchable(text: $session.keyword, placement: .navigationBarDrawer(displayMode: .always), prompt: "按菜名筛选")
        .sensoryFeedback(.selection, trigger: session.selectedStuff)
    }

    private func recommendList(favoriteIds: Set<Int>) -> some View {
        @Bindable var session = session
        let matches = store.recommender.recommend(session.options)
        let ranks = Dictionary(matches.enumerated().map { ($1.recipe.id, $0) }, uniquingKeysWith: { first, _ in first })
        let suggestions = Recommender.unlockSuggestions(matches)

        return List {
            Section {
                Picker("推荐方式", selection: $session.mode) {
                    ForEach(Self.modes, id: \.self) { mode in
                        Text(Self.label(for: mode)).tag(mode)
                    }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("已选：\(session.selectedStuff.joined(separator: "、"))")
                    // 筛选条件在做菜页的 sheet 里设置，这里也写出来，免得结果变少时不知道为什么
                    if !filterSummary.isEmpty {
                        Text("筛选：\(filterSummary.joined(separator: "、"))")
                    }
                }
            }

            if !suggestions.isEmpty {
                UnlockSection(suggestions: suggestions) { name in
                    withAnimation(.snappy) { session.addStuff(name) }
                }
            }

            if matches.isEmpty {
                ContentUnavailableView(
                    "没有找到能做的菜",
                    systemImage: "fork.knife",
                    description: Text("换几样食材，或者把推荐方式改成「全部推荐」。")
                )
            }

            ForEach(0 ..< Self.groupLabels.count, id: \.self) { missing in
                let items = matches.filter { $0.missingStuff.count == missing }
                if !items.isEmpty {
                    Section {
                        ForEach(items, id: \.recipe.id) { match in
                            NavigationLink(value: RecipeRoute(recipe: match.recipe, match: match, rank: ranks[match.recipe.id])) {
                                RecipeRow(recipe: match.recipe, match: match)
                            }
                            .recipeActions(match.recipe, isFavorite: favoriteIds.contains(match.recipe.id), store: store) {
                                context.toggleFavorite(match.recipe.id)
                            }
                        }
                    } header: {
                        Text("\(Self.groupLabels[missing]) · \(items.count)")
                            .contentTransition(.numericText(value: Double(items.count)))
                    } footer: {
                        Text(Self.groupHints[missing])
                    }
                }
            }
        }
        .animation(.snappy, value: matches.map(\.recipe.id))
    }

    /// 没选食材、只选了厨具：用这个厨具能做的菜，忌口和筛选照样生效（和 Web 的 toolRecipes 一致）
    private func toolList(_ tool: String, favoriteIds: Set<Int>) -> some View {
        let recipes = store.recommender.recipes(using: tool, options: session.options)

        return List {
            Section {
                if recipes.isEmpty {
                    ContentUnavailableView(
                        "没有用\(store.toolLabel(tool))能做的菜",
                        systemImage: "frying.pan",
                        description: Text("换个厨具，或者放宽筛选条件。")
                    )
                }
                ForEach(recipes) { recipe in
                    NavigationLink(value: RecipeRoute(recipe: recipe)) {
                        RecipeRow(recipe: recipe)
                    }
                    .recipeActions(recipe, isFavorite: favoriteIds.contains(recipe.id), store: store) {
                        context.toggleFavorite(recipe.id)
                    }
                }
            } header: {
                if !recipes.isEmpty {
                    Text("用\(store.toolLabel(tool))能做 · \(recipes.count)")
                }
            } footer: {
                Text("再选几样食材，会按缺几样排好。")
            }
        }
        .animation(.snappy, value: recipes.map(\.id))
    }
}

/// 「再补一样」：家里正好有的话点一下，差一种的菜就变成齐全
private struct UnlockSection: View {
    let suggestions: [UnlockSuggestion]
    let onAdd: (String) -> Void
    @Environment(RecipeStore.self) private var store

    var body: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(suggestions, id: \.name) { item in
                        Button {
                            onAdd(item.name)
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "plus")
                                    .font(.caption.weight(.bold))
                                if let emoji = store.emoji(forStuff: item.name) {
                                    Text(emoji)
                                }
                                Text(item.name)
                                    .fontWeight(.semibold)
                                Text("多 \(item.count) 道")
                                    .foregroundStyle(.secondary)
                            }
                            .font(.subheadline)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(Color.accentColor.opacity(0.12), in: Capsule())
                            .contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.tint)
                        .accessibilityLabel("加上\(item.name)，多 \(item.count) 道菜")
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
            .scrollClipDisabled()
            .listRowInsets(EdgeInsets())
            .animation(.snappy, value: suggestions)
        } header: {
            Text("再补一样")
        } footer: {
            Text("家里有的话点一下，差一种的菜就能直接做")
        }
    }
}

/// 列表里的一道菜
struct RecipeRow: View {
    @Environment(RecipeStore.self) private var store
    let recipe: Recipe
    var match: RecipeMatch? = nil

    var body: some View {
        HStack(spacing: 12) {
            Text(store.thumbnail(for: recipe))
                .font(.largeTitle)
                .frame(minWidth: 44)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(recipe.name)
                    .font(.headline)
                Text(store.meta(for: recipe))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let match, !match.missingStuff.isEmpty {
                    Text("还缺 \(match.missingStuff.joined(separator: "、"))")
                        .font(.footnote)
                        .foregroundStyle(.orange)
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
        .sensoryFeedback(.success, trigger: isFavorite) { old, new in !old && new }
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
