package cn.yunyoujun.cook.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.ui.components.DecideRandomDialog
import cn.yunyoujun.cook.ui.components.buildDecideContextSummary
import cn.yunyoujun.cook.ui.navigation.CookNavKey
import cn.yunyoujun.cook.ui.state.CookSessionController

@Composable
internal fun CookDecideDialog(
    snapshot: CookCatalogSnapshot,
    container: AppContainer,
    controller: CookSessionController,
    ignoringKeyword: Boolean,
    avoidFirstId: Int?,
    batchIds: IntArray? = null,
    onRecipePreviewed: (Int) -> Unit,
    onOpenRecipe: (CookNavKey.RecipeDetail) -> Unit,
    onDismiss: () -> Unit,
) {
    val store = container.preferencesStore
    val prefs by store.state.collectAsState()
    val conditions by controller.conditions.collectAsState()
    val goal = conditions.dietaryGoal(prefs)
    val batch = remember(batchIds) { batchIds?.toSet() }
    val candidates = remember(snapshot, controller, prefs, conditions, ignoringKeyword) {
        controller.evaluateCandidates(snapshot, prefs, ignoringKeyword)
    }
    val recipes = remember(candidates, batch) {
        if (batch == null) candidates.recipes else candidates.recipes.filter { it.id in batch }
    }
    val context = buildDecideContextSummary(conditions, prefs, ignoringKeyword)
    var showFilters by rememberSaveable { mutableStateOf(false) }

    DecideRandomDialog(
        recipes = recipes,
        matchesById = candidates.matchesById,
        contextSummary = if (batch == null) context else listOfNotNull("从这批灵感里选", context).joinToString(" · "),
        dietaryNote = { recipe -> goal?.let { snapshot.dietaryCatalog?.entry(recipe, it)?.reason(it) } },
        sceneReason = { recipe -> snapshot.sceneReason(recipe, conditions.discoveryScene) },
        onClearScene = controller::clearDiscoveryScene.takeIf { conditions.discoveryScene != null },
        avoidFirstId = avoidFirstId,
        onRecipePreviewed = onRecipePreviewed,
        onOpenFilters = { showFilters = true },
        filtersOpen = showFilters,
        onOpenRecipe = { id ->
            val latestPrefs = store.current
            val latest = controller.evaluateCandidates(snapshot, latestPrefs, ignoringKeyword)
            if ((batch == null || id in batch) && latest.recipes.any { it.id == id }) {
                onOpenRecipe(CookNavKey.RecipeDetail(
                    recipeId = id,
                    dietaryGoal = controller.conditions.value.dietaryGoal(latestPrefs),
                    matchedIngredients = latest.matchesById[id]?.matchedStuff.orEmpty(),
                ))
            }
        },
        onDismiss = onDismiss,
    )

    if (showFilters) {
        val catalog by container.personalCatalogStore.state.collectAsState()
        val food = remember(catalog, snapshot) { catalog.toDocument().applyingTo(snapshot.data.food) }
        CookFilterDialog(
            snapshot = snapshot,
            food = food,
            conditions = conditions,
            controller = controller,
            prefs = prefs,
            store = store,
            count = recipes.size,
            onClear = {
                controller.clearFiltersOnly()
                if (store.current.noSpicy) store.setNoSpicy(false)
            },
            onClose = { showFilters = false },
        )
    }
}
