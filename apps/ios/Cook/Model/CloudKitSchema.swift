#if DEBUG
    import CoreData
    import Foundation
    import os
    import SwiftData

    /// 开发版第一次启动时，把 SwiftData 模型对应的 CloudKit 结构建到 Development 环境，
    /// 之后才能在 CloudKit Console 里「Deploy Schema Changes」到 Production（TestFlight 和 App Store 用 Production）。
    /// 只在 Debug 构建里编译；Release、TestFlight 不会运行。
    enum CloudKitSchema {
        /// 和 project.yml 里的 iCloud 容器一致
        static let containerIdentifier = "iCloud.fun.yunle.cook"
        /// 模型结构变了就加一，开发版下次启动会重新建一次
        static let version = 1
        private static let doneKey = "cloudkit.schemaInitialized"
        private static let logger = Logger(subsystem: "fun.yunle.cook", category: "CloudKitSchema")

        /// SwiftData 没有直接的接口，按 Apple 文档的做法：用同一个库文件临时开一个 NSPersistentCloudKitContainer，建完结构后卸载
        static func initializeIfNeeded(models: [any PersistentModel.Type], storeURL: URL, defaults: UserDefaults = .standard) {
            // 模拟器和测试用的是不签名的构建，没有 iCloud 权限，CloudKit 会直接中断进程，所以只在真机上跑；
            // 没登录 iCloud 时也跳过
            #if targetEnvironment(simulator)
                return
            #else
                guard ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil,
                      FileManager.default.ubiquityIdentityToken != nil,
                      defaults.integer(forKey: doneKey) < version
                else {
                    return
                }
                initialize(models: models, storeURL: storeURL, defaults: defaults)
            #endif
        }

        private static func initialize(models: [any PersistentModel.Type], storeURL: URL, defaults: UserDefaults) {
            guard let model = NSManagedObjectModel.makeManagedObjectModel(for: models) else {
                logger.error("无法从 SwiftData 模型生成 Core Data 模型")
                return
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
                return
            }
            do {
                try container.initializeCloudKitSchema()
                defaults.set(version, forKey: doneKey)
                logger.notice("已在 Development 环境建好 CloudKit 结构（版本 \(version)）")
            } catch {
                // 没登录 iCloud、没网或容器还没生效时会失败，下次启动再试，不影响使用
                logger.error("建 CloudKit 结构失败：\(error.localizedDescription, privacy: .public)")
            }
            for store in container.persistentStoreCoordinator.persistentStores {
                try? container.persistentStoreCoordinator.remove(store)
            }
        }
    }
#endif
