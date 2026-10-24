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
      { text: '方案记录', link: '/decisions/' },
      { text: 'iOS 隐私政策', link: '/privacy' },
      { text: '应用', link: 'https://cook.yunyoujun.cn' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: '使用指南',
          items: [
            { text: '快速开始', link: '/guide/getting-started' },
            { text: '一句话找菜', link: '/guide/sentence-input' },
          ],
        },
      ],
      '/dev/': [
        {
          text: '开发文档',
          items: [
            { text: 'Cook CLI', link: '/dev/cli' },
            { text: '移动应用', link: '/dev/app' },
            { text: '菜谱数据', link: '/dev/data' },
            { text: '私人菜谱与文件迁移', link: '/dev/private-recipes' },
            { text: '图文做法配图', link: '/dev/recipe-images' },
            { text: 'iOS App 打磨目标', link: '/dev/ios-roadmap' },
          ],
        },
      ],
      '/decisions/': [
        {
          text: '方案记录',
          items: [
            { text: '说明', link: '/decisions/' },
            { text: '0001 菜谱数据', link: '/decisions/0001-recipe-data' },
            { text: '0002 反馈与固定 id', link: '/decisions/0002-feedback-and-ids' },
            { text: '0003 原生 iOS 与 monorepo', link: '/decisions/0003-native-ios-monorepo' },
            { text: '0004 iOS App 命名', link: '/decisions/0004-ios-app-name' },
            { text: '0005 应用图标', link: '/decisions/0005-app-icon' },
            { text: '0006 iOS Bundle ID', link: '/decisions/0006-ios-bundle-id' },
            { text: '0007 AI 与整句识别', link: '/decisions/0007-ai-and-sentence-input' },
            { text: '0008 iOS 统计与反馈', link: '/decisions/0008-ios-analytics' },
            { text: '0009 图文做法', link: '/decisions/0009-text-recipes' },
            { text: '0010 视频质量分', link: '/decisions/0010-recipe-quality-score' },
            { text: '0011 冰箱与临期提醒', link: '/decisions/0011-fridge-reminders' },
            { text: '0012 反馈渠道分流', link: '/decisions/0012-feedback-channels' },
          ],
        },
      ],
    },
  },
})
