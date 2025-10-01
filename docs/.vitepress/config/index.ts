import { getVitepressConfig } from '@yunyoujun/docs'
import { defineConfig } from 'vitepress'

const vpConfig = getVitepressConfig({
  repo: 'https://github.com/YunYouJun/cook',
})

export default defineConfig({
  ...vpConfig,
  title: 'Cook',
  description: '食用手册',

  themeConfig: {
    ...vpConfig.themeConfig,
  },
})
