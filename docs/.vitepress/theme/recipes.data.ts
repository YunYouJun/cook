import type { VideoStatsFile } from '../../../packages/cook/src/utils/score.ts'
import type { LandingData } from './landing-data'
import fs from 'node:fs'
import { defineLoader } from 'vitepress'
import { parseRecipeCsv } from '../../../packages/cook/src/utils/csv.ts'
import { applyRecipeScores } from '../../../packages/cook/src/utils/score.ts'
import { compactRecipe } from './landing-data'

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
      recipes: recipes.map(compactRecipe),
    }
  },
})
