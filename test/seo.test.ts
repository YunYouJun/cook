import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { absoluteSiteUrl, appDescription, applyCookRouteSeo, publicPages, siteTitle, webApplicationJsonLd } from '../apps/web/app/constants/seo'
import { isNonIndexableProductPage, publicProductSitemapItems } from '../docs/.vitepress/theme/indexing'
import { guideArticleJsonLd, organizationIdentity, personIdentity, productFaq, productHomeJsonLd, productSiteDescription } from '../docs/.vitepress/theme/structured-data'

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
    expect(data.author).toMatchObject(personIdentity)
    expect(data.creator).toMatchObject(personIdentity)
    expect(data.publisher).toMatchObject(organizationIdentity)
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

  it('marks both the prerendered 404 route and the hosting fallback as noindex', () => {
    for (const route of ['/404', '/404/', '/404.html'])
      expect(applyCookRouteSeo(shell(), route)).toContain('<head><meta name="robots" content="noindex">')

    const indexed = shell().replace('<head>', '<head><meta name="robots" content="index">')
    const hidden = applyCookRouteSeo(indexed, '/404.html')
    expect(hidden).toContain('name="robots" content="noindex"')
    expect(hidden.match(/name="robots"/g)).toHaveLength(1)
  })

  it('labels the shared recipe page before the recipe loads and excludes it from indexing', () => {
    const sharePage = applyCookRouteSeo(shell(), '/recipes/share/?id=250')
    expect(sharePage).toContain('<title>分享的菜谱 · 食用手册</title>')
    expect(sharePage).toContain('<meta name="robots" content="noindex">')
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
    expect(web.author).toMatchObject({ '@id': personIdentity['@id'] })
    expect(web.publisher).toMatchObject({ '@id': organizationIdentity['@id'] })
    expect(ios.author).toMatchObject({ '@id': personIdentity['@id'] })
    expect(ios.publisher).toMatchObject({ '@id': organizationIdentity['@id'] })
    const person = graph.find((entry: { '@type': string }) => entry['@type'] === 'Person')
    const organization = graph.find((entry: { '@type': string }) => entry['@type'] === 'Organization')
    expect(person).toMatchObject(personIdentity)
    expect(organization).toMatchObject(organizationIdentity)
  })
})

describe('product site citation metadata', () => {
  it('uses the official host and the FAQ already shown on the homepage', () => {
    const data = productHomeJsonLd()
    const types = data['@graph'].map(entry => entry['@type'])
    expect(types).toEqual(['WebSite', 'WebApplication', 'SoftwareApplication', 'Person', 'Organization', 'FAQPage'])
    const website = data['@graph'][0]
    const application = data['@graph'][1]
    const ios = data['@graph'][2]
    const person = data['@graph'][3]
    const organization = data['@graph'][4]
    const faq = data['@graph'][5]
    expect(website).toMatchObject({ url: 'https://cook.yunle.fun/', description: productSiteDescription, publisher: { '@id': organizationIdentity['@id'] } })
    expect(application.author).toEqual({ '@id': personIdentity['@id'] })
    expect(application.publisher).toEqual({ '@id': organizationIdentity['@id'] })
    expect(ios.publisher).toEqual({ '@id': organizationIdentity['@id'] })
    expect(person).toMatchObject(personIdentity)
    expect(organization).toMatchObject(organizationIdentity)
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
    expect(llms).toContain('cook.yunle.app')
    expect(llms).toContain('不应被搜索引擎收录')

    const article = guideArticleJsonLd({
      title: '云食册 iOS 体验测试',
      description: '通过 TestFlight 加入外测',
      path: '/guide/ios-beta',
    })
    expect(article['@type']).toBe('TechArticle')
    expect(article.headline).toBe('云食册 iOS 体验测试')
    expect(article.url).toBe('https://cook.yunle.fun/guide/ios-beta')
    expect(article.author).toMatchObject(personIdentity)
    expect(article.publisher).toMatchObject(organizationIdentity)
    expect(guideArticleJsonLd({ title: '快速开始', path: 'guide/getting-started' }).description).toBeUndefined()

    const sitemap = publicProductSitemapItems([
      { url: '' },
      { url: '/guide/getting-started' },
      { url: 'guide/web-domain' },
      { url: '/terms' },
      { url: '/privacy' },
      { url: '/privacy-android' },
      { url: '/dev/cli' },
      { url: 'dev/data' },
      { url: '/decisions/' },
      { url: 'decisions/0015-product-site-domains' },
      { url: 'https://cook.yunle.fun/public/landing/README' },
      { url: '/404' },
    ])
    expect(sitemap.map(item => item.url)).toEqual(['', '/guide/getting-started', 'guide/web-domain', '/terms', '/privacy', '/privacy-android'])
    expect(isNonIndexableProductPage('404.md')).toBe(true)
    expect(isNonIndexableProductPage('dev/cli.md')).toBe(true)
    expect(isNonIndexableProductPage('decisions/index.md')).toBe(true)
    expect(isNonIndexableProductPage('guide/getting-started.md')).toBe(false)
    expect(isNonIndexableProductPage('index.md')).toBe(false)

    const pages = markdownPages('docs')
    expect(pages.filter(page => !isNonIndexableProductPage(page)).sort()).toEqual([
      'guide/getting-started.md',
      'guide/ios-beta.md',
      'guide/next-day-lunch.md',
      'guide/sentence-input.md',
      'guide/web-and-ios.md',
      'guide/web-domain.md',
      'index.md',
      'privacy-android.md',
      'privacy.md',
      'terms.md',
    ])
    expect(existsSync(join(root, 'docs/public/landing/README.md'))).toBe(false)
    expect(existsSync(join(root, 'docs/.vitepress/landing-image-prompt.md'))).toBe(true)
    expect(existsSync(join(root, 'docs/public/landing/tomato-eggs-table.webp'))).toBe(true)
    expect(readdirSync(join(root, 'docs/public/landing')).some(name => name.toLowerCase().includes('readme'))).toBe(false)
    for (const icon of ['docs/public/favicon.ico', 'apps/web/public/favicon.ico']) {
      const bytes = readFileSync(join(root, icon))
      expect(bytes.subarray(0, 4).toString('hex')).toBe('00000100')
    }
    expect(read('docs/.vitepress/theme/components/LandingIngredients.vue')).toContain(':alt="item.name"')
    expect(read('docs/.vitepress/theme/components/IngredientGallery.vue')).toContain(':alt="item.name"')
  })
})

function markdownPages(dir: string, prefix = ''): string[] {
  return readdirSync(join(root, dir), { withFileTypes: true }).flatMap((entry) => {
    if (entry.name.startsWith('.') || entry.name === 'public' || entry.name === 'node_modules')
      return []
    const rel = prefix ? `${prefix}/${entry.name}` : entry.name
    if (entry.isDirectory())
      return markdownPages(`${dir}/${entry.name}`, rel)
    return entry.name.endsWith('.md') ? [rel] : []
  })
}
