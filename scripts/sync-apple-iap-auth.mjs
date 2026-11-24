import { Buffer } from 'node:buffer'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '..')
const sharedRoot = path.join(repoRoot, 'cloudfunctions', '_shared')

const TARGET_FUNCTIONS = [
  'cook-ai-intent',
  'cook-fridge-vision',
  'cook-ai-guidance',
]

const FILES = [
  'apple-iap-auth.js',
  'certs/AppleIncRootCertificate.cer',
  'certs/AppleRootCA-G2.cer',
  'certs/AppleRootCA-G3.cer',
]

const checkOnly = process.argv.includes('--check')
let outOfSync = 0

for (const fnName of TARGET_FUNCTIONS) {
  const fnDir = path.join(repoRoot, 'cloudfunctions', fnName)
  for (const relPath of FILES) {
    const srcPath = path.join(sharedRoot, relPath)
    const destPath = path.join(fnDir, relPath)
    const srcContent = fs.readFileSync(srcPath)

    let destMatches = false
    if (fs.existsSync(destPath)) {
      const destContent = fs.readFileSync(destPath)
      destMatches = Buffer.compare(srcContent, destContent) === 0
    }

    if (!destMatches) {
      if (checkOnly) {
        console.error(`[sync-apple-iap-auth] Out of sync: ${path.relative(repoRoot, destPath)}`)
        outOfSync += 1
      }
      else {
        fs.mkdirSync(path.dirname(destPath), { recursive: true })
        fs.writeFileSync(destPath, srcContent)
        console.log(`[sync-apple-iap-auth] Synced ${path.relative(repoRoot, destPath)}`)
      }
    }
  }
}

if (checkOnly) {
  if (outOfSync > 0)
    process.exit(1)
  console.log('[sync-apple-iap-auth] All Apple IAP auth files are in sync.')
}
