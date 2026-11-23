package cn.yunyoujun.cook

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.remote.MembershipWebsiteReturn
import cn.yunyoujun.cook.data.remote.YunlefunAccountWebHandoff
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.repository.CatalogLoadState
import cn.yunyoujun.cook.platform.FridgeDailyReminderScheduler
import cn.yunyoujun.cook.platform.KitchenNotificationHelper
import cn.yunyoujun.cook.platform.RecipeShareLinks
import cn.yunyoujun.cook.platform.browserLinkIntent
import cn.yunyoujun.cook.platform.openYunlefunAuthorizationApp
import cn.yunyoujun.cook.platform.validatedNetworkChanges
import cn.yunyoujun.cook.ui.navigation.CookNavKey
import cn.yunyoujun.cook.ui.screens.AboutSettingsScreen
import cn.yunyoujun.cook.ui.screens.AccountAndSyncScreen
import cn.yunyoujun.cook.ui.screens.WeChatSignupScreen
import cn.yunyoujun.cook.ui.screens.AiSettingsScreen
import cn.yunyoujun.cook.ui.screens.AppFeedbackPurpose
import cn.yunyoujun.cook.ui.screens.AppFeedbackScreen
import cn.yunyoujun.cook.ui.screens.CookTabScreen
import cn.yunyoujun.cook.ui.screens.CookingJournalScreen
import cn.yunyoujun.cook.ui.screens.DeviceBackupScreen
import cn.yunyoujun.cook.ui.screens.FridgeTabScreen
import cn.yunyoujun.cook.ui.screens.HelpAndFeedbackScreen
import cn.yunyoujun.cook.ui.screens.KitchenWorkbenchScreen
import cn.yunyoujun.cook.ui.screens.LegalDocument
import cn.yunyoujun.cook.ui.screens.LegalDocumentScreen
import cn.yunyoujun.cook.ui.screens.LibraryTabScreen
import cn.yunyoujun.cook.ui.screens.LibraryTransferScreen
import cn.yunyoujun.cook.ui.screens.MembershipScreen
import cn.yunyoujun.cook.ui.screens.PersonalCatalogManageScreen
import cn.yunyoujun.cook.ui.screens.PrivateRecipesManageScreen
import cn.yunyoujun.cook.ui.screens.RecipeDataSettingsScreen
import cn.yunyoujun.cook.ui.screens.RecipeDetailScreen
import cn.yunyoujun.cook.ui.screens.RecipeFeedbackScreen
import cn.yunyoujun.cook.ui.screens.SettingsTabScreen
import cn.yunyoujun.cook.ui.state.AccountBrowserFlow
import cn.yunyoujun.cook.ui.state.CookSessionController
import cn.yunyoujun.cook.ui.state.FridgePhotoIntake
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

private data class IncomingRecipeShare(val recipeId: Long, val coldLaunch: Boolean, val browserUrl: String)

class MainActivity : ComponentActivity() {
    private val fridgePhotoIntake by viewModels<FridgePhotoIntake>()
    private val fridgeGallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        fridgePhotoIntake.acceptGalleryPhoto(uri)
    }
    private val accountBrowser by viewModels<AccountBrowserFlow>()
    private val pendingSharedRecipe = MutableStateFlow<IncomingRecipeShare?>(null)
    private val pendingFridgeHome = MutableStateFlow(false)
    private val pendingTimerRecipe = MutableStateFlow<Int?>(null)
    private var returningFromMembershipWebsite: MembershipWebsiteReturn? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CookApplication).container
        rememberMembershipWebsiteReturn(container)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                returningFromMembershipWebsite?.let { returned ->
                    returningFromMembershipWebsite = null
                    launch {
                        container.yunlefunSessionManager.recoverMembershipWebsiteReturn(
                            returned, application.validatedNetworkChanges(emitOnStart = true),
                        )
                    }
                }
                accountBrowser.deliver(
                    sessions = container.yunlefunSessionManager,
                    open = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                    showMessage = { message ->
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    },
                    openSignInBrowser = { url -> startActivity(browserLinkIntent(url)) },
                    openSignInApp = { url -> openYunlefunAuthorizationApp(url) },
                )
            }
        }
        if (savedInstanceState == null) {
            handleIncomingLink(intent, container, coldLaunch = true)
            if (isFinishing) return
        } else if (savedInstanceState.containsKey("sharedRecipe")) {
            val recipeId = savedInstanceState.getLong("sharedRecipe")
            pendingSharedRecipe.value = IncomingRecipeShare(
                recipeId = recipeId,
                coldLaunch = savedInstanceState.getBoolean("sharedRecipeColdLaunch", true),
                browserUrl = RecipeShareLinks.browserUrl(recipeId, savedInstanceState.getString("sharedRecipeUrl")),
            )
        }
        if (savedInstanceState != null) {
            pendingFridgeHome.value = savedInstanceState.getBoolean("pendingFridgeHome")
            pendingTimerRecipe.value = savedInstanceState.getInt("pendingTimerRecipe", -1).takeIf { it >= 0 }
        }
        setContent {
            val prefs by container.preferencesStore.state.collectAsState()
            CookTheme(themeMode = prefs.themeMode) {
                val sharedRecipe by pendingSharedRecipe.collectAsState()
                val openFridgeHome by pendingFridgeHome.collectAsState()
                val timerRecipe by pendingTimerRecipe.collectAsState()
                CookAppShell(
                    container = container,
                    fridgePhotoIntake = fridgePhotoIntake,
                    onChooseFridgePhoto = { fridgeGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    accountBrowser = accountBrowser,
                    sharedRecipe = sharedRecipe,
                    onSharedRecipeHandled = { pendingSharedRecipe.compareAndSet(it, null) },
                    openFridgeHome = openFridgeHome,
                    onFridgeHomeOpened = { pendingFridgeHome.value = false },
                    timerRecipe = timerRecipe,
                    onTimerRecipeOpened = { pendingTimerRecipe.compareAndSet(it, null) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val container = (application as CookApplication).container
        handleIncomingLink(intent, container)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSharedRecipe.value?.let {
            outState.putLong("sharedRecipe", it.recipeId)
            outState.putBoolean("sharedRecipeColdLaunch", it.coldLaunch)
            outState.putString("sharedRecipeUrl", it.browserUrl)
        }
        outState.putBoolean("pendingFridgeHome", pendingFridgeHome.value)
        pendingTimerRecipe.value?.let { outState.putInt("pendingTimerRecipe", it) }
    }

    override fun onPause() {
        val container = (application as CookApplication).container
        container.playMembership?.onBackground()
        rememberMembershipWebsiteReturn(container)
        super.onPause()
    }

    private fun rememberMembershipWebsiteReturn(container: AppContainer) {
        returningFromMembershipWebsite = container.yunlefunSessionManager.state.value.membershipWebsiteReturn
    }

    override fun onResume() {
        super.onResume()
        val container = (application as CookApplication).container
        container.yunlefunSessionManager.refreshAppSignInOnForeground()
        container.yunlefunSessionManager.refreshAccountWebsiteOnForeground()
        container.yunlefunSessionManager.requestForegroundSync()
        container.yunlefunSessionManager.requestForegroundProfileRefresh()
        container.weChatAuthorization.onForeground()
        container.playMembership?.onForeground()
        cn.yunyoujun.cook.platform.KitchenTimerScheduler.reschedulePersistedTimers(this)
        FridgeDailyReminderScheduler.reschedule(this)
    }

    private fun handleIncomingLink(intent: Intent?, container: AppContainer, coldLaunch: Boolean = false) {
        if (intent == null) return
        if (intent.action == KitchenNotificationHelper.ACTION_OPEN_FRIDGE) {
            pendingSharedRecipe.value = null
            pendingTimerRecipe.value = null
            pendingFridgeHome.value = true
            return
        }
        KitchenNotificationHelper.timerRecipeFromNotification(intent)?.let {
            pendingFridgeHome.value = false
            pendingSharedRecipe.value = null
            pendingTimerRecipe.value = it
            return
        }
        if (intent.action != Intent.ACTION_VIEW) return
        val rawUrl = intent.dataString ?: return
        RecipeShareLinks.recipeId(rawUrl)?.let {
            val opensFromLibrary = coldLaunch || pendingSharedRecipe.value?.coldLaunch == true
            pendingFridgeHome.value = false
            pendingTimerRecipe.value = null
            pendingSharedRecipe.value = IncomingRecipeShare(it, opensFromLibrary, RecipeShareLinks.browserUrl(it, rawUrl))
            return
        }
        if (YunlefunAccountWebHandoff.isCallback(rawUrl)) {
            accountBrowser.approveWebsite(container.yunlefunSessionManager, rawUrl)
            return
        }
        if (YunlefunSSO.isSignInCallback(rawUrl)) {
            accountBrowser.completeSignIn(container.yunlefunSessionManager, rawUrl)
            return
        }
        when (YunlefunSSO.callbackTarget(rawUrl)) {
            YunlefunSSO.CallbackTarget.NATIVE ->
                Toast.makeText(this, "云乐坊授权回跳无效，请重新发起授权。", Toast.LENGTH_LONG).show()
            YunlefunSSO.CallbackTarget.WEB -> returnLinkToBrowser(rawUrl, coldLaunch)
            null -> if (RecipeShareLinks.isSharePage(rawUrl)) returnLinkToBrowser(rawUrl, coldLaunch)
        }
    }

    private fun returnLinkToBrowser(url: String, coldLaunch: Boolean) {
        runCatching { startActivity(browserLinkIntent(url)) }
            .onSuccess { if (coldLaunch) finish() }
            .onFailure { Toast.makeText(this, "暂时无法打开浏览器。", Toast.LENGTH_LONG).show() }
    }
}

private data class TabItem(
    val key: CookNavKey,
    val label: String,
    val badge: String,
)

private val TopTabs = listOf(
    TabItem(CookNavKey.CookTab, "找菜", "🍳"),
    TabItem(CookNavKey.FridgeTab, "冰箱", "🧊"),
    TabItem(CookNavKey.LibraryTab, "菜谱", "📖"),
    TabItem(CookNavKey.SettingsTab, "我的", "👤"),
)

private val TopTabKeys: Set<CookNavKey> = TopTabs.map { it.key }.toSet()

@Composable
private fun CookAppShell(
    container: AppContainer,
    fridgePhotoIntake: FridgePhotoIntake,
    onChooseFridgePhoto: () -> Unit,
    accountBrowser: AccountBrowserFlow,
    sharedRecipe: IncomingRecipeShare?,
    onSharedRecipeHandled: (IncomingRecipeShare) -> Unit,
    openFridgeHome: Boolean,
    onFridgeHomeOpened: () -> Unit,
    timerRecipe: Int?,
    onTimerRecipeOpened: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backStack = rememberNavBackStack(CookNavKey.CookTab)
    val loadState by container.catalogRepository.state.collectAsState()
    val sessionController = androidx.compose.runtime.saveable.rememberSaveable(saver = CookSessionController.Saver) {
        CookSessionController()
    }
    var unavailableShareId by rememberSaveable { mutableStateOf<Long?>(null) }
    var fridgeHomeGeneration by rememberSaveable { mutableIntStateOf(0) }
    var timerToReveal by rememberSaveable { mutableStateOf<Int?>(null) }
    var unavailableShareUrl by rememberSaveable { mutableStateOf<String?>(null) }

    fun dismissUnavailableShare() {
        unavailableShareId = null
        unavailableShareUrl = null
    }

    fun resumeOrOpen(route: CookNavKey) {
        val existing = backStack.lastIndexOf(route)
        if (existing == -1) backStack.add(route)
        else while (backStack.lastIndex > existing) backStack.removeLastOrNull()
    }

    LaunchedEffect(openFridgeHome) {
        if (openFridgeHome) {
            dismissUnavailableShare()
            timerToReveal = null
            fridgePhotoIntake.close()
            backStack.clear()
            backStack.add(CookNavKey.FridgeTab)
            fridgeHomeGeneration++
            onFridgeHomeOpened()
        }
    }

    LaunchedEffect(sharedRecipe, loadState) {
        val request = sharedRecipe ?: return@LaunchedEffect
        val ready = loadState as? CatalogLoadState.Ready ?: return@LaunchedEffect
        val id = request.recipeId
        val recipe = if (id <= Int.MAX_VALUE) ready.snapshot.recipe(id.toInt()) else null
        if (recipe == null) {
            unavailableShareId = id
            unavailableShareUrl = request.browserUrl
        } else {
            dismissUnavailableShare()
            if (request.coldLaunch) {
                timerToReveal = null
                backStack.clear()
                backStack.add(CookNavKey.LibraryTab)
            }
            val route = CookNavKey.RecipeDetail(recipe.id)
            val current = backStack.lastOrNull() as? CookNavKey.RecipeDetail
            if (current?.copy(shareInstance = null) != route) {
                // Use a distinct entry when the same recipe is already below the current screen.
                val sharedDetail = route.copy(shareInstance = UUID.randomUUID().toString())
                if (current?.shareInstance != null) {
                    backStack[backStack.lastIndex] = sharedDetail
                } else {
                    backStack.add(sharedDetail)
                }
            }
        }
        onSharedRecipeHandled(request)
    }

    LaunchedEffect(timerRecipe, loadState) {
        val id = timerRecipe ?: return@LaunchedEffect
        val ready = loadState as? CatalogLoadState.Ready ?: return@LaunchedEffect
        dismissUnavailableShare()
        if (ready.snapshot.recipe(id) != null) {
            val goal = container.workbenchStore.entry(id).timer.dietaryGoal
            val workbench = CookNavKey.KitchenWorkbench(id, goal)
            resumeOrOpen(workbench)
            timerToReveal = id
        } else {
            Toast.makeText(context, "当前菜谱库没有这道菜，无法打开工作台。", Toast.LENGTH_LONG).show()
        }
        onTimerRecipeOpened(id)
    }

    fun openSharedRecipeOnWeb(url: String): Boolean =
        runCatching { context.startActivity(browserLinkIntent(url)) }
            .onFailure { Toast.makeText(context, "暂时无法打开浏览器。", Toast.LENGTH_LONG).show() }
            .isSuccess

    unavailableShareId?.let { id ->
        AlertDialog(
            onDismissRequest = ::dismissUnavailableShare,
            title = { Text("找不到分享的菜谱") },
            text = { Text("当前内置菜谱库没有编号为 #$id 的菜谱。可以在网页查看，或更新云食册后重试。") },
            confirmButton = {
                TextButton(onClick = {
                    val url = RecipeShareLinks.browserUrl(id, unavailableShareUrl)
                    if (openSharedRecipeOnWeb(url)) dismissUnavailableShare()
                }) {
                    Text("在网页查看")
                }
            },
            dismissButton = {
                TextButton(onClick = ::dismissUnavailableShare) {
                    Text("关闭")
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        container.catalogRepository.loadIfNeeded()
    }

    LaunchedEffect(loadState) {
        val ready = loadState as? CatalogLoadState.Ready ?: return@LaunchedEffect
        container.preferencesStore.ensureDefaultPantry(ready.snapshot.data.pantry.defaults)
    }

    when (val current = loadState) {
        CatalogLoadState.Loading -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text("正在加载内置菜谱库…", style = MaterialTheme.typography.bodyMedium)
                sharedRecipe?.let { request ->
                    TextButton(onClick = { openSharedRecipeOnWeb(request.browserUrl) }) { Text("在网页查看") }
                }
            }
        }

        is CatalogLoadState.Failed -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("无法读取内置菜谱数据包", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(current.message, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { scope.launch { container.catalogRepository.reload() } }) {
                    Text("重试")
                }
                sharedRecipe?.let { request ->
                    TextButton(onClick = { openSharedRecipeOnWeb(request.browserUrl) }) { Text("在网页查看") }
                }
            }
        }

        is CatalogLoadState.Ready -> {
            val snapshot = current.snapshot
            val currentKey = backStack.lastOrNull()
            val showBottomBar = currentKey in TopTabKeys
            val hasFridge = CookNavKey.FridgeTab in backStack
            LaunchedEffect(hasFridge) {
                if (!hasFridge) fridgePhotoIntake.close()
            }

            Scaffold(
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar {
                            TopTabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentKey == tab.key,
                                    onClick = {
                                        if (currentKey != tab.key) {
                                            backStack.clear()
                                            backStack.add(tab.key)
                                        }
                                    },
                                    icon = { Text(tab.badge) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                NavDisplay(
                    backStack = backStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeLastOrNull()
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    entryProvider = entryProvider {
                        entry<CookNavKey.CookTab> {
                            CookTabScreen(
                                snapshot = snapshot,
                                sessionController = sessionController,
                                container = container,
                                onOpenRecipe = { route -> backStack.add(route) },
                                onOpenWorkbench = { backStack.add(it) },
                                onOpenJournal = { backStack.add(CookNavKey.CookingJournal(it)) },
                                onOpenRecipeFeedback = { resumeOrOpen(CookNavKey.RecipeFeedback(it)) },
                            )
                        }
                        entry<CookNavKey.FridgeTab> {
                            key(fridgeHomeGeneration) {
                                FridgeTabScreen(
                                    snapshot = snapshot,
                                    container = container,
                                    photoIntake = fridgePhotoIntake,
                                    onChooseGalleryPhoto = onChooseFridgePhoto,
                                    onOpenAccount = { backStack.add(CookNavKey.AccountAndSyncScreen) },
                                    onOpenMembership = { backStack.add(CookNavKey.Membership) },
                                    onOpenPrivacy = { backStack.add(CookNavKey.PrivacySettingsScreen) },
                                    onCookWithIngredients = { ingredients ->
                                        val normalized = snapshot.data.normalizer.unique(ingredients)
                                        sessionController.setSelectedStuff(normalized)
                                        backStack.clear()
                                        backStack.add(CookNavKey.CookTab)
                                    },
                                )
                            }
                        }
                        entry<CookNavKey.LibraryTab> {
                            LibraryTabScreen(
                                snapshot = snapshot,
                                container = container,
                                sessionController = sessionController,
                                onOpenRecipe = { route -> backStack.add(route) },
                                onOpenPrivateRecipes = { backStack.add(CookNavKey.PrivateRecipesScreen()) },
                                onOpenPersonalCatalog = { backStack.add(CookNavKey.PersonalCatalogScreen) },
                                onOpenLibraryTransfer = { backStack.add(CookNavKey.LibraryTransfer) },
                                onOpenAccountSync = { backStack.add(CookNavKey.AccountAndSyncScreen) },
                            )
                        }
                        entry<CookNavKey.SettingsTab> {
                            SettingsTabScreen(
                                snapshot = snapshot,
                                container = container,
                                onOpenAccountSync = { backStack.add(CookNavKey.AccountAndSyncScreen) },
                                onOpenMembership = { backStack.add(CookNavKey.Membership) },
                                onOpenPrivateRecipes = { backStack.add(CookNavKey.PrivateRecipesScreen()) },
                                onOpenPersonalCatalog = { backStack.add(CookNavKey.PersonalCatalogScreen) },
                                onOpenAiSettings = { backStack.add(CookNavKey.AiSettingsScreen) },
                                onOpenHelp = { backStack.add(CookNavKey.HelpScreen) },
                                onOpenRecipeData = { backStack.add(CookNavKey.RecipeDataSettingsScreen) },
                                onOpenAbout = { backStack.add(CookNavKey.About) },
                                onOpenPrivacy = { backStack.add(CookNavKey.PrivacySettingsScreen) },
                                onOpenCookingJournal = { backStack.add(CookNavKey.CookingJournal()) },
                                onOpenDeviceBackup = { backStack.add(CookNavKey.DeviceBackup) },
                            )
                        }
                        entry<CookNavKey.RecipeDetail> { route ->
                            RecipeDetailScreen(
                                recipeId = route.recipeId,
                                snapshot = snapshot,
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenWorkbench = { id -> resumeOrOpen(CookNavKey.KitchenWorkbench(id, route.dietaryGoal)) },
                                onOpenJournal = { resumeOrOpen(CookNavKey.CookingJournal(it)) },
                                dietaryGoal = route.dietaryGoal,
                                recommendationSession = sessionController.takeIf { route.matchedIngredients.isNotEmpty() },
                                onOpenRecipeFeedback = { resumeOrOpen(CookNavKey.RecipeFeedback(it)) },
                            )
                        }
                        entry<CookNavKey.RecipeFeedback> { route ->
                            RecipeFeedbackScreen(
                                recipe = snapshot.recipe(route.recipeId),
                                client = container.feedbackClient,
                                recipeVersion = snapshot.data.meta.dataVersion,
                                onBack = { if (backStack.lastOrNull() == route) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.KitchenWorkbench> { route ->
                            KitchenWorkbenchScreen(
                                recipeId = route.recipeId,
                                snapshot = snapshot,
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenJournal = { resumeOrOpen(CookNavKey.CookingJournal(it)) },
                                onOpenAiSettings = { resumeOrOpen(CookNavKey.AiSettingsScreen) },
                                onOpenAccount = { resumeOrOpen(CookNavKey.AccountAndSyncScreen) },
                                onOpenMembership = { resumeOrOpen(CookNavKey.Membership) },
                                dietaryGoal = route.dietaryGoal,
                                revealTimer = timerToReveal == route.recipeId && currentKey == route,
                                onTimerRevealed = { if (timerToReveal == route.recipeId) timerToReveal = null },
                            )
                        }
                        entry<CookNavKey.PrivateRecipesScreen> { route ->
                            PrivateRecipesManageScreen(
                                snapshot = snapshot,
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                targetId = route.targetId,
                                onOpenJournal = { resumeOrOpen(CookNavKey.CookingJournal(it)) },
                            )
                        }
                        entry<CookNavKey.CookingJournal> { route ->
                            val privateRecipes by container.privateRecipesStore.state.collectAsState()
                            val privateTitles = remember(privateRecipes.recipes) {
                                privateRecipes.recipes.associate { it.customId to it.name }
                            }
                            CookingJournalScreen(
                                store = container.cookingJournalStore,
                                showcaseAssets = container.showcaseAssets,
                                recipeId = route.recipeId,
                                recipeTitle = { id ->
                                    when (id) {
                                        is JournalRecipeId.Catalog -> snapshot.recipe(id.id)?.name
                                        is JournalRecipeId.Personal -> privateTitles[id.customId]
                                    }
                                },
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenRecipe = { id ->
                                    resumeOrOpen(when (id) {
                                        is JournalRecipeId.Catalog -> CookNavKey.RecipeDetail(id.id)
                                        is JournalRecipeId.Personal -> CookNavKey.PrivateRecipesScreen(id.customId)
                                    })
                                },
                            )
                        }
                        entry<CookNavKey.DeviceBackup> {
                            DeviceBackupScreen(
                                store = container.deviceBackupStore,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.PersonalCatalogScreen> {
                            PersonalCatalogManageScreen(
                                snapshot = snapshot,
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.HelpScreen> {
                            HelpAndFeedbackScreen(
                                weChatSupport = snapshot.links.wecomKf,
                                onOpenFeedback = { backStack.add(CookNavKey.AppFeedbackScreen) },
                                onOpenContribution = { backStack.add(CookNavKey.RecipeContributionScreen) },
                                onBack = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.AppFeedbackScreen> {
                            AppFeedbackScreen(
                                purpose = AppFeedbackPurpose.FEEDBACK,
                                client = container.feedbackClient,
                                recipeVersion = snapshot.data.meta.dataVersion,
                                onOpenPrivacy = { backStack.add(CookNavKey.PrivacySettingsScreen) },
                                onBack = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.RecipeContributionScreen> {
                            AppFeedbackScreen(
                                purpose = AppFeedbackPurpose.CONTRIBUTION,
                                client = container.feedbackClient,
                                recipeVersion = snapshot.data.meta.dataVersion,
                                onOpenPrivacy = { backStack.add(CookNavKey.PrivacySettingsScreen) },
                                onBack = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.AiSettingsScreen> {
                            AiSettingsScreen(
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenAccount = { backStack.add(CookNavKey.AccountAndSyncScreen) },
                            )
                        }
                        entry<CookNavKey.RecipeDataSettingsScreen> {
                            RecipeDataSettingsScreen(
                                snapshot = snapshot,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.PrivacySettingsScreen> {
                            LegalDocumentScreen(
                                document = LegalDocument.PRIVACY,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.TermsScreen> {
                            LegalDocumentScreen(
                                document = LegalDocument.TERMS,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenPrivacy = { backStack.add(CookNavKey.PrivacySettingsScreen) },
                            )
                        }
                        entry<CookNavKey.About> {
                            AboutSettingsScreen(
                                snapshot = snapshot,
                                onOpenRecipeData = { backStack.add(CookNavKey.RecipeDataSettingsScreen) },
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.AccountAndSyncScreen> {
                            AccountAndSyncScreen(
                                container = container,
                                accountBrowser = accountBrowser,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenWeChatSignup = { resumeOrOpen(CookNavKey.WeChatSignup) },
                                onOpenLibraryTransfer = { resumeOrOpen(CookNavKey.LibraryTransfer) },
                                onOpenMembership = { resumeOrOpen(CookNavKey.Membership) },
                            )
                        }
                        entry<CookNavKey.LibraryTransfer> {
                            LibraryTransferScreen(
                                container = container,
                                onBack = { if (backStack.lastOrNull() == CookNavKey.LibraryTransfer) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.Membership> {
                            MembershipScreen(
                                sessions = container.yunlefunSessionManager,
                                accountBrowser = accountBrowser,
                                playMembership = container.playMembership,
                                onBack = { if (backStack.lastOrNull() == CookNavKey.Membership) backStack.removeLastOrNull() },
                                onOpenAccount = { resumeOrOpen(CookNavKey.AccountAndSyncScreen) },
                                onOpenPrivacy = { backStack.add(CookNavKey.PrivacySettingsScreen) },
                                onOpenTerms = { backStack.add(CookNavKey.TermsScreen) },
                            )
                        }
                        entry<CookNavKey.WeChatSignup> {
                            WeChatSignupScreen(
                                container = container,
                                onBack = { if (backStack.lastOrNull() == CookNavKey.WeChatSignup) backStack.removeLastOrNull() },
                            )
                        }
                    },
                )
            }
        }
    }
}
