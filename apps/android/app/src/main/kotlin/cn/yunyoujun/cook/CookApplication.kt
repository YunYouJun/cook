package cn.yunyoujun.cook

import android.app.Application
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.CookingJournalStore
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
import cn.yunyoujun.cook.data.remote.DeepSeekByokClient
import cn.yunyoujun.cook.data.remote.FeedbackClient
import cn.yunyoujun.cook.data.remote.FridgeVisionService
import cn.yunyoujun.cook.data.remote.ManagedCookAIService
import cn.yunyoujun.cook.data.remote.ManagedStepGuidanceService
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.data.repository.CookCatalogRepository
import cn.yunyoujun.cook.data.repository.UserLibraryRepository
import cn.yunyoujun.cook.platform.FridgeDailyReminderScheduler
import cn.yunyoujun.cook.platform.KitchenNotificationHelper
import cn.yunyoujun.cook.platform.KitchenTimerScheduler
import cn.yunyoujun.cook.platform.WeChatAuthorization
import cn.yunyoujun.cook.platform.WeChatOpenSdkDriver
import kotlinx.serialization.json.Json
import java.io.File

class AppContainer(application: Application) {
    val database: CookDatabase by lazy { CookDatabase.create(application) }
    val catalogRepository: CookCatalogRepository by lazy { CookCatalogRepository(application.assets) }
    val preferencesStore: DevicePreferencesStore by lazy { DevicePreferencesStore(application) }
    val vault: SecretVault by lazy { AndroidKeystoreVault(application) }
    val yunlefunSessionManager: YunlefunSessionManager by lazy {
        YunlefunSessionManager(vault = vault, database = database, accountService = yunlefunAccountService)
    }
    internal val yunlefunAccountService by lazy { YunlefunAccountService() }
    internal val weChatAuthorization by lazy {
        WeChatAuthorization(BuildConfig.COOK_WECHAT_LOGIN_ENABLED, WeChatOpenSdkDriver(application))
    }
    val userLibraryRepository: UserLibraryRepository by lazy {
        UserLibraryRepository(database = database, initialSessionManager = yunlefunSessionManager)
    }
    val fridgeStore: FridgeStore by lazy { FridgeStore(application) }
    val workbenchStore: KitchenWorkbenchStore by lazy { KitchenWorkbenchStore(application) }
    val cookingJournalStore: CookingJournalStore by lazy { CookingJournalStore(application) }
    val personalCatalogStore: PersonalCatalogStore by lazy { PersonalCatalogStore(application) }
    val privateRecipesStore: PrivateRecipesStore by lazy { PrivateRecipesStore(application) }
    val videoStore: PrivateRecipeVideoStore by lazy {
        PrivateRecipeVideoStore(application).also { it.removeAbandonedDrafts() }
    }
    val feedbackClient: FeedbackClient by lazy { FeedbackClient() }
    val fridgeVisionService: FridgeVisionService by lazy { FridgeVisionService() }
    internal val fridgeProduceClassifier: FridgeProduceClassifier by lazy { FridgeProduceClassifier(application.assets) }
    internal val fridgeProduceModelStore by lazy {
        val manifest = application.assets.open("cook/produce/model.json").bufferedReader().use { it.readText() }
        FridgeProduceModelStore(
            directory = File(application.noBackupFilesDir, "produce-model"),
            model = Json.decodeFromString<FridgeProduceModel>(manifest),
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
        FridgeDailyReminderScheduler.checkAndNotifyIfNeeded(this)
        FridgeDailyReminderScheduler.scheduleNextDailyCheck(this)
    }
}
