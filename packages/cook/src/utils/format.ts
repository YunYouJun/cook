import type { RecipeItem } from '../../types.js'
import consola from 'consola'

const SEP = '、'

/**
 * 格式化单条菜谱为文本
 */
export function formatRecipe(recipe: RecipeItem, index?: number): string {
  const prefix = index != null ? `${index}. ` : ''
  const lines = [`${prefix}${recipe.name}`]
  lines.push(`   食材: ${recipe.stuff.join(SEP)}`)
  if (recipe.tools.length > 0)
    lines.push(`   工具: ${recipe.tools.join(SEP)}`)
  if (recipe.difficulty)
    lines.push(`   难度: ${recipe.difficulty}`)
  if (recipe.methods && recipe.methods.length > 0)
    lines.push(`   做法: ${recipe.methods.join(SEP)}`)
  if (recipe.tags && recipe.tags.length > 0)
    lines.push(`   标签: ${recipe.tags.join(SEP)}`)
  if (recipe.bv)
    lines.push(`   🔗 https://www.bilibili.com/video/${recipe.bv}`)
  return lines.join('\n')
}

/**
 * 格式化菜谱列表为简洁表格
 */
export function formatRecipeList(recipes: RecipeItem[]): string {
  return recipes.map((r, i) => formatRecipe(r, i + 1)).join('\n\n')
}

/**
 * 根据 --json flag 输出数据
 */
export function output(data: unknown, json: boolean): void {
  if (json) {
    consola.log(JSON.stringify(data, null, 2))
  }
  else if (typeof data === 'string') {
    consola.log(data)
  }
}
