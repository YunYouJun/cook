import {
  defineConfig,
  presetAttributify,
  presetIcons,
  presetTypography,
  presetUno,
  // presetWebFonts,
  transformerDirectives,
  transformerVariantGroup,
} from 'unocss'

import { tools } from './src/data/food'

const safelist: string[] = []

tools.forEach((item) => {
  if (item.icon)
    safelist.push(item.icon)
})

export default defineConfig({
  shortcuts: [
    ['tag', 'text-sm cursor-pointer inline-flex justify-center items-center transition shadow hover:shadow-md'],
    ['btn', 'text-sm px-4 py-1 rounded inline-block bg-green-600 text-white cursor-pointer hover:bg-green-700 disabled:cursor-default disabled:bg-gray-600 disabled:opacity-50'],
    ['icon-btn', 'text-[0.9em] inline-block cursor-pointer select-none opacity-75 transition duration-200 ease-in-out hover:opacity-100 hover:text-green-600'],
  ],
  presets: [
    presetUno(),
    presetAttributify(),
    presetIcons({
      scale: 1.2,
      warn: true,
    }),
    presetTypography(),
    // simplify size
    // presetWebFonts({
    //   fonts: {
    //     serif: [
    //       {
    //         name: 'Noto Serif SC',
    //         weights: [900],
    //       },
    //     ],
    //   },
    // }),
  ],
  transformers: [
    transformerDirectives(),
    transformerVariantGroup(),
  ],
  safelist,
})
