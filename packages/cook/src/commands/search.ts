import type { RecipeItem } from '../../types.js'
import process from 'node:process'
import consola from 'consola'
import { loadRecipes } from '../utils/data.js'
import { buildAliasMap, canonicalizeIngredient } from '../utils/food-data.js'
import { formatRecipeList, output } from '../utils/format.js'

export type SearchMode = 'strict' | 'loose' | 'survival'

const SEP = '、'

/**
 * 纯函数：根据食材/工具/关键词搜索菜谱
 * 移植自 app/composables/store/recipe.ts:77-121
 */
export function searchRecipes(recipes: RecipeItem[], opts: {
  stuff: string[]
  tool?: string
  mode: SearchMode
  keyword?: string
}): RecipeItem[] {
  const { stuff, tool, mode, keyword } = opts
  let result: RecipeItem[]

  // 仅关键词搜索时跳过食材/工具过滤，直接对全部菜谱做 keyword 过滤
  const keywordOnly = !stuff.length && !tool

  if (keywordOnly) {
    result = recipes
  }
  else if (mode === 'strict') {
    result = recipes.filter((item) => {
      const stuffFlag = stuff.every(s => item.stuff.includes(s))
      const toolFlag = item.tools.includes(tool || '')
      return tool ? (stuffFlag && toolFlag) : stuffFlag
    })
  }
  else if (mode === 'loose') {
    result = recipes.filter((item) => {
      const stuffFlag = stuff.some(s => item.stuff.includes(s))
      const toolFlag = Boolean(item.tools?.includes(tool || ''))

      if (tool && stuff.length) {
        return stuffFlag && toolFlag
      }
      else if (stuff.length) {
        return stuffFlag
      }
      else {
        return toolFlag
      }
    })
  }
  else {
    // survival: 菜谱所需食材全部在手头
    result = recipes.filter((item) => {
      const stuffFlag = item.stuff.every(s => stuff.includes(s))
      const toolFlag = item.tools?.includes(tool || '')
      return Boolean(tool ? (stuffFlag && toolFlag) : stuffFlag)
    })
  }

  if (keyword)
    result = result.filter(item => item.name.includes(keyword))

  return result
}

const MODE_NAMES: Record<SearchMode, string> = {
  strict: '严格',
  loose: '宽松',
  survival: '生存',
}

export function runSearch(options: {
  stuff?: string
  tool?: string
  mode?: string
  keyword?: string
  json?: boolean
  dataDir?: string
}) {
  const { stuff: stuffStr, tool, mode = 'loose', keyword, json = false, dataDir } = options

  if (!stuffStr && !tool && !keyword) {
    consola.error('请至少提供 --stuff、--tool 或 --keyword 中的一个参数。')
    process.exit(1)
  }

  const validModes: SearchMode[] = ['strict', 'loose', 'survival']
  if (!validModes.includes(mode as SearchMode)) {
    consola.error(`无效的搜索模式: ${mode}。可选: strict, loose, survival`)
    process.exit(1)
  }

  const recipes = loadRecipes(dataDir)
  const aliasMap = buildAliasMap()

  const stuff = stuffStr
    ? stuffStr.split(SEP).map(s => canonicalizeIngredient(s.trim(), aliasMap)).filter(Boolean)
    : []

  const result = searchRecipes(recipes, {
    stuff,
    tool,
    mode: mode as SearchMode,
    keyword,
  })

  if (json) {
    output(result, true)
  }
  else {
    if (result.length === 0) {
      consola.info('未找到匹配的菜谱。')
    }
    else {
      consola.log(`找到 ${result.length} 道菜谱（模式: ${MODE_NAMES[mode as SearchMode]}）\n`)
      consola.log(formatRecipeList(result))
    }
  }
}
