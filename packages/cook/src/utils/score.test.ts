import type { RecipeItem } from '../types/index.js'
import { describe, expect, it } from 'vitest'
import { recommendRecipes } from './recommend.js'
import { applyRecipeScores, DEFAULT_RECIPE_SCORE, videoScore } from './score.js'

const stat = { view: 100_000, like: 5_000, favorite: 4_000, coin: 1_000, duration: 180 }

describe('videoScore', () => {
  it('weighs engagement over raw views', () => {
    // 播放 10 万、互动率 10%：0.4 × 0.5 + 0.6 × 0.667
    expect(videoScore(stat)).toBe(60)
    // 播放多 10 倍但几乎没人点赞收藏，分数反而低
    expect(videoScore({ ...stat, view: 1_000_000, like: 2_000, favorite: 1_000, coin: 0 })).toBeLessThan(60)
  })

  it('stays within 0 to 100', () => {
    expect(videoScore({ view: 0, like: 0, favorite: 0, coin: 0, duration: 60 })).toBe(0)
    expect(videoScore({ view: 50_000_000, like: 50_000_000, favorite: 0, coin: 0, duration: 60 })).toBe(100)
  })

  it('takes points off videos longer than 15 minutes', () => {
    expect(videoScore({ ...stat, duration: 20 * 60 })).toBe(videoScore(stat) - 10)
  })
})

describe('applyRecipeScores', () => {
  const recipes: RecipeItem[] = [
    { id: 0, name: '有数据', stuff: ['土豆'], bv: 'BV1111111111', tools: [] },
    { id: 1, name: '失效', stuff: ['土豆'], bv: 'BV2222222222', tools: [] },
    { id: 2, name: '图文', stuff: ['土豆'], link: 'https://example.com/a.md', tools: [] },
  ]

  it('scores videos, zeroes dead ones and leaves the rest to the default', () => {
    const scored = applyRecipeScores(recipes, { updatedAt: '2026-10-21', videos: { BV1111111111: stat, BV2222222222: { dead: true } } })
    expect(scored.map(r => r.score)).toEqual([60, 0, undefined])
    expect(applyRecipeScores(recipes, undefined)).toBe(recipes)
  })

  it('only breaks ties inside the same group', () => {
    const scored: RecipeItem[] = [
      { id: 0, name: '缺一样但分高', stuff: ['土豆', '牛肉'], tools: [], score: 100 },
      { id: 1, name: '低分', stuff: ['土豆'], tools: [], score: 10 },
      { id: 2, name: '没分', stuff: ['土豆'], tools: [] },
      { id: 3, name: '高分', stuff: ['土豆'], tools: [], score: 90 },
    ]
    expect(recommendRecipes(scored, { stuff: ['土豆'] }).map(m => m.recipe.name)).toEqual(['高分', '没分', '低分', '缺一样但分高'])
    expect(DEFAULT_RECIPE_SCORE).toBe(50)
  })
})
