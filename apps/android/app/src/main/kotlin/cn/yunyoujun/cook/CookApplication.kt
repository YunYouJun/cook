package cn.yunyoujun.cook

import android.app.Application
import android.provider.Settings
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.CookingJournalStore
import cn.yunyoujun.cook.data.local.DeviceBackupStore
import cn.yunyoujun.cook.data.local.DevicePreferencesStore
import cn.yunyoujun.cook.data.local.FridgeProduceClassifier
import cn.yunyoujun.cook.data.local.FridgeProduceModel
import cn.yunyoujun.cook.data.local.FridgeProduceModelStore
import cn.yunyoujun.cook.data.local.FridgeStore
import cn.yunyoujun.cook.data.local.KitchenWorkbenchStore
import cn.yunyoujun.cook.data.local.PersonalCatalogStore
import cn.yunyoujun.cook.data.local.PrivateRecipeVideoStore
import cn.yunyoujun.cook.data.local.PrivateRecipesStore
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.local.ShowcaseAssetLibrary
import cn.yunyoujun.cook.data.remote.DeepSeekByokClient
import cn.yunyoujun.cook.data.remote.FeedbackClient
import cn.yunyoujun.cook.data.remote.FridgeVisionService
import cn.yunyoujun.cook.data.remote.ManagedCookAIService
import cn.yunyoujun.cook.data.remote.ManagedStepGuidanceService
import cn.yunyoujun.cook.data.remote.PlayMembershipService
import cn.yunyoujun.cook.data.remote.RecipeImageLoader
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.data.repository.CookCatalogRepository
import cn.yunyoujun.cook.data.repository.UserLibraryRepository
import cn.yunyoujun.cook.platform.FridgeDailyReminderScheduler
import cn.yunyoujun.cook.platform.GooglePlayMembershipBilling
import cn.yunyoujun.cook.platform.KitchenNotificationHelper
import cn.yunyoujun.cook.platform.KitchenTimerScheduler
import cn.yunyoujun.cook.platform.PlayMembershipStore
import cn.yunyoujun.cook.platform.createWeChatAuthorization
import cn.yunyoujun.cook.platform.playMembershipLedger
import cn.yunyoujun.cook.platform.validatedNetworkChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

class AppContainer(application: Application) {
    val database: CookDatabase by lazy { CookDatabase.create(application) }
    val catalogRepository: CookCatalogRepository by lazy { CookCatalogRepository(application.assets) }
    internal val recipeImages by lazy { RecipeImageLoader() }
    val preferencesStore: DevicePreferencesStore by lazy { DevicePreferencesStore(application) }
    val vault: SecretVault by lazy { AndroidKeystoreVault(application) }
    val yunlefunSessionManager: YunlefunSessionManager by lazy {
        YunlefunSessionManager(
            vault = vault,
            database = database,
            accountService = yunlefunAccountService,
            networkChanges = application.validatedNetworkChanges(),
            accountWebsiteBootCount = runCatching {
                Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1)
            }.getOrNull()?.takeIf { it >= 0 },
        )
    }
    internal val yunlefunAccountService by lazy { YunlefunAccountService() }
    internal val playMembership by lazy {
        application.playMembershipLedger()?.let { ledger ->
            PlayMembershipStore(
                yunlefunSessionManager,
                PlayMembershipService(ledger),
                GooglePlayMembershipBilling(application),
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                networkChanges = application.validatedNetworkChanges(),
                checkoutVault = vault,
            )
        }
    }
    internal val weChatAuthorization by lazy {
        createWeChatAuthorization(application)
    }
    val userLibraryRepository: UserLibraryRepository by lazy {
        UserLibraryRepository(database = database, initialSessionManager = yunlefunSessionManager)
    }
    val fridgeStore: FridgeStore by lazy { FridgeStore(application) }
    val workbenchStore: KitchenWorkbenchStore by lazy { KitchenWorkbenchStore(application) }
    val cookingJournalStore: CookingJournalStore by lazy { CookingJournalStore(application) }
    internal val deviceBackupStore by lazy {
        DeviceBackupStore(
            userLibraryRepository, preferencesStore, fridgeStore, privateRecipesStore,
            personalCatalogStore, cookingJournalStore, videoStore,
        )
    }
    internal val showcaseAssets: ShowcaseAssetLibrary by lazy {
        ShowcaseAssetLibrary.bundled(application, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
    val personalCatalogStore: PersonalCatalogStore by lazy { PersonalCatalogStore(application) }
    val privateRecipesStore: PrivateRecipesStore by lazy { PrivateRecipesStore(application) }
    val videoStore: PrivateRecipeVideoStore by lazy {
        PrivateRecipeVideoStore(application).also { it.removeAbandonedDrafts() }
    }
    val feedbackClient: FeedbackClient by lazy { FeedbackClient() }
    internal val fridgeVisionService by lazy {
        if (BuildConfig.COOK_FRIDGE_VISION_ENABLED) FridgeVisionService() else null
    }
    internal val fridgeProduceClassifier: FridgeProduceClassifier by lazy { FridgeProduceClassifier(application.assets) }
    internal val fridgeProduceModelStore by lazy {
        val manifest = application.assets.open("cook/produce/model.json").bufferedReader().use { it.readText() }
        FridgeProduceModelStore(
            directory = File(application.noBackupFilesDir, "produce-model"),
            model = Json.decodeFromString<FridgeProduceModel>(manifest),
            bundledModel = if (BuildConfig.COOK_BUNDLED_PRODUCE_MODEL) {
                { application.assets.open("cook/produce/classifier.onnx") }
            } else null,
        )
    }
    val deepSeekClient: DeepSeekByokClient by lazy { DeepSeekByokClient() }
    val managedAiService: ManagedCookAIService by lazy { ManagedCookAIService() }
    val managedStepGuidanceService: ManagedStepGuidanceService by lazy { ManagedStepGuidanceService() }
}

class CookApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        KitchenNotificationHelper.ensureChannels(this)
        KitchenTimerScheduler.reschedulePersistedTimers(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val stock = container.fridgeStore.state.map { it.stock }.distinctUntilChanged()
            val reminderSettings = container.preferencesStore.state
                .map { it.fridgeRemindersEnabled to it.fridgeReminderMinutes }
                .distinctUntilChanged()
            combine(stock, reminderSettings) { _, _ -> Unit }.collect {
                FridgeDailyReminderScheduler.reschedule(this@CookApplication)
            }
        }
    }
}
