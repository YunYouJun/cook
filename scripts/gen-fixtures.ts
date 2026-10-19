/**
 * 生成 packages/data/fixtures/recommend.json：Web 和 iOS 共用的推荐用例
 *
 * 菜谱数据、别名或推荐逻辑变化后运行 pnpm fixtures，并在 PR 里看结果的变化是否符合预期。
 */
import fs from 'node:fs'
import { recipeCsvFile } from '../packages/cook/src/utils/config'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { buildFixtures } from './lib/fixtures'

export const fixturesFile = new URL('../packages/data/fixtures/recommend.json', import.meta.url)

const recipes = parseRecipeCsv(fs.readFileSync(recipeCsvFile, 'utf-8'))
// 每条结果占一行，文件小一些，diff 也好读
const json = JSON.stringify(buildFixtures(recipes), (_key, value) =>
  value && typeof value === 'object' && 'id' in value && 'missing' in value ? JSON.stringify(value) : value, 2)
fs.writeFileSync(fixturesFile, `${json.replace(/"(\{\\"id.*?\})"/g, (_, line: string) => JSON.parse(`"${line}"`))}\n`)
console.log(`Wrote ${fixturesFile.pathname}`)
