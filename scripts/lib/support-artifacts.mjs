import { Buffer } from 'node:buffer'
import { execFile } from 'node:child_process'
import { createHash } from 'node:crypto'
import { lstat, mkdir, mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import { isAbsolute, join } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const execute = promisify(execFile)
const worker = fileURLToPath(new URL('../support-artifact-worker.mjs', import.meta.url))
const MAX_ENCRYPTED_BYTES = 1024 * 1024 + 1024
const CHECKPOINT = /^cook-support-ledger-[1-9]\d*-[1-9]\d*-\d{6}\.enc$/
const DIGEST = /^sha256:[a-f\d]{64}$/
const positiveInteger = value => Number.isSafeInteger(Number(value)) && Number(value) > 0 && /^[1-9]\d*$/.test(String(value))

function failure() {
  return new Error('Support artifact operation failed; retain the previous checkpoint')
}

/**
 * Persist encrypted checkpoints through the pinned official artifact SDK in an isolated worker.
 *
 * @param {{modulePath:string,directory:string,runId:string|number,runAttempt:string|number}} options Absolute SDK and private staging paths, plus the current Actions run identity.
 * @returns {{upload:Function,download:Function}} Finalized upload and authenticated download operations; SDK output is never forwarded.
 * @throws {Error} If configuration, artifact metadata, SDK execution, or downloaded files are invalid.
 */
export function createSupportArtifactStore({ modulePath, directory, runId, runAttempt }) {
  if (!isAbsolute(modulePath ?? '') || !isAbsolute(directory ?? '') || !positiveInteger(runId) || !positiveInteger(runAttempt))
    throw failure()

  async function operate(config, encrypted) {
    let temporary
    try {
      await mkdir(directory, { recursive: true, mode: 0o700 })
      const parent = await lstat(directory)
      if (!parent.isDirectory() || parent.mode & 0o077)
        throw failure()
      temporary = await mkdtemp(join(directory, '.artifact-'))
      const resultPath = join(temporary, 'result.json')
      const payloadPath = encrypted ? join(temporary, config.name) : undefined
      if (payloadPath)
        await writeFile(payloadPath, encrypted, { mode: 0o600, flag: 'wx' })
      const configPath = join(temporary, 'input.json')
      await writeFile(configPath, JSON.stringify({ ...config, modulePath, directory: temporary, resultPath, ...(payloadPath ? { payloadPath } : {}) }), { mode: 0o600, flag: 'wx' })
      // The SDK may log transport errors and signed URLs before throwing. Keep its output private.
      await execute(process.execPath, [worker, configPath], { timeout: 120_000, maxBuffer: 64 * 1024, encoding: 'utf8' })
      const result = JSON.parse(await readFile(resultPath, 'utf8'))
      if (config.operation === 'upload') {
        if (!positiveInteger(result.id) || result.size !== encrypted.length || result.digest !== `sha256:${createHash('sha256').update(encrypted).digest('hex')}`)
          throw failure()
        return result
      }
      const downloaded = join(temporary, 'download')
      const files = await readdir(downloaded)
      const expected = config.artifact.name === 'cook-support-ledger' ? 'ledger.enc' : config.artifact.name
      if (files.length !== 1 || files[0] !== expected || result.digestMismatch !== false)
        throw failure()
      const path = join(downloaded, expected)
      const entry = await lstat(path)
      if (!entry.isFile() || entry.size <= 0 || entry.size > MAX_ENCRYPTED_BYTES)
        throw failure()
      const contents = await readFile(path)
      if (config.artifact.name !== 'cook-support-ledger' && config.artifact.digest !== `sha256:${createHash('sha256').update(contents).digest('hex')}`)
        throw failure()
      return contents
    }
    catch {
      throw failure()
    }
    finally {
      if (temporary)
        await rm(temporary, { recursive: true, force: true })
    }
  }

  return {
    upload: async (encrypted, sequence) => {
      if (!Buffer.isBuffer(encrypted) || !encrypted.length || encrypted.length > MAX_ENCRYPTED_BYTES || !Number.isSafeInteger(sequence) || sequence < 1 || sequence > 999_999)
        throw failure()
      const name = `cook-support-ledger-${runId}-${runAttempt}-${String(sequence).padStart(6, '0')}.enc`
      return operate({ operation: 'upload', name }, encrypted)
    },
    download: async (artifact) => {
      if (!positiveInteger(artifact?.id) || (artifact.name !== 'cook-support-ledger' && !CHECKPOINT.test(artifact.name)) || !Number.isSafeInteger(artifact.size_in_bytes) || artifact.size_in_bytes <= 0 || artifact.size_in_bytes > 2 * 1024 * 1024 || !DIGEST.test(artifact.digest) || !positiveInteger(artifact.workflow_run?.id))
        throw failure()
      return operate({ operation: 'download', artifact: { id: artifact.id, name: artifact.name, size_in_bytes: artifact.size_in_bytes, digest: artifact.digest, workflow_run: { id: artifact.workflow_run.id } } })
    },
  }
}
