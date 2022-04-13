// convert csv to json
import fs from 'fs'
import path from 'path'
import consola from 'consola'
import type { Recipe } from '~/types'

const recipeCsvFile = path.resolve(__dirname, '../src/data/recipe.csv')
const recipeJsonFile = path.resolve(__dirname, '../src/data/recipe.json')

function run() {
  const csvData = fs.readFileSync(recipeCsvFile, 'utf-8')
  const lines = csvData.split(/\r?\n/)

  if (lines[0].trim() !== '名称,食材,链接,标签/描述,方法,工具') {
    consola.warn(`Headers Changed: ${lines[0]}`)
    return
  }

  const recipeJson: Recipe = []
  const sep = '、'

  lines.slice(1).forEach((line) => {
    if (line) {
      const attrs = line.split(',')
      recipeJson.push({
        name: attrs[0].trim(),
        stuff: attrs[1].trim().split(sep),
        link: attrs[2].trim(),
        tags: attrs[3].trim().split(sep),
        methods: attrs[4].trim().split(sep),
        tools: attrs[5].trim().split(sep),
      })
    }
  })

  fs.writeFileSync(recipeJsonFile, JSON.stringify(recipeJson))
}

run()
