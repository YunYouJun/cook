export type Difficulty = '简单' | '普通' | '困难'

export interface RecipeItem {
  /**
   * 菜名
   */
  name: string
  /**
   * 链接
   */
  link?: string
  /**
   * BiliBili video id
   */
  bv?: string
  /**
   * 材料
   */
  stuff: string[]
  /**
   * 根据材料生成
   */
  emojis?: string[]
  /**
   * 难度
   */
  difficulty?: Difficulty | ''
  /**
   * 标签
   */
  tags?: string[]
  /**
   * 方式
   */
  methods?: ('炒' | '煎' | '烘' | '炸')[]
  /**
   * 工具
   */
  tools: string[]
}

export type Recipes = RecipeItem[]
