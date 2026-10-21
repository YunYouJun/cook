export type Difficulty = '简单' | '普通' | '困难'

export interface RecipeItem {
  /**
   * 固定编号，对应 recipe.csv 的 id 列；收藏和反馈都按它记录
   */
  id?: number
  /**
   * 菜名
   */
  name: string
  /**
   * 图文做法链接；自己添加的菜谱也用它记录非 B 站的做法链接
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
  /**
   * 视频质量分 0～100，`pnpm convert` 按 packages/data/video-stats.json 算好写入；
   * 没有时按 DEFAULT_RECIPE_SCORE。只在缺的食材、用上的食材都一样时决定先后
   */
  score?: number
}

export type Recipes = RecipeItem[]

export interface StuffItem {
  /**
   * 食材名称
   */
  name: string
  /**
   * 例如:🥔
   */
  emoji: string
  /**
   * 图片链接
   */
  image?: string
  /**
   * 别名,譬如:西红柿/番茄
   */
  alias?: string
  /**
   * 图标名称
   */
  icon?: string
  /**
   * 显示标签
   */
  label?: string
  /**
   * 不常用：选择页默认收进「更多」，已选中时照常显示
   */
  more?: boolean
}
