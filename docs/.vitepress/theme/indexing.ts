const productOrigin = 'https://cook.yunle.fun'

export function productPagePath(page: string) {
  return page.replace(/^\//, '').replace(/(^|\/)index\.md$/, '$1').replace(/\.md$/, '')
}

export function isNonIndexableProductPage(page: string) {
  return isNonIndexableProductPath(productPagePath(page))
}

/** Paths that must stay readable but out of search indexes. */
function isNonIndexableProductPath(url: string) {
  const path = new URL(url, productOrigin).pathname.replace(/\/$/, '')
  return path === '/404' || /^\/(?:dev|decisions|public)(?:\/|$)/.test(path)
}

export function publicProductSitemapItems<T extends { url: string }>(items: T[]) {
  return items.filter(item => !isNonIndexableProductPath(item.url))
}
