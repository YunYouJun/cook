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
        // 构建、验收和审查报告保留原句与机器输出；数据源另有 schema/锚点校验。
        'artifacts/**',
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
