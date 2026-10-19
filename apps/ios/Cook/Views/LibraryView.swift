import CookCore
import SwiftData
import SwiftUI

/// 菜谱：全部、收藏、最近浏览，可以按菜名或食材搜
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
    @Query(sort: \FavoriteRecipe.createdAt, order: .reverse) private var favorites: [FavoriteRecipe]
    @Query(sort: \RecipeVisit.visitedAt, order: .reverse) private var visits: [RecipeVisit]
    @State private var scope: Scope = .all
    @State private var query = ""

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

        NavigationStack {
            List {
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
                }
            }
            .navigationTitle("菜谱")
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索菜名或食材")
            .recipeDestinations()
        }
    }

    private var emptyTitle: String {
        if !query.isEmpty {
            return "没有找到相关的菜"
        }
        switch scope {
        case .all: return "还没有菜谱"
        case .favorites: return "还没有收藏，在菜谱详情里点 ♡ 收藏"
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
