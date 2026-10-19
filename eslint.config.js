// @ts-check
import antfu from '@antfu/eslint-config'
import nuxt from './apps/web/.nuxt/eslint.config.mjs'

export default nuxt(
  antfu(
    {
      unocss: true,
      formatters: true,
    },
    {
      ignores: [
        'packages/data/**/*.json',
        'ios/**/*',
        'android/**/*',
        'apps/web/public/**/*',
        'node_modules/**/*',
        '**/.nuxt/**/*',
        '**/.output/**/*',
        '**/dist/**/*',
      ],
    },
    {
      rules: {
        'vue/no-deprecated-slot-attribute': 'off',
      },
    },
  ),
)
