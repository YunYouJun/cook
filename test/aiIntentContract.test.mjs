import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
// eslint-disable-next-line test/no-import-node-test -- The deployed contract is verified without the application test runtime.
import test from 'node:test'
import { buildAIIntentContract } from '../scripts/generate-ai-intent-contract.mjs'

test('deployed intent vocabulary matches the current public recipe bundle', async () => {
  const committed = JSON.parse(await readFile(new URL('../cloudfunctions/cook-ai-intent/contract.json', import.meta.url), 'utf8'))
  assert.deepEqual(committed, await buildAIIntentContract())
  assert.equal(committed.availableGoals.includes('weight-aware'), false)
  for (const key of ['stuff', 'tools', 'difficulty', 'methodGroups']) {
    assert.equal(new Set(committed[key]).size, committed[key].length)
    assert.ok(committed[key].length > 0)
  }
})
