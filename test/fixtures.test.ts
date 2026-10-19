import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { expect, it } from 'vitest'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { buildFixtures } from '../scripts/lib/fixtures'

it('shared recommendation fixtures match the current data and logic (run `pnpm fixtures` to update)', () => {
  const recipes = parseRecipeCsv(readFileSync(resolve(process.cwd(), 'packages/data/recipe.csv'), 'utf8'))
  const committed = JSON.parse(readFileSync(resolve(process.cwd(), 'packages/data/fixtures/recommend.json'), 'utf8'))
  expect(committed).toEqual(JSON.parse(JSON.stringify(buildFixtures(recipes))))
})
