import CookCore
import SwiftData
import SwiftUI

/// 推荐结果：按缺几样分组，可以切换推荐方式、按菜名搜，「再补一样」一点就加上；
/// 只选了厨具时列出用它能做的菜
struct ResultsView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(FavoriteActions.self) private var favoriteActions
    @State private var showingDietaryGoals = false
    @Query(filter: #Predicate<Favorite> { $0.deletedAt == nil }) private var favorites: [Favorite]
    /// 宽屏的左栏已有搜索和筛选，右栏专注实时结果。
    var isEmbedded = false

    static let groupLabels = ["齐全", "差一种", "差两种"]
    static let groupHints = ["食材都有", "补一种就能做", "补两种就能做"]

    private var filterSummary: [String] {
        var items: [String] = []
        if let goal = session.dietaryGoal { items.append(goal.title) }
        if let tool = session.tool {
            items.append(store.toolLabel(tool))
        }
        if let scene = session.discoveryScene { items.append(scene.label) }
        items += session.methodGroups + session.difficulty
        if session.noSpicy {
            items.append("不吃辣")
        }
        return items
    }

    var body: some View {
        @Bindable var session = session
        Group {
            if isEmbedded {
                resultLists
            } else {
                resultLists
                    .searchable(text: $session.keyword, placement: .navigationBarDrawer(displayMode: .always), prompt: "按菜名筛选")
            }
        }
        // The result list can disappear when a goal is cleared; keep its presenter above that branch.
        .sheet(isPresented: $showingDietaryGoals) { DietaryGoalSelectionSheet() }
        .navigationTitle(session.dietaryGoal?.title ?? "找到的菜")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !isEmbedded {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { showingDietaryGoals = true } label: {
                            Label("饮食目标", systemImage: "slider.horizontal.3")
                        }
                        .accessibilityIdentifier("results.dietaryGoal")
                    } label: {
                        Label("更多选项", systemImage: "ellipsis.circle")
                    }
                    .accessibilityValue(session.dietaryGoal?.title ?? "不限目标")
                    .accessibilityIdentifier("results.options")
                }
            }
        }
        .sensoryFeedback(.selection, trigger: session.selectedStuff)
    }

    @ViewBuilder
    private var resultLists: some View {
        let favoriteIds = Set(favorites.map(\.recipeId))
        if session.selectedStuff.isEmpty {
            candidateList(favoriteIds: favoriteIds)
        } else {
            recommendList(favoriteIds: favoriteIds)
        }
    }

    private func recommendList(favoriteIds: Set<Int>) -> some View {
        @Bindable var session = session
        let matches = session.matches(in: store)
        let ranks = Dictionary(matches.enumerated().map { ($1.recipe.id, $0) }, uniquingKeysWith: { first, _ in first })
        let suggestions = Recommender.unlockSuggestions(matches)

        return List {
            Section {
                Picker("推荐方式", selection: $session.mode) {
                    ForEach(RecommendationMode.displayModes, id: \.self) { mode in
                        Text(mode.displayTitle).tag(mode)
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
                if session.dietaryGoal != nil {
                    goalEmptyState
                } else {
                    ContentUnavailableView(
                        "没有找到能做的菜",
                        systemImage: "fork.knife",
                        description: Text("换几样食材，或者把推荐方式改成「全部推荐」。")
                    )
                }
            }

            ForEach(0 ..< Self.groupLabels.count, id: \.self) { missing in
                let items = matches.filter { $0.missingStuff.count == missing }
                if !items.isEmpty {
                    Section {
                        ForEach(items, id: \.recipe.id) { match in
                            NavigationLink(value: RecipeRoute(recipe: match.recipe, match: match, rank: ranks[match.recipe.id], dietaryGoal: session.dietaryGoal)) {
                                RecipeRow(recipe: match.recipe, match: match, dietaryGoal: session.dietaryGoal)
                            }
                            .accessibilityIdentifier("dietary.recipe.\(match.recipe.id)")
                            .recipeActions(match.recipe, isFavorite: favoriteIds.contains(match.recipe.id), store: store) {
                                favoriteActions.set(match.recipe.id, isFavorite: !favoriteIds.contains(match.recipe.id))
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
        .accessibilityIdentifier("dietary.results")
        .animation(.snappy, value: matches.map(\.recipe.id))
    }

    private func candidateList(favoriteIds: Set<Int>) -> some View {
        let recipes = session.candidates(in: store)
        return List {
            if recipes.isEmpty {
                if session.dietaryGoal != nil { goalEmptyState }
                else {
                    ContentUnavailableView("没有找到合适的菜", systemImage: "magnifyingglass", description: Text("试试更换菜名、场景或筛选条件。"))
                }
            }
            Section {
                ForEach(recipes) { recipe in
                    NavigationLink(value: RecipeRoute(recipe: recipe, dietaryGoal: session.dietaryGoal)) {
                        RecipeRow(recipe: recipe, dietaryGoal: session.dietaryGoal)
                    }
                    .accessibilityIdentifier("dietary.recipe.\(recipe.id)")
                    .recipeActions(recipe, isFavorite: favoriteIds.contains(recipe.id), store: store) {
                        favoriteActions.set(recipe.id, isFavorite: !favoriteIds.contains(recipe.id))
                    }
                }
            } header: {
                if !recipes.isEmpty { Text("\(session.dietaryGoal == nil ? "当前候选" : "已核对做法") · \(recipes.count)") }
            } footer: {
                if let goal = session.dietaryGoal { Text(goal.explanation) }
                Text("可继续选择食材和厨具；选择食材后，推荐方式才参与筛选。")
            }
        }
        .accessibilityIdentifier("dietary.results")
        .animation(.snappy, value: recipes.map(\.id))
    }

    private var goalEmptyState: some View {
        ContentUnavailableView {
            Label("暂无符合条件的已核对做法", systemImage: "fork.knife")
        } description: {
            Text(store.dietaryUnavailableMessage(for: session.dietaryGoal) ?? "试试更换食材、厨具或筛选。你的忌口会继续保留。")
        } actions: {
            Button("取消饮食目标") { session.selectDietaryGoal(nil) }
                .accessibilityIdentifier("dietary.clearGoal")
        }
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
