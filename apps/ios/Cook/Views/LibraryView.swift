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
    @Environment(AppRouter.self) private var router
    @Environment(\.openURL) private var openURL
    @Environment(\.modelContext) private var context
    @Environment(FavoriteActions.self) private var favoriteActions
    @Environment(\.accountUserData) private var accountData
    @Query(filter: #Predicate<Favorite> { $0.deletedAt == nil }, sort: \Favorite.createdAt, order: .reverse)
    private var favorites: [Favorite]
    @Query(filter: #Predicate<HistoryEntry> { $0.deletedAt == nil }, sort: \HistoryEntry.visitedAt, order: .reverse)
    private var visits: [HistoryEntry]
    @State private var scope: Scope = .all
    @State private var query = ""
    @State private var selection: RecipeRoute?
    @State private var detailPath = NavigationPath()
    @State private var unavailableShareID: Int?
    @State private var confirmingClear = false
    @State private var historyRemovalFailed = false
    @State private var compactColumn: NavigationSplitViewColumn = .sidebar
    @State private var decideRequest: CookDecideRequest?
    @State private var lastDecidedID: Int?

    private struct RecentVisit: Identifiable {
        enum ID: Hashable {
            case account(revision: UUID, name: String, time: Double)
            case device(PersistentIdentifier)
        }

        let id: ID
        let name: String
        let ingredients: [String]
        let recipe: Recipe?
        let date: Date
        let remove: () throws -> Void
    }

    var body: some View {
        let favoriteIds = Set(activeFavoriteIDs)
        let recent = scope == .recent ? recentVisits : []

        NavigationSplitView(preferredCompactColumn: $compactColumn) {
            List(selection: $selection) {
                Picker("范围", selection: $scope) {
                    ForEach(Scope.allCases) { scope in
                        Text(scope.rawValue).tag(scope)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())

                Section {
                    if scope == .recent {
                        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
                        let ingredient = store.data.normalizer.normalize([text]).first ?? text
                        let visible = recent.filter { text.isEmpty || $0.name.contains(text) || $0.ingredients.contains(ingredient) }
                        if visible.isEmpty { ContentUnavailableView(emptyTitle, systemImage: emptySymbol) }
                        ForEach(visible) { visit in
                            recentRow(visit, isFavorite: visit.recipe.map { favoriteIds.contains($0.id) } ?? false)
                        }
                    } else {
                        let base = scope == .favorites ? activeFavoriteIDs.compactMap { store.recipesById[$0] } : store.recipes
                        let visible = store.filter(base, query: query)
                        if visible.isEmpty { ContentUnavailableView(emptyTitle, systemImage: emptySymbol) }
                        ForEach(visible) { recipe in
                            recipeLink(recipe, isFavorite: favoriteIds.contains(recipe.id)) { RecipeRow(recipe: recipe) }
                        }
                    }
                }
            }
            .listSectionSpacing(12)
            .navigationTitle("菜谱")
            // 分栏视图的侧栏默认用小标题，和其他几个页签的大标题对齐
            .navigationBarTitleDisplayMode(.large)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索菜名或食材")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        decideRequest = CookDecideRequest(batchIDs: nil, ignoringKeyword: true)
                    } label: {
                        Label("今天吃什么", systemImage: "dice")
                            .labelStyle(.iconOnly)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityHint("按当前食材、忌口和饮食目标随机选一道")
                    .accessibilityIdentifier("library.random")
                }
                if scope == .recent, !recent.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("清空") { confirmingClear = true }
                    }
                }
            }
            .confirmationDialog("清空浏览记录？", isPresented: $confirmingClear, titleVisibility: .visible) {
                Button("清空浏览记录", role: .destructive) {
                    if let accountData, accountData.usesAccountLibrary { accountData.clearHistory() }
                    else { context.clearHistory() }
                }
            }
            .alert("未能移除浏览记录", isPresented: $historyRemovalFailed) {
                Button("好", role: .cancel) {}
            } message: {
                Text("请稍后重试。")
            }
        } detail: {
            NavigationStack(path: $detailPath) {
                if let selection {
                    RecipeDetailView(route: selection)
                } else {
                    ContentUnavailableView("选一道菜看看", systemImage: "book", description: Text("从左边的列表里选"))
                }
            }
        }
        .cookDecideSheet(request: $decideRequest, lastDecidedID: $lastDecidedID) { route in
            selection = route
            compactColumn = .detail
        }
        .onChange(of: router.sharedRecipeID, initial: true) {
            guard router.tab == .library, let id = router.takeSharedRecipeID() else { return }
            guard let recipe = store.recipesById[id] else {
                unavailableShareID = id
                return
            }
            detailPath = NavigationPath()
            selection = RecipeRoute(recipe: recipe)
            compactColumn = .detail
        }
        .alert("当前版本找不到这道菜", isPresented: Binding(
            get: { unavailableShareID != nil },
            set: { if !$0 { unavailableShareID = nil } }
        ), presenting: unavailableShareID) { id in
            if let url = CookUniversalLink.recipeURL(id: id) {
                Button("在网页查看") { openURL(url) }
            }
            Button("关闭", role: .cancel) {}
        } message: { _ in
            Text("可以在网页版查看，或更新云食册后重试。")
        }
    }

    private func recipeLink<Content: View>(_ recipe: Recipe, isFavorite: Bool, @ViewBuilder content: () -> Content) -> some View {
        NavigationLink(value: RecipeRoute(recipe: recipe), label: content)
            .recipeActions(recipe, isFavorite: isFavorite, store: store) {
                withAnimation(.snappy) { favoriteActions.set(recipe.id, isFavorite: !isFavorite) }
            }
    }

    private func recentRow(_ visit: RecentVisit, isFavorite: Bool) -> some View {
        let label = VStack(alignment: .leading, spacing: 4) {
            if let recipe = visit.recipe {
                RecipeRow(recipe: recipe)
            } else {
                Text(visit.name).font(.headline)
                Text("当前版本找不到这道菜").font(.subheadline).foregroundStyle(.secondary)
            }
            Text(visit.date.formatted(date: .abbreviated, time: .shortened))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        return Group {
            if let recipe = visit.recipe {
                recipeLink(recipe, isFavorite: isFavorite) { label }
            } else {
                label
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) { removeVisit(visit) } label: { Label("移除", systemImage: "trash") }
        }
        .accessibilityAction(named: Text("移除浏览记录")) { removeVisit(visit) }
    }

    private func removeVisit(_ visit: RecentVisit) {
        do { try withAnimation(.snappy) { try visit.remove() } }
        catch is CancellationError { }
        catch { historyRemovalFailed = true }
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

    private var activeFavoriteIDs: [Int] {
        accountData?.usesAccountLibrary == true ? accountData!.favoriteIDs : uniqueRecipeIds(favorites.map(\.recipeId))
    }

    private var recentVisits: [RecentVisit] {
        if let accountData, accountData.usesAccountLibrary {
            let revision = accountData.libraryRevision
            return accountData.snapshot.document.histories.reversed().map { visit in
                let name = visit.recipe.name
                let recipe = store.recipeIdByName[name].flatMap { store.recipesById[$0] }
                return RecentVisit(id: .account(revision: revision, name: name, time: visit.time), name: name, ingredients: recipe?.stuff ?? visit.recipe.stuff,
                                   recipe: recipe, date: Date(timeIntervalSince1970: visit.time / 1000)) {
                    try accountData.removeVisit(visit, expectedRevision: revision)
                }
            }
        }
        return visits.map { visit in
            let id = visit.recipeId
            let date = visit.visitedAt
            let recipe = store.recipesById[id]
            return RecentVisit(id: .device(visit.persistentModelID), name: recipe?.name ?? "已下架菜谱", ingredients: recipe?.stuff ?? [],
                               recipe: recipe, date: date) {
                guard accountData?.usesAccountLibrary != true else { throw CancellationError() }
                try context.removeVisit(recipeId: id, visitedAt: date)
            }
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
