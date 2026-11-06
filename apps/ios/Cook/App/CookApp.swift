import CookCore
import os
import SwiftData
import SwiftUI
import UIKit
import UserNotifications

/// 启动阶段打点，在 Instruments 的 Points of Interest 里看耗时
private let launchSignposter = OSSignposter(subsystem: "fun.yunle.cook", category: .pointsOfInterest)

@main
struct CookApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var loadResult: Result<RecipeStore, Error>
    @State private var session: CookSession
    @State private var feedback = FeedbackClient()
    @State private var favoriteActions: FavoriteActions
    @State private var accounts: YunlefunAccountStore
    @State private var workbench: KitchenWorkbench
    @State private var ai: CookAISettings
    @State private var userData: AccountUserDataStore
    #if DEBUG
        @State private var videoFixtureLoaded = false
    #endif
    private let container: ModelContainer
    private let privateVideos: PrivateRecipeVideoStore
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
        _session = State(initialValue: CookSession(defaultPantry: defaultPantry, context: container.mainContext, dietaryDefaults: inMemory ? nil : .standard))
        let accounts = YunlefunAccountStore(vault: inMemory ? MemoryYunlefunAccountVault() : KeychainYunlefunAccountVault())
        _accounts = State(initialValue: accounts)
        let disabledSyncTransport: UserDataSyncService.Transport = { _ in throw URLError(.notConnectedToInternet) }
        var syncTransport: UserDataSyncService.Transport? = inMemory ? disabledSyncTransport : nil
        var syncCredential: () async throws -> YunlefunCredential = { try await accounts.credentialForRequest() }
        #if DEBUG
            if uiTesting, ProcessInfo.processInfo.arguments.contains("-ui-testing-account-sync") {
                let fixture = UserDataSyncFixture()
                syncTransport = { try await fixture.respond($0) }
                syncCredential = { YunlefunCredential(accessToken: "public-ui-fixture", refreshToken: "unused", subject: "ui-fixture-member",
                                                     expiresAt: Date().addingTimeInterval(3600), deviceID: "fixture") }
                if let recipe = (try? result.get())?.recipes.first {
                    try? container.mainContext.setFavorite(recipe.id, isFavorite: true)
                    container.mainContext.recordVisit(recipe.id)
                }
            }
        #endif
        let userData = AccountUserDataStore(cache: inMemory ? MemoryAccountUserDataCache() : AccountUserDataCache(),
                                           service: UserDataSyncService(transport: syncTransport), credential: syncCredential)
        _userData = State(initialValue: userData)
        let actions = FavoriteActions(context: container.mainContext)
        actions.accountData = userData
        _favoriteActions = State(initialValue: actions)
        _workbench = State(initialValue: KitchenWorkbench(defaults: inMemory ? nil : .standard, notifications: inMemory ? .disabled : .live))
        _ai = State(initialValue: CookAISettings(defaults: inMemory ? nil : .standard,
                                                vault: inMemory ? MemoryCookAIKeyVault() : KeychainCookAIKeyVault(),
                                                managedService: ManagedCookAIService(endpoint: inMemory ? nil : ManagedCookAIService.configuredEndpoint)))
        #if DEBUG
            if uiTesting {
                // Functional tests exercise controls, validation and draft retention.
                // Avoid XCTest waiting indefinitely for keyboard/menu/layout animation completion.
                if ProcessInfo.processInfo.arguments.contains("-ui-testing-disable-animations") {
                    UIView.setAnimationsEnabled(false)
                }
                // Reset the fixture without a launch-domain override that would lock later UI changes.
                if ProcessInfo.processInfo.arguments.contains("-ui-testing-reset-presentation") {
                    UserDefaults.standard.set(CookPresentation.list.rawValue, forKey: "cook.presentation")
                }
                let environment = ProcessInfo.processInfo.environment
                let dietaryDefaults = UserDefaults(suiteName: "cook.dietary.ui-tests")!
                if ProcessInfo.processInfo.arguments.contains("-ui-testing-reset-dietary") {
                    dietaryDefaults.removePersistentDomain(forName: "cook.dietary.ui-tests")
                    if let rawGoal = environment["COOK_UI_TEST_SAVED_DIETARY_GOAL"],
                       let goal = DietaryGoal(rawValue: rawGoal) {
                        dietaryDefaults.set(goal.rawValue, forKey: CookSession.dietaryGoalKey)
                    }
                }
                if !ProcessInfo.processInfo.arguments.contains("-ui-testing-show-dietary-introduction") {
                    dietaryDefaults.set(true, forKey: CookSession.dietaryIntroductionKey)
                }
                _session = State(initialValue: CookSession(defaultPantry: defaultPantry, context: container.mainContext, dietaryDefaults: dietaryDefaults))
                let kitchenDefaults = UserDefaults(suiteName: "cook.kitchen.ui-tests")!
                if ProcessInfo.processInfo.arguments.contains("-ui-testing-reset-kitchen") {
                    kitchenDefaults.removePersistentDomain(forName: "cook.kitchen.ui-tests")
                }
                _workbench = State(initialValue: KitchenWorkbench(defaults: kitchenDefaults, notifications: .disabled))
                // UI 测试隔离于用户数据库，统计请求也不会离开设备。
                let defaults = UserDefaults(suiteName: "cook.ui-tests")!
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
                let testActions = FavoriteActions(context: container.mainContext) { context in
                    if failuresRemaining > 0 {
                        failuresRemaining -= 1
                        throw CocoaError(.fileWriteOutOfSpace)
                    }
                    try context.save()
                }
                testActions.accountData = userData
                _favoriteActions = State(initialValue: testActions)
            }
        #endif
        self.container = container
        privateVideos = inMemory
            ? PrivateRecipeVideoStore(localRoot: FileManager.default.temporaryDirectory.appendingPathComponent("cook-test-videos-\(ProcessInfo.processInfo.processIdentifier)"), deviceID: "00000000-0000-0000-0000-000000000001", cloudRoot: { nil })
            : .shared
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
                    .environment(workbench)
                    .environment(accounts)
                    .environment(ai)
                    .environment(\.accountUserData, userData)
                    .environment(\.privateRecipeVideos, privateVideos)
                    .preferredColorScheme(testColorScheme)
                    .task {
                        await privateVideos.removeAbandonedDrafts()
                        #if DEBUG
                            if !videoFixtureLoaded, ProcessInfo.processInfo.arguments.contains("-ui-testing"),
                               let path = ProcessInfo.processInfo.environment["COOK_UI_TEST_VIDEO_SOURCE"] {
                                videoFixtureLoaded = true
                                do {
                                    let draft = try await privateVideos.stage(URL(fileURLWithPath: path))
                                    var recipe = PrivateRecipe(customId: "custom-ui-video", name: "视频测试蒸蛋", stuff: ["鸡蛋"], tools: [], createdAt: 1000, updatedAt: 1000)
                                    recipe.video = draft.video
                                    _ = try await PrivateRecipeVideoPersistence.save(recipe, replacing: nil, draft: draft, inCloud: false, in: container, files: privateVideos)
                                    await privateVideos.discard(draft)
                                } catch { assertionFailure("Video fixture failed: \(error)") }
                            }
                        #endif
                        await accounts.restore()
                        if ai.wantsManagedDeepSeek { await ai.refreshManagedCapability(accounts: accounts) }
                    }
                    .onChange(of: accounts.sessionRevision) { ai.invalidateManagedCapability(accounts: accounts) }
                    .onChange(of: accounts.dataAccountID, initial: true) { _, uid in
                        favoriteActions.failure = nil
                        #if DEBUG
                            if ProcessInfo.processInfo.arguments.contains("-ui-testing"), ProcessInfo.processInfo.arguments.contains("-ui-testing-account-sync") {
                                userData.activate("ui-fixture-member")
                            } else { userData.activate(uid) }
                        #else
                            userData.activate(uid)
                        #endif
                    }
                    .onChange(of: scenePhase) { _, phase in
                        if phase == .active {
                            ai.expireManagedCapabilityIfNeeded()
                            Task { await accounts.refreshIfNeeded() }
                            userData.requestSync()
                        }
                    }

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

    private var testColorScheme: ColorScheme? {
        #if DEBUG
            let arguments = ProcessInfo.processInfo.arguments
            guard arguments.contains("-ui-testing") else { return nil }
            return arguments.contains("-ui-testing-dark") ? .dark : .light
        #else
            return nil
        #endif
    }
}
