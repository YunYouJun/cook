import { book, bookOutline, library, libraryOutline, personCircle, personCircleOutline, restaurant, restaurantOutline } from 'ionicons/icons'

const primaryNavigation = [
  { tab: 'home', label: '做菜', href: '/home', icon: bookOutline, activeIcon: book, paths: ['/', '/home', '/tabs', '/tabs/home'] },
  { tab: 'library', label: '菜谱', href: '/library', icon: libraryOutline, activeIcon: library, paths: ['/library', '/tabs/library', '/recipes'] },
  { tab: 'apps', label: '发现', href: '/apps', icon: restaurantOutline, activeIcon: restaurant, paths: ['/apps', '/tabs/apps'] },
  { tab: 'my', label: '我的', href: '/my', icon: personCircleOutline, activeIcon: personCircle, paths: ['/my', '/tabs/my'] },
]

export function normalizeNavigationPath(path: string) {
  return path.replace(/\/+$/, '') || '/'
}

export function getPrimaryNavigation(path: string) {
  const currentPath = normalizeNavigationPath(path)
  return primaryNavigation.map(item => ({
    ...item,
    active: item.paths.some(root => currentPath === root || (item.tab === 'apps' && currentPath.startsWith(`${root}/`))),
  }))
}
