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
      '~': './',
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
