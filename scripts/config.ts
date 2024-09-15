import path from 'node:path'
import url from 'node:url'

const __dirname = url.fileURLToPath(new URL('.', import.meta.url))
const recipeCsvFile = path.resolve(__dirname, '../app/data/recipe.csv')
const recipeJsonFile = path.resolve(__dirname, '../app/data/recipe.json')

export const config = {
  recipeCsvFile,
  recipeJsonFile,
}
