import path, { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const recipeCsvFile = path.resolve(__dirname, '../app/data/recipe.csv')
const recipeJsonFile = path.resolve(__dirname, '../app/data/recipe.json')
const incompatibleFoodsCsvFile = path.resolve(__dirname, '../app/data/incompatible-foods.csv')
const incompatibleFoodsJsonFile = path.resolve(__dirname, '../app/data/incompatible-foods.json')

export const config = {
  recipeCsvFile,
  recipeJsonFile,
  incompatibleFoodsCsvFile,
  incompatibleFoodsJsonFile,
}
