package cn.yunyoujun.cook.core.search

import cn.yunyoujun.cook.core.model.IncompatibleRule
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.recommend.StuffNormalizer
import kotlinx.serialization.Serializable

@Serializable
data class SearchOptions(
    val stuff: List<String>? = null,
    val tool: String? = null,
    val difficulty: String? = null,
    val tag: String? = null,
    val method: String? = null,
    val limit: Int? = null,
)

data class SearchResultItem(
    val recipe: Recipe,
    val matchedStuff: List<String>,
    val missingStuff: List<String>,
)

data class SearchResult(
    val recipes: List<SearchResultItem>,
    val warnings: List<IncompatibleRule>,
)

fun searchRecipes(
    recipes: List<Recipe>,
    incompatible: List<IncompatibleRule>,
    options: SearchOptions,
    normalizer: StuffNormalizer,
): SearchResult {
    val stuffLower = normalizer.normalize(options.stuff.orEmpty()).map { it.lowercase() }
    val tool = options.tool.orEmpty()
    val difficulty = options.difficulty.orEmpty()
    val tag = options.tag.orEmpty()
    val method = options.method.orEmpty()
    val limit = options.limit ?: 10

    fun isSelected(item: String): Boolean = item.lowercase() in stuffLower

    var matched = recipes.withIndex().filter { (_, recipe) ->
        if (stuffLower.isNotEmpty() && recipe.stuff.none(::isSelected)) return@filter false
        if (tool.isNotEmpty() && recipe.tools.none { it.contains(tool) }) return@filter false
        if (difficulty.isNotEmpty() && recipe.difficulty != difficulty) return@filter false
        if (tag.isNotEmpty() && recipe.tags.none { it.contains(tag) }) return@filter false
        if (method.isNotEmpty() && recipe.methods.none { it.contains(method) }) return@filter false
        true
    }

    if (stuffLower.isNotEmpty()) {
        matched = matched.sortedWith { a, b ->
            val aCount = a.value.stuff.count(::isSelected)
            val bCount = b.value.stuff.count(::isSelected)
            if (aCount != bCount) return@sortedWith bCount.compareTo(aCount)
            if (a.value.stuff.size != b.value.stuff.size) {
                return@sortedWith a.value.stuff.size.compareTo(b.value.stuff.size)
            }
            a.index.compareTo(b.index)
        }
    }

    val count = if (limit >= 0) minOf(limit, matched.size) else maxOf(0, matched.size + limit)
    val items = matched.take(count).map { (_, recipe) ->
        SearchResultItem(
            recipe = recipe,
            matchedStuff = recipe.stuff.filter(::isSelected),
            missingStuff = recipe.stuff.filter { !isSelected(it) },
        )
    }

    val warnings = if (stuffLower.size >= 2) {
        incompatible.filter { isSelected(it.foodA) && isSelected(it.foodB) }
    } else {
        emptyList()
    }

    return SearchResult(recipes = items, warnings = warnings)
}
