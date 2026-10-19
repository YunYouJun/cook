import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { describe, expect, it } from 'vitest'
import { STUFF_ALIASES } from '../packages/cook/src/utils/alias'
import { isEmptyCookQuery, parseCookQuery } from '../packages/cook/src/utils/query'
import { DIFFICULTY_LEVELS, METHOD_GROUPS } from '../packages/cook/src/utils/recommend'
import food from '../packages/data/food.json'
import rules from '../packages/data/query.json'

const fixtures = JSON.parse(readFileSync(resolve(process.cwd(), 'packages/data/fixtures/query.json'), 'utf8'))

describe('parseCookQuery', () => {
  it.each(fixtures.cases)('$input', ({ input, extraStuff, output }) => {
    const query = parseCookQuery(input, extraStuff)
    expect({ ...query, tool: query.tool ?? null }).toEqual(output)
  })

  it('treats an unrecognized sentence as empty', () => {
    expect(isEmptyCookQuery(parseCookQuery('我也不知道吃什么'))).toBe(true)
    expect(isEmptyCookQuery(parseCookQuery('不辣'))).toBe(false)
  })
})

// 词表是 Web 和 iOS 共用的数据，写错的词会悄悄失效，这里兜底
describe('packages/data/query.json', () => {
  const foods = [...food.vegetable, ...food.meat, ...food.staple].map(item => item.name)

  it('maps to names the recommender knows', () => {
    for (const name of Object.values(rules.stuff))
      expect(foods).toContain(name)
    for (const name of Object.values(rules.tools))
      expect(food.tools.map(item => item.name)).toContain(name)
    for (const label of Object.keys(rules.methodGroups))
      expect(METHOD_GROUPS.map(group => group.label)).toContain(label)
    for (const level of Object.keys(rules.difficulty))
      expect(DIFFICULTY_LEVELS).toContain(level)
  })

  it('lists every word once', () => {
    const words = [
      ...foods,
      ...Object.keys(STUFF_ALIASES),
      ...food.tools.map(item => item.name),
      ...Object.keys(rules.stuff),
      ...rules.ignore,
      ...Object.keys(rules.tools),
      ...rules.have,
      ...rules.avoid,
      ...rules.lack,
      ...rules.noSpicy,
      ...Object.values(rules.difficulty).flat(),
      ...Object.values(rules.methodGroups).flat(),
    ]
    expect(words.filter((word, index) => words.indexOf(word) !== index)).toEqual([])
  })
})
