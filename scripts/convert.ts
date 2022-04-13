// convert csv to json
import fs from 'fs'
import path from 'path'
import type { Recipe } from '~/types'

const recipeCsvFile = path.resolve(__dirname, '../public/data/recipe.csv')
const recipeJsonFile = path.resolve(__dirname, '../public/data/recipe.json')

function run() {
  const csvData = fs.readFileSync(recipeCsvFile, 'utf-8')
  const lines = csvData.split(/\r?\n/)

  if (lines[0] !== '名称,食材,链接,标签/描述,方法,工具')
    console.log('Headers Changed!')

  const recipeJson: Recipe = []
  const sep = '、'

  lines.slice(1).forEach((line) => {
    if (line) {
      const attrs = line.split(',')
      recipeJson.push({
        name: attrs[0],
        stuff: attrs[1].split(sep),
        link: attrs[2],
        tags: attrs[3].split(sep),
        methods: attrs[4].split(sep),
        tools: attrs[5].split(sep),
      })
    }
  })

  fs.writeFileSync(recipeJsonFile, JSON.stringify(recipeJson))
}

run()
