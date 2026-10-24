import CookCore
import SwiftData
import SwiftUI

enum AppTab: Hashable {
    case cook
    case fridge
    case library
    case random
    case settings
}

struct RootView: View {
    /// 每个窗口自己的 Tab，不跟别的窗口联动
    @State private var router = AppRouter()
    @Environment(\.scenePhase) private var scenePhase
    @Query(filter: #Predicate<FridgeItem> { $0.deletedAt == nil }) private var fridgeItems: [FridgeItem]
    @Query private var preferenceRecords: [Preferences]
    @Environment(CookSession.self) private var session
    @AppStorage(FridgeReminder.enabledKey) private var remindersEnabled = false
    @AppStorage(FridgeReminder.timeKey) private var reminderMinutes = FridgeReminder.defaultMinutes
    @Environment(FeedbackClient.self) private var feedback
    @Environment(FavoriteActions.self) private var favoriteActions
    @State private var showingSignalConsent = false

    var body: some View {
        TabView(selection: $router.tab) {
            CookView()
                .tabItem { Label("做菜", systemImage: "frying.pan") }
                .tag(AppTab.cook)
            FridgeView()
                .tabItem { Label("冰箱", systemImage: "refrigerator") }
                .tag(AppTab.fridge)
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
        .environment(router)
        .onChange(of: preferenceRecords.map(\.snapshot), initial: true) {
            session.refreshPreferences()
        }
        .onReceive(NotificationCenter.default.publisher(for: .showFridgeHome)) { _ in
            PendingFridgeHome.requested = false
            router.showFridgeHome()
        }
        .onAppear {
            if PendingFridgeHome.take() {
                router.showFridgeHome()
            }
        }
        // 冰箱里的食材（包括其他设备同步过来的）或提醒设置一变，就重排临期通知；回到前台时也排一次，过了一天也不会漏
        .onChange(of: reminderSignature, initial: true) {
            FridgeReminder.reschedule(reminderEntries)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                FridgeReminder.reschedule(reminderEntries)
            }
        }
        .task {
            showingSignalConsent = feedback.needsSignalConsent
        }
        .sheet(isPresented: $showingSignalConsent, onDismiss: {
            // 下滑关闭也保持统计关闭，之后仍可在设置里主动开启。
            if feedback.needsSignalConsent { feedback.setSignalConsent(false) }
        }) {
            SignalConsentView()
        }
        .alert(
            favoriteActions.failure?.title ?? "收藏未能保存",
            isPresented: Binding(get: { favoriteActions.failure != nil }, set: { if !$0 { favoriteActions.failure = nil } }),
            presenting: favoriteActions.failure
        ) { failure in
            Button("取消", role: .cancel) {}
            Button("重试") {
                // 等旧弹窗关闭后再尝试，连续失败时也能重新显示提示。
                Task { @MainActor in
                    await Task.yield()
                    favoriteActions.retry(failure)
                }
            }
        } message: { _ in
            Text("已保留原来的收藏状态。请检查设备的可用储存空间，然后重试。")
        }
        .sensoryFeedback(.success, trigger: favoriteActions.savedCount)
    }

    private var reminderEntries: [FridgeReminder.Entry] {
        fridgeItems.map { FridgeReminder.Entry(name: $0.name, expiresAt: $0.expiryDay()) }
    }

    private var reminderSignature: String {
        let items = reminderEntries
            .map { "\($0.name)@\(Int($0.expiresAt.timeIntervalSince1970))" }
            .sorted()
        return ([remindersEnabled ? "on" : "off", String(reminderMinutes)] + items).joined(separator: "|")
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
