<script lang="ts" setup>
import type { BottomMenuItem } from '@yunlefun/vue'

const items: BottomMenuItem[] = [
  {
    icon: 'i-ri-home-line',
    activeIcon: 'i-ri-home-fill',
    title: '首页',
    to: '/',
  },
  {
    icon: 'i-ri-compass-2-line',
    activeIcon: 'i-ri-compass-2-fill',
    title: '吃什么',
    to: '/random',
  },
  // {
  //   icon: 'i-ri-compass-2-line',
  //   activeIcon: 'i-ri-compass-2-fill',
  //   title: '吃什么',
  //   to: '/about',
  // },
  {
    icon: 'i-ri-question-line',
    activeIcon: 'i-ri-question-fill',
    title: '帮助',
    to: '/help',
  },
  {
    icon: 'i-ri-user-line',
    activeIcon: 'i-ri-user-fill',
    title: '我的',
    to: '/user',
  },
]

const route = useRoute()
const router = useRouter()

function onClick(item: BottomMenuItem) {
  // router.push(item.to || '/')
  router.replace(item.to || '/')
}
</script>

<template>
  <YlfBottomMenu shadow-2xl pb="$cook-bottom-menu-padding-bottom">
    <YlfBottomMenuItem
      v-for="item in items"
      :key="item.to"
      :item="item"
      :active="route.path === item.to"
      class="pt-3"
      @click="onClick"
    />
  </YlfBottomMenu>
</template>
