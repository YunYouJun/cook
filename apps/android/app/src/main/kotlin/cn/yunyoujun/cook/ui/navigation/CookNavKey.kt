package cn.yunyoujun.cook.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface CookNavKey : NavKey {
    @Serializable
    data object CookTab : CookNavKey

    @Serializable
    data object FridgeTab : CookNavKey

    @Serializable
    data object LibraryTab : CookNavKey

    @Serializable
    data object SettingsTab : CookNavKey

    @Serializable
    data class RecipeDetail(val recipeId: Int) : CookNavKey

    @Serializable
    data class KitchenWorkbench(val recipeId: Int) : CookNavKey

    @Serializable
    data object PrivateRecipesScreen : CookNavKey

    @Serializable
    data object PersonalCatalogScreen : CookNavKey

    @Serializable
    data object AppFeedbackScreen : CookNavKey

    @Serializable
    data object AiSettingsScreen : CookNavKey

    @Serializable
    data object RecipeDataSettingsScreen : CookNavKey

    @Serializable
    data object PrivacySettingsScreen : CookNavKey

    @Serializable
    data object AccountAndSyncScreen : CookNavKey
}
