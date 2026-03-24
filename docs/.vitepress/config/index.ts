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

    nav: [
      { text: '使用指南', link: '/guide/getting-started' },
      { text: '开发文档', link: '/dev/cli' },
      { text: '应用', link: 'https://cook.yunyoujun.cn' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: '使用指南',
          items: [
            { text: '快速开始', link: '/guide/getting-started' },
          ],
        },
      ],
      '/dev/': [
        {
          text: '开发文档',
          items: [
            { text: 'Cook CLI', link: '/dev/cli' },
            { text: '移动应用', link: '/dev/app' },
          ],
        },
      ],
    },
  },
})
