package cn.yunyoujun.cook.ui.navigation

import androidx.navigation3.runtime.NavKey
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.data.local.JournalRecipeId
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
    data class RecipeDetail(
        val recipeId: Int,
        val dietaryGoal: DietaryGoal? = null,
        val matchedIngredients: List<String> = emptyList(),
        val shareInstance: String? = null,
    ) : CookNavKey

    @Serializable
    data class RecipeFeedback(val recipeId: Int) : CookNavKey

    @Serializable
    data class KitchenWorkbench(val recipeId: Int, val dietaryGoal: DietaryGoal? = null) : CookNavKey

    @Serializable
    data class PrivateRecipesScreen(val targetId: String? = null) : CookNavKey

    @Serializable
    data class CookingJournal(val recipeId: JournalRecipeId? = null) : CookNavKey

    @Serializable
    data object PersonalCatalogScreen : CookNavKey

    @Serializable
    data object HelpScreen : CookNavKey

    @Serializable
    data object AppFeedbackScreen : CookNavKey

    @Serializable
    data object RecipeContributionScreen : CookNavKey

    @Serializable
    data object AiSettingsScreen : CookNavKey

    @Serializable
    data object RecipeDataSettingsScreen : CookNavKey

    @Serializable
    data object About : CookNavKey

    @Serializable
    data object PrivacySettingsScreen : CookNavKey

    @Serializable
    data object TermsScreen : CookNavKey

    @Serializable
    data object AccountAndSyncScreen : CookNavKey

    @Serializable
    data object LibraryTransfer : CookNavKey

    @Serializable
    data object Membership : CookNavKey

    @Serializable
    data object DeviceBackup : CookNavKey

    @Serializable
    data object WeChatSignup : CookNavKey
}
