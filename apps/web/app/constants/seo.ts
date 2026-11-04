/**
 * Public citation metadata for the web app.
 * Canonical host is the deployed web app. The product site at cook.yunle.fun
 * stays on its own host; see docs/decisions/0015-product-site-domains.md.
 */
export const siteOrigin = 'https://cook.yunyoujun.cn'
export const siteName = '食用手册'
export const siteTitle = '食用手册｜根据家里的食材和厨具找菜谱'
export const appDescription = '根据家里现有的食材和厨具，找到今天能做的家常菜。食用手册（Cook）免费使用，也叫云食册：点选食材、一句话找菜、看做法、收藏，或随机决定今天吃什么。'
export const socialImagePath = '/about/assets/light-2-cook-selected.webp'

export const authorName = '云游君'
export const authorAlternateName = 'YunYouJun'
export const authorUrl = 'https://github.com/YunYouJun'

export interface CookSeoPage {
  title: string
  description: string
  path: string
}

export const publicPages = {
  home: {
    title: siteTitle,
    description: appDescription,
    path: '/',
  },
  random: {
    title: '今天吃什么｜食用手册',
    description: '还没想好吃什么？从食用手册里随机看一道菜，不合适再换一道。',
    path: '/random',
  },
  library: {
    title: '菜谱｜食用手册',
    description: '浏览食用手册收录的家常菜，按菜名查找，也可以只看收藏。',
    path: '/library',
  },
  apps: {
    title: '发现｜食用手册',
    description: '进入「今天吃什么」，或提交新功能建议。',
    path: '/apps',
  },
  help: {
    title: '帮助｜食用手册',
    description: '食用手册怎么用：匹配模式、一句话找菜、清空已选食材，以及网页版的常见问题。',
    path: '/help',
  },
} as const satisfies Record<string, CookSeoPage>

const routeSeoByPath: Record<string, CookSeoPage> = {
  '/': publicPages.home,
  '/home': publicPages.home,
  '/tabs': publicPages.home,
  '/tabs/home': publicPages.home,
  '/random': publicPages.random,
  '/apps/random': publicPages.random,
  '/library': publicPages.library,
  '/tabs/library': publicPages.library,
  '/apps': publicPages.apps,
  '/tabs/apps': publicPages.apps,
  '/help': publicPages.help,
}

export function absoluteSiteUrl(path = '/') {
  return new URL(path, siteOrigin).href
}

export function personEntity() {
  return {
    '@type': 'Person',
    'name': authorName,
    'alternateName': authorAlternateName,
    'url': authorUrl,
  }
}

export function webApplicationJsonLd() {
  return {
    '@context': 'https://schema.org',
    '@type': 'WebApplication',
    '@id': `${siteOrigin}/#app`,
    'name': siteName,
    'alternateName': ['Cook', '云食册'],
    'url': absoluteSiteUrl('/'),
    'description': appDescription,
    'applicationCategory': 'LifestyleApplication',
    'operatingSystem': 'Web',
    'inLanguage': 'zh-CN',
    'isAccessibleForFree': true,
    'offers': {
      '@type': 'Offer',
      'price': '0',
      'priceCurrency': 'CNY',
    },
    'author': personEntity(),
    'creator': personEntity(),
    'image': absoluteSiteUrl(socialImagePath),
  }
}

export function serializeJsonLd(data: unknown) {
  return JSON.stringify(data).replace(/</g, '\\u003c')
}

export function cookDocumentHead(page: CookSeoPage = publicPages.home) {
  const url = absoluteSiteUrl(page.path)
  const image = absoluteSiteUrl(socialImagePath)
  return {
    title: page.title,
    link: [
      { key: 'canonical', rel: 'canonical' as const, href: url },
    ],
    meta: [
      { key: 'description', name: 'description' as const, content: page.description },
      { key: 'og:type', property: 'og:type' as const, content: 'website' },
      { key: 'og:locale', property: 'og:locale' as const, content: 'zh_CN' },
      { key: 'og:site_name', property: 'og:site_name' as const, content: siteName },
      { key: 'og:title', property: 'og:title' as const, content: page.title },
      { key: 'og:description', property: 'og:description' as const, content: page.description },
      { key: 'og:url', property: 'og:url' as const, content: url },
      { key: 'og:image', property: 'og:image' as const, content: image },
      { key: 'twitter:card', name: 'twitter:card' as const, content: 'summary_large_image' },
      { key: 'twitter:title', name: 'twitter:title' as const, content: page.title },
      { key: 'twitter:description', name: 'twitter:description' as const, content: page.description },
      { key: 'twitter:image', name: 'twitter:image' as const, content: image },
    ],
    script: [
      {
        key: 'ld-webapp',
        type: 'application/ld+json' as const,
        innerHTML: serializeJsonLd(webApplicationJsonLd()),
      },
    ],
  }
}

function normalizePath(route: string) {
  const path = route.split(/[?#]/, 1)[0] || '/'
  if (path.length > 1 && path.endsWith('/'))
    return path.slice(0, -1)
  return path
}

function escapeHtml(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

function replaceTagAttribute(html: string, tagName: string, matches: (tag: string) => boolean, attribute: string, value: string) {
  return html.replace(new RegExp(`<${tagName}\\b[^>]*>`, 'gi'), (tag) => {
    if (!matches(tag) || !tag.includes(`${attribute}=`))
      return tag
    return tag.replace(new RegExp(`\\b${attribute}=(["'])[\\s\\S]*?\\1`), () => `${attribute}="${escapeHtml(value)}"`)
  })
}

function hasAttribute(tag: string, attribute: string, value: string) {
  return new RegExp(`(?:^|[\\s])${attribute}=(["'])${value}\\1`).test(tag)
}

function replaceMetaContent(html: string, attribute: 'name' | 'property', key: string, content: string) {
  return replaceTagAttribute(html, 'meta', tag => hasAttribute(tag, attribute, key), 'content', content)
}

function replaceCanonical(html: string, url: string) {
  return replaceTagAttribute(html, 'link', tag => hasAttribute(tag, 'rel', 'canonical'), 'href', url)
}

/**
 * The generated SPA shell carries the homepage head. Rewrite public routes so a
 * crawler that does not run JavaScript still sees that route's title and canonical.
 * Homepage aliases already match the shell and are left untouched.
 */
export function applyCookRouteSeo(html: string, route: string) {
  const page = routeSeoByPath[normalizePath(route)]
  if (!page || page.path === '/' || !html.includes(`<title>${siteTitle}</title>`))
    return html
  const url = absoluteSiteUrl(page.path)
  let next = html.replace(`<title>${siteTitle}</title>`, () => `<title>${escapeHtml(page.title)}</title>`)
  next = replaceMetaContent(next, 'name', 'description', page.description)
  next = replaceMetaContent(next, 'property', 'og:title', page.title)
  next = replaceMetaContent(next, 'property', 'og:description', page.description)
  next = replaceMetaContent(next, 'property', 'og:url', url)
  next = replaceMetaContent(next, 'name', 'twitter:title', page.title)
  next = replaceMetaContent(next, 'name', 'twitter:description', page.description)
  return replaceCanonical(next, url)
}
