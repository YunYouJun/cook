import type { StuffItem } from '../types'
import food from '../../../../packages/data/food.json'

// 词典本身在 packages/data/food.json，Web 和 iOS 共用；这里只补上类型
export const vegetable: StuffItem[] = food.vegetable
export const meat: StuffItem[] = food.meat
export const staple: StuffItem[] = food.staple
export const tools: StuffItem[] = food.tools
