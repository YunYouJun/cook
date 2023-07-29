// convert csv to json
import fs from 'node:fs'
import path from 'node:path'
import url from 'node:url'
import consola from 'consola'
import type { RecipeItem, Recipes } from '../types'

const __dirname = url.fileURLToPath(new URL('.', import.meta.url))
const recipeCsvFile = path.resolve(__dirname, '../data/recipe.csv')
const recipeJsonFile = path.resolve(__dirname, '../data/recipe.json')

function run() {
  const csvData = fs.readFileSync(recipeCsvFile, 'utf-8')
  const lines = csvData.split(/\r?\n/)

  const headers = 'name,stuff,bv,difficulty,tags,methods,tools,'
  if (lines[0].trim() !== headers) {
    consola.warn(`Headers Changed: ${lines[0]}`)
    return
  }

  const recipeJson: Recipes = []
  const sep = '、'

  lines.slice(1).forEach((line) => {
    if (line) {
      const attrs = line.split(',')
      const stuff = attrs[1].trim().split(sep)
      recipeJson.push({
        name: attrs[0].trim(),
        stuff,
        // link: attrs[2].trim(),
        // bv id
        bv: attrs[2].trim().replace('https://www.bilibili.com/video/', ''),
        difficulty: attrs[3] && attrs[3].trim() as RecipeItem['difficulty'],
        tags: attrs[4] ? attrs[4].trim().split(sep) : [],
        methods: attrs[5] ? (attrs[5].trim().split(sep)) as RecipeItem['methods'] : [],
        tools: attrs[6] ? attrs[6].trim().split(sep) : [],
      })
    }
  })

  fs.writeFileSync(recipeJsonFile, JSON.stringify(recipeJson))
  consola.success(`Generate file: ${recipeJsonFile}`)
}

run()
