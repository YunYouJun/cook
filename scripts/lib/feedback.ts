import type { RecipeItem } from '../../packages/cook/src/types/index'
import { recommendRecipes } from '../../packages/cook/src/utils/recommend'

/** Rows as returned by the cook-feedback export. */
export interface FeedbackRow {
  recipeId: number
  recipeName: string
  kind: string
  note: string
  reporter: string
  signedIn: boolean
  day: string
}

/** General feedback about the app, from the web or iOS. */
export interface AppFeedbackRow {
  category: string
  note: string
  contact: string
  platform: string
  version: string
  signedIn: boolean
  day: string
}

export interface SignalRow {
  type: 'empty' | 'open'
  stuff: string[]
  tool: string
  mode: string
  recipeId: number
  count: number
  rankSum: number
}

/** A selection users made and the recipe they opened, for evaluating ranking. */
export interface EvalCase {
  stuff: string[]
  tool?: string
  mode?: string
  /** Recipe ids users opened for this selection, with how often. */
  opened?: { id: number, count: number }[]
  /** How many times this selection was left with no results. */
  empty?: number
}

const SIGNED_IN_WEIGHT = 2

export function summarizeReports(rows: FeedbackRow[]) {
  const byRecipe = new Map<number, { recipeId: number, recipeName: string, score: number, kinds: Record<string, number>, notes: string[], reporters: Set<string> }>()
  for (const row of rows) {
    const item = byRecipe.get(row.recipeId) ?? { recipeId: row.recipeId, recipeName: row.recipeName, score: 0, kinds: {}, notes: [], reporters: new Set<string>() }
    if (!item.reporters.has(`${row.reporter}|${row.kind}`)) {
      item.reporters.add(`${row.reporter}|${row.kind}`)
      item.score += row.signedIn ? SIGNED_IN_WEIGHT : 1
      item.kinds[row.kind] = (item.kinds[row.kind] ?? 0) + 1
    }
    if (row.note)
      item.notes.push(row.note)
    byRecipe.set(row.recipeId, item)
  }
  return [...byRecipe.values()]
    .map(({ reporters, ...rest }) => ({ ...rest, reporters: new Set([...reporters].map(r => r.split('|')[0])).size }))
    .sort((a, b) => b.score - a.score || a.recipeId - b.recipeId)
}

function selectionKey(row: { stuff: string[], tool?: string, mode?: string }) {
  return [[...row.stuff].sort().join('、'), row.tool ?? '', row.mode ?? 'loose'].join('|')
}

/** Merge signals into one eval case per selection. */
export function buildEvalCases(signals: SignalRow[]): EvalCase[] {
  const cases = new Map<string, EvalCase>()
  for (const s of signals) {
    const key = selectionKey(s)
    const item = cases.get(key) ?? { stuff: [...s.stuff].sort(), ...(s.tool ? { tool: s.tool } : {}), ...(s.mode && s.mode !== 'loose' ? { mode: s.mode } : {}) }
    if (s.type === 'empty')
      item.empty = (item.empty ?? 0) + s.count
    else
      item.opened = [...(item.opened ?? []), { id: s.recipeId, count: s.count }].sort((a, b) => b.count - a.count || a.id - b.id)
    cases.set(key, item)
  }
  return [...cases.values()].sort((a, b) => selectionKey(a).localeCompare(selectionKey(b)))
}

export interface EvalResult {
  cases: number
  /** Opened recipes: share found in the top 10, and mean reciprocal rank (weighted by count). */
  opened: { total: number, hitAt10: number, mrr: number, missing: number }
  /** Selections that had no results: how many still have none. */
  empty: { total: number, stillEmpty: number }
  worst: { stuff: string[], id: number, rank: number | null, count: number }[]
}

/** Run the current recommendation logic over the eval cases. */
export function evaluate(recipes: RecipeItem[], cases: EvalCase[], pantry: string[] = []): EvalResult {
  let total = 0
  let hits = 0
  let rr = 0
  let missing = 0
  let emptyTotal = 0
  let stillEmpty = 0
  const worst: EvalResult['worst'] = []

  for (const c of cases) {
    const matches = recommendRecipes(recipes, { stuff: c.stuff, pantry, tool: c.tool, mode: c.mode as never })
    if (c.empty) {
      emptyTotal += c.empty
      if (!matches.length)
        stillEmpty += c.empty
    }
    for (const { id, count } of c.opened ?? []) {
      const index = matches.findIndex(m => m.recipe.id === id)
      total += count
      if (index < 0) {
        missing += count
        worst.push({ stuff: c.stuff, id, rank: null, count })
        continue
      }
      if (index < 10)
        hits += count
      else
        worst.push({ stuff: c.stuff, id, rank: index + 1, count })
      rr += count / (index + 1)
    }
  }

  const ratio = (n: number) => total ? Math.round(n / total * 1000) / 1000 : 0
  return {
    cases: cases.length,
    opened: { total, hitAt10: ratio(hits), mrr: ratio(rr), missing },
    empty: { total: emptyTotal, stillEmpty },
    worst: worst.sort((a, b) => b.count - a.count).slice(0, 20),
  }
}
