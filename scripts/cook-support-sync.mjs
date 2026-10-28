import { Buffer } from 'node:buffer'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile, rm, stat } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import process from 'node:process'
import { collectCookGitHubChanges, verifyCookChecks } from './lib/cook-github-changes.mjs'
import { runCookSupportSync } from './lib/cook-support-runner.mjs'
import { verifyCookWebAcceptance } from './lib/cook-web-acceptance.mjs'
import { verifyEdgeOneRelease } from './lib/edgeone-release.mjs'
import { createCookSupportClient } from './lib/support-agent-http.mjs'
import { createSupportArtifactStore } from './lib/support-artifacts.mjs'
import { createLedgerWriter, decryptLedger, loadLatestLedger } from './lib/support-ledger.mjs'

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
let writer
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
  let store
  if (process.env.GITHUB_ACTIONS === 'true') {
    if (process.env.GITHUB_REPOSITORY !== 'YunYouJun/cook' || process.env.GITHUB_REF !== 'refs/heads/main')
      throw new Error('Production automation only runs trusted Cook main')
    store = createSupportArtifactStore({ modulePath: process.env.COOK_ARTIFACT_MODULE, directory, runId: process.env.GITHUB_RUN_ID, runAttempt: process.env.GITHUB_RUN_ATTEMPT })
    encrypted = await loadLatestLedger({ token: process.env.GH_TOKEN, repository: 'YunYouJun/cook', allowInitialize: flags.has('--allow-initialize'), download: store.download })
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
  writer = createLedgerWriter({ path: ledgerPath, secret, publish: store?.upload, initial: encrypted ? ledger : undefined })
  if (store && (!ledger.checkpointedAt || Date.now() - Date.parse(ledger.checkpointedAt) >= 86_400_000))
    ledger.checkpointedAt = new Date().toISOString()
  const { save } = writer
  // Even a failed run must retain the loaded encrypted journal for the next retry.
  await save(ledger)
  const client = createCookSupportClient(config)
  const github = { token: process.env.GH_TOKEN }
  const result = await runCookSupportSync({ bindings, ledger, apply: flags.has('--apply'), sendReplies: flags.has('--send-replies') }, {
    collect: entries => collectCookGitHubChanges(entries, github),
    checks: sha => verifyCookChecks(sha, github),
    release: expectedSha => verifyEdgeOneRelease({ token: process.env.EDGEONE_API_TOKEN, expectedSha }),
    browser: async (profiles) => {
      if (process.env.GITHUB_ACTIONS === 'true')
        execFileSync(process.execPath, [join(dirname(process.env.COOK_PLAYWRIGHT_MODULE), 'cli.js'), 'install', '--with-deps', 'chromium'], { stdio: 'inherit' })
      return verifyCookWebAcceptance({ profiles, modulePath: process.env.COOK_PLAYWRIGHT_MODULE, channel: process.env.COOK_RELEASE_BROWSER_CHANNEL })
    },
    detail: async id => (await client.query({ productId: 'cook', id })).detail,
    command: client.command,
    save,
  })
  process.stdout.write(`${JSON.stringify(result)}\n`)
  if (result.blocked > 0)
    process.exitCode = 1
}
catch (error) {
  if (error.code === 'CHECKPOINT_BUDGET_EXHAUSTED') {
    process.stdout.write('{"phase":"awaiting_next_checkpoint_batch"}\n')
  }
  else {
    process.stderr.write('Cook Support synchronization stopped. Review private configuration or the retained encrypted journal; no private payload is printed.\n')
    process.exitCode = 1
  }
}
finally {
  try {
    await writer?.flush()
  }
  catch {
    process.stderr.write('Final checkpoint failed; the last finalized command intent remains authoritative.\n')
    process.exitCode = 1
  }
  if (lock)
    await rm(lock, { recursive: true, force: true })
}
