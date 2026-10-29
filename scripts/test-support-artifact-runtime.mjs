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
    await writeFile(configPath, JSON.stringify({ ...config, directory, resultPath, modulePath: process.env.COOK_ARTIFACT_MODULE }), { mode: 0o600 })
    await execute(process.execPath, ['scripts/support-artifact-worker.mjs', configPath], { timeout: 120_000, maxBuffer: 64 * 1024 })
    return JSON.parse(await readFile(resultPath, 'utf8'))
  }
  const uploaded = await operate({ operation: 'upload', name, payloadPath })
  assert(Number.isSafeInteger(uploaded.id) && uploaded.id > 0)
  assert.equal(uploaded.size, bytes.length)
  const downloaded = await operate({ operation: 'download', artifact: { ...uploaded, name, workflow_run: { id: Number(process.env.GITHUB_RUN_ID) } } })
  assert.equal(downloaded.digestMismatch, false)
  const received = await readFile(join(directory, 'download', name))
  assert.deepEqual(received, bytes)
  assert.deepEqual(decryptLedger(received, secret), ledger)
  process.stdout.write('Real Actions artifact upload, digest verification and encrypted restore passed.\n')
}
catch {
  process.stderr.write('Actions artifact contract verification failed; provider payloads are suppressed.\n')
  process.exitCode = 1
}
finally {
  if (directory)
    await rm(directory, { recursive: true, force: true })
}
