import type { VueWrapper } from '@vue/test-utils'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, defineComponent, h, nextTick, onMounted, ref, shallowRef, watch } from 'vue'
import App from '../apps/web/app/app.vue'
import { favoriteEntries, setFavoriteEntries } from '../apps/web/app/composables/store/favorite'
import { recipeHistories } from '../apps/web/app/composables/store/history'
import Favorites from '../apps/web/app/pages/recipes/favorites.vue'
import History from '../apps/web/app/pages/recipes/history.vue'

vi.unmock('vue')
const mocks = vi.hoisted(() => ({ catalog: vi.fn(), confirm: vi.fn() }))
vi.mock('../apps/web/app/utils/db', () => ({ db: { recipes: { toArray: mocks.catalog } } }))
vi.mock('../apps/web/app/utils/dialog', () => ({ confirmDestructive: mocks.confirm }))
vi.mock('../apps/web/app/utils/recipe', () => ({ openRecipe: vi.fn() }))
vi.mock('../apps/web/app/utils/yunleHost', () => ({ yunleHaptic: vi.fn() }))
vi.mock('../apps/web/app/composables/db', () => ({ useIndexedDB: () => ({ init: vi.fn() }) }))
vi.mock('../apps/web/app/components/feedback/RecipeButton.vue', () => ({ default: { template: '<span />' } }))

const a = { id: 101, name: '甲账号的菜', stuff: [], tools: [] }
const b = { id: 202, name: '乙账号的菜', stuff: [], tools: [] }
const wrappers: VueWrapper[] = []
let scope: { scopeBlocked: ReturnType<typeof shallowRef<boolean>>, scopeRevision: ReturnType<typeof shallowRef<number>>, syncNow: ReturnType<typeof vi.fn> }

const stubs = Object.fromEntries([
  'ion-page',
  'ion-content',
  'ion-header',
  'ion-toolbar',
  'ion-buttons',
  'ion-title',
  'ion-list',
  'ion-item',
  'ion-item-sliding',
  'ion-item-options',
  'ion-item-option',
  'ion-label',
  'ion-icon',
  'ion-searchbar',
  'ion-segment',
  'ion-segment-button',
  'ion-skeleton-text',
  'ion-back-button',
  'ion-item-group',
  'ion-item-divider',
  'ion-note',
].map(name => [name, { template: '<div><slot /></div>' }]))

function render(component: typeof Favorites | typeof History | typeof App) {
  const wrapper = mount(component, { global: { stubs: {
    ...stubs,
    'ion-app': { template: '<main><slot /></main>' },
    'ion-button': { template: '<button><slot /></button>' },
    'ion-router-outlet': { template: '<div>甲账号的缓存页面</div>' },
    'DesktopSidebar': true,
    'DishLabel': defineComponent({ props: ['dish'], setup: props => () => h('span', props.dish.name) }),
  } } })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  scope = { scopeBlocked: shallowRef(false), scopeRevision: shallowRef(0), syncNow: vi.fn() }
  mocks.catalog.mockReset().mockResolvedValue([a, b])
  mocks.confirm.mockReset().mockResolvedValue(true)
  setFavoriteEntries([{ id: a.id, time: 1000 }])
  recipeHistories.value = [{ recipe: a, time: 1000 }]
  for (const [name, value] of Object.entries({ computed, onMounted, ref, shallowRef, watch }))
    vi.stubGlobal(name, value)
  vi.stubGlobal('useUserDataSync', () => scope)
  vi.stubGlobal('definePageMeta', vi.fn())
  vi.stubGlobal('useHead', vi.fn())
  vi.stubGlobal('useDarkMode', () => ({ isDark: shallowRef(false), setDarkMode: vi.fn(), setLightMode: vi.fn() }))
})

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  vi.unstubAllGlobals()
})

describe('retained account pages', () => {
  it('updates the mounted favorites page after a sync or account change', async () => {
    const page = render(Favorites)
    await flushPromises()
    expect(page.text()).toContain(a.name)
    setFavoriteEntries([{ id: b.id, time: 2000 }])
    await nextTick()
    expect(page.text()).not.toContain(a.name)
    expect(page.text()).toContain(b.name)
    setFavoriteEntries([])
    await nextTick()
    expect(page.text()).not.toContain(b.name)
  })

  it('does not clear another account after a pending favorites confirmation', async () => {
    let confirm!: (value: boolean) => void
    mocks.confirm.mockReturnValue(new Promise<boolean>((resolve) => {
      confirm = resolve
    }))
    const page = render(Favorites)
    await flushPromises()
    await page.get('button[title="清空收藏"]').trigger('click')
    scope.scopeRevision.value += 1
    setFavoriteEntries([{ id: b.id, time: 2000 }])
    confirm(true)
    await flushPromises()
    expect(favoriteEntries.value.map(item => item.id)).toEqual([b.id])
  })

  it('does not clear another account after a pending history confirmation', async () => {
    let confirm!: (value: boolean) => void
    mocks.confirm.mockReturnValue(new Promise<boolean>((resolve) => {
      confirm = resolve
    }))
    const page = render(History)
    await page.get('button[title="清空记录"]').trigger('click')
    scope.scopeRevision.value += 1
    recipeHistories.value = [{ recipe: b, time: 2000 }]
    confirm(true)
    await flushPromises()
    expect(recipeHistories.value.map(item => item.recipe.name)).toEqual([b.name])
  })

  it('unmounts account content when scope selection fails, then allows retry', async () => {
    const page = render(App)
    expect(page.text()).toContain('甲账号的缓存页面')
    scope.scopeBlocked.value = true
    await nextTick()
    expect(page.text()).not.toContain('甲账号的缓存页面')
    expect(page.text()).toContain('暂时无法切换做菜记录')
    await page.get('button').trigger('click')
    expect(scope.syncNow).toHaveBeenCalledOnce()
    scope.scopeBlocked.value = false
    await nextTick()
    expect(page.text()).toContain('甲账号的缓存页面')
  })
})
