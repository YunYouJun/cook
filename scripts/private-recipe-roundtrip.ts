import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import process from 'node:process'
import { exportPrivateRecipes, parsePrivateRecipeFile } from '../packages/cook/src/utils/custom-recipes'

const fixture = parsePrivateRecipeFile(readFileSync('packages/data/fixtures/private-recipes.json', 'utf8'))
const [mode, path] = process.argv.slice(2)
if (!path)
  throw new Error('Expected write|verify and a file path')
if (mode === 'write') {
  writeFileSync(path, exportPrivateRecipes(fixture.recipes, fixture.exportedAt))
}
else if (mode === 'verify') {
  assert.deepEqual(parsePrivateRecipeFile(readFileSync(path, 'utf8')), fixture)
  console.log('Web → Swift → Web private recipe roundtrip passed')
}
else {
  throw new Error('Expected write|verify')
}
