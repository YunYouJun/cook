import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// eslint-disable-next-line test/no-import-node-test -- Private release automation uses the standalone Node runtime.
import { test } from 'node:test'
import { createLedgerWriter, decryptLedger, encryptLedger, loadLatestLedger, MAX_LEDGER_BYTES } from '../scripts/lib/support-ledger.mjs'

const secret = 'dedicated-random-ledger-secret-for-tests-only'
const privateLedger = {
  schemaVersion: 1,
  events: { 'private-event': { idempotencyKey: 'private-idempotency-key' } },
  tickets: { 'private-ticket-id': { replyDraft: 'private-user-reply' } },
  inputs: { 'private-event': { verifiedAt: '2026-10-28T12:00:00.000Z' } },
}
const repository = 'YunYouJun/cook'
function artifact(id, extra = {}) {
  return { id, name: `cook-support-ledger-${id}-1-000001.enc`, expired: false, workflow_run: { id }, created_at: new Date(Date.UTC(2026, 9, 28, 12, 0, id)).toISOString(), size_in_bytes: 512, digest: `sha256:${'a'.repeat(64)}`, ...extra }
}
function workflowRun(id, fields = {}) {
  return { id, path: '.github/workflows/support-sync.yml', repository: { full_name: repository }, head_repository: { full_name: repository }, head_branch: 'main', event: 'push', status: 'completed', conclusion: 'failure', ...fields }
}
function transport(pages, { runFields = {}, status = 200, downloadFailure = false } = {}) {
  const calls = []
  const downloaded = []
  return {
    calls,
    downloaded,
    fetch: async (input, options) => {
      const url = new URL(input)
      assert.equal(new Headers(options.headers).get('authorization'), 'Bearer test-token')
      assert.equal(url.origin, 'https://api.github.com')
      calls.push(url)
      const data = url.pathname.endsWith('/actions/artifacts') ? pages[Number(url.searchParams.get('page')) - 1] : workflowRun(Number(url.pathname.split('/').at(-1)), runFields)
      return new Response(JSON.stringify(data), { status })
    },
    download: async (selected) => {
      downloaded.push(selected)
      if (downloadFailure)
        throw new Error('private signed URL token must not escape')
      return encryptLedger(privateLedger, secret)
    },
  }
}
const load = (mock, extra = {}) => loadLatestLedger({ token: 'test-token', repository, fetch: mock.fetch, download: mock.download, ...extra })

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

test('initialization requires a completely empty ledger history and explicit authorization', async () => {
  const empty = transport([{ total_count: 0, artifacts: [] }])
  await assert.rejects(load(empty), /initialization must be explicit/)
  assert.equal(await load(empty, { allowInitialize: true }), null)
  const expired = transport([{ total_count: 1, artifacts: [artifact(1, { expired: true })] }])
  await assert.rejects(load(expired, { allowInitialize: true }), /refusing to reset/)
})

test('complete history selects newest checkpoint even for an older run rerun', async () => {
  const all = Array.from({ length: 102 }, (_, i) => artifact(i + 1))
  all[0] = artifact(1, { name: 'cook-support-ledger-1-2-000003.enc', created_at: '2026-10-29T00:00:00Z' })
  all[4] = { id: 5, name: 'unrelated-build-output' }
  const mock = transport([{ total_count: 102, artifacts: all.slice(0, 100) }, { total_count: 102, artifacts: all.slice(100) }])
  assert.deepEqual(decryptLedger(await load(mock), secret), privateLedger)
  assert.equal(mock.downloaded[0].id, 1)
  assert.equal(mock.calls.filter(url => url.pathname.endsWith('/actions/artifacts')).length, 2)
})

test('earlier attempts of the current workflow run retain their pending intent', async () => {
  const mock = transport([{ total_count: 1, artifacts: [artifact(77, { name: 'cook-support-ledger-77-1-000009.enc' })] }])
  await load(mock, { runId: 77 })
  assert.equal(mock.downloaded[0].workflow_run.id, 77)
})

test('untrusted latest workflow never falls back to an older checkpoint', async () => {
  for (const runFields of [
    { path: '.github/workflows/ci.yml' },
    { path: '.github/workflows/support-sync.yml@refs/heads/other' },
    { repository: { full_name: 'another/repository' } },
    { head_repository: { full_name: 'attacker/cook' } },
    { head_repository: null },
    { head_branch: 'other' },
    { event: 'pull_request' },
    { event: 'pull_request_target' },
    { id: 1 },
  ]) {
    const mock = transport([{ total_count: 2, artifacts: [artifact(1), artifact(2)] }], { runFields })
    await assert.rejects(load(mock, { allowInitialize: true }), /source requires maintainer review/)
    assert.equal(mock.downloaded.length, 0)
    assert.equal(mock.calls.at(-1).pathname, '/repos/YunYouJun/cook/actions/runs/2')
  }
})

test('failed trusted push, schedule and manual runs can restore their last finalized intent', async () => {
  for (const event of ['push', 'schedule', 'workflow_dispatch']) {
    const mock = transport([{ total_count: 1, artifacts: [artifact(1)] }], { runFields: { event, conclusion: 'failure' } })
    assert.deepEqual(decryptLedger(await load(mock), secret), privateLedger)
  }
})

test('malformed, incomplete, changed or expired newest history fails closed', async () => {
  const histories = [
    [{ total_count: 2, artifacts: [artifact(1)] }],
    [{ total_count: 2, artifacts: [artifact(1), artifact(1)] }],
    [{ total_count: 1, artifacts: [artifact(1, { name: 'cook-support-ledger-invalid' })] }],
    [{ total_count: 1, artifacts: [artifact(1, { name: 'cook-support-ledger-2-1-000001.enc' })] }],
    [{ total_count: 1, artifacts: [artifact(1, { digest: 'invalid' })] }],
    [{ total_count: 1, artifacts: [artifact(1, { size_in_bytes: 3 * MAX_LEDGER_BYTES })] }],
    [{ total_count: 2, artifacts: [artifact(1), artifact(2, { expired: true })] }],
    [{ total_count: 101, artifacts: Array.from({ length: 100 }, (_, i) => artifact(i + 1)) }, { total_count: 102, artifacts: [artifact(101)] }],
  ]
  for (const pages of histories)
    await assert.rejects(load(transport(pages), { allowInitialize: true }), /Support ledger/)
})

test('unavailable metadata or checkpoint downloads never expose upstream private errors', async () => {
  const mock = transport([{ total_count: 1, artifacts: [artifact(1)] }], { downloadFailure: true })
  await assert.rejects(load(mock), error => /refusing to reset/.test(error.message) && !error.message.includes('signed URL'))
  for (const fetch of [async () => {
    throw new Error('private signed URL')
  }, async () => new Response('private', { status: 403 }), async () => new Response('invalid JSON'), async () => new Response('x', { headers: { 'content-length': 2 * MAX_LEDGER_BYTES } })])
    await assert.rejects(loadLatestLedger({ token: 'test-token', repository, fetch }), error => /metadata/.test(error.message) && !error.message.includes('private'))
  await assert.rejects(loadLatestLedger({ token: 'test-token', repository: 'another/repository' }), /configuration/)
})

async function withWriter(run) {
  const directory = await mkdtemp(join(tmpdir(), 'cook-ledger-writer-test-'))
  const path = join(directory, 'ledger.enc')
  const remote = []
  const writer = createLedgerWriter({ path, secret, publish: async (bytes, sequence) => remote.push({ bytes: Buffer.from(bytes), sequence }) })
  try {
    await run({ writer, path, remote })
  }
  finally { await rm(directory, { recursive: true, force: true }) }
}
const intent = key => ({ phase: 'publish-resolution', key, input: { id: 'synthetic-ticket', version: 3, command: { action: 'reply.send' } } })

test('command intent is finalized remotely before save returns, surviving total local runner loss', async () => {
  await withWriter(async ({ writer, path, remote }) => {
    const ledger = { schemaVersion: 1, tickets: { test: { pending: intent('stable-command-key') } }, events: {}, inputs: {} }
    await writer.save(ledger)
    assert.equal(remote.length, 1)
    assert.deepEqual(decryptLedger(remote[0].bytes, secret), ledger)
    assert.equal((await stat(path)).mode & 0o077, 0)
    await rm(path)
    const restored = decryptLedger(remote[0].bytes, secret)
    assert.deepEqual(restored.tickets.test.pending, ledger.tickets.test.pending)
    // A killed runner does not need flush() for recovery of the command it was about to send.
  })
})

test('unchanged pending intent is checkpointed once; final state is retained separately', async () => {
  await withWriter(async ({ writer, path, remote }) => {
    const ledger = { schemaVersion: 1, tickets: { test: { pending: intent('stable-command-key') } } }
    await writer.save(ledger)
    await writer.save(ledger)
    assert.equal(remote.length, 1)
    delete ledger.tickets.test.pending
    await writer.save(ledger)
    assert.equal(remote.length, 1)
    await writer.flush()
    assert.equal(remote.length, 2)
    assert.deepEqual(decryptLedger(remote[1].bytes, secret), ledger)
    assert.deepEqual(decryptLedger(await readFile(path), secret), ledger)
  })
})

test('failed remote persistence prevents the caller from sending any command', async () => {
  await withWriter(async ({ path }) => {
    let commands = 0
    const writer = createLedgerWriter({ path, secret, publish: async () => {
      throw new Error('upload failed')
    } })
    const send = async () => {
      await writer.save({ schemaVersion: 1, tickets: { test: { pending: intent('stable-command-key') } } })
      commands++
    }
    await assert.rejects(send(), /upload failed/)
    assert.equal(commands, 0)
  })
})

test('checkpoint batch stops before the tenth command and reserves a final recoverable intent', async () => {
  await withWriter(async ({ writer, remote }) => {
    const ledger = { schemaVersion: 1, tickets: { test: {} } }
    for (let i = 1; i <= 9; i++) {
      ledger.tickets.test.pending = intent(`command-${i}`)
      await writer.save(ledger)
      delete ledger.tickets.test.pending
      await writer.save(ledger)
    }
    ledger.tickets.test.pending = intent('command-10')
    await assert.rejects(writer.save(ledger), error => error.code === 'CHECKPOINT_BUDGET_EXHAUSTED')
    assert.equal(remote.length, 9)
    await writer.flush()
    assert.equal(remote.length, 10)
    assert.deepEqual(decryptLedger(remote.at(-1).bytes, secret).tickets.test.pending, intent('command-10'))
  })
})

test('unchanged polling reuses remote state until its daily retention refresh', async () => {
  await withWriter(async ({ path, remote }) => {
    const ledger = { schemaVersion: 1, tickets: {}, checkpointedAt: '2026-10-28T12:00:00Z' }
    const writer = createLedgerWriter({ path, secret, initial: ledger, publish: async bytes => remote.push(bytes) })
    await writer.save(ledger)
    await writer.flush()
    assert.equal(remote.length, 0)
    ledger.checkpointedAt = '2026-10-29T12:00:00Z'
    await writer.save(ledger)
    await writer.flush()
    assert.equal(remote.length, 1)
  })
})
