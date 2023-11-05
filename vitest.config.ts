import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'jsdom',
    server: {
      deps: {
        inline: ['@vue', '@vueuse', 'vue-demi'],
      },
    },

    setupFiles: ['test/setup.ts'],

    alias: {
      '~': './',
    },
  },
})
