import { Buffer } from 'node:buffer'
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import process from 'node:process'
import { collectCookGitHubChanges, verifyCookChecks } from './lib/cook-github-changes.mjs'
import { runCookSupportSync } from './lib/cook-support-runner.mjs'
import { verifyCookWebAcceptance } from './lib/cook-web-acceptance.mjs'
import { verifyEdgeOneRelease } from './lib/edgeone-release.mjs'
import { createCookSupportClient } from './lib/support-agent-http.mjs'
import { decryptLedger, encryptLedger, loadLatestLedger } from './lib/support-ledger.mjs'

const flags = new Set(process.argv.slice(2))
if ([...flags].some(flag => !['--apply', '--send-replies', '--allow-initialize'].includes(flag))) {
  process.stderr.write('Usage: node scripts/cook-support-sync.mjs [--apply] [--send-replies] [--allow-initialize]\n')
  process.exit(2)
}

async function privateJson(path) {
  if ((await stat(path)).mode & 0o077)
    throw new Error('Private automation input must be mode 0600')
  return JSON.parse(await readFile(path, 'utf8'))
}

let lock
try {
  const config = process.env.COOK_SUPPORT_AGENT_CONFIG
    ? await privateJson(process.env.COOK_SUPPORT_AGENT_CONFIG)
    : JSON.parse(process.env.COOK_SUPPORT_AGENT_JSON || 'null')
  if (config?.privateKeyPath) {
    if ((await stat(config.privateKeyPath)).mode & 0o077)
      throw new Error('Private key must be mode 0600')
    config.privateKey = await readFile(config.privateKeyPath, 'utf8')
  }
  const bindings = process.env.COOK_SUPPORT_BINDINGS_FILE
    ? await privateJson(process.env.COOK_SUPPORT_BINDINGS_FILE)
    : JSON.parse(process.env.COOK_SUPPORT_BINDINGS_JSON || 'null')
  const secret = process.env.COOK_SUPPORT_LEDGER_KEY
  if (typeof secret !== 'string' || Buffer.byteLength(secret) < 32 || !process.env.COOK_SUPPORT_STATE_DIR)
    throw new Error('Private state directory and encryption key are required')
  const directory = resolve(process.env.COOK_SUPPORT_STATE_DIR)
  await mkdir(directory, { recursive: true, mode: 0o700 })
  if ((await stat(directory)).mode & 0o077)
    throw new Error('Private state directory must be mode 0700')
  const lockPath = resolve(directory, '.sync-lock')
  await mkdir(lockPath, { mode: 0o700 })
  lock = lockPath
  const ledgerPath = resolve(directory, 'ledger.enc')
  let encrypted
  if (process.env.GITHUB_ACTIONS === 'true') {
    if (process.env.GITHUB_REPOSITORY !== 'YunYouJun/cook' || process.env.GITHUB_REF !== 'refs/heads/main')
      throw new Error('Production automation only runs trusted Cook main')
    encrypted = await loadLatestLedger({ token: process.env.GH_TOKEN, repository: 'YunYouJun/cook', runId: process.env.GITHUB_RUN_ID, allowInitialize: flags.has('--allow-initialize') })
  }
  else {
    try {
      encrypted = await readFile(ledgerPath)
    }
    catch (error) {
      if (error.code !== 'ENOENT' || !flags.has('--allow-initialize'))
        throw error
    }
  }
  const ledger = encrypted ? decryptLedger(encrypted, secret) : { schemaVersion: 1, events: {}, tickets: {}, inputs: {} }
  const save = async (value) => {
    await writeFile(`${ledgerPath}.tmp`, encryptLedger(value, secret), { mode: 0o600 })
    await rename(`${ledgerPath}.tmp`, ledgerPath)
  }
  // Even a failed run must retain the loaded encrypted journal for the next retry.
  await save(ledger)
  const client = createCookSupportClient(config)
  const github = { token: process.env.GH_TOKEN }
  const result = await runCookSupportSync({ bindings, ledger, apply: flags.has('--apply'), sendReplies: flags.has('--send-replies') }, {
    collect: entries => collectCookGitHubChanges(entries, github),
    checks: sha => verifyCookChecks(sha, github),
    release: expectedSha => verifyEdgeOneRelease({ token: process.env.EDGEONE_API_TOKEN, expectedSha }),
    browser: profiles => verifyCookWebAcceptance({ profiles, modulePath: process.env.COOK_PLAYWRIGHT_MODULE, channel: process.env.COOK_RELEASE_BROWSER_CHANNEL }),
    detail: async id => (await client.query({ productId: 'cook', id })).detail,
    command: client.command,
    save,
  })
  process.stdout.write(`${JSON.stringify(result)}\n`)
  if (result.blocked > 0)
    process.exitCode = 1
}
catch {
  process.stderr.write('Cook Support synchronization stopped. Review private configuration or the retained encrypted journal; no private payload is printed.\n')
  process.exitCode = 1
}
finally {
  if (lock)
    await rm(lock, { recursive: true, force: true })
}
