import process from 'node:process'
import { pwa } from './app/config/pwa'
import { appDescription } from './app/constants/index'

// for cloudflare
// Object.assign(process.env, {
//   VITE_COMMIT_REF: process.env.CF_PAGES_COMMIT_SHA || '',
// })

import { getLatestCommit } from './scripts/git'

const latestCommit = await getLatestCommit()
/**
 * CF_PAGES_COMMIT_SHA is Cloudflare Pages env
 */
import.meta.env.VITE_COMMIT_REF = process.env.CF_PAGES_COMMIT_SHA || latestCommit?.hash || ''
// add build date string to env
import.meta.env.VITE_APP_BUILD_DATE = latestCommit?.date || new Date().toString()

export default defineNuxtConfig({
  modules: [
    '@vueuse/nuxt',
    '@unocss/nuxt',
    '@pinia/nuxt',
    '@nuxtjs/color-mode',
    '@vite-pwa/nuxt',
    '@nuxt/eslint',
    '@nuxt/test-utils/module',
    '@yunlefun/vue/nuxt',
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
      viewport: 'width=device-width,initial-scale=1',
      link: [
        { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' },
        { rel: 'apple-touch-icon', href: '/apple-touch-icon.png' },
      ],
      meta: [
        { name: 'viewport', content: 'width=device-width, initial-scale=1, maximum-scale=1.0, user-scalable=no' },
        { name: 'description', content: appDescription },
        { name: 'apple-mobile-web-app-status-bar-style', content: 'black-translucent' },
        { name: 'color-scheme', content: 'light dark' },
        { name: 'theme-color', media: '(prefers-color-scheme: light)', content: 'white' },
        { name: 'theme-color', media: '(prefers-color-scheme: dark)', content: '#222222' },
      ],
    },
  },

  css: [
    '@unocss/reset/tailwind.css',
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
      routes: ['/', '/random', '/help', '/user', '/404', '/settings'],
      ignore: ['/hi'],
    },
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
      // utilities: true,
    },
    config: {
      mode: 'ios',
    },
  },

  pwa,

  /**
   * @see https://scripts.nuxt.com/scripts/tracking/google-tag-manager
   */
  scripts: {
    registry: {
      googleTagManager: {
        id: 'GTM-5FJSV46',
      },
    },
  },
})
