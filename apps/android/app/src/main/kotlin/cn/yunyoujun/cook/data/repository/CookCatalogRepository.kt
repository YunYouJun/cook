package cn.yunyoujun.cook.data.repository

import android.content.res.AssetManager
import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.dietary.DietaryCatalog
import cn.yunyoujun.cook.core.dietary.InstitutionalLowGICatalog
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.StuffItem
import cn.yunyoujun.cook.core.recipe.RecipeAmountAnnotations
import cn.yunyoujun.cook.core.recipe.RecipeText
import cn.yunyoujun.cook.core.recipe.RecipeTextLibrary
import cn.yunyoujun.cook.core.recommend.Recommender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class SharedLinks(
    val wecomKf: String? = null,
)

internal data class ReviewedRecipe(val goal: DietaryGoal, val entry: DietaryCatalog.Entry, val text: RecipeText)

data class CookCatalogSnapshot(
    val data: CookData,
    val recommender: Recommender,
    val textLibrary: RecipeTextLibrary?,
    val dietaryCatalog: DietaryCatalog?,
    val dietaryError: String?,
    val institutionalLowGICatalog: InstitutionalLowGICatalog?,
    val institutionalLowGIError: String?,
    val links: SharedLinks,
) {
    val recipesById: Map<Int, Recipe> = data.recipes.associateBy { it.id }

    val availableDietaryGoals: List<DietaryGoal>
        get() = DietaryGoal.entries.filter { dietaryCatalog?.availableGoals?.contains(it) == true }

    fun recipe(id: Int): Recipe? = recipesById[id]

    fun recipeText(id: Int): RecipeText? = textLibrary?.text(id)

    internal fun sceneReason(recipe: Recipe, scene: CookDiscoveryScene?): String? = when (scene) {
        null -> null
        CookDiscoveryScene.INSTITUTIONAL_LOW_GI -> institutionalLowGICatalog?.entry(recipe)?.sourceRecipeTitle
        else -> CookDiscovery.reason(recipe, scene.filters)
    }

    internal fun reviewedRecipe(recipe: Recipe, goal: DietaryGoal?): ReviewedRecipe? {
        if (goal == null) return null
        val catalog = dietaryCatalog ?: return null
        val entry = catalog.entry(recipe, goal) ?: return null
        val text = catalog.text(recipe.id) ?: return null
        return ReviewedRecipe(goal, entry, text)
    }

    fun stuffItemsByCategory(): Triple<List<StuffItem>, List<StuffItem>, List<StuffItem>> =
        Triple(data.food.vegetable, data.food.meat, data.food.staple)
}

sealed interface CatalogLoadState {
    data object Loading : CatalogLoadState
    data class Ready(val snapshot: CookCatalogSnapshot) : CatalogLoadState
    data class Failed(val message: String) : CatalogLoadState
}

class CookCatalogRepository(
    private val assets: AssetManager,
) {
    private val _state = MutableStateFlow<CatalogLoadState>(CatalogLoadState.Loading)
    val state: StateFlow<CatalogLoadState> = _state.asStateFlow()

    suspend fun loadIfNeeded() {
        if (_state.value is CatalogLoadState.Ready) return
        reload()
    }

    suspend fun reload() {
        _state.value = CatalogLoadState.Loading
        val result = withContext(Dispatchers.IO) {
            runCatching { loadSnapshot() }
        }
        _state.value = result.fold(
            onSuccess = { CatalogLoadState.Ready(it) },
            onFailure = { CatalogLoadState.Failed(it.message ?: "无法读取内置菜谱包") },
        )
    }

    private fun loadSnapshot(): CookCatalogSnapshot {
        fun readAssetBytes(fileName: String): ByteArray =
            assets.open("cook/$fileName").use { it.readBytes() }

        fun readOptionalAssetBytes(fileName: String): ByteArray? =
            runCatching { readAssetBytes(fileName) }.getOrNull()

        val cookData = CookData.loadFromBytes(
            readRequiredBytes = ::readAssetBytes,
            readOptionalBytes = ::readOptionalAssetBytes,
        )

        val amountsBytes = readOptionalAssetBytes(RecipeAmountAnnotations.FILE_NAME)
        val annotations = amountsBytes?.let { bytes ->
            RecipeAmountAnnotations.decode(bytes.toString(Charsets.UTF_8))
        }
        val textLibrary = readOptionalAssetBytes(RecipeTextLibrary.FILE_NAME)?.let { bytes ->
            RecipeTextLibrary.decode(bytes.toString(Charsets.UTF_8), annotations)
        }

        var dietaryCatalog: DietaryCatalog? = null
        var dietaryError: String? = null
        val dietaryBytes = readOptionalAssetBytes(DietaryCatalog.FILE_NAME)
        if (dietaryBytes != null && amountsBytes != null && textLibrary != null) {
            runCatching {
                DietaryCatalog.fromJson(
                    jsonText = dietaryBytes.toString(Charsets.UTF_8),
                    recipes = cookData.recipes,
                    amountsFingerprint = DietaryCatalog.amountsFingerprint(amountsBytes),
                    textForRecipe = textLibrary::text,
                )
            }.onSuccess { dietaryCatalog = it }
                .onFailure { dietaryError = it.message }
        }

        var lowGiCatalog: InstitutionalLowGICatalog? = null
        var lowGiError: String? = null
        val lowGiBytes = readOptionalAssetBytes(InstitutionalLowGICatalog.FILE_NAME)
        if (lowGiBytes != null) {
            runCatching {
                InstitutionalLowGICatalog.fromJson(
                    jsonText = lowGiBytes.toString(Charsets.UTF_8),
                    recipes = cookData.recipes,
                )
            }.onSuccess { lowGiCatalog = it }
                .onFailure { lowGiError = it.message }
        }

        val links = readOptionalAssetBytes("links.json")?.let { bytes ->
            runCatching { CookData.coreJson.decodeFromString<SharedLinks>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        } ?: SharedLinks()

        return CookCatalogSnapshot(
            data = cookData,
            recommender = Recommender(cookData.recipes, cookData.normalizer),
            textLibrary = textLibrary,
            dietaryCatalog = dietaryCatalog,
            dietaryError = dietaryError,
            institutionalLowGICatalog = lowGiCatalog,
            institutionalLowGIError = lowGiError,
            links = links,
        )
    }
}
