import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import process from 'node:process'
import { promisify } from 'node:util'
import { decryptLedger, encryptLedger } from './lib/support-ledger.mjs'

const execute = promisify(execFile)
let directory
let stage = 'setup'
let diagnostic = ''
try {
  assert.equal(process.env.GITHUB_ACTIONS, 'true')
  directory = await mkdtemp(join(process.env.RUNNER_TEMP, 'cook-artifact-contract-'))
  const name = `cook-artifact-contract-${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}.enc`
  const secret = randomBytes(32).toString('base64')
  const ledger = { schemaVersion: 1, tickets: {}, events: {}, inputs: { synthetic: true } }
  const bytes = encryptLedger(ledger, secret)
  const payloadPath = join(directory, name)
  await writeFile(payloadPath, bytes, { mode: 0o600 })
  async function operate(config) {
    const resultPath = join(directory, `${config.operation}-result.json`)
    const configPath = join(directory, `${config.operation}-input.json`)
    await writeFile(configPath, JSON.stringify({ ...config, directory, resultPath, diagnostics: true, modulePath: process.env.COOK_ARTIFACT_MODULE }), { mode: 0o600 })
    stage = config.operation
    try {
      await execute(process.execPath, ['scripts/support-artifact-worker.mjs', configPath], { timeout: 120_000, maxBuffer: 64 * 1024 })
    }
    catch {
      const failure = await readFile(resultPath, 'utf8').then(value => JSON.parse(value).failure).catch(() => undefined)
      if (Number.isInteger(failure?.status) && failure.status >= 100 && failure.status <= 599)
        diagnostic += `; HTTP ${failure.status}`
      if (['EAI_AGAIN', 'ENOTFOUND', 'ECONNRESET', 'ETIMEDOUT', 'ECONNREFUSED', 'ENOENT'].includes(failure?.code))
        diagnostic += `; ${failure.code}`
      throw new Error('Artifact worker failed')
    }
    stage = `${config.operation}-result`
    return JSON.parse(await readFile(resultPath, 'utf8'))
  }
  const uploaded = await operate({ operation: 'upload', name, payloadPath })
  stage = 'verify-upload'
  assert(Number.isSafeInteger(uploaded.id) && uploaded.id > 0)
  assert.equal(uploaded.size, bytes.length)
  const downloaded = await operate({ operation: 'download', artifact: { ...uploaded, name, workflow_run: { id: Number(process.env.GITHUB_RUN_ID) } } })
  stage = 'verify-digest'
  assert.equal(downloaded.digestMismatch, false)
  stage = 'read-download'
  const received = await readFile(join(directory, 'download', name))
  stage = 'verify-bytes'
  assert.deepEqual(received, bytes)
  stage = 'decrypt'
  assert.deepEqual(decryptLedger(received, secret), ledger)
  process.stdout.write('Real Actions artifact upload, digest verification and encrypted restore passed.\n')
}
catch {
  process.stderr.write(`Actions artifact contract verification failed at ${stage}${diagnostic}; provider payloads are suppressed.\n`)
  process.exitCode = 1
}
finally {
  if (directory)
    await rm(directory, { recursive: true, force: true })
}
