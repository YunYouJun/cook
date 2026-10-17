import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { useAppStore } from '../app/composables/store/app'

// Exercise actual localStorage reads/migration/writes, rather than the default test stub.
vi.unmock('@vueuse/core')

let app: ReturnType<typeof useAppStore> | undefined
beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})
afterEach(() => {
  app?.$dispose()
  localStorage.clear()
})

describe('recipe view preference', () => {
  it('defaults to classic for fresh and legacy settings without losing other preferences', () => {
    localStorage.setItem('cook:settings', JSON.stringify({ keepLocalData: false }))
    app = useAppStore()
    expect(app.recipeView).toBe('classic')
    expect(app.settings.keepLocalData).toBe(false)
  })

  it('remembers the selected view independently of keeping ingredient data', async () => {
    app = useAppStore()
    expect(app.recipeView).toBe('classic')
    app.settings.keepLocalData = false
    app.recipeView = 'recommendation'
    await nextTick()
    expect(JSON.parse(localStorage.getItem('cook:settings')!)).toEqual({ keepLocalData: false, recipeView: 'recommendation' })
    app.$dispose()
    setActivePinia(createPinia())
    app = useAppStore()
    expect(app.recipeView).toBe('recommendation')
    app.recipeView = 'classic'
    await nextTick()
    expect(JSON.parse(localStorage.getItem('cook:settings')!).recipeView).toBe('classic')
  })

  it('falls back to classic for an unknown stored view', () => {
    localStorage.setItem('cook:settings', JSON.stringify({ keepLocalData: true, recipeView: 'unknown' }))
    app = useAppStore()
    expect(app.recipeView).toBe('classic')
  })
})
