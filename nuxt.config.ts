import process from 'node:process'
import { pwa } from './app/config/pwa'
import { appDescription } from './app/constants/index'

Object.assign(process.env, {
  VITE_COMMIT_REF: process.env.CF_PAGES_COMMIT_SHA || '',
})

// add build time to env
import.meta.env.VITE_APP_BUILD_TIME = new Date().getTime().toString()
export default defineNuxtConfig({
  ssr: false,

  modules: [
    '@vueuse/nuxt',
    '@unocss/nuxt',
    '@pinia/nuxt',

    '@nuxt/test-utils/module',
    '@nuxt/eslint',
    '@nuxtjs/color-mode',
    '@vite-pwa/nuxt',

    '@zadigetvoltaire/nuxt-gtm',

    '@yunlefun/vue/nuxt',

    // fix QQ in iOS
    // See https://github.com/unjs/ofetch/pull/366
    'nuxt-fix-ofetch',
  ],

  experimental: {
    // when using generate, payload js assets included in sw precache manifest
    // but missing on offline, disabling extraction it until fixed
    payloadExtraction: false,
    renderJsonPayloads: true,
    typedPages: true,
  },

  css: [
    '@unocss/reset/tailwind.css',
    '~/styles/css-vars.scss',
    '~/styles/index.scss',
  ],

  colorMode: {
    classSuffix: '',
  },

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
        { name: 'theme-color', media: '(prefers-color-scheme: light)', content: 'white' },
        { name: 'theme-color', media: '(prefers-color-scheme: dark)', content: '#222222' },
      ],
    },
  },

  components: [
    { path: '~/components', pathPrefix: false },
  ],

  gtm: {
    id: 'GTM-5FJSV46',
  },

  pwa,

  devtools: {
    enabled: true,
  },

  features: {
    // For UnoCSS
    inlineStyles: false,
  },

  eslint: {
    config: {
      standalone: false,
    },
  },

  future: {
    compatibilityVersion: 4,
  },

  compatibilityDate: '2024-08-14',
})
