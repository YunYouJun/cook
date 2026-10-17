import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts', 'packages/**/src/**/*.test.ts'],
    environment: 'jsdom',
    server: {
      deps: {
        inline: ['@vue', '@vueuse', 'vue-demi'],
      },
    },

    setupFiles: ['test/setup.ts'],

    alias: {
      '~': fileURLToPath(new URL('./app', import.meta.url)),
      '@yunyoujun/cook/recommend': fileURLToPath(new URL('./packages/cook/src/utils/recommend.ts', import.meta.url)),
    },

    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      include: ['app/**/*.{ts,vue}', 'packages/**/src/**/*.ts'],
      exclude: [
        'test/**',
        '**/*.test.ts',
        '**/*.d.ts',
        '**/types.ts',
        '**/*.config.ts',
      ],
    },
  },
})
