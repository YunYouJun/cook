package cn.yunyoujun.cook.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.data.remote.UserDataSyncException
import cn.yunyoujun.cook.data.remote.UserLibraryScope
import cn.yunyoujun.cook.data.repository.UserLibraryRepository
import kotlinx.coroutines.launch

@Composable
internal fun RecordRecipeVisit(recipe: Recipe?, library: UserLibraryRepository, openingScope: UserLibraryScope) {
    val tracker: RecipeVisitViewModel = viewModel()
    LaunchedEffect(recipe?.id, library) { tracker.showRecipe(recipe, library, openingScope) }
}

internal class RecipeVisitViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private var shownRecipeId = savedState.get<Int>(HANDLED_RECIPE_ID)
    private var opening = 0L
    private var failedOpening: Pair<Int, UserLibraryScope>? = null

    fun showRecipe(recipe: Recipe?, library: UserLibraryRepository, openingScope: UserLibraryScope) {
        if (recipe?.id == shownRecipeId && failedOpening == null) return
        val target = failedOpening?.takeIf { it.first == recipe?.id }?.second ?: openingScope
        failedOpening = null
        shownRecipeId = recipe?.id
        savedState.remove<Int>(HANDLED_RECIPE_ID)
        val currentOpening = ++opening
        if (recipe == null) return

        // Keep the write alive across Activity recreation and scope it to this navigation entry.
        viewModelScope.launch {
            val result = library.recordVisit(recipe, target)
            if (currentOpening == opening) {
                if (result.isSuccess || result.exceptionOrNull() is UserDataSyncException.LibraryChanged) {
                    savedState[HANDLED_RECIPE_ID] = recipe.id
                } else {
                    failedOpening = recipe.id to target
                }
            }
        }
    }

    private companion object {
        const val HANDLED_RECIPE_ID = "recordedRecipeId"
    }
}
