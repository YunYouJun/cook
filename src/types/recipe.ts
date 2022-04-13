export interface RecipeItem {
  /**
   * 菜名
   */
  name: string
  /**
   * 链接
   */
  link: string
  /**
   * 材料
   */
  stuff: string[]
  /**
   * 标签
   */
  tags: string[]
  /**
   * 方式
   */
  methods: string[]
  /**
   * 工具
   */
  tools: string[]
}

export type Recipe = RecipeItem[]
