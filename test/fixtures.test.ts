import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { expect, it } from 'vitest'
import { parseIncompatibleFoodsCsv, parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { buildFixtures } from '../scripts/lib/fixtures'

const dataDir = resolve(process.cwd(), 'packages/data')
const recipes = parseRecipeCsv(readFileSync(resolve(dataDir, 'recipe.csv'), 'utf8'))
const rules = parseIncompatibleFoodsCsv(readFileSync(resolve(dataDir, 'incompatible-foods.csv'), 'utf8'))

it.each(Object.entries(buildFixtures(recipes, rules)))('shared fixture %s matches the current data and logic (run `pnpm fixtures` to update)', (name, content) => {
  const committed = JSON.parse(readFileSync(resolve(dataDir, 'fixtures', name), 'utf8'))
  expect(committed).toEqual(JSON.parse(JSON.stringify(content)))
})
