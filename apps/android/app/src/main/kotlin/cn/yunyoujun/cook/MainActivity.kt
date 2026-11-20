package cn.yunyoujun.cook

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.remote.MembershipCheckReason
import cn.yunyoujun.cook.data.remote.YunlefunAccountWebHandoff
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.repository.CatalogLoadState
import cn.yunyoujun.cook.platform.RecipeShareLinks
import cn.yunyoujun.cook.ui.navigation.CookNavKey
import cn.yunyoujun.cook.ui.screens.AccountAndSyncScreen
import cn.yunyoujun.cook.ui.screens.WeChatSignupScreen
import cn.yunyoujun.cook.ui.screens.AiSettingsScreen
import cn.yunyoujun.cook.ui.screens.AppFeedbackScreen
import cn.yunyoujun.cook.ui.screens.CookTabScreen
import cn.yunyoujun.cook.ui.screens.CookingJournalScreen
import cn.yunyoujun.cook.ui.screens.FridgeTabScreen
import cn.yunyoujun.cook.ui.screens.KitchenWorkbenchScreen
import cn.yunyoujun.cook.ui.screens.LibraryTabScreen
import cn.yunyoujun.cook.ui.screens.MembershipScreen
import cn.yunyoujun.cook.ui.screens.PersonalCatalogManageScreen
import cn.yunyoujun.cook.ui.screens.PrivacySettingsScreen
import cn.yunyoujun.cook.ui.screens.PrivateRecipesManageScreen
import cn.yunyoujun.cook.ui.screens.RecipeDataSettingsScreen
import cn.yunyoujun.cook.ui.screens.RecipeDetailScreen
import cn.yunyoujun.cook.ui.screens.SettingsTabScreen
import cn.yunyoujun.cook.ui.state.CookSessionController
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingSharedRecipe = MutableStateFlow<Long?>(null)
    private var returningFromMembershipWebsite = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CookApplication).container
        returningFromMembershipWebsite = savedInstanceState?.getBoolean("returningFromMembershipWebsite") == true
        if (savedInstanceState == null) {
            handleIncomingLink(intent, container)
        } else if (savedInstanceState.containsKey("sharedRecipe")) {
            pendingSharedRecipe.value = savedInstanceState.getLong("sharedRecipe")
        }
        setContent {
            val prefs by container.preferencesStore.state.collectAsState()
            CookTheme(themeMode = prefs.themeMode) {
                val sharedRecipe by pendingSharedRecipe.collectAsState()
                CookAppShell(
                    container = container,
                    sharedRecipe = sharedRecipe,
                    onSharedRecipeHandled = { pendingSharedRecipe.compareAndSet(it, null) },
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
        pendingSharedRecipe.value?.let { outState.putLong("sharedRecipe", it) }
        outState.putBoolean("returningFromMembershipWebsite", returningFromMembershipWebsite)
    }

    override fun onPause() {
        val container = (application as CookApplication).container
        returningFromMembershipWebsite = container.yunlefunSessionManager.state.value.awaitingMembershipReturn
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        cn.yunyoujun.cook.platform.KitchenTimerScheduler.reschedulePersistedTimers(this)
        if (returningFromMembershipWebsite) {
            returningFromMembershipWebsite = false
            val container = (application as CookApplication).container
            lifecycleScope.launch {
                container.yunlefunSessionManager.checkMembership(MembershipCheckReason.CHECKOUT_RETURN)
            }
        }
    }

    private fun handleIncomingLink(intent: Intent?, container: AppContainer) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val rawUrl = intent.dataString ?: return
        RecipeShareLinks.recipeId(rawUrl)?.let {
            pendingSharedRecipe.value = it
            return
        }
        if (YunlefunAccountWebHandoff.isCallback(rawUrl)) {
            lifecycleScope.launch {
                try {
                    container.yunlefunSessionManager.openAccountWebsiteFromCallback(rawUrl) { url ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "无法打开云乐坊网页，请从原入口重试。", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        if (!YunlefunSSO.isCallback(rawUrl) || !rawUrl.substringAfter('#', "").startsWith("ylf_sso=")) return
        lifecycleScope.launch {
            container.yunlefunSessionManager.handleSsoCallbackUrl(rawUrl)
        }
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
    sharedRecipe: Long?,
    onSharedRecipeHandled: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val backStack = rememberNavBackStack(CookNavKey.CookTab)
    val loadState by container.catalogRepository.state.collectAsState()
    val sessionController = androidx.compose.runtime.saveable.rememberSaveable(saver = CookSessionController.Saver) {
        CookSessionController()
    }
    var unavailableShareId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(sharedRecipe, loadState) {
        val id = sharedRecipe ?: return@LaunchedEffect
        val ready = loadState as? CatalogLoadState.Ready ?: return@LaunchedEffect
        val recipe = if (id <= Int.MAX_VALUE) ready.snapshot.recipe(id.toInt()) else null
        if (recipe == null) {
            unavailableShareId = id
        } else {
            unavailableShareId = null
            backStack.clear()
            backStack.add(CookNavKey.LibraryTab)
            backStack.add(CookNavKey.RecipeDetail(recipe.id))
        }
        onSharedRecipeHandled(id)
    }

    unavailableShareId?.let { id ->
        AlertDialog(
            onDismissRequest = { unavailableShareId = null },
            title = { Text("找不到分享的菜谱") },
            text = { Text("当前内置菜谱库没有编号为 #$id 的菜谱。") },
            confirmButton = {
                TextButton(onClick = { unavailableShareId = null }) {
                    Text("知道了")
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
            }
        }

        is CatalogLoadState.Ready -> {
            val snapshot = current.snapshot
            val currentKey = backStack.lastOrNull()
            val showBottomBar = currentKey in TopTabKeys

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
                                onOpenRecipe = { recipeId ->
                                    backStack.add(CookNavKey.RecipeDetail(recipeId))
                                },
                            )
                        }
                        entry<CookNavKey.FridgeTab> {
                            FridgeTabScreen(
                                snapshot = snapshot,
                                container = container,
                                onOpenAccount = { backStack.add(CookNavKey.AccountAndSyncScreen) },
                                onCookWithIngredients = { ingredients ->
                                    val normalized = snapshot.data.normalizer.unique(ingredients)
                                    sessionController.setSelectedStuff(normalized)
                                    backStack.clear()
                                    backStack.add(CookNavKey.CookTab)
                                },
                            )
                        }
                        entry<CookNavKey.LibraryTab> {
                            LibraryTabScreen(
                                snapshot = snapshot,
                                container = container,
                                sessionController = sessionController,
                                onOpenRecipe = { recipeId -> backStack.add(CookNavKey.RecipeDetail(recipeId)) },
                                onOpenPrivateRecipes = { backStack.add(CookNavKey.PrivateRecipesScreen()) },
                                onOpenPersonalCatalog = { backStack.add(CookNavKey.PersonalCatalogScreen) },
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
                                onOpenFeedback = { backStack.add(CookNavKey.AppFeedbackScreen) },
                                onOpenRecipeData = { backStack.add(CookNavKey.RecipeDataSettingsScreen) },
                                onOpenPrivacy = { backStack.add(CookNavKey.PrivacySettingsScreen) },
                                onOpenCookingJournal = { backStack.add(CookNavKey.CookingJournal()) },
                            )
                        }
                        entry<CookNavKey.RecipeDetail> { route ->
                            RecipeDetailScreen(
                                recipeId = route.recipeId,
                                snapshot = snapshot,
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenWorkbench = { id -> backStack.add(CookNavKey.KitchenWorkbench(id)) },
                                onOpenJournal = { backStack.add(CookNavKey.CookingJournal(it)) },
                            )
                        }
                        entry<CookNavKey.KitchenWorkbench> { route ->
                            KitchenWorkbenchScreen(
                                recipeId = route.recipeId,
                                snapshot = snapshot,
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenJournal = { backStack.add(CookNavKey.CookingJournal(it)) },
                            )
                        }
                        entry<CookNavKey.PrivateRecipesScreen> { route ->
                            PrivateRecipesManageScreen(
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                targetId = route.targetId,
                                onOpenJournal = { backStack.add(CookNavKey.CookingJournal(it)) },
                            )
                        }
                        entry<CookNavKey.CookingJournal> { route ->
                            CookingJournalScreen(
                                store = container.cookingJournalStore,
                                recipeId = route.recipeId,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenRecipe = { id ->
                                    backStack.add(when (id) {
                                        is JournalRecipeId.Catalog -> CookNavKey.RecipeDetail(id.id)
                                        is JournalRecipeId.Personal -> CookNavKey.PrivateRecipesScreen(id.customId)
                                    })
                                },
                            )
                        }
                        entry<CookNavKey.PersonalCatalogScreen> {
                            PersonalCatalogManageScreen(
                                snapshot = snapshot,
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.AppFeedbackScreen> {
                            AppFeedbackScreen(
                                snapshot = snapshot,
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.AiSettingsScreen> {
                            AiSettingsScreen(
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.RecipeDataSettingsScreen> {
                            RecipeDataSettingsScreen(
                                snapshot = snapshot,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.PrivacySettingsScreen> {
                            PrivacySettingsScreen(
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.AccountAndSyncScreen> {
                            AccountAndSyncScreen(
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenWeChatSignup = { backStack.add(CookNavKey.WeChatSignup) },
                                onOpenMembership = {
                                    val previous = backStack.getOrNull(backStack.lastIndex - 1)
                                    if (previous == CookNavKey.Membership) backStack.removeLastOrNull()
                                    else backStack.add(CookNavKey.Membership)
                                },
                            )
                        }
                        entry<CookNavKey.Membership> {
                            MembershipScreen(
                                sessions = container.yunlefunSessionManager,
                                onBack = { if (backStack.lastOrNull() == CookNavKey.Membership) backStack.removeLastOrNull() },
                                onOpenAccount = {
                                    val previous = backStack.getOrNull(backStack.lastIndex - 1)
                                    if (previous == CookNavKey.AccountAndSyncScreen) backStack.removeLastOrNull()
                                    else backStack.add(CookNavKey.AccountAndSyncScreen)
                                },
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
