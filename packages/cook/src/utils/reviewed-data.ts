import type { RecipeItem } from '../types/recipe'
import type { RecipeText } from '../types/recipe-text'

export const dietaryGoals = ['weight-aware', 'less-added-sugar', 'balanced-pairing'] as const
export type DietaryGoalID = typeof dietaryGoals[number]

export const addedSugarIngredients = ['糖', '白糖', '冰糖', '砂糖', '糖浆', '蜂蜜'] as const

export const institutionalLowGITag = '机构低 GI 食谱'
export const institutionalLowGIAuthorityURL = 'https://glycemicindex.com/2026/01/copyright-and-permission-105/'

export interface InstitutionalLowGIChineseReference {
  id: 'adult-diabetes-dietary-guide-2023' | 'ws-t-652-2019'
  publisher: '国家卫生健康委'
  title: string
  url: string
  scope: string
}

export const institutionalLowGIChineseReferences: InstitutionalLowGIChineseReference[] = [
  {
    id: 'adult-diabetes-dietary-guide-2023',
    publisher: '国家卫生健康委',
    title: '《成人糖尿病食养指南（2023年版）》问答',
    url: 'https://www.nhc.gov.cn/wjw/c100378/202301/7faed9993c9b45aea158c5335346f282/files/1732844468167_10535.pdf',
    scope: '用于核对 GI 的定义和膳食边界；不证明这些配方经过实测，也不代表适合个人疾病饮食。',
  },
  {
    id: 'ws-t-652-2019',
    publisher: '国家卫生健康委',
    title: '食物血糖生成指数测定方法（WS/T 652—2019）',
    url: 'https://www.nhc.gov.cn/fzs/c100048/201906/ae057c7a93744972bd7722077c91ca34/files/1733125684903_75354.pdf',
    scope: '规定食物 GI 的标准测定方法；不提供本批次具体配方的 GI 实测值。',
  },
]

function fingerprintTokens(tokens: string[]) {
  let hash = 14695981039346656037n
  for (const byte of new TextEncoder().encode(tokens.join('\0')))
    hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * 1099511628211n)
  return hash.toString(16).padStart(16, '0')
}

/** Bind reviewed metadata to recipe identity while ignoring display-only scores. */
export function dietaryRecipeFingerprint(recipe: RecipeItem): string {
  const tokens = [String(recipe.id), recipe.name, recipe.link ?? '', recipe.bv ?? '', recipe.difficulty ?? '']
  for (const values of [recipe.stuff, recipe.tools, recipe.methods ?? [], recipe.tags ?? []])
    tokens.push(String(values.length), ...values)
  return fingerprintTokens(tokens)
}

/** Fingerprint the exact localized preparation text used by the review. */
export function recipeTextFingerprint(source: RecipeText): string {
  const tokens = source.blocks.flatMap((block): string[] => {
    switch (block.type) {
      case 'heading':
      case 'paragraph':
        return [block.type, block.text]
      case 'list':
        return ['list', String(block.ordered), String(block.start ?? 1), String(block.items.length), ...block.items]
      case 'image':
        return ['image', block.src, String(block.width), String(block.height), block.alt ?? '']
      default:
        return []
    }
  })
  return fingerprintTokens(tokens)
}
