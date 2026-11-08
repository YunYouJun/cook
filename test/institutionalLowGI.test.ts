import type { InstitutionalLowGIData } from '../scripts/lib/institutional-low-gi'
import { readFileSync } from 'node:fs'
import Ajv from 'ajv'
import { describe, expect, it } from 'vitest'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { institutionalLowGIDataVersion, validateInstitutionalLowGIData } from '../scripts/lib/institutional-low-gi'

const read = (path: string) => JSON.parse(readFileSync(path, 'utf8'))
const data = read('packages/data/recipe-institutional-low-gi.json') as InstitutionalLowGIData
const recipes = parseRecipeCsv(readFileSync('packages/data/recipe.csv', 'utf8'))
const schema = read('packages/data/schema/recipe-institutional-low-gi.schema.json')

describe('institutional low GI source review', () => {
  it('binds ten distinct original recipes to an editorial claim without measured GI values', () => {
    expect(new Ajv().validate(schema, data)).toBe(true)
    expect(validateInstitutionalLowGIData(data, recipes)).toEqual([])
    expect(Object.keys(data.recipes)).toHaveLength(10)
    expect(Object.keys(data.recipes)).toEqual(Array.from({ length: 10 }, (_, i) => String(878 + i)))
    // Two distinct recipes on one page retain their original section identity.
    expect(data.recipes['884'].sourceURL).toBe(data.recipes['885'].sourceURL)
    expect(data.recipes['884'].sourceRecipeTitle).not.toBe(data.recipes['885'].sourceRecipeTitle)
  })

  it('rejects invented numeric claims and replacement of the institution by a lookalike host', () => {
    const claimed = structuredClone(data)
    Object.assign(claimed.recipes['881'], { gi: 54 })
    expect(new Ajv().validate(schema, claimed)).toBe(false)
    const changed = structuredClone(data)
    changed.recipes['881'].sourceURL = changed.recipes['881'].sourceURL.replace('glycemicindex.com', 'glycemicindex.com.example.org')
    expect(validateInstitutionalLowGIData(changed, recipes).some(error => error.includes('来源必须'))).toBe(true)
  })

  it('requires renewed review when a recipe, required tool or source link changes', () => {
    for (const change of [
      { name: '普通虾仁饭' },
      { tools: ['电饭煲'] },
      { link: 'https://example.org/recipe' },
    ]) {
      const changed = recipes.map(recipe => recipe.id === 881 ? { ...recipe, ...change } : recipe)
      expect(validateInstitutionalLowGIData(data, changed)).toContain('881: 目录或来源已变化，需要重新审核')
    }
  })

  it('does not promote legacy health tags, duplicate one recipe or omit main ingredient exclusions', () => {
    const changed = structuredClone(data)
    changed.recipes['885'].sourceRecipeTitle = changed.recipes['884'].sourceRecipeTitle
    changed.recipes['881'].avoidIngredients = ['米']
    expect(validateInstitutionalLowGIData(changed, recipes)).toContain('885: 同一来源配方重复收录')
    expect(validateInstitutionalLowGIData(changed, recipes)).toContain('881: 配方要求、内容依据或主料忌口映射不完整')
    delete changed.recipes['878']
    expect(validateInstitutionalLowGIData(changed, recipes)).toContain('878: 机构标签缺少独立审核记录')
    expect(validateInstitutionalLowGIData(changed, recipes)).toContain('机构筛选至少需要 10 道有效核对配方')
  })

  it('versions audit-only edits without rewriting the original recipes', () => {
    const changed = structuredClone(data)
    changed.recipes['881'].requirements = '新的配方要求'
    expect(institutionalLowGIDataVersion(changed)).not.toBe(data.dataVersion)
    expect(validateInstitutionalLowGIData(changed, recipes)).toContain('机构食谱版本与内容不一致')
  })
})
