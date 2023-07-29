<script lang="ts" setup>
import VueAboutMe from 'vue-about-me'
import 'vue-about-me/style.css'

const color = useColorMode()
const isDark = computed(() => color.value === 'dark')

const copyright = {
  name: 'Cook',
  repo: 'YunYouJun/cook',
  color: '#0078E7',
  iconUrl: 'https://sponsors.yunyoujun.cn',
  author: '云游君',
  authorUrl: 'https://www.yunyoujun.cn',
}

const links = [
  {
    type: 'github',
    label: 'GitHub: YunYouJun',
    href: 'https://github.com/YunYouJun',
  },
  {
    type: 'telegram',
    label: 'Telegram Channel',
    href: 'https://t.me/elpsycn',
  },
  {
    type: 'weibo',
    label: '微博：机智的云游君',
    href: 'http://weibo.com/jizhideyunyoujun',
  },
  {
    type: 'twitter',
    label: 'Twitter: YunYouJun',
    href: 'https://twitter.com/YunYouJun',
  },
  {
    type: 'wechat',
    label: '微信公众号：云游君',
    href: '/wechat',
    target: '_self',
  },
  {
    type: 'bilibili',
    label: '云游君Official',
    href: 'https://space.bilibili.com/1579790',
  },
  {
    type: 'blog',
    label: '博客：yunyoujun.cn',
    href: 'http://www.yunyoujun.cn',
  },
]
</script>

<template>
  <VueAboutMe :is-dark="isDark" :copyright="copyright" :links="links" />
</template>
