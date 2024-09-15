import type { Recipes } from './recipe'

export interface Cookbook {
  /**
   * 菜谱 ID，自定义，唯一标识符
   */
  id: string
  cover?: string
  /**
   * 菜谱名称
   */
  title: string
  description: string
  author: string | string[]
  /**
   * 菜谱
   */
  recipes: Recipes

  createdAt: string
  updatedAt: string
}
