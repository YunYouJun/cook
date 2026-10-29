import { Buffer } from 'node:buffer'
import { createCipheriv, createDecipheriv, createHash, hkdfSync, randomBytes } from 'node:crypto'
import { rename, writeFile } from 'node:fs/promises'

const MAGIC = Buffer.from('COOKLDGR')
const FORMAT_VERSION = 1
const SALT_BYTES = 32
const IV_BYTES = 12
const TAG_BYTES = 16
const HEADER_BYTES = MAGIC.length + 1 + SALT_BYTES + IV_BYTES
const KEY_CONTEXT = 'cook-support-ledger-v1'
const ARTIFACT_NAME = /^cook-support-ledger(?:-(\d+)-\d+-\d{6}\.enc)?$/
const REPOSITORY = 'YunYouJun/cook'
const TRUSTED_WORKFLOW = '.github/workflows/support-sync.yml'
const TRUSTED_EVENTS = new Set(['push', 'schedule', 'workflow_dispatch'])
const API_ORIGIN = 'https://api.github.com'
const MAX_METADATA_BYTES = 1024 * 1024

/** Maximum plaintext ledger size; encrypted input is bounded before decryption. */
export const MAX_LEDGER_BYTES = 1024 * 1024

function secretBytes(secret) {
  if (typeof secret !== 'string' || Buffer.byteLength(secret, 'utf8') < 32 || Buffer.byteLength(secret, 'utf8') > 4096)
    throw new Error('Support ledger requires a dedicated secret of at least 32 bytes')
  return Buffer.from(secret, 'utf8')
}

function validateJson(value, ancestors = new Set(), depth = 0) {
  if (depth > 50)
    throw new Error('Support ledger payload is invalid')
  if (value === null || typeof value === 'string' || typeof value === 'boolean')
    return
  if (typeof value === 'number' && Number.isFinite(value))
    return
  if (!value || typeof value !== 'object' || ancestors.has(value))
    throw new Error('Support ledger payload is invalid')
  if (!Array.isArray(value) && ![Object.prototype, null].includes(Object.getPrototypeOf(value)))
    throw new Error('Support ledger payload is invalid')
  ancestors.add(value)
  if (Array.isArray(value)) {
    for (const key of Reflect.ownKeys(value)) {
      if (key !== 'length' && (typeof key !== 'string' || !/^(?:0|[1-9]\d*)$/.test(key) || Number(key) >= value.length))
        throw new Error('Support ledger payload is invalid')
    }
    for (const item of value)
      validateJson(item, ancestors, depth + 1)
  }
  else {
    for (const key of Reflect.ownKeys(value)) {
      const property = Object.getOwnPropertyDescriptor(value, key)
      if (typeof key !== 'string' || ['__proto__', 'constructor', 'prototype'].includes(key) || !property.enumerable || !Object.hasOwn(property, 'value'))
        throw new Error('Support ledger payload is invalid')
      validateJson(property.value, ancestors, depth + 1)
    }
  }
  ancestors.delete(value)
}

function validateLedger(ledger) {
  validateJson(ledger)
  if (!ledger || Array.isArray(ledger) || !Object.hasOwn(ledger, 'schemaVersion') || ledger.schemaVersion !== 1)
    throw new Error('Support ledger schemaVersion must be 1')
  return ledger
}

/**
 * Encrypt the private reconciliation ledger for storage in a public Actions artifact.
 *
 * @param {object} ledger JSON ledger with schemaVersion 1; plaintext is never returned or logged.
 * @param {string} secret Dedicated random ledger secret, independent of Support signing credentials.
 * @returns {Buffer} Versioned AES-256-GCM envelope with random salt and IV.
 * @throws {Error} If the secret, schema, JSON values, or size are invalid.
 */
export function encryptLedger(ledger, secret) {
  validateLedger(ledger)
  const plaintext = Buffer.from(JSON.stringify(ledger), 'utf8')
  if (plaintext.length > MAX_LEDGER_BYTES)
    throw new Error('Support ledger exceeds the maximum size')
  const material = secretBytes(secret)
  const salt = randomBytes(SALT_BYTES)
  const iv = randomBytes(IV_BYTES)
  const header = Buffer.concat([MAGIC, Buffer.from([FORMAT_VERSION]), salt, iv])
  const key = Buffer.from(hkdfSync('sha256', material, salt, KEY_CONTEXT, 32))
  try {
    const cipher = createCipheriv('aes-256-gcm', key, iv, { authTagLength: TAG_BYTES })
    cipher.setAAD(header)
    const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()])
    return Buffer.concat([header, cipher.getAuthTag(), ciphertext])
  }
  finally {
    plaintext.fill(0)
    material.fill(0)
    key.fill(0)
  }
}

/**
 * Authenticate and decrypt a previously encrypted ledger without a reset fallback.
 *
 * @param {Buffer} envelope Encrypted artifact bytes produced by encryptLedger.
 * @param {string} secret The same dedicated random ledger secret used for encryption.
 * @returns {object} Authenticated schemaVersion 1 JSON ledger.
 * @throws {Error} If framing, version, authentication, schema, or size validation fails.
 */
export function decryptLedger(envelope, secret) {
  if (!Buffer.isBuffer(envelope) || envelope.length <= HEADER_BYTES + TAG_BYTES)
    throw new Error('Support ledger envelope is invalid')
  if (envelope.length > HEADER_BYTES + TAG_BYTES + MAX_LEDGER_BYTES)
    throw new Error('Support ledger exceeds the maximum size')
  if (!envelope.subarray(0, MAGIC.length).equals(MAGIC) || envelope[MAGIC.length] !== FORMAT_VERSION)
    throw new Error('Support ledger envelope version is unsupported')
  const material = secretBytes(secret)
  const salt = envelope.subarray(MAGIC.length + 1, MAGIC.length + 1 + SALT_BYTES)
  const iv = envelope.subarray(HEADER_BYTES - IV_BYTES, HEADER_BYTES)
  const key = Buffer.from(hkdfSync('sha256', material, salt, KEY_CONTEXT, 32))
  let plaintext
  try {
    const decipher = createDecipheriv('aes-256-gcm', key, iv, { authTagLength: TAG_BYTES })
    decipher.setAAD(envelope.subarray(0, HEADER_BYTES))
    decipher.setAuthTag(envelope.subarray(HEADER_BYTES, HEADER_BYTES + TAG_BYTES))
    plaintext = Buffer.concat([decipher.update(envelope.subarray(HEADER_BYTES + TAG_BYTES)), decipher.final()])
    try {
      return validateLedger(JSON.parse(plaintext.toString('utf8')))
    }
    catch {
      throw new Error('Support ledger payload is invalid')
    }
  }
  catch {
    // Crypto and parser exceptions can expose details that do not belong in public run logs.
    throw new Error('Support ledger authentication or payload validation failed')
  }
  finally {
    plaintext?.fill(0)
    material.fill(0)
    key.fill(0)
  }
}

async function metadata(fetcher, url, headers) {
  const message = 'Support ledger artifact metadata is unavailable or invalid'
  try {
    const response = await fetcher(url, { headers, redirect: 'error', signal: AbortSignal.timeout(15_000) })
    if (response.status !== 200 || Number(response.headers.get('content-length')) > MAX_METADATA_BYTES)
      throw new Error(message)
    const body = await response.text()
    if (Buffer.byteLength(body) > MAX_METADATA_BYTES)
      throw new Error(message)
    return JSON.parse(body)
  }
  catch {
    throw new Error(message)
  }
}

/**
 * Restore the newest finalized checkpoint, including failed runs and earlier rerun attempts.
 * Metadata selection and trusted-workflow checks stay here; the official artifact adapter downloads bytes.
 * @param {{token:string,repository:string,allowInitialize?:boolean,fetch?:typeof fetch,download:Function}} options Trusted GitHub history and artifact adapter.
 * @returns {Promise<Buffer|null>} Authenticated-envelope bytes, or null only for explicit first use.
 * @throws {Error} If history or its newest checkpoint cannot be trusted; never falls back to old state.
 */
export async function loadLatestLedger({ token, repository, allowInitialize = false, fetch: fetcher = globalThis.fetch, download }) {
  if (repository !== REPOSITORY || typeof token !== 'string' || !token || /[\r\n]/.test(token))
    throw new Error('Support ledger GitHub configuration is invalid')
  const headers = { 'Authorization': `Bearer ${token}`, 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' }
  const artifacts = []
  const ids = new Set()
  let expectedTotal
  let complete = false
  for (let page = 1; page <= 100; page++) {
    const data = await metadata(fetcher, `${API_ORIGIN}/repos/${REPOSITORY}/actions/artifacts?per_page=100&page=${page}`, headers)
    if (!Number.isSafeInteger(data?.total_count) || data.total_count < 0 || !Array.isArray(data.artifacts) || data.artifacts.length > 100 || (expectedTotal !== undefined && expectedTotal !== data.total_count))
      throw new Error('Support ledger artifact history is invalid')
    expectedTotal = data.total_count
    for (const artifact of data.artifacts) {
      if (!Number.isSafeInteger(artifact?.id) || artifact.id < 1 || ids.has(artifact.id))
        throw new Error('Support ledger artifact history is invalid')
      ids.add(artifact.id)
      if (!artifact.name?.startsWith('cook-support-ledger'))
        continue
      const name = ARTIFACT_NAME.exec(artifact.name)
      if (!name || typeof artifact.expired !== 'boolean' || !Number.isSafeInteger(artifact.workflow_run?.id) || artifact.workflow_run.id < 1 || (name[1] && Number(name[1]) !== artifact.workflow_run.id) || !Number.isFinite(Date.parse(artifact.created_at)) || !Number.isSafeInteger(artifact.size_in_bytes) || artifact.size_in_bytes < 1 || artifact.size_in_bytes > 2 * MAX_LEDGER_BYTES || !/^sha256:[a-f\d]{64}$/.test(artifact.digest))
        throw new Error('Support ledger artifact history is invalid')
      artifacts.push(artifact)
    }
    if (data.artifacts.length < 100) {
      complete = true
      break
    }
  }
  if (!complete || ids.size !== expectedTotal)
    throw new Error('Support ledger artifact history is incomplete')
  if (!artifacts.length) {
    if (allowInitialize)
      return null
    throw new Error('Support ledger history is absent; first-use initialization must be explicit')
  }
  // Artifact creation order also handles a rerun of an older workflow run.
  const latest = artifacts.sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at) || b.id - a.id)[0]
  if (latest.expired)
    throw new Error('Latest Support ledger history is unavailable; refusing to reset')
  const run = await metadata(fetcher, `${API_ORIGIN}/repos/${REPOSITORY}/actions/runs/${latest.workflow_run.id}`, headers)
  if (run?.id !== latest.workflow_run.id || run.path !== TRUSTED_WORKFLOW || run.repository?.full_name !== REPOSITORY || run.head_repository?.full_name !== REPOSITORY || run.head_branch !== 'main' || !TRUSTED_EVENTS.has(run.event))
    throw new Error('Support ledger artifact source requires maintainer review')
  try {
    const encrypted = await download(latest)
    if (!Buffer.isBuffer(encrypted) || encrypted.length > MAX_LEDGER_BYTES + 1024)
      throw new Error('invalid checkpoint')
    return encrypted
  }
  catch {
    throw new Error('Latest Support ledger checkpoint download failed; refusing to reset')
  }
}

/**
 * Save encrypted local state and finalize each new pending intent remotely before its command can run.
 * A bounded batch reserves one final artifact; later work resumes with the original pending request.
 * @param {{path:string,secret:string,publish?:Function,initial?:object}} options Private local path and optional durable artifact adapter.
 * @returns {{save:Function,flush:Function}} Persistence seam used by the runner and its finalizer.
 * @throws {Error} If persistence fails or the remote checkpoint budget is exhausted.
 */
export function createLedgerWriter({ path, secret, publish, initial }) {
  let sequence = 0
  const checkpointed = new Set()
  let uploadedHash = initial ? createHash('sha256').update(JSON.stringify(initial)).digest('hex') : undefined
  let latest
  let latestHash
  async function upload() {
    await publish(latest, ++sequence)
    uploadedHash = latestHash
  }
  return {
    async save(ledger) {
      latest = encryptLedger(ledger, secret)
      latestHash = createHash('sha256').update(JSON.stringify(ledger)).digest('hex')
      await writeFile(`${path}.tmp`, latest, { mode: 0o600 })
      await rename(`${path}.tmp`, path)
      const pending = Object.entries(ledger.tickets ?? {}).filter(([, state]) => state.pending && !state.pending.blocked).map(([id, state]) => [id, state.pending])
      const keys = pending.map(value => createHash('sha256').update(JSON.stringify(value)).digest('hex'))
      if (publish && keys.some(key => !checkpointed.has(key))) {
        if (sequence >= 9)
          throw Object.assign(new Error('Support checkpoint batch is full; resume next run'), { code: 'CHECKPOINT_BUDGET_EXHAUSTED' })
        await upload()
        for (const key of keys)
          checkpointed.add(key)
      }
    },
    async flush() {
      if (publish && latest && latestHash !== uploadedHash)
        await upload()
    },
  }
}
