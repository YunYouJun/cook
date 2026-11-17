package cn.yunyoujun.cook.ui.state

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import cn.yunyoujun.cook.core.ai.LocalSearchIntentParser
import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.core.recommend.RecipeMatch
import cn.yunyoujun.cook.core.recommend.RecommendationOptions
import cn.yunyoujun.cook.data.local.DevicePreferencesSnapshot
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
) {
    fun isFilterActive(prefs: DevicePreferencesSnapshot): Boolean =
        methodGroups.isNotEmpty() ||
            difficulty.isNotEmpty() ||
            avoid.isNotEmpty() ||
            noSpicy ||
            prefs.noSpicy ||
            prefs.rememberedDietaryGoal != null ||
            discoveryScene != null

    fun activeFilterCount(prefs: DevicePreferencesSnapshot): Int =
        methodGroups.size +
            difficulty.size +
            avoid.size +
            (if (noSpicy || prefs.noSpicy) 1 else 0) +
            (if (prefs.rememberedDietaryGoal != null) 1 else 0) +
            (if (discoveryScene != null) 1 else 0)
}

data class CookCandidateResult(
    val recipes: List<Recipe>,
    val matchesById: Map<Int, RecipeMatch>,
    val isCandidatePoolMode: Boolean,
)

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

    fun toggleDiscoveryScene(scene: CookDiscoveryScene) {
        update { prev ->
            prev.copy(discoveryScene = if (prev.discoveryScene == scene) null else scene)
        }
    }

    /** 清空本次所有临时选菜条件（保留长期常备调料与默认饮食目标）。 */
    fun clearSessionConditions() {
        _conditions.value = CookSessionConditions()
    }

    /** 仅清除筛选条件与场景（保留已选食材和关键词）。 */
    fun clearFiltersOnly() {
        update {
            it.copy(
                avoid = emptyList(),
                noSpicy = false,
                tool = null,
                methodGroups = emptyList(),
                difficulty = emptyList(),
                discoveryScene = null,
            )
        }
    }

    fun applySearchIntent(intent: SearchIntent): DietaryGoal? {
        update {
            it.copy(
                selectedStuff = intent.query.stuff,
                avoid = intent.query.avoid,
                noSpicy = intent.query.noSpicy,
                tool = intent.query.tool,
                methodGroups = intent.query.methodGroups,
                difficulty = intent.query.difficulty,
                keyword = "",
                discoveryScene = null,
            )
        }
        return intent.goal
    }

    fun parseLocalIntent(snapshot: CookCatalogSnapshot, rawText: String): SearchIntent =
        LocalSearchIntentParser(snapshot.data, snapshot.availableDietaryGoals).parse(rawText)

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
        val goal = prefs.rememberedDietaryGoal
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
                    ),
                )
            },
        )
    }
}
