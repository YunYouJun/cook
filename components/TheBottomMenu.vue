<script lang="ts" setup>
import type { BottomMenuItem } from '@yunlefun/vue'
import { ref } from 'vue'

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

const active = ref(route.path)
function onClick(item: BottomMenuItem) {
  active.value = item.to || ''
  router.push(item.to || '/')
}
</script>

<template>
  <YlfBottomMenu shadow-2xl>
    <YlfBottomMenuItem
      v-for="item in items"
      :key="item.to"
      :item="item"
      :active="active === item.to"
      @click="onClick"
    />
  </YlfBottomMenu>
</template>
