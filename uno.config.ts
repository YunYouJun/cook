import {
  defineConfig,
  presetAttributify,
  presetIcons,
  presetTypography,
  presetUno,
  transformerDirectives,
  transformerVariantGroup,
} from 'unocss'

import { tools } from './app/data/food'

const safelist: string[] = ['text-left']

tools.forEach((item) => {
  if (item.icon)
    safelist.push(item.icon)
})

export default defineConfig({
  shortcuts: [
    ['tag', 'text-sm cursor-pointer inline-flex justify-center items-center transition shadow hover:shadow-md'],
    ['btn', 'text-sm px-4 py-1 rounded inline-block bg-blue-600 text-white cursor-pointer hover:bg-blue-700 disabled:cursor-default disabled:bg-gray-600 disabled:opacity-50'],
  ],
  presets: [
    presetUno(),
    presetAttributify(),
    presetIcons({
      scale: 1.2,
    }),
    presetTypography(),
  ],
  transformers: [
    transformerDirectives(),
    transformerVariantGroup(),
  ],
  safelist,
})
