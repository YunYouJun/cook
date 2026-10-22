import CookCore
import os
import SwiftData
import SwiftUI
import UserNotifications

/// 启动阶段打点，在 Instruments 的 Points of Interest 里看耗时
private let launchSignposter = OSSignposter(subsystem: "fun.yunle.cook", category: .pointsOfInterest)

@main
struct CookApp: App {
    @State private var loadResult: Result<RecipeStore, Error>
    @State private var session: CookSession
    @State private var feedback = FeedbackClient()
    private let container: ModelContainer
    /// 通知中心只弱引用代理，这里留着
    private let notificationDelegate: NotificationDelegate

    init() {
        let result = launchSignposter.withIntervalSignpost("加载菜谱数据") {
            Result { try RecipeStore.loadBundled() }
        }
        let container = launchSignposter.withIntervalSignpost("打开本地数据库") {
            Persistence.makeContainer()
        }
        let defaultPantry = (try? result.get())?.data.pantry.defaults ?? []
        _loadResult = State(initialValue: result)
        _session = State(initialValue: CookSession(defaultPantry: defaultPantry, context: container.mainContext))
        self.container = container
        // 点开通知会在启动完成前回调，代理要在这里就设好
        notificationDelegate = NotificationDelegate()
        UNUserNotificationCenter.current().delegate = notificationDelegate
    }

    var body: some Scene {
        WindowGroup {
            switch loadResult {
            case let .success(store):
                RootView()
                    .environment(store)
                    .environment(session)
                    .environment(feedback)
            case let .failure(error):
                ContentUnavailableView(
                    "菜谱数据加载失败",
                    systemImage: "exclamationmark.triangle",
                    description: Text(error.localizedDescription)
                )
            }
        }
        .modelContainer(container)
    }
}
