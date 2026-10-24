import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { exportPrivateRecipes, mergePrivateRecipeImport, parsePrivateRecipeFile, previewPrivateRecipeImport } from '../packages/cook/src/utils/custom-recipes'

const text = readFileSync('packages/data/fixtures/private-recipes.json', 'utf8')
const file = parsePrivateRecipeFile(text)
const [recipe] = file.recipes

describe('private recipe contract shared with iOS', () => {
  it('round trips all fields and preserves legacy IDs and timestamps', () => {
    expect(parsePrivateRecipeFile(exportPrivateRecipes(file.recipes, file.exportedAt))).toEqual(file)
    expect(file.recipes[0].customId).toBe('custom-legacy-a')
    expect(file.recipes[0].note).toContain('\n')
  })

  it.each([
    '{',
    JSON.stringify({ ...file, recipes: [{ ...recipe, futureField: 'must not be dropped' }] }),
    JSON.stringify({ ...file, recipes: [{ ...recipe, note: null }] }),
    JSON.stringify({ ...file, version: 2 }),
    JSON.stringify({ ...file, kind: 'favorites' }),
    JSON.stringify({ ...file, recipes: [recipe, { ...recipe, name: '冲突' }] }),
    JSON.stringify({ ...file, recipes: [{ ...recipe, link: 'javascript:alert(1)' }] }),
    JSON.stringify({ ...file, recipes: [{ ...recipe, stuff: [] }] }),
    JSON.stringify({ ...file, recipes: [{ ...recipe, updatedAt: -1 }] }),
    JSON.stringify({ ...file, recipes: [{ ...recipe, link: 'https://user:pass@example.com' }] }),
  ])('rejects the whole bad file without touching current data', (bad) => {
    const current = structuredClone(file.recipes)
    expect(() => parsePrivateRecipeFile(bad)).toThrow()
    expect(current).toEqual(file.recipes)
  })

  it('deduplicates identical repeated records in the file', () => {
    expect(parsePrivateRecipeFile(JSON.stringify({ ...file, recipes: [recipe, recipe] })).recipes).toEqual([recipe])
  })

  it('previews and imports without overwriting; identical content with a new timestamp is a duplicate', () => {
    const local = [{ ...recipe, name: '本机版本' }]
    const preview = previewPrivateRecipeImport(local, file.recipes)
    expect(preview.conflicts).toHaveLength(1)
    expect(preview.additions).toHaveLength(1)
    expect(mergePrivateRecipeImport(local, file.recipes, false, () => 'copy')).toEqual([...local, file.recipes[1]])
    expect(previewPrivateRecipeImport([recipe], [{ ...recipe, updatedAt: recipe.updatedAt + 1 }]).duplicates).toHaveLength(1)
  })

  it('keeps a stable copiedFrom reference and repeated imports do not make more copies', () => {
    const local = [{ ...recipe, name: '本机版本' }]
    const merged = mergePrivateRecipeImport(local, [recipe], true, () => 'custom-copy')
    expect(merged[0]).toEqual(local[0])
    expect(merged[1]).toMatchObject({ customId: 'custom-copy', copiedFrom: recipe.customId, name: recipe.name })
    expect(mergePrivateRecipeImport(merged, [recipe], true, () => 'another-copy')).toEqual(merged)
  })

  it('does not resurrect deleted identities or confuse same-name different recipes', () => {
    expect(previewPrivateRecipeImport([], [recipe], [recipe.customId]).conflicts).toHaveLength(1)
    expect(mergePrivateRecipeImport([], [recipe], false, () => 'copy', [recipe.customId])).toEqual([])
    expect(previewPrivateRecipeImport([recipe], [{ ...recipe, customId: 'new-id' }]).additions).toHaveLength(1)
  })
})
