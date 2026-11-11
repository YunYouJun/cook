import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
// eslint-disable-next-line test/no-import-node-test -- The deployed source snapshot is verified without the application test runtime.
import test from 'node:test'
import {
  buildStepGuidanceSources,
  recipeTextFingerprint,
} from '../scripts/generate-ai-guidance-sources.mjs'

test('committed guidance sources match the current public recipe bundle', async () => {
  const committed = JSON.parse(await readFile(new URL('../cloudfunctions/cook-ai-guidance/sources.json', import.meta.url), 'utf8'))
  assert.deepEqual(committed, await buildStepGuidanceSources())
  assert.equal(committed.schemaVersion, 1)
  assert.equal(committed.policyVersion, 1)
  assert.ok(Object.keys(committed.recipes).length >= 200)
  assert.deepEqual(committed.recipes[250].steps[0], { id: '1|西红柿洗净', text: '西红柿洗净' })
  assert.equal(committed.recipes[250].steps.length, 11)
  assert.equal(committed.recipes[218].sourceFingerprint, '4f2c7172160e5a24')
})

test('the generator reproduces repeated step identity, annotated sections and ingredient trimming', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'cook-guidance-sources-'))
  try {
    const source = {
      blocks: [
        { type: 'paragraph', text: '简介' },
        { type: 'heading', text: '必备原料和工具' },
        { type: 'list', ordered: false, items: ['鸡蛋', ' 盐 ', '鸡蛋', '  '] },
        { type: 'heading', text: '操作' },
        { type: 'list', ordered: true, items: ['热锅', '热锅'] },
        { type: 'paragraph', text: ' 倒入蛋液 ' },
        { type: 'heading', text: '自定义步骤' },
        { type: 'list', ordered: true, start: 4, items: ['翻炒'] },
      ],
    }
    const empty = { blocks: [{ type: 'paragraph', text: '没有步骤' }] }
    await Promise.all([
      writeFile(path.join(directory, 'recipe-meta.json'), JSON.stringify({
        schemaVersion: 1,
        dataVersion: 'fixture-v1',
        chunked: false,
        total: 2,
      })),
      writeFile(path.join(directory, 'recipe.json'), JSON.stringify([
        { id: 1, name: '测试菜' },
        { id: 2, name: '空菜' },
      ])),
      writeFile(path.join(directory, 'recipe-text.json'), JSON.stringify({
        recipes: { 1: source, 2: empty },
      })),
      writeFile(path.join(directory, 'recipe-amounts.json'), JSON.stringify({
        schemaVersion: 1,
        recipes: {
          1: {
            sourceFingerprint: recipeTextFingerprint(source),
            stepSections: [{ block: 6, text: '自定义步骤' }],
          },
        },
      })),
    ])
    const generated = await buildStepGuidanceSources(directory)
    assert.deepEqual(generated.recipes[1], {
      sourceFingerprint: recipeTextFingerprint(source),
      name: '测试菜',
      ingredients: ['鸡蛋', '盐'],
      steps: [
        { id: '1|热锅', text: '热锅' },
        { id: '2|热锅', text: '热锅' },
        { id: '1|倒入蛋液', text: '倒入蛋液' },
        { id: 'annotated|1|翻炒', text: '翻炒' },
      ],
    })
    assert.equal(generated.recipes[2], undefined)
  }
  finally {
    await rm(directory, { recursive: true, force: true })
  }
})
