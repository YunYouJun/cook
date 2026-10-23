#if DEBUG
    import CoreData
    import Foundation
    import os
    import SwiftData

    /// 开发版第一次启动时，把 SwiftData 模型对应的 CloudKit 结构建到 Development 环境，
    /// 之后才能在 CloudKit Console 里「Deploy Schema Changes」到 Production（TestFlight 和 App Store 用 Production）。
    /// 只在 Debug 构建里编译；Release、TestFlight 不会运行。流程见 apps/ios/README.md。
    enum CloudKitSchema {
        /// 和 project.yml 里的 iCloud 容器一致
        static let containerIdentifier = "iCloud.fun.yunle.cook"
        /// 模型结构变了就加一，开发版下次启动会重新建一次
        // 版本 2 新增 FridgeItem；已初始化版本 1 的开发设备需要重新建结构。
        static let version = 2
        /// 成功后记下 version。要强制重建（比如在 Console 里 Reset 了 Development），启动参数加 `-cloudkit.schemaInitialized 0`
        static let doneKey = "cloudkit.schemaInitialized"
        /// UI 测试在真机上跑时设置这个环境变量，跳过初始化
        static let skipEnvironmentKey = "COOK_SKIP_CLOUDKIT_SCHEMA"
        private static let logger = Logger(subsystem: "fun.yunle.cook", category: "CloudKitSchema")

        /// SwiftData 没有直接的接口，按 Apple 文档的做法：用同一个库文件临时开一个 NSPersistentCloudKitContainer，
        /// 建完结构、卸载之后才让 SwiftData 打开同一个库。同步执行，只在 Debug 首次启动时多等几秒。
        static func initializeIfNeeded(models: [any PersistentModel.Type], storeURL: URL, defaults: UserDefaults = .standard) {
            // 模拟器和测试用的是不签名的构建，没有 iCloud 权限，CloudKit 会直接中断进程，所以只在真机上跑。
            // 没登录 iCloud 时 initializeCloudKitSchema 会返回错误，下次启动再试
            #if targetEnvironment(simulator)
                return
            #else
                let environment = ProcessInfo.processInfo.environment
                guard environment["XCTestConfigurationFilePath"] == nil,
                      environment[skipEnvironmentKey] == nil,
                      defaults.integer(forKey: doneKey) < version
                else {
                    return
                }
                // 临时的 Core Data 栈在这里面创建和释放，保证 SwiftData 打开同一个库之前已经完全放开
                let succeeded = autoreleasepool {
                    initialize(models: models, storeURL: storeURL)
                }
                if succeeded {
                    defaults.set(version, forKey: doneKey)
                    logger.notice("已在 Development 环境建好 CloudKit 结构（版本 \(version)）")
                }
            #endif
        }

        private static func initialize(models: [any PersistentModel.Type], storeURL: URL) -> Bool {
            guard let model = NSManagedObjectModel.makeManagedObjectModel(for: models) else {
                logger.error("无法从 SwiftData 模型生成 Core Data 模型")
                return false
            }
            let description = NSPersistentStoreDescription(url: storeURL)
            description.cloudKitContainerOptions = NSPersistentCloudKitContainerOptions(containerIdentifier: containerIdentifier)
            description.shouldAddStoreAsynchronously = false

            let container = NSPersistentCloudKitContainer(name: "Cook", managedObjectModel: model)
            container.persistentStoreDescriptions = [description]
            var loadError: Error?
            container.loadPersistentStores { _, error in loadError = error }
            if let loadError {
                logger.error("打开本地库失败，跳过建 CloudKit 结构：\(loadError.localizedDescription, privacy: .public)")
                return false
            }

            var initialized = false
            do {
                try container.initializeCloudKitSchema()
                initialized = true
            } catch {
                // 没登录 iCloud、没网或容器还没生效时会失败，下次启动再试，不影响使用
                logger.error("建 CloudKit 结构失败：\(error.localizedDescription, privacy: .public)")
            }

            var unloaded = true
            for store in container.persistentStoreCoordinator.persistentStores {
                do {
                    try container.persistentStoreCoordinator.remove(store)
                } catch {
                    unloaded = false
                    logger.error("卸载临时库失败：\(error.localizedDescription, privacy: .public)")
                }
            }
            return initialized && unloaded
        }
    }
#endif
