import type { RecipeItem } from '~/types'

interface ClassicSearchOptions {
  stuff: string[]
  tool: string
  keyword: string
  mode: 'loose' | 'strict' | 'survival'
}

/** Preserve the original search semantics and source order in the classic view. */
export function filterClassicRecipes(recipes: RecipeItem[], options: ClassicSearchOptions): RecipeItem[] {
  const { stuff, tool, keyword, mode } = options
  if (!stuff.length && !tool)
    return []

  return recipes.filter((recipe) => {
    if (tool && !recipe.tools?.includes(tool))
      return false
    if (keyword && !recipe.name.includes(keyword))
      return false
    if (mode === 'strict')
      return stuff.every(item => recipe.stuff.includes(item))
    if (mode === 'survival')
      return recipe.stuff.every(item => stuff.includes(item))
    return !stuff.length || stuff.some(item => recipe.stuff.includes(item))
  })
}
