/**
 * 生成 packages/data/fixtures/*.json：Web 和 iOS 共用的推荐、搜索、相克用例
 *
 * 菜谱数据、别名或推荐逻辑变化后运行 pnpm fixtures，并在 PR 里看结果的变化是否符合预期。
 */
import fs from 'node:fs'
import { incompatibleFoodsCsvFile, recipeCsvFile } from '../packages/cook/src/utils/config'
import { parseIncompatibleFoodsCsv, parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { applyRecipeScores } from '../packages/cook/src/utils/score'
import { readVideoStats } from '../packages/cook/src/utils/video-stats'
import { buildFixtures } from './lib/fixtures'

export const fixturesDir = new URL('../packages/data/fixtures/', import.meta.url)

// 和 pnpm convert 一样带上质量分，用例里的排序才和 App 里一致
const recipes = applyRecipeScores(parseRecipeCsv(fs.readFileSync(recipeCsvFile, 'utf-8')), readVideoStats())
const rules = parseIncompatibleFoodsCsv(fs.readFileSync(incompatibleFoodsCsvFile, 'utf-8'))
for (const [name, content] of Object.entries(buildFixtures(recipes, rules))) {
  // 每条结果占一行，文件小一些，diff 也好读
  const json = JSON.stringify(content, (_key, value) =>
    value && typeof value === 'object' && 'id' in value && 'matched' in value ? JSON.stringify(value) : value, 2)
  const file = new URL(name, fixturesDir)
  fs.writeFileSync(file, `${json.replace(/"(\{\\"id.*?\})"/g, (_, line: string) => JSON.parse(`"${line}"`))}\n`)
  console.log(`Wrote ${file.pathname}`)
}
