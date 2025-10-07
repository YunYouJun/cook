import path from 'node:path'

const __dirname = import.meta.dirname
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
