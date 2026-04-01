import process from 'node:process'
import consola from 'consola'
import { loadRecipes } from '../utils/data.js'
import { formatRecipe, output } from '../utils/format.js'

export function runInfo(name: string, options: {
  json?: boolean
  dataDir?: string
}) {
  const { json = false, dataDir } = options

  if (!name || !name.trim()) {
    consola.error('请提供菜名。用法: cook info "番茄炒蛋"')
    process.exit(1)
  }

  const recipes = loadRecipes(dataDir)
  const matches = recipes.filter(r => r.name.includes(name.trim()))

  if (json) {
    output(matches, true)
  }
  else {
    if (matches.length === 0) {
      consola.info(`未找到包含"${name}"的菜谱。`)
    }
    else if (matches.length === 1) {
      consola.log(`📖 ${matches[0]!.name}\n`)
      consola.log(formatRecipe(matches[0]!))
    }
    else {
      consola.log(`找到 ${matches.length} 道包含"${name}"的菜谱\n`)
      for (const [i, r] of matches.entries()) {
        consola.log(formatRecipe(r, i + 1))
        consola.log()
      }
    }
  }
}
