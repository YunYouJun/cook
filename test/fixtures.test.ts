import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { expect, it } from 'vitest'
import { parseIncompatibleFoodsCsv, parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { applyRecipeScores } from '../packages/cook/src/utils/score'
import { readVideoStats } from '../packages/cook/src/utils/video-stats'
import { buildFixtures } from '../scripts/lib/fixtures'

const dataDir = resolve(process.cwd(), 'packages/data')
// 和 pnpm fixtures 一样带上质量分
const recipes = applyRecipeScores(parseRecipeCsv(readFileSync(resolve(dataDir, 'recipe.csv'), 'utf8')), readVideoStats())
const rules = parseIncompatibleFoodsCsv(readFileSync(resolve(dataDir, 'incompatible-foods.csv'), 'utf8'))

it.each(Object.entries(buildFixtures(recipes, rules)))('shared fixture %s matches the current data and logic (run `pnpm fixtures` to update)', (name, content) => {
  const committed = JSON.parse(readFileSync(resolve(dataDir, 'fixtures', name), 'utf8'))
  expect(committed).toEqual(JSON.parse(JSON.stringify(content)))
})
