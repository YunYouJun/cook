package cn.yunyoujun.cook

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.repository.CatalogLoadState
import cn.yunyoujun.cook.ui.navigation.CookNavKey
import cn.yunyoujun.cook.ui.screens.AccountAndSyncScreen
import cn.yunyoujun.cook.ui.screens.AiSettingsScreen
import cn.yunyoujun.cook.ui.screens.AppFeedbackScreen
import cn.yunyoujun.cook.ui.screens.CookTabScreen
import cn.yunyoujun.cook.ui.screens.FridgeTabScreen
import cn.yunyoujun.cook.ui.screens.KitchenWorkbenchScreen
import cn.yunyoujun.cook.ui.screens.LibraryTabScreen
import cn.yunyoujun.cook.ui.screens.PersonalCatalogManageScreen
import cn.yunyoujun.cook.ui.screens.PrivacySettingsScreen
import cn.yunyoujun.cook.ui.screens.PrivateRecipesManageScreen
import cn.yunyoujun.cook.ui.screens.RecipeDataSettingsScreen
import cn.yunyoujun.cook.ui.screens.RecipeDetailScreen
import cn.yunyoujun.cook.ui.screens.SettingsTabScreen
import cn.yunyoujun.cook.ui.state.CookSessionController
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CookApplication).container
        handleIncomingSsoIntent(intent, container)
        setContent {
            val prefs by container.preferencesStore.state.collectAsState()
            CookTheme(themeMode = prefs.themeMode) {
                CookAppShell(container = container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val container = (application as CookApplication).container
        handleIncomingSsoIntent(intent, container)
    }

    override fun onResume() {
        super.onResume()
        cn.yunyoujun.cook.platform.KitchenTimerScheduler.reschedulePersistedTimers(this)
    }

    private fun handleIncomingSsoIntent(intent: Intent?, container: AppContainer) {
        val rawUrl = intent?.dataString ?: return
        if (!YunlefunSSO.isCallback(rawUrl)) return
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
) {
    val scope = rememberCoroutineScope()
    val backStack = rememberNavBackStack(CookNavKey.CookTab)
    val loadState by container.catalogRepository.state.collectAsState()
    val sessionController = androidx.compose.runtime.saveable.rememberSaveable(saver = CookSessionController.Saver) {
        CookSessionController()
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
                                onOpenRecipe = { recipeId -> backStack.add(CookNavKey.RecipeDetail(recipeId)) },
                                onOpenPrivateRecipes = { backStack.add(CookNavKey.PrivateRecipesScreen) },
                                onOpenPersonalCatalog = { backStack.add(CookNavKey.PersonalCatalogScreen) },
                                onOpenAccountSync = { backStack.add(CookNavKey.AccountAndSyncScreen) },
                            )
                        }
                        entry<CookNavKey.SettingsTab> {
                            SettingsTabScreen(
                                snapshot = snapshot,
                                container = container,
                                onOpenAccountSync = { backStack.add(CookNavKey.AccountAndSyncScreen) },
                                onOpenPrivateRecipes = { backStack.add(CookNavKey.PrivateRecipesScreen) },
                                onOpenPersonalCatalog = { backStack.add(CookNavKey.PersonalCatalogScreen) },
                                onOpenAiSettings = { backStack.add(CookNavKey.AiSettingsScreen) },
                                onOpenFeedback = { backStack.add(CookNavKey.AppFeedbackScreen) },
                                onOpenRecipeData = { backStack.add(CookNavKey.RecipeDataSettingsScreen) },
                                onOpenPrivacy = { backStack.add(CookNavKey.PrivacySettingsScreen) },
                            )
                        }
                        entry<CookNavKey.RecipeDetail> { route ->
                            RecipeDetailScreen(
                                recipeId = route.recipeId,
                                snapshot = snapshot,
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onOpenWorkbench = { id -> backStack.add(CookNavKey.KitchenWorkbench(id)) },
                            )
                        }
                        entry<CookNavKey.KitchenWorkbench> { route ->
                            KitchenWorkbenchScreen(
                                recipeId = route.recipeId,
                                snapshot = snapshot,
                                container = container,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }
                        entry<CookNavKey.PrivateRecipesScreen> {
                            PrivateRecipesManageScreen(
                                container = container,
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
                            )
                        }
                    },
                )
            }
        }
    }
}
