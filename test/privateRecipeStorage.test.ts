import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { readPrivateRecipeStorage, updatePrivateRecipeStorage } from '../apps/web/app/utils/privateRecipeStorage'
import { mergePrivateRecipeImport, parsePrivateRecipeFile } from '../packages/cook/src/utils/custom-recipes'

const recipes = parsePrivateRecipeFile(readFileSync('packages/data/fixtures/private-recipes.json', 'utf8')).recipes
function storage(raw: string | null) {
  return {
    getItem: () => raw,
    setItem: (_key: string, value: string) => {
      raw = value
    },
  }
}

describe('private recipe storage safety', () => {
  it('migrates existing Web arrays without changing stable IDs or metadata', () => {
    const saved = storage(JSON.stringify(recipes))
    const before = saved.getItem()
    expect(readPrivateRecipeStorage(saved, 'recipes')).toEqual(recipes)
    expect(saved.getItem()).toBe(before)
    expect(updatePrivateRecipeStorage(saved, 'recipes', current => current)).toEqual(recipes)
  })
  it('rejects malformed existing data and preserves the original bytes', () => {
    for (const raw of ['{bad', '{}', '[{"customId":"old"}]']) {
      const saved = storage(raw)
      expect(() => updatePrivateRecipeStorage(saved, 'recipes', () => recipes)).toThrow()
      expect(saved.getItem()).toBe(raw)
    }
  })
  it('does not overwrite storage on invalid incoming data or a quota error', () => {
    const raw = JSON.stringify(recipes)
    const saved = storage(raw)
    expect(() => updatePrivateRecipeStorage(saved, 'recipes', () => [{ ...recipes[0]!, name: '' }])).toThrow()
    expect(saved.getItem()).toBe(raw)
    saved.setItem = () => {
      throw new DOMException('Full', 'QuotaExceededError')
    }
    expect(() => updatePrivateRecipeStorage(saved, 'recipes', () => [])).toThrow('Full')
    expect(saved.getItem()).toBe(raw)
  })
  it('uses current storage at confirmation and keeps repeated imports idempotent', () => {
    const saved = storage(JSON.stringify([recipes[0]]))
    saved.setItem('recipes', JSON.stringify(recipes)) // another tab saves before confirmation
    const update = (current: typeof recipes) => mergePrivateRecipeImport(current, recipes, true, () => 'copy')
    expect(updatePrivateRecipeStorage(saved, 'recipes', update)).toEqual(recipes)
    expect(updatePrivateRecipeStorage(saved, 'recipes', update)).toEqual(recipes)
  })
})
