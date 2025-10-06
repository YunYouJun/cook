// @ts-check
import antfu from '@antfu/eslint-config'
import nuxt from './.nuxt/eslint.config.mjs'

export default nuxt(
  antfu(
    {
      unocss: true,
      formatters: true,
    },
    {
      ignores: [
        'app/data/*.json',
        'ios/**/*',
        'android/**/*',
        'dist/**/*',
        'public/**/*',
        'node_modules/**/*',
        '.nuxt/**/*',
        '.output/**/*',
      ],
    },
    {
      rules: {
        'vue/no-deprecated-slot-attribute': 'off',
      },
    },
  ),
)
