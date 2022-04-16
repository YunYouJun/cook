// convert csv to json
import fs from 'fs'
import path from 'path'
import consola from 'consola'
import type { Recipe, RecipeItem } from '~/types'
import { generateEmojisFromStuff } from '~/utils'

const recipeCsvFile = path.resolve(__dirname, '../src/data/recipe.csv')
const recipeJsonFile = path.resolve(__dirname, '../src/data/recipe.json')

function run() {
  const csvData = fs.readFileSync(recipeCsvFile, 'utf-8')
  const lines = csvData.split(/\r?\n/)

  if (lines[0].trim() !== 'name,stuff,link,difficulty,tags,methods,tools') {
    consola.warn(`Headers Changed: ${lines[0]}`)
    return
  }

  const recipeJson: Recipe = []
  const sep = '、'

  lines.slice(1).forEach((line) => {
    if (line) {
      const attrs = line.split(',')
      const stuff = attrs[1].trim().split(sep)
      recipeJson.push({
        name: attrs[0].trim(),
        stuff,
        emojis: generateEmojisFromStuff(stuff),
        link: attrs[2].trim(),
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
