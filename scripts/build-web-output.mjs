import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { buildWebOutput } from './lib/web-build.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
buildWebOutput(root, { mode: process.argv[2] ?? 'generate' })
console.log('Generated and copied web output with verified build provenance to dist/')
