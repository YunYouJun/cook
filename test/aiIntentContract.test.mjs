import assert from 'node:assert/strict'
import { copyFile, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
// eslint-disable-next-line test/no-import-node-test -- The deployed contract is verified without the application test runtime.
import test from 'node:test'
import { buildAIIntentContract } from '../scripts/generate-ai-intent-contract.mjs'

test('deployed intent vocabulary matches the current public recipe bundle', async () => {
  const committed = JSON.parse(await readFile(new URL('../cloudfunctions/cook-ai-intent/contract.json', import.meta.url), 'utf8'))
  assert.deepEqual(committed, await buildAIIntentContract())
  assert.equal(committed.availableGoals.includes('weight-aware'), true)
  for (const key of ['stuff', 'tools', 'difficulty', 'methodGroups']) {
    assert.equal(new Set(committed[key]).size, committed[key].length)
    assert.ok(committed[key].length > 0)
  }
})

test('the generated AI contract cannot offer a dietary goal with only nine passing records', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'cook-intent-goal-'))
  try {
    const source = new URL('../packages/data/', import.meta.url)
    const meta = JSON.parse(await readFile(new URL('recipe-meta.json', source), 'utf8'))
    const recipes = meta.chunked ? meta.chunks.map(chunk => `recipe-chunk-${chunk.index}.json`) : ['recipe.json']
    const files = ['recipe-meta.json', ...recipes, 'incompatible-foods.json', 'aliases.json', 'food.json', 'pantry.json', 'query.json', 'shelf-life.json', 'recipe-dietary.json', 'recipe-amounts.json']
    await Promise.all(files.map(file => copyFile(new URL(file, source), path.join(directory, file))))
    const file = path.join(directory, 'recipe-dietary.json')
    const dietary = JSON.parse(await readFile(file, 'utf8'))
    dietary.recipes['333'].assessments['weight-aware'].status = 'unknown'
    await writeFile(file, JSON.stringify(dietary))
    await assert.rejects(buildAIIntentContract(directory), /at least ten reviewed recipes/)
  }
  finally {
    await rm(directory, { recursive: true, force: true })
  }
})
