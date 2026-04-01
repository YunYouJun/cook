import process from 'node:process'
import consola from 'consola'
import { allCategories } from '../utils/food-data.js'
import { output } from '../utils/format.js'

const CATEGORY_NAMES: Record<string, string> = {
  vegetable: '🥬 素菜',
  meat: '🥩 荤菜',
  staple: '🍚 主食',
  tool: '🔧 工具',
}

export function runIngredients(options: {
  category?: string
  json?: boolean
}) {
  const { category, json = false } = options

  if (category && !(category in allCategories)) {
    consola.error(`无效的类别: ${category}。可选: vegetable, meat, staple, tool`)
    process.exit(1)
  }

  const categories = category
    ? { [category]: allCategories[category as keyof typeof allCategories] }
    : allCategories

  if (json) {
    output(categories, true)
  }
  else {
    for (const [key, items] of Object.entries(categories)) {
      const label = CATEGORY_NAMES[key] || key
      const names = items.map(i => `${i.name}${i.emoji ? ` ${i.emoji}` : ''}`).join('  ')
      consola.log(`${label} (${items.length})`)
      consola.log(`  ${names}\n`)
    }
  }
}
