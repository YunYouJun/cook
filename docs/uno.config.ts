import { defineConfig, presetAttributify, presetIcons, presetWind3, transformerDirectives, transformerVariantGroup } from 'unocss'

export default defineConfig({
  presets: [
    presetWind3(),
    presetAttributify(),
    presetIcons({ scale: 1.2 }),
  ],
  transformers: [
    transformerDirectives(),
    transformerVariantGroup(),
  ],
})
