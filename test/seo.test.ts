import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { absoluteSiteUrl, appDescription, applyCookRouteSeo, publicPages, siteTitle, webApplicationJsonLd } from '../apps/web/app/constants/seo'
import { productFaq, productHomeJsonLd, productSiteDescription } from '../docs/.vitepress/theme/structured-data'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

function read(path: string) {
  return readFileSync(join(root, path), 'utf8')
}

function shell(title = siteTitle) {
  return `<!doctype html><html lang="zh-CN"><head><title>${title}</title><meta name="description" content="${appDescription}"><link rel="canonical" href="${absoluteSiteUrl('/')}"><meta property="og:title" content="${title}"><meta property="og:description" content="${appDescription}"><meta property="og:url" content="${absoluteSiteUrl('/')}"><meta name="twitter:title" content="${title}"><meta name="twitter:description" content="${appDescription}"></head><body></body></html>`
}

describe('web app citation metadata', () => {
  it('describes the free web app under the three public names', () => {
    const data = webApplicationJsonLd()
    expect(data['@type']).toBe('WebApplication')
    expect(data.name).toBe('食用手册')
    expect(data.alternateName).toEqual(['Cook', '云食册'])
    expect(data.url).toBe('https://cook.yunyoujun.cn/')
    expect(data.isAccessibleForFree).toBe(true)
    expect(data.offers).toMatchObject({ price: '0', priceCurrency: 'CNY' })
    expect(data.author).toMatchObject({ name: '云游君', alternateName: 'YunYouJun' })
    expect(data.creator).toMatchObject({ name: '云游君', alternateName: 'YunYouJun' })
    expect(appDescription).toContain('食材')
    expect(appDescription).toContain('厨具')
    expect(appDescription).not.toBe('好的，今天我们来做菜！')
  })

  it('rewrites public route shells without touching the homepage or unrelated html', () => {
    const random = applyCookRouteSeo(shell(), '/random/')
    expect(random).toContain(`<title>${publicPages.random.title}</title>`)
    expect(random).toContain(`content="${publicPages.random.description}"`)
    expect(random).toContain('href="https://cook.yunyoujun.cn/random"')
    expect(random).toContain('property="og:url" content="https://cook.yunyoujun.cn/random"')
    expect(applyCookRouteSeo(shell(), '/home')).toBe(shell())
    expect(applyCookRouteSeo(shell('其他标题'), '/library')).toBe(shell('其他标题'))
    expect(applyCookRouteSeo(shell(), '/settings')).toBe(shell())
  })

  it('publishes a sitemap, robots entry, and llms.txt for the web app host', () => {
    const sitemap = read('apps/web/public/sitemap.xml')
    const robots = read('apps/web/public/robots.txt')
    const llms = read('apps/web/public/llms.txt')
    for (const url of [
      'https://cook.yunyoujun.cn/',
      'https://cook.yunyoujun.cn/about/',
      'https://cook.yunyoujun.cn/random',
      'https://cook.yunyoujun.cn/library',
      'https://cook.yunyoujun.cn/apps',
      'https://cook.yunyoujun.cn/help',
    ]) {
      expect(sitemap).toContain(`<loc>${url}</loc>`)
    }
    expect(robots).toContain('Sitemap: https://cook.yunyoujun.cn/sitemap.xml')
    expect(llms).toContain('食用手册')
    expect(llms).toContain('Cook')
    expect(llms).toContain('云食册')
    expect(llms).toContain('https://cook.yunyoujun.cn/')
    expect(llms).toContain('https://github.com/YunYouJun/cook')
    expect(llms).toContain('https://cook.yunle.fun/')
  })

  it('copies the about page FAQ into FAQPage and describes the iOS app', () => {
    const html = read('apps/web/public/about/index.html')
    const document = new DOMParser().parseFromString(html, 'text/html')
    expect(document.title).toBe('云食册 · 食用手册｜有什么食材，就做什么菜')
    expect(document.querySelector('meta[name="description"]')?.getAttribute('content')).toContain('根据已有食材和厨具找菜')
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe('https://cook.yunyoujun.cn/about/')
    expect(document.querySelector('meta[property="og:url"]')?.getAttribute('content')).toBe('https://cook.yunyoujun.cn/about/')
    expect(document.querySelector('meta[name="twitter:card"]')?.getAttribute('content')).toBe('summary_large_image')

    const visible = [...document.querySelectorAll('.faq details')].map(item => ({
      question: item.querySelector('summary')?.textContent?.trim(),
      answer: item.querySelector('p')?.textContent?.trim(),
    }))
    const graph = JSON.parse(document.querySelector('script[type="application/ld+json"]')?.textContent || '{}')['@graph']
    const faq = graph.find((entry: { '@type': string }) => entry['@type'] === 'FAQPage')
    const ios = graph.find((entry: { '@type': string }) => entry['@type'] === 'SoftwareApplication')
    const web = graph.find((entry: { '@type': string }) => entry['@type'] === 'WebApplication')
    expect(visible.length).toBeGreaterThan(0)
    expect(faq.mainEntity.map((item: { name: string, acceptedAnswer: { text: string } }) => ({
      question: item.name,
      answer: item.acceptedAnswer.text,
    }))).toEqual(visible)
    expect(ios).toMatchObject({
      name: '云食册',
      alternateName: ['食用手册', 'Cook'],
      operatingSystem: 'iOS 17 或更新版本',
      url: 'https://cook.yunyoujun.cn/about/',
    })
    expect(ios.downloadUrl).toBeUndefined()
    expect(web).toMatchObject({
      '@id': 'https://cook.yunyoujun.cn/#app',
      'name': '食用手册',
      'url': 'https://cook.yunyoujun.cn/',
      'isAccessibleForFree': true,
    })
    expect(web.offers.price).toBe('0')
  })
})

describe('product site citation metadata', () => {
  it('uses the official host and the FAQ already shown on the homepage', () => {
    const data = productHomeJsonLd()
    const types = data['@graph'].map(entry => entry['@type'])
    expect(types).toEqual(['WebSite', 'WebApplication', 'SoftwareApplication', 'Person', 'FAQPage'])
    const website = data['@graph'][0]
    const application = data['@graph'][1]
    const ios = data['@graph'][2]
    const faq = data['@graph'][4]
    expect(website).toMatchObject({ url: 'https://cook.yunle.fun/', description: productSiteDescription })
    expect(application).toMatchObject({
      '@id': 'https://cook.yunyoujun.cn/#app',
      'name': '食用手册',
      'alternateName': ['Cook', '云食册'],
      'url': 'https://cook.yunyoujun.cn/',
      'isAccessibleForFree': true,
    })
    expect(ios).toMatchObject({ '@id': 'https://cook.yunle.fun/#ios', 'name': '云食册', 'sameAs': ['https://testflight.apple.com/join/KTTJNhDh'] })
    expect(faq).toMatchObject({
      mainEntity: productFaq.map(item => ({ name: item.question, acceptedAnswer: { text: item.answer } })),
    })
    expect(read('docs/index.md')).toContain(productSiteDescription)
    const services = read('docs/.vitepress/theme/components/ProductServices.vue')
    expect(services).toContain('productFaq')
    expect(services).not.toContain(productFaq[0].question)
    const robots = read('docs/public/robots.txt')
    const llms = read('docs/public/llms.txt')
    expect(robots).toContain('Sitemap: https://cook.yunle.fun/sitemap.xml')
    expect(robots).not.toContain('cook.yunyoujun.cn')
    expect(llms).toContain('云食册')
    expect(llms).toContain('食用手册')
    expect(llms).toContain('Cook')
    expect(llms).toContain('https://cook.yunle.fun/')
    expect(llms).toContain('https://github.com/YunYouJun/cook')
  })
})
