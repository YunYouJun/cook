import type { StuffItem } from '../../types.js'

/**
 * 素菜
 */
export const vegetable: StuffItem[] = [
  { name: '土豆', emoji: '🥔' },
  { name: '胡萝卜', emoji: '🥕' },
  { name: '花菜', emoji: '🥦' },
  { name: '白萝卜', emoji: '🥣' },
  { name: '西葫芦', emoji: '🥒' },
  { name: '番茄', emoji: '🍅', alias: '西红柿' },
  { name: '芹菜', emoji: '🥬' },
  { name: '黄瓜', emoji: '🥒' },
  { name: '洋葱', emoji: '🧅' },
  { name: '莴笋', emoji: '🎍' },
  { name: '菌菇', emoji: '🍄' },
  { name: '茄子', emoji: '🍆' },
  { name: '豆腐', emoji: '🍲' },
  { name: '包菜', emoji: '🥗' },
  { name: '白菜', emoji: '🥬' },
]

/**
 * 荤菜
 */
export const meat: StuffItem[] = [
  { name: '午餐肉', emoji: '🥓' },
  { name: '香肠', emoji: '🌭' },
  { name: '腊肠', emoji: '🌭' },
  { name: '鸡肉', emoji: '🐤' },
  { name: '猪肉', emoji: '🐷' },
  { name: '鸡蛋', emoji: '🥚' },
  { name: '虾', emoji: '🦐' },
  { name: '牛肉', emoji: '🐮' },
  { name: '骨头', emoji: '🦴' },
  { name: '鱼（Todo）', emoji: '🐟' },
]

/**
 * 主食
 */
export const staple: StuffItem[] = [
  { name: '面食', emoji: '🍝' },
  { name: '面包', emoji: '🍞' },
  { name: '米', emoji: '🍚' },
  { name: '方便面', emoji: '🍜' },
]

/**
 * 工具
 */
export const tools: StuffItem[] = [
  { name: '烤箱', emoji: '', icon: 'i-mdi-toaster-oven' },
  { name: '空气炸锅', emoji: '', icon: 'i-fe-frying-pan' },
  { name: '微波炉', emoji: '', icon: 'i-ic-outline-microwave' },
  { name: '电饭煲', emoji: '', icon: 'i-gg-smart-home-cooker' },
  { label: '一口能炒又能煮的大锅', name: '一口大锅', emoji: '', icon: 'i-mdi-pot-steam-outline' },
]

/**
 * 所有食材分类
 */
export const allCategories = { vegetable, meat, staple, tool: tools } as const

/**
 * 构建别名→标准名映射表
 * 例如: "西红柿" → "番茄", "番茄" → "番茄"
 */
export function buildAliasMap(): Map<string, string> {
  const map = new Map<string, string>()
  const allItems = [...vegetable, ...meat, ...staple, ...tools]
  for (const item of allItems) {
    map.set(item.name, item.name)
    if (item.alias) {
      for (const alias of item.alias.split('/')) {
        map.set(alias.trim(), item.name)
      }
    }
  }
  return map
}

/**
 * 将用户输入的食材名规范化为菜谱中使用的名称
 */
export function canonicalizeIngredient(name: string, aliasMap: Map<string, string>): string {
  return aliasMap.get(name) ?? name
}
