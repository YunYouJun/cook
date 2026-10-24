import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useRecipeStore } from '../apps/web/app/composables/store/recipe'
import { usePersonalCatalog } from '../apps/web/app/composables/usePersonalCatalog'
import { readPersonalCatalog, updatePersonalCatalog } from '../apps/web/app/utils/personalCatalogStorage'
import { catalogItems, emptyPersonalCatalog, exportPersonalCatalog, mergePersonalCatalog, parsePersonalCatalog } from '../packages/cook/src/utils/personal-catalog'
import { parseCookQuery } from '../packages/cook/src/utils/query'
import fixtureData from '../packages/data/fixtures/personal-catalog.json'

const fixture = JSON.stringify(fixtureData)
describe('personal catalog portable state', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })
  it('round trips the shared iOS fixture and merges idempotently', () => {
    const catalog = parsePersonalCatalog(fixture)
    expect(parsePersonalCatalog(exportPersonalCatalog(catalog))).toEqual(catalog)
    expect(mergePersonalCatalog(catalog, catalog)).toEqual(catalog)
    const local = { ...emptyPersonalCatalog(), items: [{ category: 'staple' as const, name: '鹰嘴豆' }] }
    expect(mergePersonalCatalog(local, catalog).items[0]).toEqual(local.items[0])
    expect(mergePersonalCatalog(local, catalog).items).toHaveLength(4)
  })
  it('hides builtins without mutating source data and retains added tools', () => {
    const source = [{ name: '番茄', emoji: '🍅' }]
    const catalog = parsePersonalCatalog(fixture)
    expect(catalogItems(source, catalog, 'vegetable', name => ({ name, emoji: '🥬' }))).toEqual([{ name: '鹰嘴豆', emoji: '🥬' }])
    expect(source).toEqual([{ name: '番茄', emoji: '🍅' }])
    expect(catalogItems(source, catalog, 'vegetable', name => ({ name, emoji: '🥬' }), true)).toHaveLength(2)
    expect(parseCookQuery('鹰嘴豆用塔吉锅', ['鹰嘴豆'], ['塔吉锅'])).toMatchObject({ stuff: ['鹰嘴豆'], tool: '塔吉锅' })
  })
  it('rejects malformed, oversize, duplicate and future files as a whole', () => {
    const valid = parsePersonalCatalog(fixture)
    for (const invalid of [
      { ...valid, version: 2 },
      { ...valid, future: 'preserve' },
      { ...valid, items: [{ category: 'meat', name: 'a', future: 1 }] },
      { ...valid, items: [{ category: 'bad', name: 'a' }] },
      { ...valid, items: [{ category: 'meat', name: 'a\nb' }] },
      { ...valid, items: [{ category: 'tools', name: ' ' }] },
      { ...valid, items: [...valid.items, { category: 'staple', name: '鹰嘴豆' }] },
      { ...valid, items: [{ category: 'meat', name: '😀'.repeat(16) }] },
    ]) expect(() => parsePersonalCatalog(JSON.stringify(invalid))).toThrow()
    expect(() => parsePersonalCatalog(' '.repeat(256 * 1024 + 1))).toThrow()
  })
  it('reads latest data before a write and never destroys unreadable or quota-limited data', () => {
    const key = 'catalog-test'
    localStorage.setItem(key, fixture)
    updatePersonalCatalog(localStorage, key, value => ({ ...value, hidden: [] }))
    expect(readPersonalCatalog(localStorage, key).items).toHaveLength(4)
    const before = localStorage.getItem(key)
    expect(() => updatePersonalCatalog({ getItem: key => localStorage.getItem(key), setItem: () => {
      throw new Error('quota')
    } }, key, () => emptyPersonalCatalog())).toThrow('quota')
    expect(localStorage.getItem(key)).toBe(before)
    localStorage.setItem(key, '{broken')
    expect(() => updatePersonalCatalog(localStorage, key, () => emptyPersonalCatalog())).toThrow()
    expect(localStorage.getItem(key)).toBe('{broken')
  })
  it('uses custom selections immediately, including tools, without clearing saved filters', () => {
    const catalog = usePersonalCatalog()
    catalog.update(() => parsePersonalCatalog(fixture))
    const store = useRecipeStore()
    expect(store.parseText('鹰嘴豆用塔吉锅')).toMatchObject({ stuff: ['鹰嘴豆'], tool: '塔吉锅' })
    store.toggleStuff('番茄')
    store.toggleAvoid('番茄')
    catalog.update(() => emptyPersonalCatalog())
    expect(store.selectedStuff).toContain('番茄')
    expect(store.selectedAvoid).toContain('番茄')
  })
})
