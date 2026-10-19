/**
 * 图文做法的正文（packages/data/recipe-text.json），App 离线排版用
 *
 * 文字里可以有加粗和外链（行内 Markdown），图片不收录。
 */
export type RecipeTextBlock
  = | { type: 'heading', text: string }
    | { type: 'paragraph', text: string }
    | RecipeTextList

/** 列表项里的换行是补充说明或子项 */
export interface RecipeTextList {
  type: 'list'
  ordered: boolean
  start?: number
  items: string[]
}

export interface RecipeText {
  blocks: RecipeTextBlock[]
  /** 原文里的配图数量，App 里提示去原文看 */
  images?: number
}

export interface RecipeTextData {
  /** 正文来源，App 里注明出处和许可 */
  source: {
    name: string
    url: string
    license: string
    /** 导入时 HowToCook 的提交 */
    commit: string
  }
  /** 按菜谱 id 索引 */
  recipes: Record<string, RecipeText>
}
