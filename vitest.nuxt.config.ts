import path from 'node:path'
import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  resolve: {
    alias: {
      '~/': path.resolve(__dirname, './'),
    },
  },

  test: {
    include: ['test/**/*.test.ts'],
    environment: 'nuxt',

    setupFiles: ['test/setup.ts'],
  },
})
