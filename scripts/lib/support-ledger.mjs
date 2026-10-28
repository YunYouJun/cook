import { Buffer } from 'node:buffer'
import { execFileSync } from 'node:child_process'
import { createCipheriv, createDecipheriv, hkdfSync, randomBytes } from 'node:crypto'
import { mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const MAGIC = Buffer.from('COOKLDGR')
const FORMAT_VERSION = 1
const SALT_BYTES = 32
const IV_BYTES = 12
const TAG_BYTES = 16
const HEADER_BYTES = MAGIC.length + 1 + SALT_BYTES + IV_BYTES
const KEY_CONTEXT = 'cook-support-ledger-v1'
const ARTIFACT_NAME = 'cook-support-ledger'
const REPOSITORY = 'YunYouJun/cook'
const TRUSTED_WORKFLOW = '.github/workflows/support-sync.yml'
const TRUSTED_EVENTS = new Set(['push', 'schedule', 'workflow_dispatch'])
const API_ORIGIN = 'https://api.github.com'
const MAX_ARCHIVE_BYTES = 2 * 1024 * 1024
const MAX_METADATA_BYTES = 1024 * 1024
const ASSET_HOST = /^productionresultssa\d+\.blob\.core\.windows\.net$/

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

async function cancelBody(response) {
  try {
    await response.body?.cancel()
  }
  catch {
    // Cancellation errors are not useful evidence and may contain transport URLs.
  }
}

async function readLimitedBody(response, limit, message) {
  const length = response.headers.get('content-length')
  if (length && (!/^\d+$/.test(length) || Number(length) > limit)) {
    await cancelBody(response)
    throw new Error(message)
  }
  if (!response.body)
    throw new Error(message)
  const chunks = []
  let size = 0
  const reader = response.body.getReader()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done)
        break
      size += value.length
      if (size > limit) {
        await reader.cancel()
        throw new Error(message)
      }
      chunks.push(Buffer.from(value))
    }
  }
  catch {
    throw new Error(message)
  }
  finally {
    reader.releaseLock()
  }
  return Buffer.concat(chunks, size)
}

async function request(fetcher, url, headers, message) {
  try {
    return await fetcher(url, { headers, redirect: 'manual', signal: AbortSignal.timeout(15_000) })
  }
  catch {
    // Network exceptions may include signed asset URLs; never forward their messages.
    throw new Error(message)
  }
}

function extractEncryptedLedger(archive) {
  const temporary = mkdtempSync(join(realpathSync(tmpdir()), 'cook-support-ledger-'))
  try {
    const path = join(temporary, 'artifact.zip')
    writeFileSync(path, archive, { mode: 0o600, flag: 'wx' })
    const entries = execFileSync('unzip', ['-Z', '-1', path], { encoding: 'utf8', maxBuffer: 1024, stdio: ['ignore', 'pipe', 'pipe'] })
      .trimEnd()
      .split('\n')
    if (entries.length !== 1 || entries[0] !== 'ledger.enc')
      throw new Error('Unexpected archive contents')
    // Extract only stdout: no member path, symlink, or traversal is written to disk.
    const encrypted = execFileSync('unzip', ['-p', path, 'ledger.enc'], { maxBuffer: HEADER_BYTES + TAG_BYTES + MAX_LEDGER_BYTES, stdio: ['ignore', 'pipe', 'pipe'] })
    if (encrypted.length <= HEADER_BYTES + TAG_BYTES || !encrypted.subarray(0, MAGIC.length).equals(MAGIC) || encrypted[MAGIC.length] !== FORMAT_VERSION)
      throw new Error('Unexpected encrypted ledger framing')
    return encrypted
  }
  catch {
    throw new Error('Support ledger artifact archive is invalid')
  }
  finally {
    rmSync(temporary, { recursive: true, force: true })
  }
}

async function verifyArtifactSource(artifact, fetcher, headers) {
  const message = 'Support ledger artifact source requires maintainer review'
  const url = `${API_ORIGIN}/repos/${REPOSITORY}/actions/runs/${artifact.workflow_run.id}`
  const response = await request(fetcher, url, headers, message)
  if (response.status !== 200) {
    await cancelBody(response)
    throw new Error(message)
  }
  const body = await readLimitedBody(response, MAX_METADATA_BYTES, message)
  let run
  try {
    run = JSON.parse(body.toString('utf8'))
  }
  catch {
    throw new Error(message)
  }
  if (run?.id !== artifact.workflow_run.id || run.path !== TRUSTED_WORKFLOW || run.repository?.full_name !== REPOSITORY || run.head_repository?.full_name !== REPOSITORY || run.head_branch !== 'main' || !TRUSTED_EVENTS.has(run.event))
    throw new Error(message)
  // Failed trusted runs may contain the only persisted record of an unknown command result.
  // A same-name artifact from an untrusted run must not trigger fallback to older state.
}

/**
 * Download the newest historical private ledger without treating missing history as a reset.
 *
 * @param {{ token: string, repository: 'YunYouJun/cook', runId?: string | number, allowInitialize?: boolean, fetch?: typeof globalThis.fetch }} options GitHub read token and current run identity.
 * @returns {Promise<Buffer | null>} Encrypted ledger bytes, or null only after explicit first-use initialization with no historical artifact.
 * @throws {Error} If history is expired, inaccessible, malformed, incomplete, from an untrusted run, or has an unsafe download redirect.
 */
export async function loadLatestLedger({ token, repository, runId, allowInitialize = false, fetch: fetcher = globalThis.fetch }) {
  if (repository !== REPOSITORY || typeof token !== 'string' || !token || /[\r\n]/.test(token) || typeof fetcher !== 'function')
    throw new Error('Support ledger GitHub configuration is invalid')
  if (runId !== undefined && (!/^\d+$/.test(String(runId)) || !Number.isSafeInteger(Number(runId)) || Number(runId) < 1))
    throw new Error('Support ledger run identity is invalid')
  const headers = { 'Authorization': `Bearer ${token}`, 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' }
  const artifacts = []
  let complete = false
  let expectedTotal
  for (let page = 1; page <= 100; page++) {
    const url = `${API_ORIGIN}/repos/${REPOSITORY}/actions/artifacts?name=${ARTIFACT_NAME}&per_page=100&page=${page}`
    const response = await request(fetcher, url, headers, 'Unable to read Support ledger artifact history')
    if (response.status !== 200) {
      await cancelBody(response)
      throw new Error('Unable to read Support ledger artifact history')
    }
    const body = await readLimitedBody(response, MAX_METADATA_BYTES, 'Support ledger artifact history is invalid')
    let data
    try {
      data = JSON.parse(body.toString('utf8'))
    }
    catch {
      throw new Error('Support ledger artifact history is invalid')
    }
    if (!Number.isSafeInteger(data?.total_count) || data.total_count < 0 || !Array.isArray(data.artifacts) || data.artifacts.length > 100)
      throw new Error('Support ledger artifact history is invalid')
    if (expectedTotal !== undefined && expectedTotal !== data.total_count)
      throw new Error('Support ledger artifact history changed during pagination')
    expectedTotal = data.total_count
    for (const artifact of data.artifacts) {
      if (artifact?.name !== ARTIFACT_NAME || !Number.isSafeInteger(artifact.id) || artifact.id < 1 || typeof artifact.expired !== 'boolean' || !Number.isSafeInteger(artifact.workflow_run?.id) || artifact.workflow_run.id < 1 || typeof artifact.created_at !== 'string' || !Number.isFinite(Date.parse(artifact.created_at)))
        throw new Error('Support ledger artifact history is invalid')
      artifacts.push(artifact)
    }
    if (data.artifacts.length < 100) {
      complete = true
      break
    }
  }
  if (!complete || artifacts.length !== expectedTotal || new Set(artifacts.map(artifact => artifact.id)).size !== artifacts.length)
    throw new Error('Support ledger artifact history is incomplete')
  if (!artifacts.length) {
    if (allowInitialize === true)
      return null
    throw new Error('Support ledger history is absent; first-use initialization must be explicit')
  }
  const history = artifacts.filter(artifact => String(artifact.workflow_run.id) !== String(runId))
    .sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at) || b.id - a.id)
  const latest = history[0]
  if (!latest || latest.expired)
    throw new Error('Latest Support ledger history is unavailable; refusing to reset')
  await verifyArtifactSource(latest, fetcher, headers)
  const download = `${API_ORIGIN}/repos/${REPOSITORY}/actions/artifacts/${latest.id}/zip`
  const redirect = await request(fetcher, download, headers, 'Unable to download Support ledger artifact')
  if (redirect.status !== 302) {
    await cancelBody(redirect)
    throw new Error('Unable to download Support ledger artifact')
  }
  let asset
  try {
    asset = new URL(redirect.headers.get('location'))
  }
  catch {
    throw new Error('Support ledger artifact redirect is invalid')
  }
  if (asset.protocol !== 'https:' || asset.username || asset.password || asset.port || asset.hash || !ASSET_HOST.test(asset.hostname))
    throw new Error('Support ledger artifact redirect is invalid')
  await cancelBody(redirect)
  // The signed Azure URL is sufficient authorization; never forward the GitHub token.
  const response = await request(fetcher, asset.href, { Accept: 'application/octet-stream' }, 'Unable to download Support ledger artifact')
  if (response.status !== 200) {
    await cancelBody(response)
    throw new Error('Unable to download Support ledger artifact')
  }
  const archive = await readLimitedBody(response, MAX_ARCHIVE_BYTES, 'Support ledger artifact exceeds the maximum size')
  return extractEncryptedLedger(archive)
}
