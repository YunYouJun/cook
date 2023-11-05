import { defineVitestConfig } from 'nuxt-vitest/config'

export default defineVitestConfig({
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'nuxt',

    setupFiles: ['test/setup.ts'],
  },
})
