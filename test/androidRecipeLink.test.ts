import { mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import AndroidRecipeLink from '../apps/web/app/components/recipe/AndroidRecipeLink.vue'

vi.unmock('vue')

const androidChrome = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/143.0.0.0 Mobile Safari/537.36'

async function render(agent: string, recipeId?: number) {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(agent)
  const wrapper = mount(AndroidRecipeLink, {
    props: { recipeId },
    global: {
      stubs: {
        'ion-item': { template: '<a><slot /></a>' },
        'ion-label': { template: '<span><slot /></span>' },
      },
    },
  })
  await nextTick()
  return wrapper
}

afterEach(() => vi.restoreAllMocks())

it('opens the current public recipe and uses the same recipe for browser fallback', async () => {
  const screen = await render(androidChrome, 250)
  const link = screen.get('a')
  expect(link.text()).toBe('在云食册中打开')
  expect(link.attributes('href')).toBe('intent://cook.yunle.fun/recipes/share/?id=250#Intent;scheme=https;package=fun.yunle.cook;S.browser_fallback_url=https%3A%2F%2Fcook.yunle.fun%2Frecipes%2Fshare%2F%3Fid%3D250;end')

  await screen.setProps({ recipeId: 588 })
  expect(screen.get('a').attributes('href')).toContain('/recipes/share/?id=588#Intent;')
  await screen.setProps({ recipeId: 2_147_483_648 })
  expect(screen.find('a').exists()).toBe(false)
  screen.unmount()
})

it.each([
  'Mozilla/5.0 (X11; Linux x86_64) Chrome/143.0.0.0',
  'Mozilla/5.0 (iPhone; CPU iPhone OS 27_0 like Mac OS X) Mobile Safari/537.36',
  'Mozilla/5.0 (Android 15; Mobile; rv:143.0) Gecko/143.0 Firefox/143.0',
  androidChrome.replace('K)', 'K; wv)'),
  `${androidChrome} MicroMessenger/8.0.64`,
])('keeps the web recipe without an Android app action in %s', async (agent) => {
  const screen = await render(agent, 250)
  expect(screen.find('a').exists()).toBe(false)
  screen.unmount()
})
