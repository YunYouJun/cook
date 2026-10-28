import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { readdirSync, realpathSync } from 'node:fs'
import { tmpdir } from 'node:os'
// eslint-disable-next-line test/no-import-node-test -- Ledger encryption and artifact transport are standalone Node automation tools.
import { test } from 'node:test'
import { decryptLedger, encryptLedger, loadLatestLedger, MAX_LEDGER_BYTES } from '../scripts/lib/support-ledger.mjs'

const secret = 'dedicated-random-ledger-secret-for-tests-only'
const privateLedger = {
  schemaVersion: 1,
  events: { 'private-event': { idempotencyKey: 'private-idempotency-key' } },
  tickets: { 'private-ticket-id': { replyDraft: 'private-user-reply' } },
  inputs: { 'private-event': { verifiedAt: '2026-10-28T12:00:00.000Z' } },
}
const repository = 'YunYouJun/cook'
const assetUrl = 'https://productionresultssa32.blob.core.windows.net/actions-results/private?sig=private-signature'

function artifact(id, { expired = false, runId = id, createdAt = new Date(Date.UTC(2026, 9, 28, 12, 0, id)).toISOString() } = {}) {
  return { id, name: 'cook-support-ledger', expired, workflow_run: { id: runId }, created_at: createdAt }
}

function workflowRun(id, fields = {}) {
  return {
    id,
    path: '.github/workflows/support-sync.yml',
    repository: { full_name: repository },
    head_repository: { full_name: repository },
    head_branch: 'main',
    event: 'push',
    status: 'completed',
    conclusion: 'failure',
    ...fields,
  }
}

function crc32(bytes) {
  let value = 0xFFFFFFFF
  for (const byte of bytes) {
    value ^= byte
    for (let bit = 0; bit < 8; bit++)
      value = (value >>> 1) ^ (value & 1 ? 0xEDB88320 : 0)
  }
  return (value ^ 0xFFFFFFFF) >>> 0
}

function zip(entries) {
  const local = []
  const central = []
  let offset = 0
  for (const [name, bytes] of entries) {
    const file = Buffer.from(name)
    const data = Buffer.from(bytes)
    const header = Buffer.alloc(30)
    header.writeUInt32LE(0x04034B50, 0)
    header.writeUInt16LE(20, 4)
    header.writeUInt32LE(crc32(data), 14)
    header.writeUInt32LE(data.length, 18)
    header.writeUInt32LE(data.length, 22)
    header.writeUInt16LE(file.length, 26)
    const record = Buffer.alloc(46)
    record.writeUInt32LE(0x02014B50, 0)
    record.writeUInt16LE(20, 4)
    record.writeUInt16LE(20, 6)
    record.writeUInt32LE(crc32(data), 16)
    record.writeUInt32LE(data.length, 20)
    record.writeUInt32LE(data.length, 24)
    record.writeUInt16LE(file.length, 28)
    record.writeUInt32LE(offset, 42)
    local.push(header, file, data)
    central.push(record, file)
    offset += header.length + file.length + data.length
  }
  const records = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054B50, 0)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(records.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...local, records, end])
}

function transport(pages, archive, { location = assetUrl, failedDownload = false, runFields = {}, runStatus = 200 } = {}) {
  const calls = []
  const fetcher = async (input, options) => {
    const url = new URL(input)
    const headers = new Headers(options.headers)
    assert.equal(options.redirect, 'manual')
    calls.push({ url, headers })
    if (url.origin === 'https://api.github.com') {
      assert.equal(headers.get('authorization'), 'Bearer test-token')
      if (url.pathname.endsWith('/actions/artifacts')) {
        assert.equal(url.searchParams.get('name'), 'cook-support-ledger')
        const page = Number(url.searchParams.get('page'))
        return new Response(JSON.stringify(pages[page - 1]), { status: 200 })
      }
      if (url.pathname.endsWith('/zip'))
        return new Response(null, { status: failedDownload ? 404 : 302, headers: { Location: location } })
      if (/\/actions\/runs\/\d+$/.test(url.pathname))
        return new Response(JSON.stringify(workflowRun(Number(url.pathname.split('/').at(-1)), runFields)), { status: runStatus })
    }
    assert.equal(headers.has('authorization'), false)
    return new Response(archive, { status: 200 })
  }
  return { calls, fetch: fetcher }
}

test('authenticated encryption round-trips private state and randomizes every envelope', () => {
  const first = encryptLedger(privateLedger, secret)
  const second = encryptLedger(privateLedger, secret)
  assert.ok(Buffer.isBuffer(first))
  assert.notDeepEqual(first, second)
  assert.deepEqual(decryptLedger(first, secret), privateLedger)
  assert.deepEqual(decryptLedger(second, secret), privateLedger)
  for (const text of ['private-ticket-id', 'private-user-reply', 'private-idempotency-key'])
    assert.equal(first.includes(Buffer.from(text)), false)
})

test('tampering, truncation, unknown versions, and a different secret never reset the ledger', () => {
  const encrypted = encryptLedger(privateLedger, secret)
  for (const index of [0, 8, 12, 45, 58, encrypted.length - 1]) {
    const changed = Buffer.from(encrypted)
    changed[index] ^= 1
    assert.throws(() => decryptLedger(changed, secret), /Support ledger/)
  }
  assert.throws(() => decryptLedger(encrypted.subarray(0, 60), secret), /envelope is invalid/)
  assert.throws(() => decryptLedger(encrypted, `${secret}-wrong`), /authentication or payload validation failed/)
  assert.throws(() => decryptLedger(encrypted.toString('base64'), secret), /envelope is invalid/)
})

test('schema, secret length, size, and non-JSON state are validated before persistence', () => {
  for (const ledger of [{}, { schemaVersion: 2 }, { schemaVersion: '1' }, [], null])
    assert.throws(() => encryptLedger(ledger, secret), /Support ledger/)
  for (const invalid of ['', 'short', null, Buffer.alloc(32)])
    assert.throws(() => encryptLedger(privateLedger, invalid), /dedicated secret/)
  for (const invalid of [undefined, Number.NaN, 1n, () => {}, new Date()])
    assert.throws(() => encryptLedger({ schemaVersion: 1, invalid }, secret), /payload is invalid/)
  const cycle = { schemaVersion: 1 }
  cycle.self = cycle
  assert.throws(() => encryptLedger(cycle, secret), /payload is invalid/)
  assert.throws(() => encryptLedger({ schemaVersion: 1, text: 'x'.repeat(MAX_LEDGER_BYTES) }, secret), /maximum size/)
  assert.throws(() => decryptLedger(Buffer.alloc(MAX_LEDGER_BYTES + 1024), secret), /maximum size/)
  assert.throws(() => encryptLedger(JSON.parse('{"schemaVersion":1,"__proto__":{}}'), secret), /payload is invalid/)
})

test('only explicit initialization with a completely empty artifact history returns null', async () => {
  const mock = transport([{ total_count: 0, artifacts: [] }])
  const options = { token: 'test-token', repository, fetch: mock.fetch }
  await assert.rejects(loadLatestLedger(options), /initialization must be explicit/)
  assert.equal(await loadLatestLedger({ ...options, allowInitialize: true }), null)
  for (const artifacts of [[artifact(1, { expired: true })], [artifact(1, { runId: 77 })]]) {
    const existing = transport([{ total_count: artifacts.length, artifacts }])
    await assert.rejects(loadLatestLedger({ ...options, fetch: existing.fetch, runId: 77, allowInitialize: true }), /refusing to reset/)
  }
})

test('complete pagination selects latest historical state and never sends the GitHub token to Azure', async () => {
  const encrypted = encryptLedger(privateLedger, secret)
  const all = Array.from({ length: 102 }, (_, index) => artifact(index + 1))
  const mock = transport([
    { total_count: 102, artifacts: all.slice(0, 100) },
    { total_count: 102, artifacts: all.slice(100) },
  ], zip([['ledger.enc', encrypted]]))
  const received = await loadLatestLedger({ token: 'test-token', repository, runId: 102, fetch: mock.fetch })
  assert.deepEqual(received, encrypted)
  assert.deepEqual(decryptLedger(received, secret), privateLedger)
  assert.equal(mock.calls.filter(call => call.url.pathname.endsWith('/actions/artifacts')).length, 2)
  assert.equal(mock.calls.find(call => call.url.pathname.endsWith('/zip')).url.pathname, '/repos/YunYouJun/cook/actions/artifacts/101/zip')
  assert.equal(mock.calls.find(call => /\/actions\/runs\/\d+$/.test(call.url.pathname)).url.pathname, '/repos/YunYouJun/cook/actions/runs/101')
  assert.equal(mock.calls.at(-1).headers.has('authorization'), false)
})

test('expired latest state, changed pagination, and inaccessible history fail closed', async () => {
  const histories = [
    [{ total_count: 2, artifacts: [artifact(1), artifact(2, { expired: true })] }],
    [{ total_count: 2, artifacts: [artifact(1)] }],
    [{ total_count: 2, artifacts: [artifact(1), artifact(1)] }],
    [{ total_count: 1, artifacts: [{ ...artifact(1), name: 'other-artifact' }] }],
    [{ total_count: 101, artifacts: Array.from({ length: 100 }, (_, index) => artifact(index + 1)) }, { total_count: 102, artifacts: [artifact(101)] }],
  ]
  for (const history of histories) {
    const mock = transport(history)
    await assert.rejects(loadLatestLedger({ token: 'test-token', repository, allowInitialize: true, fetch: mock.fetch }), /Support ledger/)
  }
  await assert.rejects(loadLatestLedger({ token: 'test-token', repository, allowInitialize: true, fetch: async () => new Response('private-body', { status: 403 }) }), /Unable to read/)
})

test('the newest artifact must come from the exact trusted main-branch workflow before download', async () => {
  const encrypted = encryptLedger(privateLedger, secret)
  const archive = zip([['ledger.enc', encrypted]])
  const history = [{ total_count: 2, artifacts: [artifact(1), artifact(2)] }]
  for (const runFields of [
    { path: '.github/workflows/ci.yml' },
    { path: '.github/workflows/support-sync.yml@refs/heads/other' },
    { repository: { full_name: 'another/repository' } },
    { head_repository: { full_name: 'attacker/cook' } },
    { head_repository: null },
    { head_branch: 'feature-branch' },
    { event: 'pull_request' },
    { event: 'pull_request_target' },
    { event: 'deployment_status' },
    { id: 1 },
  ]) {
    const mock = transport(history, archive, { runFields })
    await assert.rejects(loadLatestLedger({ token: 'test-token', repository, fetch: mock.fetch, allowInitialize: true }), /source requires maintainer review/)
    assert.equal(mock.calls.some(call => call.url.pathname.endsWith('/zip')), false)
    assert.equal(mock.calls.filter(call => /\/actions\/runs\/\d+$/.test(call.url.pathname)).length, 1, 'an untrusted latest artifact never falls back to older state')
    assert.ok(mock.calls.at(-1).url.pathname.endsWith('/actions/runs/2'))
  }
})

test('trusted push, schedule and manual runs retain failed-run intents without requiring success', async () => {
  const encrypted = encryptLedger(privateLedger, secret)
  for (const event of ['push', 'schedule', 'workflow_dispatch']) {
    const mock = transport([{ total_count: 1, artifacts: [artifact(1)] }], zip([['ledger.enc', encrypted]]), { runFields: { event, conclusion: 'failure' } })
    assert.deepEqual(await loadLatestLedger({ token: 'test-token', repository, fetch: mock.fetch }), encrypted)
  }
})

test('unavailable or malformed run provenance fails closed and never exposes provider messages', async () => {
  for (const runStatus of [403, 404, 503]) {
    const mock = transport([{ total_count: 1, artifacts: [artifact(1)] }], undefined, { runStatus })
    await assert.rejects(loadLatestLedger({ token: 'test-token', repository, fetch: mock.fetch, allowInitialize: true }), /source requires maintainer review/)
    assert.equal(mock.calls.some(call => call.url.pathname.endsWith('/zip')), false)
  }
  for (const failure of ['network', 'json']) {
    const mock = transport([{ total_count: 1, artifacts: [artifact(1)] }])
    const fetcher = (url, options) => {
      if (/\/actions\/runs\/\d+$/.test(new URL(url).pathname)) {
        if (failure === 'network')
          throw new Error(`private provider ${assetUrl}`)
        return new Response('private provider body is not JSON', { status: 200 })
      }
      return mock.fetch(url, options)
    }
    await assert.rejects(loadLatestLedger({ token: 'test-token', repository, fetch: fetcher, allowInitialize: true }), error => /source requires maintainer review/.test(error.message) && !error.message.includes('private provider') && !error.message.includes('private-signature'))
  }
})

test('download redirects are allowlisted and signed URL errors cannot leak into logs', async () => {
  for (const location of ['https://evil.example/ledger?sig=private-signature', 'http://productionresultssa32.blob.core.windows.net/ledger', 'https://user:password@productionresultssa32.blob.core.windows.net/ledger', 'https://productionresultssa32.blob.core.windows.net:444/ledger']) {
    const mock = transport([{ total_count: 1, artifacts: [artifact(1)] }], undefined, { location })
    await assert.rejects(loadLatestLedger({ token: 'test-token', repository, fetch: mock.fetch }), error => /redirect is invalid/.test(error.message) && !error.message.includes('private-signature'))
    assert.equal(mock.calls.length, 3)
  }
  await assert.rejects(loadLatestLedger({
    token: 'test-token',
    repository,
    fetch: async () => {
      throw new Error(`private failure ${assetUrl}`)
    },
  }), error => !error.message.includes('private-signature') && /Unable to read/.test(error.message))
})

test('only one exact ledger.enc member is read; traversal, duplicate, malformed, and oversized archives fail', async () => {
  const encrypted = encryptLedger(privateLedger, secret)
  const before = readdirSync(realpathSync(tmpdir())).filter(name => name.startsWith('cook-support-ledger-')).sort()
  const archives = [
    zip([['../ledger.enc', encrypted]]),
    zip([['ledger.enc', encrypted], ['private.json', Buffer.from('private-data')]]),
    zip([['ledger.enc', encrypted], ['ledger.enc', encrypted]]),
    zip([['ledger.enc', Buffer.from('plain private-data')]]),
    zip([['ledger.enc', Buffer.alloc(MAX_LEDGER_BYTES + 1024)]]),
    Buffer.from('not a zip archive'),
  ]
  for (const archive of archives) {
    const mock = transport([{ total_count: 1, artifacts: [artifact(1)] }], archive)
    await assert.rejects(loadLatestLedger({ token: 'test-token', repository, fetch: mock.fetch }), /artifact archive is invalid/)
  }
  const after = readdirSync(realpathSync(tmpdir())).filter(name => name.startsWith('cook-support-ledger-')).sort()
  assert.deepEqual(after, before, 'all extraction directories are removed in finally')
})

test('transport rejects another repository, malformed run identity, and overlarge responses', async () => {
  await assert.rejects(loadLatestLedger({ token: 'test-token', repository: 'other/repo' }), /configuration is invalid/)
  await assert.rejects(loadLatestLedger({ token: 'test-token', repository, runId: '../1' }), /run identity is invalid/)
  await assert.rejects(loadLatestLedger({ token: 'test-token', repository, fetch: async () => new Response('x', { headers: { 'Content-Length': 2 * 1024 * 1024 } }) }), /history is invalid/)
  const normal = transport([{ total_count: 1, artifacts: [artifact(1)] }], Buffer.alloc(2 * 1024 * 1024 + 1))
  await assert.rejects(loadLatestLedger({ token: 'test-token', repository, fetch: normal.fetch }), /maximum size/)
})
