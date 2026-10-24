import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import process from 'node:process'
import { exportPersonalCatalog, parsePersonalCatalog } from '../packages/cook/src/utils/personal-catalog'

const fixture = parsePersonalCatalog(readFileSync('packages/data/fixtures/personal-catalog.json', 'utf8'))
const [mode, path] = process.argv.slice(2)
if (!path)
  throw new Error('Expected write|verify and a file path')
if (mode === 'write') {
  writeFileSync(path, exportPersonalCatalog(fixture))
}
else if (mode === 'verify') {
  assert.deepEqual(parsePersonalCatalog(readFileSync(path, 'utf8')), fixture)
  console.log('Web → Swift → Web personal catalog roundtrip passed')
}
else {
  throw new Error('Expected write|verify')
}
