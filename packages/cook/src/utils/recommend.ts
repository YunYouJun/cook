import type { RecipeItem } from '../types/recipe'
import { normalizeStuff } from './alias'

export type RecommendationMode = 'loose' | 'strict' | 'survival'

export interface RecipeMatch {
  recipe: RecipeItem
  /** Stable position in the source data, also used to break sorting ties. */
  sourceIndex: number
  requiredStuff: string[]
  matchedStuff: string[]
  pantryStuff: string[]
  missingStuff: string[]
}

export interface RecommendationOptions {
  stuff: string[]
  pantry?: string[]
  tool?: string
  keyword?: string
  mode?: RecommendationMode
}

/** Reuse the catalog aliases; split recorded separators, trim and deduplicate. */
export function uniqueStuff(stuff: string[]): string[] {
  return [...new Set(normalizeStuff(stuff.flatMap(item => item.split(/[,，、;；\n]/))).filter(Boolean))]
}

/** Only explicitly recorded ingredients are requirements. Names/tags are never parsed. */
export function recommendRecipes(recipes: RecipeItem[], options: RecommendationOptions): RecipeMatch[] {
  const selected = new Set(uniqueStuff(options.stuff))
  const pantry = new Set(uniqueStuff(options.pantry ?? []))
  const keyword = options.keyword?.trim() ?? ''
  if (!selected.size)
    return []

  return recipes.flatMap((recipe, sourceIndex): RecipeMatch[] => {
    if (options.tool && !recipe.tools?.includes(options.tool))
      return []
    if (keyword && !recipe.name.includes(keyword))
      return []

    const requiredStuff = uniqueStuff(recipe.stuff)
    const matchedStuff = requiredStuff.filter(item => selected.has(item))
    const pantryStuff = requiredStuff.filter(item => !selected.has(item) && pantry.has(item))
    const missingStuff = requiredStuff.filter(item => !selected.has(item) && !pantry.has(item))

    // Pantry alone must not create an unrelated recommendation; empty records are unknown.
    if (!matchedStuff.length || missingStuff.length > 2)
      return []
    if (options.mode === 'strict' && ![...selected].every(item => requiredStuff.includes(item)))
      return []
    if (options.mode === 'survival' && missingStuff.length)
      return []

    return [{ recipe, sourceIndex, requiredStuff, matchedStuff, pantryStuff, missingStuff }]
  }).sort((a, b) =>
    a.missingStuff.length - b.missingStuff.length
    || b.matchedStuff.length - a.matchedStuff.length
    || a.sourceIndex - b.sourceIndex,
  )
}
