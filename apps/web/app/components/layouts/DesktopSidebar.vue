<script setup lang="ts">
import { useIonRouter } from '@ionic/vue'

const collapsed = defineModel<boolean>('collapsed', { default: false })
const router = useRouter()
const ionRouter = useIonRouter()
const path = computed(() => router.currentRoute.value.path)

const navigation = computed(() => [
  { label: '做菜', href: '/home', icon: ioniconsRestaurantOutline, active: ['/', '/home', '/tabs', '/tabs/home'].includes(path.value) },
  { label: '菜谱', href: '/library', icon: ioniconsLibraryOutline, active: ['/library', '/tabs/library', '/recipes'].includes(path.value) },
  { label: '发现', href: '/apps', icon: ioniconsCompassOutline, active: path.value.startsWith('/apps') || path.value.startsWith('/tabs/apps') },
  { label: '我的', href: '/my', icon: ioniconsPersonCircleOutline, active: ['/my', '/tabs/my'].includes(path.value) },
])
const kitchen = [
  { label: '我的收藏', href: '/recipes/favorites', icon: ioniconsStarOutline },
  { label: '历史记录', href: '/recipes/history', icon: ioniconsTimeOutline },
  { label: '食材和厨具', href: '/catalog', icon: ioniconsGridOutline },
]

function navigate(event: MouseEvent, href: string) {
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0)
    return
  event.preventDefault()
  if (path.value === href || (href === '/home' && navigation.value[0]?.active)) {
    const content = [...document.querySelectorAll<HTMLIonContentElement>('.cook-main-outlet .ion-page > ion-content')]
      .find(element => !element.closest('.ion-page-hidden, .ion-page-invisible'))
    void content?.scrollToTop(0)
    return
  }
  ionRouter.navigate(href, 'root', 'push')
}
</script>

<template>
  <aside class="desktop-sidebar" :class="{ 'is-collapsed': collapsed }" aria-label="应用侧栏">
    <div class="desktop-sidebar-brand">
      <img src="/brand/cook-app-icon.svg" width="36" height="36" alt="">
      <span class="desktop-sidebar-brand-copy"><strong>食用手册</strong><span>今天做点什么？</span></span>
    </div>

    <nav class="desktop-sidebar-navigation" aria-label="主导航">
      <a
        v-for="item in navigation"
        :key="item.href"
        class="desktop-sidebar-item"
        :href="item.href"
        :title="collapsed ? item.label : undefined"
        :aria-current="item.active ? 'page' : undefined"
        :aria-label="collapsed ? item.label : undefined"
        @click="navigate($event, item.href)"
      >
        <ion-icon :icon="item.icon" aria-hidden="true" />
        <span class="desktop-sidebar-label">{{ item.label }}</span>
      </a>

      <h2 class="desktop-sidebar-group">
        我的厨房
      </h2>
      <a
        v-for="item in kitchen"
        :key="item.href"
        class="desktop-sidebar-item"
        :href="item.href"
        :title="collapsed ? item.label : undefined"
        :aria-current="path === item.href ? 'page' : undefined"
        :aria-label="collapsed ? item.label : undefined"
        @click="navigate($event, item.href)"
      >
        <ion-icon :icon="item.icon" aria-hidden="true" />
        <span class="desktop-sidebar-label">{{ item.label }}</span>
      </a>
    </nav>

    <div class="desktop-sidebar-footer">
      <a class="desktop-sidebar-item" href="/settings" :title="collapsed ? '设置' : undefined" :aria-label="collapsed ? '设置' : undefined" :aria-current="path === '/settings' ? 'page' : undefined" @click="navigate($event, '/settings')">
        <ion-icon :icon="ioniconsSettingsOutline" aria-hidden="true" />
        <span class="desktop-sidebar-label">设置</span>
      </a>
      <LegacyVersionLink :title="collapsed ? '切换到旧版' : undefined" aria-label="切换到旧版" />
      <button class="desktop-sidebar-toggle" type="button" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" :title="collapsed ? '展开侧栏' : '收起侧栏'" :aria-expanded="!collapsed" @click="collapsed = !collapsed">
        <ion-icon :icon="collapsed ? ioniconsChevronForwardOutline : ioniconsChevronBackOutline" aria-hidden="true" />
        <span class="desktop-sidebar-label">收起侧栏</span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.desktop-sidebar {
  display: none;
}
@media (min-width: 1024px) {
  .desktop-sidebar {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 10;
    display: flex;
    flex-direction: column;
    box-sizing: border-box;
    width: var(--cook-sidebar-width);
    padding: 20px 12px 12px;
    border-inline-end: 1px solid var(--ion-item-border-color, rgba(128, 128, 128, 0.2));
    background: var(--ion-background-color, #f2f2f6);
    color: var(--ion-text-color, #1c1c1e);
  }
}
.desktop-sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  padding: 0 8px;
  margin-bottom: 28px;
}
.desktop-sidebar-brand img {
  flex: none;
  border-radius: 9px;
}
.desktop-sidebar-brand-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  white-space: nowrap;
}
.desktop-sidebar-brand-copy strong {
  font-size: 15px;
  font-weight: 650;
}
.desktop-sidebar-brand-copy > span {
  font-size: 11px;
  color: var(--ion-text-color-step-400, #6d6d72);
}
.desktop-sidebar-navigation {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-width: thin;
}
.desktop-sidebar-item,
.desktop-sidebar-toggle {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 36px;
  margin: 2px 0;
  padding: 8px 10px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: inherit;
  font-size: 14px;
  line-height: 20px;
  text-align: start;
  text-decoration: none;
  white-space: nowrap;
  cursor: pointer;
}
.desktop-sidebar-item ion-icon,
.desktop-sidebar-toggle ion-icon {
  flex: none;
  width: 18px;
  height: 18px;
  color: var(--ion-color-primary);
}
.desktop-sidebar-item[aria-current='page'] {
  background: var(--cook-fill);
  font-weight: 600;
}
.desktop-sidebar-item:hover,
.desktop-sidebar-toggle:hover {
  background: var(--cook-fill);
}
.desktop-sidebar-group {
  margin: 26px 10px 8px;
  color: var(--ion-text-color-step-400, #6d6d72);
  font-size: 11px;
  font-weight: 600;
}
.desktop-sidebar-footer {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--ion-item-border-color, rgba(128, 128, 128, 0.2));
}
.desktop-sidebar-toggle {
  color: var(--ion-text-color-step-400, #6d6d72);
  font-size: 12px;
}
.desktop-sidebar-toggle ion-icon {
  color: inherit;
  width: 14px;
  height: 14px;
}
.desktop-sidebar :is(a, button):focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: -2px;
}
.desktop-sidebar.is-collapsed {
  padding-inline: 8px;
}
.is-collapsed .desktop-sidebar-brand {
  justify-content: center;
  padding: 0;
}
.is-collapsed :is(.desktop-sidebar-brand-copy, .desktop-sidebar-label, .desktop-sidebar-group),
.is-collapsed :deep(.legacy-version-link > span) {
  display: none;
}
.is-collapsed .desktop-sidebar-navigation > a:nth-of-type(5) {
  margin-top: 28px;
}
.is-collapsed .desktop-sidebar-navigation {
  /* Keep the narrow rail scrollable without taking space from its icon buttons. */
  scrollbar-width: none;
}
.is-collapsed .desktop-sidebar-navigation::-webkit-scrollbar {
  display: none;
}
.is-collapsed :is(.desktop-sidebar-item, .desktop-sidebar-toggle),
.is-collapsed :deep(.legacy-version-link) {
  justify-content: center;
  width: 44px;
  height: 44px;
  min-height: 44px;
  margin: 2px auto;
  padding: 0;
  border-radius: 8px;
}
</style>
