import consola from 'consola'
import { loadRecipes } from '../utils/data.js'
import { allCategories } from '../utils/food-data.js'
import { output } from '../utils/format.js'

export function runStats(options: {
  json?: boolean
  dataDir?: string
}) {
  const { json = false, dataDir } = options
  const recipes = loadRecipes(dataDir)

  const difficulty: Record<string, number> = {}
  const methods: Record<string, number> = {}

  for (const r of recipes) {
    const d = r.difficulty || '未标注'
    difficulty[d] = (difficulty[d] || 0) + 1

    if (r.methods) {
      for (const m of r.methods) {
        methods[m] = (methods[m] || 0) + 1
      }
    }
  }

  const stats = {
    totalRecipes: recipes.length,
    ingredients: {
      vegetable: allCategories.vegetable.length,
      meat: allCategories.meat.length,
      staple: allCategories.staple.length,
    },
    tools: allCategories.tool.length,
    difficulty,
    methods,
  }

  if (json) {
    output(stats, true)
  }
  else {
    consola.log('📊 菜谱统计\n')
    consola.log(`  菜谱总数: ${stats.totalRecipes}`)
    consola.log(`  食材总数: ${allCategories.vegetable.length + allCategories.meat.length + allCategories.staple.length} (素菜 ${allCategories.vegetable.length} / 荤菜 ${allCategories.meat.length} / 主食 ${allCategories.staple.length})`)
    consola.log(`  工具总数: ${stats.tools}\n`)

    consola.log('  难度分布:')
    for (const [d, count] of Object.entries(difficulty)) {
      const pct = ((count / recipes.length) * 100).toFixed(0)
      consola.log(`    ${d}: ${count} (${pct}%)`)
    }

    if (Object.keys(methods).length > 0) {
      consola.log('\n  做法分布:')
      const methodStr = Object.entries(methods).map(([m, c]) => `${m}: ${c}`).join('  ')
      consola.log(`    ${methodStr}`)
    }
  }
}
