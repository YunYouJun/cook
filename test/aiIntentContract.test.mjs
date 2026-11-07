import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
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

test('the distributed build 12 contract remains an immutable production snapshot', async () => {
  const retained = JSON.parse(await readFile(new URL('../cloudfunctions/cook-ai-intent/compatible-contracts.json', import.meta.url), 'utf8'))
  assert.equal(retained.length, 2)
  assert.equal(retained[0].dataVersion, 'faf919fc0041')
  // Verified against the downloaded production package and the archived build 12 data bundle.
  assert.equal(createHash('sha256').update(JSON.stringify(retained[0])).digest('hex'), '7f63acad75d3e4952bc80b82e319940532751e49c3e7a7c1d0ceeb1aa8378926')
  assert.deepEqual(retained[0].availableGoals, ['less-added-sugar', 'balanced-pairing'])
  assert.equal(retained[1].dataVersion, '1878cc5772dc')
  assert.equal(createHash('sha256').update(JSON.stringify(retained[1])).digest('hex'), '089fcf62a50ea1c3ee882f73afe4762e46918afff1dde2714d3acf9ed195b952')
  assert.equal(retained[1].availableGoals.includes('weight-aware'), true)
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
