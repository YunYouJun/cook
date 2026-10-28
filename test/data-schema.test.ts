import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import Ajv from 'ajv'
import { describe, expect, it } from 'vitest'
import { parseIncompatibleFoodsCsv, parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { applyRecipeScores } from '../packages/cook/src/utils/score'
import { readVideoStats } from '../packages/cook/src/utils/video-stats'

const dataDir = resolve(process.cwd(), 'packages/data')
const readJson = (file: string) => JSON.parse(readFileSync(resolve(dataDir, file), 'utf8'))

function validate(schemaFile: string, data: unknown) {
  const ajv = new Ajv({ allErrors: true })
  const valid = ajv.validate(readJson(`schema/${schemaFile}`), data)
  return valid ? [] : ajv.errors
}

// Web 和 iOS 读同一份 JSON，结构变化必须同时改 schema、DATA_SCHEMA_VERSION 和 CookCore
describe('packages/data matches its JSON Schema', () => {
  it('recipes parsed from recipe.csv', () => {
    const recipes = applyRecipeScores(parseRecipeCsv(readFileSync(resolve(dataDir, 'recipe.csv'), 'utf8')), readVideoStats())
    expect(validate('recipe.schema.json', JSON.parse(JSON.stringify(recipes)))).toEqual([])
  })

  it('incompatible foods parsed from incompatible-foods.csv', () => {
    const rules = parseIncompatibleFoodsCsv(readFileSync(resolve(dataDir, 'incompatible-foods.csv'), 'utf8'))
    expect(validate('incompatible-foods.schema.json', rules)).toEqual([])
  })

  it.each([
    ['food.json', 'food.schema.json'],
    ['pantry.json', 'pantry.schema.json'],
    ['aliases.json', 'aliases.schema.json'],
    ['query.json', 'query.schema.json'],
    ['recipe-text.json', 'recipe-text.schema.json'],
    ['recipe-amounts.json', 'recipe-amounts.schema.json'],
    ['recipe-dietary.json', 'recipe-dietary.schema.json'],
    ['shelf-life.json', 'shelf-life.schema.json'],
  ])('%s', (file, schema) => {
    expect(validate(schema, readJson(file))).toEqual([])
  })

  it.runIf(existsSync(resolve(dataDir, 'video-stats.json')))('video-stats.json', () => {
    expect(validate('video-stats.schema.json', readJson('video-stats.json'))).toEqual([])
  })

  // 冰箱按它给默认到期日，词典里的食材都要有
  it('shelf-life.json covers every food in food.json', () => {
    const food = readJson('food.json')
    const shelfLife = readJson('shelf-life.json')
    const names = [...food.vegetable, ...food.meat, ...food.staple].map((item: { name: string }) => item.name)
    expect(names.filter(name => !(name in shelfLife.items))).toEqual([])
  })

  // pnpm convert 生成，CI 里先 convert 再 test
  it.runIf(existsSync(resolve(dataDir, 'recipe-meta.json')))('generated recipe-meta.json', () => {
    expect(validate('recipe-meta.schema.json', readJson('recipe-meta.json'))).toEqual([])
  })
})
