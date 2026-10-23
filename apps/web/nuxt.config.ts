import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { defineNuxtConfig } from 'nuxt/config'
import { simpleGit } from 'simple-git'

// import { pwa } from './app/config/pwa'
import { appDescription, appStoreId } from './app/constants/index'

// for cloudflare
// Object.assign(process.env, {
//   VITE_COMMIT_REF: process.env.CF_PAGES_COMMIT_SHA || '',
// })

/**
 * Get git repo latest commit
 */
async function getLatestCommit() {
  const git = simpleGit()
  try {
    const log = await git.log({ maxCount: 1 })
    return log.latest
  }
  catch (error) {
    console.error('Error fetching latest commit:', error)
    return null
  }
}

try {
  const latestCommit = await getLatestCommit()
  /**
   * CF_PAGES_COMMIT_SHA is Cloudflare Pages env
   */
  process.env.VITE_COMMIT_REF = process.env.CF_PAGES_COMMIT_SHA || latestCommit?.hash || ''
  // add build date string to env
  process.env.VITE_APP_BUILD_DATE = latestCommit?.date || new Date().toString()
}
catch (e) {
  console.error('Not in git repo, get latest commit failed:', e)
  process.env.VITE_APP_BUILD_DATE = new Date().toString()
  process.env.VITE_COMMIT_REF = ''
}

/**
 * @ionic/core 没有声明 sideEffects，@ionic/vue 引用的每个组件模块即使没用到也会被打进首屏 chunk。
 * 组件模块只在调用 defineCustomElement 时才注册元素，标记为无副作用后，未使用的组件可以被摇树移除。
 */
const IONIC_COMPONENT_MODULE_RE = /[\\/]@ionic[\\/]core[\\/]components[\\/]ion-[\w-]+\.js$/

export default defineNuxtConfig({
  modules: [
    '@vueuse/nuxt',
    '@unocss/nuxt',
    '@pinia/nuxt',
    '@nuxtjs/color-mode',
    // '@vite-pwa/nuxt',
    '@nuxt/eslint',
    '@nuxt/test-utils/module',
    // fix QQ in iOS, Done
    // See https://github.com/unjs/ofetch/pull/366
    // 'nuxt-fix-ofetch',
    '@nuxt/scripts',
    '@nuxtjs/ionic',
  ],
  ssr: false,

  components: [
    { path: '~/components', pathPrefix: false },
  ],

  devtools: {
    enabled: true,
  },

  app: {
    head: {
      // Mask replay text, including account details and user-created recipes.
      bodyAttrs: { 'data-clarity-mask': 'true' },
      viewport: 'width=device-width,initial-scale=1',
      link: [
        { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' },
        { rel: 'apple-touch-icon', href: '/apple-touch-icon.png' },
      ],
      meta: [
        { name: 'viewport', content: 'width=device-width, initial-scale=1, maximum-scale=1.0, user-scalable=no, viewport-fit=cover' },
        { name: 'description', content: appDescription },
        { name: 'apple-mobile-web-app-status-bar-style', content: 'black-translucent' },
        { name: 'color-scheme', content: 'light dark' },
        // 与 iOS 模式的页面 / 导航栏背景一致
        { name: 'theme-color', media: '(prefers-color-scheme: light)', content: '#f2f2f6' },
        { name: 'theme-color', media: '(prefers-color-scheme: dark)', content: '#000000' },
        // Safari 顶部的「在 App Store 中打开」横幅，需要写在静态 HTML 里才会生效
        ...(appStoreId ? [{ name: 'apple-itunes-app', content: `app-id=${appStoreId}` }] : []),
      ],
      script: [
        // 系统开启「减弱动态效果」时关闭 Ionic 的转场动画；需要在 Ionic 初始化前写入 window.Ionic.config
        { innerHTML: 'try{if(matchMedia("(prefers-reduced-motion: reduce)").matches)window.Ionic={config:{animated:false}}}catch(e){}' },
      ],
    },
  },

  css: [
    '@unocss/reset/tailwind.css',
    '@yunlefun/ui/css',
    '~/styles/css-vars.scss',
    '~/styles/index.scss',
    '~/styles/index.ts',
  ],

  router: {
    options: {
      scrollBehaviorType: 'smooth',
    },
  },

  colorMode: {
    classSuffix: '',
  },
  spaLoadingTemplate: 'spa-loading-template.html',
  runtimeConfig: {
    public: {
      clarityProjectId: process.env.NUXT_PUBLIC_CLARITY_PROJECT_ID ?? 'ks1ebw03i5',
      clarityDomains: process.env.NUXT_PUBLIC_CLARITY_DOMAINS ?? 'cook.yunyoujun.cn,cook.yunle.fun',
      yunlefunCloudbaseEnv: process.env.NUXT_PUBLIC_YUNLEFUN_CLOUDBASE_ENV || 'yunlefun-8g7ybcxc7345c490',
      yunlefunSsoClientId: process.env.NUXT_PUBLIC_YUNLEFUN_SSO_CLIENT_ID || 'cook-web',
      yunlefunSsoExchangeUrl: process.env.NUXT_PUBLIC_YUNLEFUN_SSO_EXCHANGE_URL || 'https://api.yunle.fun/sso-ticket',
      yunlefunSsoNativeClientId: process.env.NUXT_PUBLIC_YUNLEFUN_SSO_NATIVE_CLIENT_ID || 'cook-mobile',
      yunlefunSsoNativeRedirectUri: process.env.NUXT_PUBLIC_YUNLEFUN_SSO_NATIVE_REDIRECT_URI || 'https://cook.yunyoujun.cn/auth/callback?platform=native',
      yunlefunSsoOrigin: process.env.NUXT_PUBLIC_YUNLEFUN_SSO_ORIGIN || 'https://www.yunle.fun',
      yunlefunSsoRedirectUri: process.env.NUXT_PUBLIC_YUNLEFUN_SSO_REDIRECT_URI || 'https://cook.yunyoujun.cn/auth/callback',
      yunlefunSsoRedirectUris: process.env.NUXT_PUBLIC_YUNLEFUN_SSO_REDIRECT_URIS || [
        process.env.NUXT_PUBLIC_YUNLEFUN_SSO_REDIRECT_URI || 'https://cook.yunyoujun.cn/auth/callback',
        'https://cook.yunle.fun/auth/callback',
      ].join(','),
      yunlefunSsoScope: process.env.NUXT_PUBLIC_YUNLEFUN_SSO_SCOPE || 'identity:bootstrap',
    },
  },

  alias: {
    '@cook/data': fileURLToPath(new URL('../../packages/data', import.meta.url)),
    '@cook/types': fileURLToPath(new URL('../../packages/cook/src/types', import.meta.url)),
    // Use browser-safe source in the workspace, without requiring a package build first.
    '@yunyoujun/cook/recommend': fileURLToPath(new URL('../../packages/cook/src/utils/recommend.ts', import.meta.url)),
  },

  future: {
    compatibilityVersion: 4,
  },

  experimental: {
    // when using generate, payload js assets included in sw precache manifest
    // but missing on offline, disabling extraction it until fixed
    payloadExtraction: false,
    renderJsonPayloads: true,
    typedPages: true,
  },
  compatibilityDate: '2025-05-15',

  nitro: {
    esbuild: {
      options: {
        target: 'esnext',
      },
    },
    prerender: {
      crawlLinks: false,
      routes: ['/', '/random', '/help', '/feedback', '/user', '/404', '/settings', '/auth/callback'],
      ignore: ['/hi'],
    },
  },

  vite: {
    plugins: [
      {
        name: 'cook:ionic-components-tree-shaking',
        transform(_code, id) {
          if (IONIC_COMPONENT_MODULE_RE.test(id))
            return { moduleSideEffects: false }
        },
      },
    ],
  },

  eslint: {
    config: {
      standalone: false,
      nuxt: {
        sortConfigKeys: true,
      },
    },
  },

  ionic: {
    css: {
      core: true,
      basic: true,
      utilities: true,
    },
    config: {
      mode: 'ios',
      backButtonText: '返回',
    },
  },

  // pwa,

})
