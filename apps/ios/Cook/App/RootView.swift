import CookCore
import SwiftUI

enum AppTab: Hashable {
    case cook
    case library
    case random
    case settings
}

struct RootView: View {
    @State private var tab: AppTab = .cook

    var body: some View {
        TabView(selection: $tab) {
            CookView()
                .tabItem { Label("做菜", systemImage: "frying.pan") }
                .tag(AppTab.cook)
            LibraryView()
                .tabItem { Label("菜谱", systemImage: "book") }
                .tag(AppTab.library)
            RandomView()
                .tabItem { Label("随机", systemImage: "dice") }
                .tag(AppTab.random)
            SettingsView()
                .tabItem { Label("我的", systemImage: "person.crop.circle") }
                .tag(AppTab.settings)
        }
        .minimizesTabBarOnScroll()
    }
}

/// 打开菜谱详情；从推荐结果进入时带上匹配信息，详情页按已有、还缺、常备分开列
struct RecipeRoute: Hashable {
    let recipe: Recipe
    var match: RecipeMatch? = nil
    /// 在推荐结果里排第几（从 0 开始），用来匿名记录点开的位置；其他入口为 nil
    var rank: Int? = nil
}

extension View {
    func recipeDestinations() -> some View {
        navigationDestination(for: RecipeRoute.self) { route in
            RecipeDetailView(route: route)
        }
    }
}
