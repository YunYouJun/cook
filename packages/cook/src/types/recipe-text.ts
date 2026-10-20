/**
 * 图文做法的正文（packages/data/recipe-text.json），App 离线排版用
 *
 * 文字里可以有加粗和外链（行内 Markdown）。配图不打包，放在对象存储上按 `imageBase + src` 加载。
 */
export type RecipeTextBlock
  = | { type: 'heading', text: string }
    | { type: 'paragraph', text: string }
    | RecipeTextList
    | RecipeTextImage

/** 列表项里的换行是补充说明或子项 */
export interface RecipeTextList {
  type: 'list'
  ordered: boolean
  start?: number
  items: string[]
}

/** 配图：压缩成 webp，按原图内容哈希命名，内容不变地址就不变，可以长期缓存 */
export interface RecipeTextImage {
  type: 'image'
  /** 相对 imageBase 的路径，例如 `howtocook/0123456789abcdef.webp` */
  src: string
  /** 压缩后的尺寸，加载前先按比例占位 */
  width: number
  height: number
  alt?: string
}

export interface RecipeText {
  blocks: RecipeTextBlock[]
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
  /** 配图地址前缀（以 / 结尾）；没有配置时 App 不显示配图，提示去原文看 */
  imageBase?: string
  /** 按菜谱 id 索引 */
  recipes: Record<string, RecipeText>
}
