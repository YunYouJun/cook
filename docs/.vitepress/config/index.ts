import type { HeadConfig, MarkdownRenderer } from 'vitepress'
import { createRequire } from 'node:module'
import { transformerTwoslash } from '@shikijs/vitepress-twoslash'
import { createFileSystemTypesCache } from '@shikijs/vitepress-twoslash/cache-fs'
import { defineConfig } from 'vitepress'
import { groupIconMdPlugin } from 'vitepress-plugin-group-icons'
import { withYunlefun, yunlefunMarkdown, zhThemeConfig } from 'vitepress-theme-yunlefun/config'
import { isNonIndexableProductPage, productPagePath, publicProductSitemapItems } from '../theme/indexing.ts'
import { guideArticleJsonLd, serializeJsonLd, serializeProductJsonLd } from '../theme/structured-data.ts'

const repo = 'https://github.com/YunYouJun/cook'
const brandIcon = '/brand/cook-favicon.svg'
const taskLists: (md: MarkdownRenderer) => void = createRequire(import.meta.url)('markdown-it-task-lists')
const noindex: HeadConfig = ['meta', { name: 'robots', content: 'noindex' }]

export default defineConfig(withYunlefun({
  lang: 'zh-CN',
  title: '云食册',
  cleanUrls: true,
  description: '有什么食材，就做什么菜。云食册产品官网、App Store 下载入口与食用手册使用文档。',
  lastUpdated: true,
  ignoreDeadLinks: 'localhostLinks',
  srcExclude: ['public/**/*.md'],
  head: [
    ['meta', { name: 'author', content: 'YunYouJun' }],
    ['meta', { name: 'apple-itunes-app', content: 'app-id=6824589542' }],
    ['link', { rel: 'icon', href: '/favicon.ico?v=2', sizes: 'any' }],
    ['link', { rel: 'icon', href: brandIcon, type: 'image/svg+xml', sizes: 'any' }],
  ],
  sitemap: {
    hostname: 'https://cook.yunle.fun',
    transformItems: items => publicProductSitemapItems(items),
  },
  transformHead({ page }) {
    // VitePress's built-in 404 does not pass through transformPageData.
    if (page === '404.md')
      return [noindex]
  },
  transformPageData(pageData) {
    const path = productPagePath(pageData.relativePath)
    pageData.frontmatter.head ??= []
    pageData.frontmatter.head.push(['link', { rel: 'canonical', href: `https://cook.yunle.fun/${path}` }])
    // Keep robots metadata in the page data so it follows client navigation.
    if (pageData.relativePath !== '404.md' && isNonIndexableProductPage(pageData.relativePath))
      pageData.frontmatter.head.push(noindex)
    if (pageData.relativePath.startsWith('guide/')) {
      const description = typeof pageData.frontmatter.description === 'string' ? pageData.frontmatter.description : ''
      const frontmatterTitle = typeof pageData.frontmatter.title === 'string' ? pageData.frontmatter.title : ''
      pageData.frontmatter.head.push([
        'script',
        { type: 'application/ld+json' },
        serializeJsonLd(guideArticleJsonLd({
          title: frontmatterTitle || pageData.title || '云食册使用指南',
          description,
          path,
        })),
      ])
    }
    if (pageData.relativePath !== 'index.md')
      return
    const description = typeof pageData.frontmatter.description === 'string' ? pageData.frontmatter.description : ''
    const image = 'https://cook.yunle.fun/ios/light-2-cook-selected.webp'
    const title = '云食册 · 食用手册｜有什么食材，就做什么菜'
    pageData.frontmatter.head.push(
      ['meta', { property: 'og:type', content: 'website' }],
      ['meta', { property: 'og:locale', content: 'zh_CN' }],
      ['meta', { property: 'og:site_name', content: '云食册' }],
      ['meta', { property: 'og:title', content: title }],
      ['meta', { property: 'og:description', content: description }],
      ['meta', { property: 'og:url', content: 'https://cook.yunle.fun/' }],
      ['meta', { property: 'og:image', content: image }],
      ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
      ['meta', { name: 'twitter:title', content: title }],
      ['meta', { name: 'twitter:description', content: description }],
      ['meta', { name: 'twitter:image', content: image }],
      ['script', { type: 'application/ld+json' }, serializeProductJsonLd()],
    )
  },
  markdown: {
    config(md) {
      md.use(taskLists)
      md.use(groupIconMdPlugin, { titleBar: { includeSnippet: true } })
      md.use(yunlefunMarkdown)
    },
    codeTransformers: [transformerTwoslash({ typesCache: createFileSystemTypesCache() })],
    languages: ['ts'],
    lineNumbers: true,
  },

  themeConfig: {
    ...zhThemeConfig,
    logo: brandIcon,
    editLink: {
      pattern: `${repo}/edit/main/docs/:path`,
      text: '在 GitHub 上编辑此页',
    },
    socialLinks: [{ icon: 'github', link: repo }],

    footer: {
      message: '运营主体：海州区云乐坊信息技术工作室<br><a href="/terms">用户协议</a> · <a href="/privacy">iOS 隐私政策</a> · <a href="/privacy-android">Android 隐私政策</a> · <a href="mailto:me@yunyoujun.cn">联系开发者</a>',
      copyright: '© 2022–2026 云游君（YunYouJun） · <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">苏ICP备2023020936号</a>',
    },
    nav: [
      { text: '产品官网', link: '/' },
      { text: '使用指南', link: '/guide/getting-started' },
      { text: 'App Store 下载', link: 'https://apps.apple.com/app/id6824589542' },
      { text: '开发文档', link: '/dev/cli' },
      { text: '方案记录', link: '/decisions/' },
      { text: 'iOS 隐私政策', link: '/privacy' },
      { text: '打开网页版', link: 'https://cook.yunyoujun.cn/', target: '_self' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: '使用指南',
          items: [
            { text: '快速开始', link: '/guide/getting-started' },
            { text: 'Web 与 iOS 的定位', link: '/guide/web-and-ios' },
            { text: '一句话找菜', link: '/guide/sentence-input' },
            { text: '第二天带饭', link: '/guide/next-day-lunch' },
            { text: '网页版域名与原有数据', link: '/guide/web-domain' },
            { text: '云食册 iOS 测试安装', link: '/guide/ios-beta#testflight' },
          ],
        },
      ],
      '/dev/': [
        {
          text: '开发文档',
          items: [
            { text: 'Cook CLI', link: '/dev/cli' },
            { text: '文档站部署', link: '/dev/docs-site' },
            { text: 'Docker 部署', link: '/dev/docker' },
            { text: '移动应用', link: '/dev/app' },
            { text: '原生 Android 开发计划', link: '/dev/android-roadmap' },
            { text: 'TestFlight 外部测试', link: '/dev/ios-external-testflight' },
            { text: '外测自动化与反馈', link: '/dev/testflight-automation' },
            { text: '菜谱数据', link: '/dev/data' },
            { text: '私人菜谱与文件迁移', link: '/dev/private-recipes' },
            { text: '图文做法配图', link: '/dev/recipe-images' },
            { text: '食材模型展柜', link: '/dev/ingredient-models' },
            { text: '云厨房实现', link: '/dev/cloud-kitchen' },
            { text: 'iOS App 打磨目标', link: '/dev/ios-roadmap' },
            { text: 'iOS 步骤讲解与提问', link: '/dev/ios-step-guidance' },
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
            { text: '0013 私人菜谱与文件迁移', link: '/decisions/0013-private-recipes' },
            { text: '0014 个人食材和厨具', link: '/decisions/0014-personal-catalog' },
            { text: '0015 官网与网页版域名分工', link: '/decisions/0015-product-site-domains' },
            { text: '0016 原生 Android 与外壳退役', link: '/decisions/0016-native-android-and-capacitor-retirement' },
          ],
        },
      ],
    },
  },
}))
