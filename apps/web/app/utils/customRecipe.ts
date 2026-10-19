import type { RecipeItem } from '~/types'
import { uniqueStuff } from '@yunyoujun/cook/recommend'

/**
 * 用户自己添加的菜谱：只存在本机，和收录的菜谱一起参与匹配和「吃什么」
 */
export interface CustomRecipe extends RecipeItem {
  customId: string
  /** 做法备注，没有视频时可以简单记几句 */
  note?: string
  createdAt: number
  updatedAt: number
}

export interface CustomRecipeInput {
  name: string
  stuff: string[]
  tools: string[]
  /** B 站链接、BV 号或任意做法链接 */
  source: string
  note: string
}

export const CUSTOM_RECIPE_NAME_MAX = 30

const BV_PATTERN = /(?:^|[^0-9a-z])(BV[0-9a-z]{10})(?![0-9a-z])/i
const URL_PATTERN = /^https?:\/\/\S+$/i

/** 识别做法来源：能找到 BV 号就按 B 站视频存，否则只接受 http(s) 链接 */
export function parseRecipeSource(text: string): Pick<RecipeItem, 'bv' | 'link'> | undefined {
  const value = text.trim()
  if (!value)
    return {}
  const bv = value.match(BV_PATTERN)?.[1]
  if (bv)
    return { bv: `BV${bv.slice(2)}` }
  if (URL_PATTERN.test(value))
    return { link: value }
  return undefined
}

export function isCustomRecipe(recipe: RecipeItem): recipe is CustomRecipe {
  return typeof (recipe as Partial<CustomRecipe>).customId === 'string'
}

export function createCustomRecipeId() {
  return `custom-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

/** 校验表单并生成菜谱；出错时返回给用户看的提示 */
export function buildCustomRecipe(
  input: CustomRecipeInput,
  existing?: Pick<CustomRecipe, 'customId' | 'createdAt'>,
  now = Date.now(),
): { recipe: CustomRecipe } | { error: string } {
  const name = input.name.trim()
  if (!name)
    return { error: '给菜起个名字吧' }
  if (name.length > CUSTOM_RECIPE_NAME_MAX)
    return { error: `菜名最多 ${CUSTOM_RECIPE_NAME_MAX} 个字` }
  const stuff = uniqueStuff(input.stuff)
  if (!stuff.length)
    return { error: '至少选一样食材，才能被匹配到' }
  const source = parseRecipeSource(input.source)
  if (!source)
    return { error: '做法链接需要是 B 站 BV 号或 http 开头的网址' }
  const note = input.note.trim()
  return {
    recipe: {
      customId: existing?.customId ?? createCustomRecipeId(),
      name,
      stuff,
      tools: [...new Set(input.tools)],
      ...source,
      ...(note ? { note } : {}),
      tags: [],
      createdAt: existing?.createdAt ?? now,
      updatedAt: now,
    },
  }
}
