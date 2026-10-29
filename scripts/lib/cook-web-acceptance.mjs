import { pathToFileURL } from 'node:url'

const targets = [
  { origin: 'https://cook.yunyoujun.cn', path: '/' },
  { origin: 'https://cook.yunle.fun', path: '/home' },
]

function requireState(value) {
  if (!value)
    throw Object.assign(new Error('Production browser acceptance failed'), { code: 'ACCEPTANCE_FAILED' })
}

/**
 * Verify a reviewed functional acceptance profile against both live production applications.
 * Browsing uses fresh contexts and blocks writes, so acceptance never submits real feedback.
 * @param {{profiles:string[],modulePath:string,channel?:string}} options Pinned Playwright runtime and reviewed profiles.
 * @returns {Promise<{summary:string,evidenceRefs:string[]}>} Successful public verification evidence.
 */
export async function verifyCookWebAcceptance({ profiles, modulePath, channel } = {}) {
  requireState(Array.isArray(profiles) && profiles.length > 0 && profiles.every(profile => ['app-smoke-v1', 'desktop-layout-v1'].includes(profile)))
  requireState(typeof modulePath === 'string' && modulePath.startsWith('/'))
  const { chromium } = await import(pathToFileURL(modulePath).href)
  const browser = await chromium.launch({ headless: true, ...(channel ? { channel } : {}) })
  const evidenceRefs = []
  try {
    for (const target of targets) {
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' })
      try {
        await context.route('**/*', route => ['GET', 'HEAD', 'OPTIONS'].includes(route.request().method()) ? route.continue() : route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }))
        const page = await context.newPage()
        const errors = []
        page.on('pageerror', error => errors.push(error.name))
        page.setDefaultTimeout(20_000)
        const response = await page.goto(target.origin + target.path, { waitUntil: 'domcontentloaded' })
        requireState(response?.ok() && new URL(page.url()).origin === target.origin)
        await page.locator('.choose-food').waitFor()
        requireState(await page.locator('vite-error-overlay,.nuxt-error-overlay').count() === 0)
        await page.getByText('试试番茄＋鸡蛋', { exact: true }).click()
        await page.locator('.recipe-result-row').first().waitFor()
        if (profiles.includes('desktop-layout-v1')) {
          requireState(await page.locator('.desktop-sidebar').isVisible())
          requireState(!await page.locator('ion-tab-bar').isVisible())
          requireState(await page.locator('.choose-food-more').count() === 0)
          requireState(await page.locator('.choose-food-categories .ingredient-chip').count() >= 43)
          requireState(await page.locator('.legacy-version-link').getAttribute('href') === 'https://v1.cook.yunyoujun.cn')
          await page.locator('.results-panel.is-desktop .recipe-result-row').first().waitFor()
          const positions = await page.evaluate(() => ({ left: document.querySelector('.choose-food').getBoundingClientRect().right, right: document.querySelector('.results-panel').getBoundingClientRect().left, overflow: document.documentElement.scrollWidth > innerWidth }))
          requireState(positions.left <= positions.right && !positions.overflow)
          await page.setViewportSize({ width: 390, height: 844 })
          await page.locator('.results-panel:not(.is-desktop)').waitFor()
          requireState(!await page.locator('.desktop-sidebar').isVisible() && await page.locator('ion-tab-bar').isVisible())
          requireState(await page.locator('.choose-food-more').count() >= 1)
          requireState((await page.locator('.choose-food-categories .ingredient-chip').first().boundingBox()).height >= 44)
          await page.locator('.results-preview-open').click()
          await page.locator('.results-panel.is-full .recipe-result-row').first().waitFor()
        }
        requireState(errors.length === 0)
        evidenceRefs.push(target.origin + target.path)
      }
      finally { await context.close() }
    }
    return { summary: `两个正式站点的线上浏览器验收通过：${[...new Set(profiles)].join(', ')}；验收浏览器阻止生产写入。`, evidenceRefs }
  }
  finally { await browser.close() }
}
