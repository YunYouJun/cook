import type { VideoStatsFile } from '../../../packages/cook/src/utils/score.ts'
import fs from 'node:fs'
import { defineLoader } from 'vitepress'
import { parseRecipeCsv } from '../../../packages/cook/src/utils/csv.ts'
import { applyRecipeScores } from '../../../packages/cook/src/utils/score.ts'

/** 首页「先试一下」只用得到这几列，压短字段名，减小首页体积 */
export interface LandingRecipe {
  /** Stable recipe identifier from the tracked CSV. */
  id?: number
  /** 菜名 */
  n: string
  /** 食材 */
  s: string[]
  /** 难度 */
  d?: string
  /** 做法 */
  m?: string[]
  /** 厨具 */
  t?: string[]
  /** 视频质量分 */
  c?: number
}

export interface LandingData {
  total: number
  recipes: LandingRecipe[]
}

declare const data: LandingData
export { data }

/**
 * 直接读提交进仓库的 recipe.csv（recipe.json 是生成物，文档构建时不一定有），
 * 并按 video-stats.json 写上质量分，排序和 App 一致。
 */
export default defineLoader({
  watch: ['../../../packages/data/recipe.csv', '../../../packages/data/video-stats.json'],
  load(files): LandingData {
    const csvFile = files.find(file => file.endsWith('recipe.csv'))
    const statsFile = files.find(file => file.endsWith('video-stats.json'))
    if (!csvFile)
      throw new Error('packages/data/recipe.csv not found')

    const stats = statsFile ? JSON.parse(fs.readFileSync(statsFile, 'utf-8')) as VideoStatsFile : undefined
    const recipes = applyRecipeScores(parseRecipeCsv(fs.readFileSync(csvFile, 'utf-8')), stats)

    return {
      total: recipes.length,
      recipes: recipes.map(recipe => ({
        id: recipe.id,
        n: recipe.name,
        s: recipe.stuff,
        ...(recipe.difficulty ? { d: recipe.difficulty } : {}),
        ...(recipe.methods?.length ? { m: recipe.methods } : {}),
        ...(recipe.tools.length ? { t: recipe.tools } : {}),
        ...(recipe.score === undefined ? {} : { c: recipe.score }),
      })),
    }
  },
})
