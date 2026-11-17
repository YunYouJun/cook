const productOrigin = 'https://cook.yunle.fun'

/** Sitemap and page paths that must stay readable but out of search indexes. */
export function isNonIndexableProductPage(page: string) {
  const source = page.replace(/^\//, '')
  return source === '404.md'
    || source.startsWith('dev/')
    || source.startsWith('decisions/')
    || source.startsWith('public/')
}

export function productPathname(url: string) {
  const withoutOrigin = url.startsWith(productOrigin) ? url.slice(productOrigin.length) : url
  const path = withoutOrigin.split(/[?#]/, 1)[0] || '/'
  const withSlash = path.startsWith('/') ? path : `/${path}`
  if (withSlash.length > 1 && withSlash.endsWith('/'))
    return withSlash.slice(0, -1)
  return withSlash || '/'
}

export function isNonIndexableProductPath(url: string) {
  const path = productPathname(url)
  return path === '/404'
    || path === '/dev'
    || path.startsWith('/dev/')
    || path === '/decisions'
    || path.startsWith('/decisions/')
    || path.startsWith('/public/')
}

export function publicProductSitemapItems<T extends { url: string }>(items: T[]) {
  return items.filter(item => !isNonIndexableProductPath(item.url))
}
