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
  /** Missing on rows from before platforms were recorded: those are web. */
  platform?: string
  version?: string
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
  /** Missing on rows from before platforms were recorded: those are web. */
  platform?: string
  lastVersion?: string
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

export function platformOf(row: { platform?: string }): string {
  return row.platform || 'web'
}

/** Add up a count per platform, e.g. `{ web: 3, ios: 1 }`. */
export function countByPlatform<T extends { platform?: string }>(rows: T[], count: (row: T) => number = () => 1): Record<string, number> {
  const counts: Record<string, number> = {}
  for (const row of rows)
    counts[platformOf(row)] = (counts[platformOf(row)] ?? 0) + count(row)
  return counts
}

export function formatPlatforms(counts: Record<string, number>): string {
  return Object.entries(counts).sort(([a], [b]) => a.localeCompare(b)).map(([p, n]) => `${p} ${n}`).join(' / ') || '-'
}

export function summarizeReports(rows: FeedbackRow[]) {
  const byRecipe = new Map<number, { recipeId: number, recipeName: string, score: number, kinds: Record<string, number>, platforms: Record<string, number>, notes: string[], reporters: Set<string> }>()
  for (const row of rows) {
    const item = byRecipe.get(row.recipeId) ?? { recipeId: row.recipeId, recipeName: row.recipeName, score: 0, kinds: {}, platforms: {}, notes: [], reporters: new Set<string>() }
    if (!item.reporters.has(`${row.reporter}|${row.kind}`)) {
      item.reporters.add(`${row.reporter}|${row.kind}`)
      item.score += row.signedIn ? SIGNED_IN_WEIGHT : 1
      item.kinds[row.kind] = (item.kinds[row.kind] ?? 0) + 1
      item.platforms[platformOf(row)] = (item.platforms[platformOf(row)] ?? 0) + 1
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

export interface PlatformSignals {
  platform: string
  /** Selections left with no results, counted each time. */
  empty: number
  opened: number
  /** Mean position of opened results, 1 = first. */
  meanRank: number | null
}

/** Search signals per platform: the web and iOS screens differ, so compare them side by side. */
export function summarizeSignals(signals: SignalRow[]): PlatformSignals[] {
  const byPlatform = new Map<string, { empty: number, opened: number, rankSum: number }>()
  for (const s of signals) {
    const item = byPlatform.get(platformOf(s)) ?? { empty: 0, opened: 0, rankSum: 0 }
    if (s.type === 'empty') {
      item.empty += s.count
    }
    else {
      item.opened += s.count
      item.rankSum += s.rankSum
    }
    byPlatform.set(platformOf(s), item)
  }
  return [...byPlatform].sort(([a], [b]) => a.localeCompare(b)).map(([platform, { empty, opened, rankSum }]) => ({
    platform,
    empty,
    opened,
    meanRank: opened ? Math.round((rankSum / opened + 1) * 10) / 10 : null,
  }))
}

/** Merge signals into one eval case per selection, adding up web and iOS. */
export function buildEvalCases(signals: SignalRow[]): EvalCase[] {
  const cases = new Map<string, EvalCase>()
  for (const s of signals) {
    const key = selectionKey(s)
    const item = cases.get(key) ?? { stuff: [...s.stuff].sort(), ...(s.tool ? { tool: s.tool } : {}), ...(s.mode && s.mode !== 'loose' ? { mode: s.mode } : {}) }
    if (s.type === 'empty')
      item.empty = (item.empty ?? 0) + s.count
    else
      item.opened = addOpened(item.opened ?? [], s.recipeId, s.count)
    cases.set(key, item)
  }
  return [...cases.values()].sort((a, b) => selectionKey(a).localeCompare(selectionKey(b)))
}

function addOpened(opened: { id: number, count: number }[], id: number, count: number) {
  const same = opened.find(o => o.id === id)
  const next = same ? opened.map(o => o === same ? { id, count: o.count + count } : o) : [...opened, { id, count }]
  return next.sort((a, b) => b.count - a.count || a.id - b.id)
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
