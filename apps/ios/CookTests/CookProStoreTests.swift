import CookCore
import Foundation
import StoreKit
import SwiftUI
import UIKit
import XCTest
@testable import Cook

@MainActor
private final class MockCookProStoreBackend: CookProStoreBackend {
    var storefront: String?
    var entitlement: CookProEntitlementReading = .empty
    var catalog: [CookProProductOption]
    var catalogError: (any Error)?
    var fetchedIDs: [Set<String>] = []
    var purchasedIDs: [String] = []
    var purchaseHandler: ((String) -> CookProPurchaseOutcome)?
    var restoreCount = 0
    var restoredEntitlement: CookProEntitlementReading?

    init(
        storefront: String?,
        catalog: [CookProProductOption]? = nil,
        entitlement: CookProEntitlementReading = .empty
    ) {
        self.storefront = storefront
        self.catalog = catalog ?? Self.defaultCatalog
        self.entitlement = entitlement
    }

    static let defaultCatalog: [CookProProductOption] = [
        CookProProductOption(
            id: CookProProductID.proLifetime,
            title: "Cook Pro 永久买断",
            subtitle: "解锁本机高级功能",
            displayPrice: "$3.99",
            periodLabel: "一次买断 · 永久有效",
            kind: .proLifetime
        ),
        CookProProductOption(
            id: CookProProductID.aiYear,
            title: "云端 AI 年度订阅",
            subtitle: "全年海外云端 AI",
            displayPrice: "$19.99",
            periodLabel: "每年自动续期",
            kind: .aiYearly
        ),
        CookProProductOption(
            id: CookProProductID.aiMonth,
            title: "云端 AI 月度订阅",
            subtitle: "每月海外云端 AI",
            displayPrice: "$2.99",
            periodLabel: "每月自动续期",
            kind: .aiMonthly
        ),
    ]

    func currentStorefrontCountryCode() async -> String? { storefront }
    func currentEntitlements() async -> CookProEntitlementReading { entitlement }

    func fetchProducts(ids: Set<String>) async throws -> [CookProProductOption] {
        fetchedIDs.append(ids)
        if let catalogError { throw catalogError }
        return catalog.filter { ids.contains($0.id) }
    }

    func purchase(productID: String, purchaseAction: PurchaseAction?) async throws -> CookProPurchaseOutcome {
        purchasedIDs.append(productID)
        if let outcome = purchaseHandler?(productID) {
            if case let .purchased(_, updated) = outcome {
                entitlement = updated
            }
            return outcome
        }
        return .cancelled
    }

    func restorePurchases() async throws {
        restoreCount += 1
        if let restoredEntitlement { entitlement = restoredEntitlement }
    }
}

private actor OverseasManagedAIStub: ManagedCookAIServing {
    nonisolated let isConfigured = true
    var recordedAuthorizations: [CookAIAuthorization] = []
    let capability: ManagedCookAICapability
    let intentResult: SearchIntent

    init(capability: ManagedCookAICapability, intentResult: SearchIntent) {
        self.capability = capability
        self.intentResult = intentResult
    }

    func capabilities(credential: YunlefunCredential) async throws -> ManagedCookAICapability {
        throw ManagedCookAIError.loginRequired
    }

    func parse(text: String, credential: YunlefunCredential, data: CookData,
               availableGoals: [DietaryGoal]) async throws -> SearchIntent {
        throw ManagedCookAIError.loginRequired
    }

    func capabilities(authorization: CookAIAuthorization) async throws -> ManagedCookAICapability {
        recordedAuthorizations.append(authorization)
        return capability
    }

    func parse(text: String, authorization: CookAIAuthorization, data: CookData,
               availableGoals: [DietaryGoal]) async throws -> SearchIntent {
        recordedAuthorizations.append(authorization)
        return intentResult
    }
}

@MainActor
final class CookProStoreTests: XCTestCase {
    private let validSampleJWS = ["test-header", "test-payload", "test-signature"].joined(separator: ".")

    func testMainlandChinaHidesAndBlocksAISubscriptionWhileAllowingBuyout() async {
        for region in ["CHN", "chn"] {
            let backend = MockCookProStoreBackend(storefront: region)
            backend.purchaseHandler = { id in
                XCTAssertEqual(id, CookProProductID.proLifetime)
                return .purchased(
                    productID: id,
                    entitlement: CookProEntitlementReading(
                        snapshot: CookProSnapshot(hasProLifetime: true),
                        aiSubscriptionJWS: nil
                    )
                )
            }
            let store = CookProStore(
                backend: backend,
                isolated: false,
                watchUpdates: false,
                mainlandAIEnabled: false,
                overseasAIEnabled: true
            )

            await store.start()
            XCTAssertTrue(store.isMainlandChinaStorefront)
            XCTAssertFalse(store.isOnlineAIAllowedInCurrentRegion)
            XCTAssertEqual(backend.fetchedIDs, [[CookProProductID.proLifetime]])
            XCTAssertTrue(store.aiSubscriptionProducts.isEmpty)
            XCTAssertNotNil(store.proProduct)

            if !CookProStore.shouldBypassQuotaForUITests {
                XCTAssertTrue(store.canAddFridgeItem(currentInStockCount: 11))
                XCTAssertFalse(store.canAddFridgeItem(currentInStockCount: 12))
                XCTAssertTrue(store.canAddPrivateRecipe(currentCount: 2))
                XCTAssertFalse(store.canAddPrivateRecipe(currentCount: 3))
                XCTAssertTrue(store.canOpenSpecialShowcase(index: 0))
                XCTAssertFalse(store.canOpenSpecialShowcase(index: 1))
            }

            // AI subscription purchase is fail-closed in Mainland
            await store.purchase(CookProProductID.aiMonth)
            XCTAssertTrue(backend.purchasedIDs.isEmpty)
            XCTAssertTrue(store.noticeIsError)

            // Permanent buyout unlocks all local limits without login
            await store.purchase(CookProProductID.proLifetime)
            XCTAssertTrue(store.hasProAccess)
            XCTAssertFalse(store.hasActiveAISubscription)
            XCTAssertTrue(store.canAddFridgeItem(currentInStockCount: 99))
            XCTAssertTrue(store.canAddPrivateRecipe(currentCount: 50))
            XCTAssertTrue(store.canOpenSpecialShowcase(index: 10))
        }
    }

    func testUnavailableProductKeepsExistingAccessAndBlocksPurchase() async {
        for region in [nil, "", " ", "GBR"] {
            let backend = MockCookProStoreBackend(storefront: region, catalog: [])
            let store = CookProStore(backend: backend, isolated: false, watchUpdates: false, overseasAIEnabled: false)
            await store.refreshAll()

            XCTAssertFalse(store.isProStoreAvailableInCurrentRegion)
            XCTAssertNil(store.proProduct)
            XCTAssertEqual(backend.fetchedIDs, [[CookProProductID.proLifetime]])
            XCTAssertNil(store.fridgeItemLimit)
            XCTAssertNil(store.privateRecipeLimit)
            XCTAssertTrue(store.canOpenSpecialShowcase(index: 10))

            await store.purchase(CookProProductID.proLifetime)
            XCTAssertTrue(backend.purchasedIDs.isEmpty)
        }
    }

    func testRetryCanLoadAndBuyLifetimeProductBeforeStorefrontIsKnown() async {
        let backend = MockCookProStoreBackend(storefront: nil)
        backend.catalogError = URLError(.notConnectedToInternet)
        backend.purchaseHandler = { productID in
            .purchased(
                productID: productID,
                entitlement: CookProEntitlementReading(
                    snapshot: CookProSnapshot(hasProLifetime: true),
                    aiSubscriptionJWS: nil
                )
            )
        }
        let store = CookProStore(backend: backend, isolated: false, watchUpdates: false, overseasAIEnabled: true)

        await store.start()
        XCTAssertNil(store.proProduct)
        backend.catalogError = nil
        await store.refreshAll()

        XCTAssertEqual(backend.fetchedIDs, [[CookProProductID.proLifetime], [CookProProductID.proLifetime]])
        XCTAssertEqual(store.proProduct?.displayPrice, "$3.99")
        XCTAssertNil(store.fridgeItemLimit)
        XCTAssertNil(store.privateRecipeLimit)
        XCTAssertTrue(store.aiSubscriptionProducts.isEmpty)

        await store.purchase(CookProProductID.proLifetime)
        XCTAssertEqual(backend.purchasedIDs, [CookProProductID.proLifetime])
        XCTAssertTrue(store.hasProAccess)
    }

    func testStorefrontChangeDiscardsPreviousPriceAndAppliesQuotaWhenProductLoads() async {
        let backend = MockCookProStoreBackend(storefront: "USA")
        let store = CookProStore(backend: backend, isolated: false, watchUpdates: false, overseasAIEnabled: false)
        await store.start()
        XCTAssertEqual(store.proProduct?.displayPrice, "$3.99")
        XCTAssertEqual(store.fridgeItemLimit, CookEntitlementPolicy.freeFridgeInStockLimit)

        backend.storefront = "JPN"
        backend.catalogError = URLError(.notConnectedToInternet)
        await store.refreshEntitlements()
        XCTAssertFalse(store.isProStoreAvailableInCurrentRegion)
        XCTAssertNil(store.proProduct)
        await store.loadProducts()
        XCTAssertEqual(backend.fetchedIDs, [[CookProProductID.proLifetime], [CookProProductID.proLifetime]])
        XCTAssertNil(store.fridgeItemLimit)
        XCTAssertNil(store.privateRecipeLimit)
        XCTAssertTrue(store.canOpenSpecialShowcase(index: 10))

        backend.catalogError = nil
        backend.catalog = [CookProProductOption(
            id: CookProProductID.proLifetime, title: "Cook Pro", subtitle: "",
            displayPrice: "¥600", periodLabel: nil, kind: .proLifetime
        )]
        await store.refreshAll()
        XCTAssertTrue(store.isProStoreAvailableInCurrentRegion)
        XCTAssertEqual(store.proProduct?.displayPrice, "¥600")
        XCTAssertEqual(store.fridgeItemLimit, CookEntitlementPolicy.freeFridgeInStockLimit)
        XCTAssertEqual(store.privateRecipeLimit, CookEntitlementPolicy.freePrivateRecipeLimit)

        backend.catalog = []
        await store.refreshAll()
        XCTAssertNil(store.proProduct)
        XCTAssertNil(store.fridgeItemLimit)
    }

    func testRestoreReadsLifetimeEntitlementWhenProductIsUnavailable() async {
        let backend = MockCookProStoreBackend(storefront: "GBR", catalog: [])
        let store = CookProStore(backend: backend, isolated: false, watchUpdates: false, overseasAIEnabled: false)
        await store.start()
        XCTAssertFalse(store.hasProAccess)
        backend.restoredEntitlement = CookProEntitlementReading(
            snapshot: CookProSnapshot(hasProLifetime: true), aiSubscriptionJWS: nil
        )
        await store.restorePurchases()
        XCTAssertEqual(backend.restoreCount, 1)
        XCTAssertTrue(store.hasProAccess)
        XCTAssertFalse(store.noticeIsError)
        XCTAssertNil(store.proProduct)
    }

    func testOverseasSubscriptionGrantsProAccessAndCapturesAppleJWSWithoutLogin() async {
        for region in ["USA", "HKG", "TWN", "JPN"] {
            let expiry = Date().addingTimeInterval(3_600)
            let backend = MockCookProStoreBackend(storefront: region)
            backend.purchaseHandler = { id in
                .purchased(
                    productID: id,
                    entitlement: CookProEntitlementReading(
                        snapshot: CookProSnapshot(
                            hasProLifetime: false,
                            aiSubscriptionExpiresAt: expiry,
                            aiSubscriptionProductID: CookProProductID.aiYear
                        ),
                        aiSubscriptionJWS: self.validSampleJWS
                    )
                )
            }
            let store = CookProStore(
                backend: backend,
                isolated: false,
                watchUpdates: false,
                mainlandAIEnabled: false,
                overseasAIEnabled: true
            )

            await store.start()
            XCTAssertFalse(store.isMainlandChinaStorefront)
            XCTAssertTrue(store.isOnlineAIAllowedInCurrentRegion)
            XCTAssertEqual(backend.fetchedIDs, [Set(CookProProductID.allProductIDs)])
            XCTAssertEqual(store.aiSubscriptionProducts.map(\.id), [CookProProductID.aiYear, CookProProductID.aiMonth])

            await store.purchase(CookProProductID.aiYear)
            XCTAssertTrue(store.hasActiveAISubscription)
            XCTAssertTrue(store.hasProAccess)
            XCTAssertEqual(store.aiSubscriptionJWS, validSampleJWS)
            XCTAssertTrue(store.canAddFridgeItem(currentInStockCount: 30))
        }
    }

    func testCookAIAuthorizationValidationFormatsAndRejectsMalformedJWS() {
        let valid = CookAIAuthorization.appleJWS(validSampleJWS)
        XCTAssertTrue(valid.isValidForTransmission)
        XCTAssertEqual(valid.headerValue, "AppleJWS \(validSampleJWS)")

        for bad in [
            "",
            "only.two",
            "a.b.c.d",
            "a..c",
            "a.b.c with space",
            String(repeating: "a", count: 16_500) + ".b.c",
        ] {
            XCTAssertFalse(CookAIAuthorization.appleJWS(bad).isValidForTransmission, bad)
        }
    }

    func testAllThreeManagedAIServicesSendAppleJWSHeader() async throws {
        let intentEndpoint = URL(string: "https://api.yunle.fun/cook-ai-intent")!
        let visionEndpoint = FridgeVisionService.productionURL
        let guidanceEndpoint = URL(string: "https://api.yunle.fun/cook-ai-guidance")!
        let expiryMs = Date().addingTimeInterval(3_600).timeIntervalSince1970 * 1_000
        let cookData = try RecipeStore.loadBundled(inMemory: true).data
        let expectedHeader = "AppleJWS \(validSampleJWS)"

        // 1. ManagedCookAIService
        let intentHeaders = LockedBox<[String]>([])
        let managedAI = ManagedCookAIService(endpoint: intentEndpoint) { request in
            intentHeaders.append(request.value(forHTTPHeaderField: "Authorization") ?? "")
            let bodyObject: [String: Any]
            if let httpBody = request.httpBody,
               let parsed = try? JSONSerialization.jsonObject(with: httpBody) as? [String: Any],
               parsed["action"] as? String == "capabilities" {
                bodyObject = [
                    "ok": true,
                    "serviceVersion": 1,
                    "enabled": true,
                    "membership": ["isActive": true, "expireAt": expiryMs],
                ]
            } else {
                bodyObject = [
                    "ok": true,
                    "intent": [
                        "schemaVersion": SearchIntent.currentSchemaVersion,
                        "policyVersion": SearchIntent.currentPolicyVersion,
                        "dataVersion": cookData.meta.dataVersion,
                        "source": "deepseek",
                        "query": [
                            "stuff": ["鸡蛋"],
                            "unknown": [],
                            "avoid": [],
                            "tool": NSNull(),
                            "noSpicy": false,
                            "difficulty": [],
                            "methodGroups": [],
                        ],
                        "goal": NSNull(),
                        "unresolved": [],
                    ],
                ]
            }
            let data = try JSONSerialization.data(withJSONObject: bodyObject)
            return (data, HTTPURLResponse(url: intentEndpoint, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let intentCaps = try await managedAI.capabilities(authorization: .appleJWS(validSampleJWS))
        XCTAssertTrue(intentCaps.enabled)
        let parsedIntent = try await managedAI.parse(
            text: "鸡蛋",
            authorization: .appleJWS(validSampleJWS),
            data: cookData,
            availableGoals: DietaryGoal.allCases
        )
        XCTAssertEqual(parsedIntent.query.stuff, ["鸡蛋"])
        XCTAssertEqual(intentHeaders.snapshot(), [expectedHeader, expectedHeader])

        // 2. FridgeVisionService
        let visionHeaders = LockedBox<[String]>([])
        let visionService = FridgeVisionService(endpoint: visionEndpoint) { request in
            visionHeaders.append(request.value(forHTTPHeaderField: "Authorization") ?? "")
            let data: Data
            if request.httpMethod == "GET" {
                data = try JSONSerialization.data(withJSONObject: [
                    "ok": true,
                    "schemaVersion": 1,
                    "provider": "cloudbase",
                    "member": true,
                ])
            } else {
                data = try JSONSerialization.data(withJSONObject: [
                    "ok": true,
                    "schemaVersion": 1,
                    "ingredients": ["番茄", "鸡蛋"],
                ])
            }
            return (data, HTTPURLResponse(url: visionEndpoint, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 16, height: 16))
        let jpegData = renderer.jpegData(withCompressionQuality: 0.8) { ctx in
            UIColor.orange.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 16, height: 16))
        }
        let preparedPhoto = try await FridgeVisionPhoto.prepare(jpegData)
        let visionProvider = try await visionService.memberProvider(authorization: .appleJWS(validSampleJWS))
        XCTAssertEqual(visionProvider, .cloudBase)
        let recognized = try await visionService.recognize(
            preparedPhoto,
            provider: .cloudBase,
            authorization: .appleJWS(validSampleJWS)
        )
        XCTAssertEqual(recognized, ["番茄", "鸡蛋"])
        XCTAssertEqual(visionHeaders.snapshot(), [expectedHeader, expectedHeader])

        // 3. ManagedStepGuidanceService
        let guidanceHeaders = LockedBox<[String]>([])
        let recipeText = RecipeText(blocks: [
            .heading("操作"),
            .list(ordered: true, start: 1, items: ["热锅加油炒蛋"]),
        ])
        let step = RecipePresentation(text: recipeText).steps[0]
        let prompt = try StepGuidancePrompt(
            recipe: Recipe(id: 42, name: "炒蛋", stuff: ["鸡蛋"]),
            text: recipeText,
            dataVersion: cookData.meta.dataVersion,
            step: step,
            servings: 2,
            kind: .explain,
            question: nil,
            requestID: "req-apple-1",
            contextRevision: "ctx-apple-1"
        )
        let guidanceService = ManagedStepGuidanceService(endpoint: guidanceEndpoint) { request in
            guidanceHeaders.append(request.value(forHTTPHeaderField: "Authorization") ?? "")
            let bodyObject: [String: Any]
            if let httpBody = request.httpBody,
               let parsed = try? JSONSerialization.jsonObject(with: httpBody) as? [String: Any],
               parsed["action"] as? String == "capabilities" {
                bodyObject = [
                    "ok": true,
                    "serviceVersion": 1,
                    "enabled": true,
                    "membership": ["isActive": true, "expireAt": expiryMs],
                ]
            } else {
                bodyObject = [
                    "ok": true,
                    "guidance": [
                        "schemaVersion": prompt.request.schemaVersion,
                        "policyVersion": prompt.request.policyVersion,
                        "dataVersion": prompt.request.dataVersion,
                        "sourceFingerprint": prompt.request.sourceFingerprint,
                        "recipeID": prompt.request.recipeID,
                        "stepID": prompt.request.stepID,
                        "requestID": prompt.request.requestID,
                        "contextRevision": prompt.request.contextRevision,
                        "status": "answer",
                        "answer": "先热锅，再加油。",
                        "sourceIDs": [prompt.currentStepSourceID],
                    ],
                ]
            }
            let data = try JSONSerialization.data(withJSONObject: bodyObject)
            return (data, HTTPURLResponse(url: guidanceEndpoint, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let guidanceCaps = try await guidanceService.capabilities(authorization: .appleJWS(validSampleJWS))
        XCTAssertTrue(guidanceCaps.enabled)
        let guidanceRes = try await guidanceService.complete(prompt, authorization: .appleJWS(validSampleJWS))
        XCTAssertEqual(guidanceRes.answer, "先热锅，再加油。")
        XCTAssertEqual(guidanceHeaders.snapshot(), [expectedHeader, expectedHeader])
    }

    func testCookAISettingsUsesAppleJWSWithoutLoginAndShutsOffInMainlandChina() async throws {
        let cookData = try RecipeStore.loadBundled(inMemory: true).data
        var stubQuery = CookQuery()
        stubQuery.stuff = ["番茄"]
        let stubIntent = SearchIntent(
            schemaVersion: SearchIntent.currentSchemaVersion,
            dataVersion: cookData.meta.dataVersion,
            policyVersion: SearchIntent.currentPolicyVersion,
            source: .deepseek,
            query: stubQuery,
            goal: nil,
            unresolved: []
        )
        let stub = OverseasManagedAIStub(
            capability: ManagedCookAICapability(
                enabled: true,
                membershipIsActive: true,
                membershipExpiresAt: Date().addingTimeInterval(3_600)
            ),
            intentResult: stubIntent
        )
        let settings = CookAISettings(
            defaults: nil,
            vault: MemoryCookAIKeyVault(),
            managedService: stub,
            onlineAIEnabled: false
        )
        let signedOutAccounts = YunlefunAccountStore(vault: MemoryYunlefunAccountVault())
        let overseasPro = CookProStore.isolated(
            snapshot: CookProSnapshot(
                hasProLifetime: false,
                aiSubscriptionExpiresAt: Date().addingTimeInterval(3_600),
                aiSubscriptionProductID: CookProProductID.aiMonth
            ),
            aiSubscriptionJWS: validSampleJWS,
            storefrontCountryCode: "USA",
            mainlandAIEnabled: false,
            overseasAIEnabled: true
        )

        XCTAssertFalse(settings.onlineAIEnabled)
        settings.updateRegionalAvailability(overseasPro.isOnlineAIAllowedInCurrentRegion)
        XCTAssertTrue(settings.onlineAIEnabled)

        await settings.refreshManagedCapability(accounts: signedOutAccounts, pro: overseasPro)
        XCTAssertEqual(settings.managedStatus, .ready)
        XCTAssertEqual(settings.managedAuthMode, .appleJWS)
        XCTAssertTrue(settings.isManagedAvailable)
        XCTAssertTrue(settings.selectProvider(.managedDeepSeek))
        XCTAssertTrue(settings.canUseManagedDeepSeek)

        let intent = try await settings.parseManaged(
            text: "番茄",
            data: cookData,
            availableGoals: DietaryGoal.allCases,
            accounts: signedOutAccounts,
            pro: overseasPro
        )
        XCTAssertEqual(intent.query.stuff, ["番茄"])
        let recorded = await stub.recordedAuthorizations
        XCTAssertEqual(recorded, [.appleJWS(validSampleJWS), .appleJWS(validSampleJWS)])

        // Switching back to Mainland shuts down AI immediately
        settings.updateRegionalAvailability(false)
        XCTAssertFalse(settings.onlineAIEnabled)
        XCTAssertEqual(settings.provider, .local)
        XCTAssertFalse(settings.isManagedAvailable)
        XCTAssertEqual(settings.managedStatus, .unconfigured)
    }
}

private final class LockedBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: T

    init(_ initial: T) {
        storage = initial
    }

    func append<Element>(_ element: Element) where T == [Element] {
        lock.lock()
        storage.append(element)
        lock.unlock()
    }

    func snapshot() -> T {
        lock.lock()
        let copy = storage
        lock.unlock()
        return copy
    }
}
