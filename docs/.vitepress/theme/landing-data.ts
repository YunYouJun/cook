import type { RecipeItem } from '../../../packages/cook/src/types/recipe'

/** Keep every field used by shared recommendation rules, including taste tags. */
export interface LandingRecipe {
  i?: number
  n: string
  s: string[]
  d?: RecipeItem['difficulty']
  m?: string[]
  t: string[]
  g?: string[]
  c?: number
  b?: string
  l?: string
}

export interface LandingData {
  total: number
  recipes: LandingRecipe[]
}

export function compactRecipe(recipe: RecipeItem): LandingRecipe {
  return {
    i: recipe.id,
    n: recipe.name,
    s: recipe.stuff,
    d: recipe.difficulty,
    m: recipe.methods,
    t: recipe.tools,
    g: recipe.tags,
    c: recipe.score,
    b: recipe.bv,
    l: recipe.link,
  }
}

export function expandRecipe(recipe: LandingRecipe): RecipeItem {
  return {
    id: recipe.i,
    name: recipe.n,
    stuff: recipe.s,
    difficulty: recipe.d,
    methods: recipe.m,
    tools: recipe.t,
    tags: recipe.g,
    score: recipe.c,
    bv: recipe.b,
    link: recipe.l,
  }
}
