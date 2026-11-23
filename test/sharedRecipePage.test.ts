import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { computed, shallowRef, watch } from 'vue'
import SharedRecipePage from '../apps/web/app/pages/recipes/share.vue'

vi.unmock('vue')

const { initCatalog, getRecipe } = vi.hoisted(() => ({
  initCatalog: vi.fn(),
  getRecipe: vi.fn(),
}))

vi.mock('~/composables/db', () => ({ useIndexedDB: () => ({ init: initCatalog }) }))
vi.mock('~/utils/db', () => ({ db: { recipes: { get: getRecipe } } }))
vi.mock('~/utils/recipe', () => ({ getRecipeLink: () => '', getRecipeTextLink: () => '' }))
vi.mock('~/components/feedback/RecipeButton.vue', () => ({ default: { template: '<div />' } }))

const route = shallowRef<{ query: { id: string | string[] } }>({ query: { id: '250' } })
const appLink = 'a[href^="intent://"]'

beforeEach(() => {
  route.value = { query: { id: '250' } }
  initCatalog.mockReset()
  getRecipe.mockReset()
  vi.stubGlobal('shallowRef', shallowRef)
  vi.stubGlobal('computed', computed)
  vi.stubGlobal('watch', watch)
  vi.stubGlobal('useRouter', () => ({ currentRoute: route }))
  vi.stubGlobal('useHead', () => ({ patch: vi.fn() }))
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (Linux; Android 10; K) Chrome/143.0.0.0 Mobile')
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function renderPage() {
  return mount(SharedRecipePage, {
    global: {
      stubs: {
        'ion-page': { template: '<main><slot /></main>' },
        'ion-header': true,
        'ion-content': { template: '<div><slot /></div>' },
        'ion-item': { template: '<a><slot /></a>' },
        'ion-label': { template: '<span><slot /></span>' },
        'ion-button': { template: '<button><slot /></button>' },
        'ion-list': { template: '<div><slot /></div>' },
      },
    },
  })
}

it('lets Chrome open the same app link before catalog load, after a failure, and after retry', async () => {
  let fail: (error: Error) => void = () => {}
  initCatalog.mockImplementationOnce(() => new Promise<void>((_, reject) => {
    fail = reject
  }))
  initCatalog.mockResolvedValue(undefined)
  getRecipe.mockResolvedValue({ name: '番茄炒蛋', stuff: [], tools: [] })
  const page = renderPage()
  try {
    await flushPromises()
    expect(page.text()).toContain('正在加载菜谱')
    const href = page.get(appLink).attributes('href')
    expect(href).toContain('/recipes/share/?id=250#Intent;')
    fail(new Error('IndexedDB is unavailable'))
    await flushPromises()
    expect(page.text()).toContain('菜谱暂时没有加载成功')
    expect(page.get(appLink).attributes('href')).toBe(href)

    await page.get('button').trigger('click')
    await flushPromises()
    expect(page.text()).toContain('番茄炒蛋')
    expect(page.findAll(appLink)).toHaveLength(1)
    expect(page.get(appLink).attributes('href')).toBe(href)
    expect(getRecipe).toHaveBeenCalledWith(250)
  }
  finally {
    page.unmount()
  }
})

it('offers the app for a missing recipe and removes the action when the link ID is ambiguous', async () => {
  initCatalog.mockResolvedValue(undefined)
  getRecipe.mockResolvedValue(undefined)
  const page = renderPage()
  try {
    await flushPromises()
    expect(page.text()).toContain('找不到这道菜')
    expect(page.findAll(appLink)).toHaveLength(1)

    route.value = { query: { id: ['250', '588'] } }
    await flushPromises()
    expect(page.find(appLink).exists()).toBe(false)
    expect(getRecipe).toHaveBeenCalledTimes(1)
  }
  finally {
    page.unmount()
  }
})
