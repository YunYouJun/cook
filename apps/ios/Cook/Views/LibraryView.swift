import CookCore
import SwiftData
import SwiftUI

/// 菜谱：全部、收藏、最近浏览，可以按菜名或食材搜。iPad 上左边列表、右边详情，iPhone 上自动收成一栏
struct LibraryView: View {
    enum Scope: String, CaseIterable, Identifiable {
        case all = "全部"
        case favorites = "收藏"
        case recent = "最近浏览"

        var id: Self {
            self
        }
    }

    @Environment(RecipeStore.self) private var store
    @Environment(\.modelContext) private var context
    @Environment(FavoriteActions.self) private var favoriteActions
    @Query(filter: #Predicate<Favorite> { $0.deletedAt == nil }, sort: \Favorite.createdAt, order: .reverse)
    private var favorites: [Favorite]
    @Query(filter: #Predicate<HistoryEntry> { $0.deletedAt == nil }, sort: \HistoryEntry.visitedAt, order: .reverse)
    private var visits: [HistoryEntry]
    @State private var scope: Scope = .all
    @State private var query = ""
    @State private var selection: RecipeRoute?
    @State private var confirmingClear = false

    private var recipes: [Recipe] {
        let base: [Recipe]
        switch scope {
        case .all:
            base = store.recipes
        case .favorites:
            base = uniqueRecipeIds(favorites.map(\.recipeId)).compactMap { store.recipesById[$0] }
        case .recent:
            base = uniqueRecipeIds(visits.map(\.recipeId)).compactMap { store.recipesById[$0] }
        }
        return store.filter(base, query: query)
    }

    var body: some View {
        let visible = recipes
        let favoriteIds = Set(favorites.map(\.recipeId))

        NavigationSplitView {
            List(selection: $selection) {
                Picker("范围", selection: $scope) {
                    ForEach(Scope.allCases) { scope in
                        Text(scope.rawValue).tag(scope)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())

                if visible.isEmpty {
                    ContentUnavailableView(emptyTitle, systemImage: emptySymbol)
                }
                ForEach(visible) { recipe in
                    NavigationLink(value: RecipeRoute(recipe: recipe)) {
                        RecipeRow(recipe: recipe)
                    }
                    .recipeActions(recipe, isFavorite: favoriteIds.contains(recipe.id), store: store) {
                        withAnimation(.snappy) { favoriteActions.set(recipe.id, isFavorite: !favoriteIds.contains(recipe.id)) }
                    }
                }
            }
            .navigationTitle("菜谱")
            // 分栏视图的侧栏默认用小标题，和其他几个页签的大标题对齐
            .navigationBarTitleDisplayMode(.large)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索菜名或食材")
            .toolbar {
                if scope == .recent, !visits.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("清空") { confirmingClear = true }
                    }
                }
            }
            .confirmationDialog("清空浏览记录？", isPresented: $confirmingClear, titleVisibility: .visible) {
                Button("清空浏览记录", role: .destructive) { context.clearHistory() }
            }
        } detail: {
            NavigationStack {
                if let selection {
                    RecipeDetailView(route: selection)
                } else {
                    ContentUnavailableView("选一道菜看看", systemImage: "book", description: Text("从左边的列表里选"))
                }
            }
        }
    }

    private var emptyTitle: String {
        if !query.isEmpty {
            return "没有找到相关的菜"
        }
        switch scope {
        case .all: return "还没有菜谱"
        case .favorites: return "还没有收藏，右滑菜谱或在详情里点 ♡ 收藏"
        case .recent: return "还没有浏览记录"
        }
    }

    private var emptySymbol: String {
        switch scope {
        case .all: return "book"
        case .favorites: return "heart"
        case .recent: return "clock"
        }
    }
}
