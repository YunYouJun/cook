import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'nuxt',

    setupFiles: ['test/setup.ts'],
  },
})
