package cn.yunyoujun.cook.core.recommend

import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.RecommendationMode
import kotlinx.serialization.Serializable

@Serializable
data class RecommendationOptions(
    val stuff: List<String> = emptyList(),
    val pantry: List<String>? = null,
    val tool: String? = null,
    val keyword: String? = null,
    val mode: RecommendationMode? = null,
    val avoid: List<String>? = null,
    val methodGroups: List<String>? = null,
    val noSpicy: Boolean? = null,
    val difficulty: List<String>? = null,
)

data class RecipeMatch(
    val recipe: Recipe,
    val sourceIndex: Int,
    val requiredStuff: List<String>,
    val matchedStuff: List<String>,
    val pantryStuff: List<String>,
    val missingStuff: List<String>,
    val optionalStuff: List<String>,
)

data class UnlockSuggestion(
    val name: String,
    val count: Int,
)

data class MethodGroup(
    val label: String,
    val methods: List<String>,
    val tags: List<String>,
)

class Recommender(
    recipes: List<Recipe>,
    val normalizer: StuffNormalizer,
) {
    private data class Entry(
        val recipe: Recipe,
        val sourceIndex: Int,
        val requiredStuff: List<String>,
    )

    private val catalog: List<Entry> = recipes.mapIndexed { index, recipe ->
        Entry(
            recipe = recipe,
            sourceIndex = index,
            requiredStuff = normalizer.unique(recipe.stuff),
        )
    }

    fun hasAvoidedStuff(recipe: Recipe, avoid: List<String>): Boolean {
        if (avoid.isEmpty()) return false
        val requiredStuff = normalizer.unique(recipe.stuff)
        if (isCatchAll(requiredStuff)) return false
        val avoided = normalizer.unique(avoid).toSet()
        return requiredStuff.any { it in avoided }
    }

    fun recipes(
        usingTool: String,
        options: RecommendationOptions = RecommendationOptions(),
    ): List<Recipe> {
        val keyword = options.keyword.orEmpty().trim()
        val avoided = normalizer.unique(options.avoid.orEmpty()).toSet()
        val noSpicy = options.noSpicy ?: false
        return catalog.mapNotNull { entry ->
            val recipe = entry.recipe
            val allowedByAvoid = isCatchAll(entry.requiredStuff) || entry.requiredStuff.none { it in avoided }
            if (usingTool in recipe.tools &&
                (keyword.isEmpty() || recipe.name.contains(keyword)) &&
                allowedByAvoid &&
                matchesTaste(recipe, options.methodGroups, noSpicy, options.difficulty)
            ) {
                recipe
            } else {
                null
            }
        }
    }

    fun candidates(options: RecommendationOptions = RecommendationOptions()): List<Recipe> {
        val tool = options.tool
        if (!tool.isNullOrEmpty()) {
            return recipes(usingTool = tool, options = options)
        }
        val avoided = normalizer.unique(options.avoid.orEmpty()).toSet()
        val noSpicy = options.noSpicy ?: false
        return catalog.mapNotNull { entry ->
            val recipe = entry.recipe
            val allowedByAvoid = isCatchAll(entry.requiredStuff) || entry.requiredStuff.none { it in avoided }
            if (allowedByAvoid && matchesTaste(recipe, options.methodGroups, noSpicy, options.difficulty)) {
                recipe
            } else {
                null
            }
        }
    }

    fun recommend(options: RecommendationOptions): List<RecipeMatch> {
        val selected = normalizer.unique(options.stuff).toSet()
        if (selected.isEmpty()) return emptyList()

        val pantry = normalizer.unique(options.pantry.orEmpty()).toSet()
        val keyword = options.keyword.orEmpty().trim()
        val avoided = normalizer.unique(options.avoid.orEmpty()).toSet()
        val tool = options.tool.orEmpty()
        val noSpicy = options.noSpicy ?: false

        val matches = ArrayList<RecipeMatch>()
        for (entry in catalog) {
            val recipe = entry.recipe
            val requiredStuff = entry.requiredStuff
            if (tool.isNotEmpty() && tool !in recipe.tools) continue
            if (keyword.isNotEmpty() && !recipe.name.contains(keyword)) continue
            val catchAll = isCatchAll(requiredStuff)
            if (!catchAll && requiredStuff.any { it in avoided }) continue
            if (!matchesTaste(recipe, options.methodGroups, noSpicy, options.difficulty)) continue

            val matchedStuff = requiredStuff.filter { it in selected }
            val pantryStuff = requiredStuff.filter { it !in selected && it in pantry }
            val unownedStuff = requiredStuff.filter { it !in selected && it !in pantry }
            val missingStuff = if (catchAll) emptyList() else unownedStuff
            val optionalStuff = if (catchAll) unownedStuff.filter { it !in avoided } else emptyList()

            if (matchedStuff.isEmpty() || missingStuff.size > 2) continue
            if (catchAll && matchedStuff.size < CATCH_ALL_MIN_MATCH) continue
            if (options.mode == RecommendationMode.STRICT && !selected.all { it in requiredStuff }) continue
            if (options.mode == RecommendationMode.SURVIVAL && missingStuff.isNotEmpty()) continue

            matches.add(
                RecipeMatch(
                    recipe = recipe,
                    sourceIndex = entry.sourceIndex,
                    requiredStuff = requiredStuff,
                    matchedStuff = matchedStuff,
                    pantryStuff = pantryStuff,
                    missingStuff = missingStuff,
                    optionalStuff = optionalStuff,
                ),
            )
        }

        return matches.sortedWith { a, b ->
            if (a.missingStuff.size != b.missingStuff.size) {
                return@sortedWith a.missingStuff.size.compareTo(b.missingStuff.size)
            }
            val aCatchAll = isCatchAll(a.requiredStuff)
            val bCatchAll = isCatchAll(b.requiredStuff)
            if (aCatchAll != bCatchAll) {
                return@sortedWith if (!aCatchAll) -1 else 1
            }
            if (a.matchedStuff.size != b.matchedStuff.size) {
                return@sortedWith b.matchedStuff.size.compareTo(a.matchedStuff.size)
            }
            val aScore = a.recipe.score ?: DEFAULT_SCORE
            val bScore = b.recipe.score ?: DEFAULT_SCORE
            if (aScore != bScore) {
                return@sortedWith bScore.compareTo(aScore)
            }
            a.sourceIndex.compareTo(b.sourceIndex)
        }
    }

    companion object {
        const val DEFAULT_SCORE = 50
        const val CATCH_ALL_MIN_STUFF = 10
        const val CATCH_ALL_MIN_MATCH = 2
        const val SPICY_TAG = "辣"

        val DIFFICULTY_LEVELS = listOf("简单", "普通", "困难")

        val METHOD_GROUPS = listOf(
            MethodGroup(label = "炒菜", methods = listOf("炒"), tags = emptyList()),
            MethodGroup(label = "汤羹", methods = emptyList(), tags = listOf("汤")),
            MethodGroup(label = "炖煮", methods = listOf("炖", "煮", "煲", "卤", "焖", "烧", "灼"), tags = emptyList()),
            MethodGroup(label = "蒸", methods = listOf("蒸"), tags = emptyList()),
            MethodGroup(label = "炸煎", methods = listOf("炸", "煎", "烙"), tags = emptyList()),
            MethodGroup(label = "烤", methods = listOf("烤", "烘", "焗"), tags = emptyList()),
            MethodGroup(label = "凉拌", methods = listOf("拌", "凉拌", "腌", "腌制", "泡菜", "油泼"), tags = emptyList()),
            MethodGroup(label = "微波", methods = listOf("微波加热"), tags = emptyList()),
        )

        fun isCatchAll(requiredStuff: List<String>): Boolean =
            requiredStuff.size >= CATCH_ALL_MIN_STUFF

        fun matchesTaste(
            recipe: Recipe,
            selectedGroups: List<String>?,
            noSpicy: Boolean,
            difficulty: List<String>?,
        ): Boolean {
            if (noSpicy && SPICY_TAG in recipe.tags) return false
            if (!difficulty.isNullOrEmpty()) {
                val recipeDiff = recipe.difficulty
                if (recipeDiff.isNullOrEmpty() || recipeDiff !in difficulty) return false
            }
            val groups = if (selectedGroups.isNullOrEmpty()) {
                emptyList()
            } else {
                METHOD_GROUPS.filter { it.label in selectedGroups }
            }
            if (groups.isEmpty()) return true
            val methods = recipe.methods.flatMap { method ->
                method.split('、', '/').filter { it.isNotEmpty() }
            }
            return groups.any { group ->
                group.methods.any { it in methods } || group.tags.any { it in recipe.tags }
            }
        }

        fun unlockSuggestions(matches: List<RecipeMatch>, limit: Int = 4): List<UnlockSuggestion> {
            val counts = LinkedHashMap<String, Int>()
            for (match in matches) {
                if (match.missingStuff.size == 1) {
                    val name = match.missingStuff[0]
                    counts[name] = (counts[name] ?: 0) + 1
                }
            }
            return counts.entries
                .asSequence()
                .mapIndexed { index, entry -> index to UnlockSuggestion(name = entry.key, count = entry.value) }
                .filter { it.second.count >= 2 }
                .sortedWith { a, b ->
                    if (a.second.count != b.second.count) {
                        b.second.count.compareTo(a.second.count)
                    } else {
                        a.first.compareTo(b.first)
                    }
                }
                .take(limit)
                .map { it.second }
                .toList()
        }
    }
}
