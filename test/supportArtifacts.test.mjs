import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { createHash } from 'node:crypto'
import { mkdtemp, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// eslint-disable-next-line test/no-import-node-test -- Artifact workers are standalone Node automation without app dependencies.
import { test } from 'node:test'
import { createSupportArtifactStore } from '../scripts/lib/support-artifacts.mjs'

const encrypted = Buffer.from('COOKLDGR-synthetic-encrypted-checkpoint')
const digest = `sha256:${createHash('sha256').update(encrypted).digest('hex')}`
const name = 'cook-support-ledger-41-2-000001.enc'
const artifact = (extra = {}) => ({ id: 77, name, size_in_bytes: encrypted.length, digest, workflow_run: { id: 41 }, ...extra })

async function fixture(source, exercise) {
  const temporary = await mkdtemp(join(tmpdir(), 'cook-support-artifacts-test-'))
  try {
    const modulePath = join(temporary, 'fake-sdk.mjs')
    const directory = join(temporary, 'state')
    const trace = join(temporary, 'trace.json')
    await writeFile(modulePath, `import { readFile, writeFile, lstat, symlink } from 'node:fs/promises'\nimport { basename, join } from 'node:path'\nimport { createHash } from 'node:crypto'\nimport { Buffer } from 'node:buffer'\nconst trace = ${JSON.stringify(trace)}\nconst encrypted = Buffer.from(${JSON.stringify(encrypted.toString('base64'))}, 'base64')\nexport class DefaultArtifactClient { ${source} }\n`, { mode: 0o600 })
    const store = createSupportArtifactStore({ modulePath, directory, runId: '41', runAttempt: '2' })
    await exercise({ store, trace, directory, temporary })
    const entry = await stat(directory).catch(error => error.code === 'ENOENT' ? undefined : Promise.reject(error))
    if (entry) {
      assert.deepEqual(await readdir(directory), [], 'all per-operation staging files are reclaimed')
      assert.equal(entry.mode & 0o077, 0)
    }
  }
  finally {
    await rm(temporary, { recursive: true, force: true })
  }
}

test('uploads finalize immutable attempt-scoped raw checkpoints before returning an ID', async () => {
  await fixture(`async uploadArtifact(name, files, root, options) {
    const bytes = await readFile(files[0])
    await writeFile(trace, JSON.stringify({ name, files: files.map(path => basename(path)), rootMatches: root === join(files[0], '..'), options, mode: (await lstat(files[0])).mode & 0o777 }))
    return { id: 77, size: bytes.length, digest: createHash('sha256').update(bytes).digest('hex') }
  }`, async ({ store, trace }) => {
    assert.deepEqual(await store.upload(encrypted, 1), { id: 77, size: encrypted.length, digest })
    const value = JSON.parse(await readFile(trace, 'utf8'))
    assert.equal(value.name, name)
    assert.deepEqual(value.files, [name])
    assert.equal(value.rootMatches, true)
    assert.equal(value.mode, 0o600)
    assert.deepEqual(value.options, { retentionDays: 90, compressionLevel: 0, skipArchive: true })
  })
})

test('downloads only the exact raw checkpoint with SDK digest verification and private staging', async () => {
  await fixture(`async downloadArtifact(id, options) {
    await writeFile(trace, JSON.stringify({ id, ...options, tokenPresent: Object.hasOwn(options.findBy, 'token') }))
    await writeFile(join(options.path, '${name}'), encrypted)
    return { digestMismatch: false }
  }`, async ({ store, trace }) => {
    assert.deepEqual(await store.download(artifact()), encrypted)
    const value = JSON.parse(await readFile(trace, 'utf8'))
    assert.equal(value.id, 77)
    assert.equal(value.skipDecompress, true)
    assert.equal(value.expectedHash, digest)
    assert.equal(value.findBy.workflowRunId, 41)
    assert.equal(value.findBy.repositoryOwner, 'YunYouJun')
    assert.equal(value.findBy.repositoryName, 'cook')
  })
})

test('legacy checkpoints use SDK extraction and still require exactly ledger.enc', async () => {
  await fixture(`async downloadArtifact(id, options) {
    await writeFile(trace, JSON.stringify(options))
    await writeFile(join(options.path, 'ledger.enc'), encrypted)
    return { digestMismatch: false }
  }`, async ({ store, trace }) => {
    assert.deepEqual(await store.download(artifact({ name: 'cook-support-ledger' })), encrypted)
    assert.equal(JSON.parse(await readFile(trace, 'utf8')).skipDecompress, false)
  })
})

test('SDK warnings and thrown transport payloads never escape the sanitized worker failure', async () => {
  await fixture(`async uploadArtifact() {
    console.warn('SYNTHETIC_PRIVATE_TOKEN https://example.invalid/?sig=SYNTHETIC_PRIVATE_TOKEN')
    throw new Error('SYNTHETIC_PRIVATE_TOKEN')
  }`, async ({ store }) => {
    await assert.rejects(store.upload(encrypted, 1), (error) => {
      assert.equal(error.message, 'Support artifact operation failed; retain the previous checkpoint')
      assert(!JSON.stringify(error).includes('SYNTHETIC_PRIVATE_TOKEN'))
      assert(!String(error).includes('SYNTHETIC_PRIVATE_TOKEN'))
      return true
    })
  })
})

test('incomplete uploads and wrong finalized digests never return success', async () => {
  for (const result of [{ size: encrypted.length, digest: digest.slice(7) }, { id: 77, size: encrypted.length, digest: 'f'.repeat(64) }]) {
    await fixture(`async uploadArtifact() { return ${JSON.stringify(result)} }`, async ({ store }) => {
      await assert.rejects(store.upload(encrypted, 1), /Support artifact operation failed/)
    })
  }
})

test('digest mismatches, extra files, symlinks and oversized downloads fail closed', async () => {
  for (const behavior of [
    `await writeFile(join(options.path, '${name}'), encrypted); return { digestMismatch: true }`,
    `await writeFile(join(options.path, '${name}'), encrypted); await writeFile(join(options.path, 'extra.txt'), 'unexpected'); return { digestMismatch: false }`,
    `await writeFile(join(options.path, 'ledger.enc'), encrypted); return { digestMismatch: false }`,
    `await symlink(trace, join(options.path, '${name}')); return { digestMismatch: false }`,
    `await writeFile(join(options.path, '${name}'), Buffer.alloc(1024 * 1024 + 1025)); return { digestMismatch: false }`,
    `await writeFile(join(options.path, '${name}'), 'tampered'); return { digestMismatch: false }`,
  ]) {
    await fixture(`async downloadArtifact(id, options) { ${behavior} }`, async ({ store }) => {
      await assert.rejects(store.download(artifact()), /Support artifact operation failed/)
    })
  }
})

test('invalid checkpoint identities and metadata are rejected before running the SDK', async () => {
  assert.throws(() => createSupportArtifactStore({ modulePath: 'relative.mjs', directory: '/tmp', runId: 1, runAttempt: 1 }), /Support artifact operation failed/)
  assert.throws(() => createSupportArtifactStore({ modulePath: '/tmp/sdk.mjs', directory: '/tmp', runId: 0, runAttempt: 1 }), /Support artifact operation failed/)
  await fixture(`async downloadArtifact() { throw new Error('must not run') }`, async ({ store, directory }) => {
    for (const patch of [{ name: '../escape.enc' }, { digest: 'invalid' }, { size_in_bytes: 3 * 1024 * 1024 }, { workflow_run: { id: 0 } }])
      await assert.rejects(store.download(artifact(patch)), /Support artifact operation failed/)
    await assert.rejects(store.upload(encrypted, 0), /Support artifact operation failed/)
    await assert.rejects(store.upload(encrypted, 1_000_000), /Support artifact operation failed/)
    // Invalid inputs never create staging directories.
    await assert.rejects(stat(directory), { code: 'ENOENT' })
  })
})
