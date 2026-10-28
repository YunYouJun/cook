import { createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))

/** Build the server vocabulary from the same public bundle consumed by CookCore. */
export async function buildAIIntentContract(dataDirectory = path.join(root, 'packages/data')) {
  const read = async name => JSON.parse(await readFile(path.join(dataDirectory, name), 'utf8'))
  const meta = await read('recipe-meta.json')
  const recipes = meta.chunked
    ? (await Promise.all([...meta.chunks].sort((a, b) => a.index - b.index).map(chunk => read(`recipe-chunk-${chunk.index}.json`)))).flat()
    : await read('recipe.json')
  const rules = await read('incompatible-foods.json')
  const shared = await Promise.all(['aliases.json', 'food.json', 'pantry.json', 'query.json', 'shelf-life.json'].map(read))
  const [aliases, food, pantry, query] = shared
  const hash = createHash('sha256').update(JSON.stringify(recipes)).update(JSON.stringify(rules))
  for (const value of shared) hash.update(JSON.stringify(value))
  if (meta.schemaVersion !== 1 || meta.dataVersion !== hash.digest('hex').slice(0, 12))
    throw new Error('Recipe data is stale; regenerate it before building the AI contract')
  const dietary = await read('recipe-dietary.json')
  const amounts = await readFile(path.join(dataDirectory, 'recipe-amounts.json'))
  if (dietary.schemaVersion !== 1 || dietary.policyVersion !== 1 || dietary.amountsFingerprint !== createHash('sha256').update(amounts).digest('hex'))
    throw new Error('Dietary annotations are stale or incompatible')
  const normalize = values => [...new Set(values.flatMap(value => value.split(/[,，、;；\r\n]/)).map(value => value.trim()).map(value => aliases[value] ?? value).filter(Boolean))]
  const offeredGoals = ['less-added-sugar', 'balanced-pairing']
  if (!Array.isArray(dietary.enabledGoals) || dietary.enabledGoals.some(goal => !offeredGoals.includes(goal)))
    throw new Error('A new dietary goal requires an explicit AI policy review')
  return {
    schemaVersion: 1,
    policyVersion: 1,
    dataVersion: meta.dataVersion,
    stuff: normalize([...food.vegetable, ...food.meat, ...food.staple].map(item => item.name).concat(recipes.flatMap(recipe => recipe.stuff), pantry.options)).sort(),
    tools: [...new Set(food.tools.map(item => item.name))].sort(),
    difficulty: ['简单', '普通', '困难'],
    methodGroups: Object.keys(query.methodGroups),
    availableGoals: [...dietary.enabledGoals],
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const destination = path.join(root, 'cloudfunctions/cook-ai-intent/contract.json')
  const contract = `${JSON.stringify(await buildAIIntentContract(), null, 2)}\n`
  if (process.argv.includes('--check')) {
    if (await readFile(destination, 'utf8') !== contract)
      throw new Error('AI contract is stale; run node scripts/generate-ai-intent-contract.mjs')
  }
  else { await writeFile(destination, contract) }
}
