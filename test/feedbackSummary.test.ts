import type { RecipeItem } from '../packages/cook/src/types/index'
import type { FeedbackRow, SignalRow } from '../scripts/lib/feedback'
import { describe, expect, it } from 'vitest'
import { buildEvalCases, evaluate, summarizeReports } from '../scripts/lib/feedback'

const row = (partial: Partial<FeedbackRow>): FeedbackRow => ({ recipeId: 1, recipeName: '番茄炒蛋', kind: 'video', note: '', reporter: 'a', signedIn: false, day: '2026-10-18', ...partial })

describe('summarizeReports', () => {
  it('scores distinct reporters per kind and weights signed-in users', () => {
    const summary = summarizeReports([
      row({}),
      row({ day: '2026-10-19' }),
      row({ reporter: 'b', signedIn: true, note: '打不开' }),
      row({ recipeId: 2, recipeName: '炒饭', kind: 'stuff' }),
    ])
    expect(summary[0]).toMatchObject({ recipeId: 1, score: 3, reporters: 2, kinds: { video: 2 }, notes: ['打不开'] })
    expect(summary[1]).toMatchObject({ recipeId: 2, score: 1 })
  })
})

const recipes: RecipeItem[] = [
  { id: 10, name: '番茄炒蛋', stuff: ['番茄', '鸡蛋'], tools: [] },
  { id: 11, name: '番茄汤', stuff: ['番茄'], tools: [] },
  { id: 12, name: '蛋炒饭', stuff: ['鸡蛋', '米'], tools: [] },
]

const signals: SignalRow[] = [
  { type: 'open', stuff: ['鸡蛋', '番茄'], tool: '', mode: 'loose', recipeId: 10, count: 3, rankSum: 3 },
  { type: 'open', stuff: ['番茄', '鸡蛋'], tool: '', mode: 'loose', recipeId: 12, count: 1, rankSum: 2 },
  { type: 'empty', stuff: ['羊肉'], tool: '', mode: 'loose', recipeId: -1, count: 5, rankSum: 0 },
]

describe('buildEvalCases', () => {
  it('merges signals per selection', () => {
    expect(buildEvalCases(signals)).toEqual([
      { stuff: ['番茄', '鸡蛋'], opened: [{ id: 10, count: 3 }, { id: 12, count: 1 }] },
      { stuff: ['羊肉'], empty: 5 },
    ])
  })
})

describe('evaluate', () => {
  it('reports hit rate, MRR and selections that are still empty', () => {
    const result = evaluate(recipes, buildEvalCases(signals))
    expect(result.opened).toEqual({ total: 4, hitAt10: 1, mrr: 0.833, missing: 0 })
    expect(result.empty).toEqual({ total: 5, stillEmpty: 5 })
  })

  it('counts opened recipes that are no longer found', () => {
    const result = evaluate(recipes.slice(0, 2), buildEvalCases(signals))
    expect(result.opened.missing).toBe(1)
    expect(result.worst[0]).toMatchObject({ id: 12, rank: null })
  })
})
