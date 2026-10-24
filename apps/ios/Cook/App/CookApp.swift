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
    @State private var favoriteActions: FavoriteActions
    private let container: ModelContainer
    /// 通知中心只弱引用代理，这里留着
    private let notificationDelegate: NotificationDelegate

    init() {
        #if DEBUG
            let uiTesting = ProcessInfo.processInfo.arguments.contains("-ui-testing")
            let unitTesting = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
                || NSClassFromString("XCTestCase") != nil
            let inMemory = uiTesting || unitTesting
        #else
            let inMemory = false
        #endif
        let result = launchSignposter.withIntervalSignpost("加载菜谱数据") {
            Result { try RecipeStore.loadBundled(inMemory: inMemory) }
        }
        let container = launchSignposter.withIntervalSignpost("打开本地数据库") {
            Persistence.makeContainer(inMemory: inMemory)
        }
        let defaultPantry = (try? result.get())?.data.pantry.defaults ?? []
        _loadResult = State(initialValue: result)
        _session = State(initialValue: CookSession(defaultPantry: defaultPantry, context: container.mainContext))
        _favoriteActions = State(initialValue: FavoriteActions(context: container.mainContext))
        #if DEBUG
            if uiTesting {
                // UI 测试隔离于用户数据库，统计请求也不会离开设备。
                let defaults = UserDefaults(suiteName: "cook.ui-tests")!
                let environment = ProcessInfo.processInfo.environment
                if ProcessInfo.processInfo.arguments.contains("-ui-testing-reset-consent") {
                    defaults.removePersistentDomain(forName: "cook.ui-tests")
                }
                if environment["COOK_UI_TEST_LEGACY_CONSENT"] == "1" {
                    defaults.set(true, forKey: FeedbackClient.signalsKey)
                }
                // Exercise either UI mode with the same failing local transport.
                // Selecting the production UI never enables real network requests.
                let feedbackEnvironment = environment["COOK_UI_TEST_FEEDBACK_ENVIRONMENT"] == "production" ? "production" : "test"
                let client = FeedbackClient(endpoint: URL(string: "https://example.invalid/feedback"), environment: feedbackEnvironment, sendsSignals: false, defaults: defaults, transport: { _ in
                    throw URLError(.unsupportedURL)
                })
                if environment["COOK_UI_TEST_DECLINE_CONSENT"] == "1" { client.setSignalConsent(false) }
                _feedback = State(initialValue: client)
                var failuresRemaining = Int(environment["COOK_UI_TEST_FAVORITE_FAILURES"] ?? "0") ?? 0
                _favoriteActions = State(initialValue: FavoriteActions(context: container.mainContext) { context in
                    if failuresRemaining > 0 {
                        failuresRemaining -= 1
                        throw CocoaError(.fileWriteOutOfSpace)
                    }
                    try context.save()
                })
            }
        #endif
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
                    .environment(favoriteActions)
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
