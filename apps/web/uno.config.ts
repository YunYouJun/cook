import { readFileSync } from 'node:fs'
import {
  defineConfig,
  presetAttributify,
  presetIcons,
  presetTypography,
  presetWind3,
  transformerDirectives,
  transformerVariantGroup,
} from 'unocss'

import { methodIcons, toolIcons } from './app/data/cookingIcons'
import { tools } from './app/data/food'

const cookingIconClasses = [...new Set([...Object.values(methodIcons), ...Object.values(toolIcons)])]
const cookingIcons = Object.fromEntries(cookingIconClasses.map((className) => {
  const name = className.slice('i-cook-'.length)
  return [name, readFileSync(new URL(`../../packages/shared/Assets.xcassets/${name}.imageset/icon.svg`, import.meta.url), 'utf8')
    .replaceAll('stroke="#000"', 'stroke="currentColor"')]
}))

const safelist: string[] = ['text-left', ...cookingIconClasses]

tools.forEach((item) => {
  if (item.icon)
    safelist.push(item.icon)
})

export default defineConfig({
  shortcuts: [
    ['tag', 'text-sm cursor-pointer inline-flex justify-center items-center transition shadow hover:shadow-md'],
    ['pureTag', 'text-sm inline-flex justify-center items-center transition shadow'],
    ['btn', 'text-sm px-4 py-1 rounded inline-block bg-blue-600 text-white cursor-pointer hover:bg-blue-700 disabled:cursor-default disabled:bg-gray-600 disabled:opacity-50'],
  ],
  presets: [
    // wind4 :host style override bug, so use wind3
    presetWind3(),
    presetAttributify(),
    presetIcons({
      scale: 1.2,
      collections: {
        cook: {
          ...cookingIcons,
          // Web 用当前文字颜色生成遮罩，iOS 则由资产目录的模板模式控制颜色。
          wechat: readFileSync(new URL('../../packages/shared/Assets.xcassets/wechat.imageset/icon.svg', import.meta.url), 'utf8')
            .replace('<svg ', '<svg fill="currentColor" '),
        },
      },
    }),
    presetTypography(),
  ],
  transformers: [
    transformerDirectives(),
    transformerVariantGroup(),
  ],
  safelist,
})
