package cn.yunyoujun.cook.ui.state

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.ai.SearchIntentValidator
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.core.recommend.RecipeMatch
import cn.yunyoujun.cook.core.recommend.RecommendationOptions
import cn.yunyoujun.cook.core.search.CookQuery
import cn.yunyoujun.cook.core.search.CookQueryParser
import cn.yunyoujun.cook.data.local.DevicePreferencesSnapshot
import cn.yunyoujun.cook.data.local.DevicePreferencesStore
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.data.repository.CookDiscovery
import cn.yunyoujun.cook.data.repository.CookDiscoveryScene
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CookSessionConditions(
    val selectedStuff: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    val noSpicy: Boolean = false,
    val tool: String? = null,
    val methodGroups: List<String> = emptyList(),
    val difficulty: List<String> = emptyList(),
    val keyword: String = "",
    val discoveryScene: CookDiscoveryScene? = null,
    val hideRememberedDietaryGoal: Boolean = false,
) {
    fun isFilterActive(prefs: DevicePreferencesSnapshot): Boolean =
        activeFilterCount(prefs) > 0

    fun dietaryGoal(prefs: DevicePreferencesSnapshot): DietaryGoal? =
        prefs.rememberedDietaryGoal.takeUnless { hideRememberedDietaryGoal }

    fun activeFilterCount(prefs: DevicePreferencesSnapshot): Int =
        methodGroups.size +
            difficulty.size +
            avoid.size +
            (if (noSpicy || prefs.noSpicy) 1 else 0) +
            (if (dietaryGoal(prefs) != null) 1 else 0) +
            (if (tool != null) 1 else 0) +
            (if (discoveryScene != null) 1 else 0)
}

data class CookCandidateResult(
    val recipes: List<Recipe>,
    val matchesById: Map<Int, RecipeMatch>,
    val isCandidatePoolMode: Boolean,
)

internal class SearchFilterConflict : Exception("本次条件与当前厨具、做法、难度或饮食目标冲突。请修改预览条件或返回调整筛选。")

class CookSessionController(
    initialConditions: CookSessionConditions = CookSessionConditions(),
) {
    private val _conditions = MutableStateFlow(initialConditions)
    val conditions: StateFlow<CookSessionConditions> = _conditions.asStateFlow()

    fun toggleStuff(name: String) {
        update { prev ->
            val next = if (name in prev.selectedStuff) prev.selectedStuff - name else prev.selectedStuff + name
            prev.copy(selectedStuff = next)
        }
    }

    fun setSelectedStuff(stuff: List<String>) {
        update { it.copy(selectedStuff = stuff.distinct()) }
    }

    fun toggleTool(name: String) {
        update { prev ->
            prev.copy(tool = if (prev.tool == name) null else name)
        }
    }

    fun toggleMethodGroup(groupLabel: String) {
        update { prev ->
            val next = if (groupLabel in prev.methodGroups) {
                prev.methodGroups - groupLabel
            } else {
                prev.methodGroups + groupLabel
            }
            prev.copy(methodGroups = next)
        }
    }

    fun toggleDifficulty(level: String) {
        update { prev ->
            val next = if (level in prev.difficulty) prev.difficulty - level else prev.difficulty + level
            prev.copy(difficulty = next)
        }
    }

    fun setKeyword(keyword: String) {
        update { it.copy(keyword = keyword) }
    }

    /** Returns the applied conditions, if any, so the caller can persist dietary restrictions. */
    fun applyDiscoveryInput(snapshot: CookCatalogSnapshot, input: String, parser: CookQueryParser): CookQuery? {
        val text = input.trim()
        if (text.isEmpty()) return null

        val parsed = parser.parse(text)
        val query = parsed.copy(unknown = emptyList())
        val hasOtherConditions = !query.copy(stuff = emptyList()).isEmpty
        val onlyKnownIngredients = query.stuff.isNotEmpty() && parsed.unknown.isEmpty() && !hasOtherConditions
        val exactDish = snapshot.data.recipes.any { it.name == text }
        val partialDish = !onlyKnownIngredients && snapshot.data.recipes.any { text in it.name }
        if (exactDish || partialDish || query.isEmpty || (parsed.unknown.isNotEmpty() && !hasOtherConditions)) {
            setKeyword(text)
            return null
        }

        val normalizer = snapshot.data.normalizer
        val avoided = normalizer.unique(query.avoid).toSet()
        update { current ->
            current.copy(
                selectedStuff = normalizer.unique(current.selectedStuff + query.stuff).filterNot(avoided::contains),
                tool = query.tool ?: current.tool,
                methodGroups = (current.methodGroups + query.methodGroups).distinct(),
                difficulty = (current.difficulty + query.difficulty).distinct(),
                keyword = "",
            )
        }
        return query
    }

    fun addDiscoveryIngredient(name: String) {
        update { it.copy(selectedStuff = (it.selectedStuff + name).distinct(), keyword = "") }
    }

    fun toggleDiscoveryScene(scene: CookDiscoveryScene) {
        update { prev ->
            prev.copy(discoveryScene = if (prev.discoveryScene == scene) null else scene)
        }
    }

    fun clearDiscoveryScene() {
        update { it.copy(discoveryScene = null) }
    }

    /** 清空本次所有临时选菜条件（保留长期常备调料与默认饮食目标）。 */
    fun clearSessionConditions() {
        update { CookSessionConditions(hideRememberedDietaryGoal = it.hideRememberedDietaryGoal) }
    }

    /** 清除本次筛选条件（保留已选食材、关键词和记住的饮食目标）。 */
    fun clearFiltersOnly() {
        update {
            it.copy(
                avoid = emptyList(),
                noSpicy = false,
                tool = null,
                methodGroups = emptyList(),
                difficulty = emptyList(),
                discoveryScene = null,
                hideRememberedDietaryGoal = true,
            )
        }
    }

    fun selectDietaryGoal(goal: DietaryGoal?, preferences: DevicePreferencesStore) {
        preferences.selectDietaryGoal(goal)
        update { it.copy(hideRememberedDietaryGoal = false) }
    }

    fun setNoSpicy(enabled: Boolean, preferences: DevicePreferencesStore) {
        preferences.setNoSpicy(enabled)
        update { it.copy(noSpicy = false) }
    }

    fun applySearchIntent(snapshot: CookCatalogSnapshot, intent: SearchIntent, preferences: DevicePreferencesStore) {
        val validated = SearchIntentValidator(snapshot.data, snapshot.availableDietaryGoals).validate(intent)
        val query = validated.query
        val current = _conditions.value
        val activeGoal = current.dietaryGoal(preferences.current)
        val toolConflicts = current.tool != null && query.tool != null && current.tool != query.tool
        val goalConflicts = activeGoal != null && validated.goal != null && activeGoal != validated.goal
        if (toolConflicts || goalConflicts) {
            throw SearchFilterConflict()
        }
        fun intersect(active: List<String>, added: List<String>): List<String> = when {
            active.isEmpty() -> added
            added.isEmpty() -> active
            else -> active.filter(added::contains).ifEmpty { throw SearchFilterConflict() }
        }
        val normalizer = snapshot.data.normalizer
        val avoided = normalizer.unique(query.avoid).toSet()
        val merged = current.copy(
            selectedStuff = normalizer.unique(current.selectedStuff + query.stuff).filterNot(avoided::contains),
            tool = query.tool ?: current.tool,
            methodGroups = intersect(current.methodGroups, query.methodGroups),
            difficulty = intersect(current.difficulty, query.difficulty),
            hideRememberedDietaryGoal = validated.goal == null && current.hideRememberedDietaryGoal,
        )
        preferences.applySearchPreferences(query.avoid, query.noSpicy, validated.goal)
        _conditions.value = merged
    }

    fun evaluateCandidates(
        snapshot: CookCatalogSnapshot,
        prefs: DevicePreferencesSnapshot,
        ignoringKeyword: Boolean = false,
    ): CookCandidateResult {
        val cond = _conditions.value
        val activeKeyword = if (ignoringKeyword) "" else cond.keyword.trim()
        val options = RecommendationOptions(
            stuff = cond.selectedStuff,
            pantry = prefs.pantry,
            tool = cond.tool,
            keyword = activeKeyword.takeIf { it.isNotEmpty() },
            mode = prefs.mode,
            avoid = (prefs.avoid + cond.avoid).distinct(),
            noSpicy = prefs.noSpicy || cond.noSpicy,
            difficulty = cond.difficulty,
            methodGroups = cond.methodGroups,
        )
        val goal = cond.dietaryGoal(prefs)
        if (cond.selectedStuff.isNotEmpty()) {
            val rawMatches = if (goal != null) {
                snapshot.dietaryCatalog?.recommend(options, goal, snapshot.recommender).orEmpty()
            } else {
                snapshot.recommender.recommend(options)
            }
            val filtered = rawMatches.filter { match ->
                matchesScene(match.recipe, cond.discoveryScene, options, snapshot)
            }
            return CookCandidateResult(
                recipes = filtered.map { it.recipe },
                matchesById = filtered.associateBy { it.recipe.id },
                isCandidatePoolMode = false,
            )
        }

        val rawCandidates = if (goal != null) {
            snapshot.dietaryCatalog?.candidates(options, goal, snapshot.recommender).orEmpty()
        } else {
            snapshot.recommender.candidates(options)
        }
        val filteredCandidates = rawCandidates.filter { recipe ->
            (activeKeyword.isEmpty() || activeKeyword in recipe.name) &&
                matchesScene(recipe, cond.discoveryScene, options, snapshot)
        }
        return CookCandidateResult(
            recipes = filteredCandidates,
            matchesById = emptyMap(),
            isCandidatePoolMode = true,
        )
    }

    private fun matchesScene(
        recipe: Recipe,
        scene: CookDiscoveryScene?,
        options: RecommendationOptions,
        snapshot: CookCatalogSnapshot,
    ): Boolean {
        if (scene == null) return true
        if (!CookDiscovery.matches(recipe, scene.filters)) return false
        return scene != CookDiscoveryScene.INSTITUTIONAL_LOW_GI ||
            snapshot.institutionalLowGICatalog?.permits(recipe, options, snapshot.data.normalizer) == true
    }

    private fun update(transform: (CookSessionConditions) -> CookSessionConditions) {
        _conditions.value = transform(_conditions.value)
    }

    companion object {
        val Saver: Saver<CookSessionController, Bundle> = Saver(
            save = { controller ->
                val c = controller.conditions.value
                Bundle().apply {
                    putStringArrayList("selectedStuff", ArrayList(c.selectedStuff))
                    putStringArrayList("avoid", ArrayList(c.avoid))
                    putBoolean("noSpicy", c.noSpicy)
                    putString("tool", c.tool)
                    putStringArrayList("methodGroups", ArrayList(c.methodGroups))
                    putStringArrayList("difficulty", ArrayList(c.difficulty))
                    putString("keyword", c.keyword)
                    putString("discoveryScene", c.discoveryScene?.name)
                    putBoolean("hideRememberedDietaryGoal", c.hideRememberedDietaryGoal)
                }
            },
            restore = { bundle ->
                val sceneName = bundle.getString("discoveryScene")
                val scene = sceneName?.let { name ->
                    CookDiscoveryScene.entries.firstOrNull { it.name == name }
                }
                CookSessionController(
                    CookSessionConditions(
                        selectedStuff = bundle.getStringArrayList("selectedStuff")?.toList().orEmpty(),
                        avoid = bundle.getStringArrayList("avoid")?.toList().orEmpty(),
                        noSpicy = bundle.getBoolean("noSpicy", false),
                        tool = bundle.getString("tool"),
                        methodGroups = bundle.getStringArrayList("methodGroups")?.toList().orEmpty(),
                        difficulty = bundle.getStringArrayList("difficulty")?.toList().orEmpty(),
                        keyword = bundle.getString("keyword").orEmpty(),
                        discoveryScene = scene,
                        hideRememberedDietaryGoal = bundle.getBoolean("hideRememberedDietaryGoal"),
                    ),
                )
            },
        )
    }
}
