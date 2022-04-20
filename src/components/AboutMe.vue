<script lang="ts" setup>
import VueAboutMe from 'vue-about-me'
import 'vue-about-me/style.css'
import { isDark } from '~/composables'

const copyright = {
  name: 'Cook',
  repo: 'YunYouJun/cook',
  color: '#0078E7',
  iconUrl: 'https://sponsors.yunyoujun.cn',
  author: '云游君',
  authorUrl: 'https://www.yunyoujun.cn',
}
</script>

<template>
  <vue-about-me :is-dark="isDark" :copyright="copyright" />
</template>
