import assert from 'node:assert/strict'
// eslint-disable-next-line test/no-import-node-test -- The production bridge and its offline gate use only the Node runtime.
import { it } from 'node:test'
import { parseCookDeliveryBindings, parseCookDeliveryEvent, syncCookDelivery } from '../scripts/lib/cook-delivery-sync.mjs'
import { decryptLedger, encryptLedger } from '../scripts/lib/support-ledger.mjs'

const sha = 'a'.repeat(40)
const head = 'b'.repeat(40)
const now = '2026-10-28T12:00:00.000Z'
const baseline = '2026-10-28T10:00:00.000Z'
const repository = 'YunYouJun/cook'
const repairFacts = { changes: ['展开电脑网页的食材选项'] }
const bindings = (extra = {}) => ({ schemaVersion: 1, tickets: [{ ticketId: 'ticket-1', repository, commitShas: [sha], pullRequests: [123], acceptanceProfile: 'desktop-layout-v1', fixSummary: repairFacts, ...extra }] })
const change = (extra = {}) => ({ schemaVersion: 1, eventKey: 'change:1', repository, kind: 'change', occurredAt: now, commitSha: sha, refs: [`https://github.com/${repository}/pull/123`], ...extra })
const deployment = (extra = {}) => ({ schemaVersion: 1, eventKey: 'deployment:1', repository, kind: 'deployment', occurredAt: now, commitSha: head, refs: [], verifiedChangeShas: [sha], deployment: { id: 'eo-deployment-1', provider: 'edgeone', environment: 'production', verified: true, sha: head, origin: 'https://cook.yunyoujun.cn', verifiedAt: now, acceptanceProfiles: ['desktop-layout-v1'], summary: 'Production desktop and mobile regression passed', evidenceRefs: ['https://github.com/YunYouJun/cook/actions/runs/42'] }, ...extra })
const reconcile = (key = 'reconcile:1', extra = {}) => deployment({ kind: 'reconcile', eventKey: key, ...extra })
const freshLedger = () => ({ schemaVersion: 1, events: {}, tickets: {} })

function harness(initial = {}) {
  const state = { ticket: { id: 'ticket-1', productId: 'cook', version: 1, status: 'new', lastRequesterMessageAt: baseline, work: { reproduction: 'maintainer reproduction', rootCause: 'maintainer root cause', changeRefs: [] }, ...initial }, messages: [], deliveries: [] }
  const calls = []
  let durable = freshLedger()
  const previous = new Map()
  let before
  let after
  const ports = {
    now: () => now,
    detail: async () => ({ detail: structuredClone(state) }),
    save: async (value) => {
      durable = structuredClone(value)
    },
    command: async (input, key) => {
      calls.push(structuredClone({ input, key }))
      if (before)
        await before(input, key)
      if (previous.has(key)) {
        assert.deepEqual(previous.get(key), input)
        return structuredClone(state.ticket)
      }
      if (state.ticket.version !== input.version)
        throw Object.assign(new Error('version'), { code: 'STATE_CONFLICT' })
      state.ticket.version++
      const command = input.command
      if (command.action === 'work') {
        state.ticket.work = structuredClone(command.work)
        delete state.ticket.latestReplyId
      }
      else if (command.action === 'status') {
        if (['resolved', 'closed'].includes(command.status)) {
          const reply = state.messages.find(item => item.id === state.ticket.latestReplyId)
          assert(state.ticket.work.verification && reply?.resolution)
          if (command.status === 'closed')
            assert(state.deliveries.some(item => item.messageId === reply.id && ['delivered', 'read'].includes(item.status)))
        }
        state.ticket.status = command.status
      }
      else if (command.action === 'reply.send') {
        const draft = state.ticket.work.replyDraft
        assert(draft?.resolution && state.ticket.work.verification)
        const id = `reply-${state.messages.length}`
        state.messages.push({ id, kind: 'reply', content: draft.content, resolution: draft.resolution, visibility: 'requester_visible', createdAt: now })
        state.deliveries.push({ id, ticketId: state.ticket.id, messageId: id, channel: 'portal', status: 'available' })
        state.ticket.latestReplyId = id
        delete state.ticket.work.replyDraft
      }
      else if (command.action === 'note') {
        state.messages.push({ id: `note-${state.messages.length}`, kind: 'internal_note', visibility: 'staff_only', content: command.content, createdAt: now })
      }
      previous.set(key, structuredClone(input))
      if (after)
        await after(input, key)
      return structuredClone(state.ticket)
    },
  }
  return {
    state,
    calls,
    ports,
    durable: () => structuredClone(durable),
    before: (value) => {
      before = value
    },
    after: (value) => {
      after = value
    },
  }
}

async function applied(h, event, ledger, options = {}) {
  return syncCookDelivery({ event, bindings: options.bindings ?? bindings(), ledger, apply: true, sendReplies: options.sendReplies }, h.ports)
}

it('validates EdgeOne production evidence, exact repository/origin, verified ancestry and safe references', () => {
  assert.equal(parseCookDeliveryEvent(deployment(), now).deployment.provider, 'edgeone')
  for (const patch of [{ provider: 'vercel' }, { environment: 'preview' }, { verified: false }, { origin: 'https://attacker.example' }, { verifiedAt: 'invalid' }, { sha: 'short' }, { evidenceRefs: [] }])
    assert.throws(() => parseCookDeliveryEvent(deployment({ deployment: { ...deployment().deployment, ...patch } }), now))
  assert.throws(() => parseCookDeliveryEvent(deployment({ verifiedChangeShas: [] }), now))
  assert.throws(() => parseCookDeliveryEvent({ schemaVersion: 1, eventKey: 'missing-proof', repository, kind: 'reconcile', occurredAt: now }, now))
  assert.throws(() => parseCookDeliveryEvent(reconcile('missing-sha', { commitSha: undefined }), now))
  assert.throws(() => parseCookDeliveryEvent(reconcile('missing-verified-commits', { verifiedChangeShas: [] }), now))
  assert.throws(() => parseCookDeliveryEvent(change({ refs: ['https://github.com/other/repo/pull/1'] }), now))
  assert.throws(() => parseCookDeliveryEvent(change({ repository: 'other/repo' }), now))
  assert.throws(() => parseCookDeliveryBindings({ ...bindings(), tickets: [...bindings().tickets, ...bindings().tickets] }))
  for (const fixSummary of [{ changes: [] }, { changes: [' '] }, { changes: 'unreviewed' }, { changes: ['fixed'], userAction: '' }, { changes: ['fixed'], extra: 'unreviewed' }])
    assert.throws(() => parseCookDeliveryBindings(bindings({ fixSummary })))
})

it('dry run queries but never mutates tickets, saves a ledger, or exposes private data', async () => {
  const h = harness()
  const ledger = freshLedger()
  const result = await syncCookDelivery({ event: change(), bindings: bindings(), ledger }, h.ports)
  assert.equal(result.dryRun, true)
  assert.equal(h.calls.length, 0)
  assert.deepEqual(ledger, freshLedger())
  assert.deepEqual(h.durable(), freshLedger())
  assert.doesNotMatch(JSON.stringify(result), /ticket-1|maintainer|root cause/)
})

it('associates commits/PR and optional issue while preserving maintainer work, and does not duplicate replayed events', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger, { bindings: bindings({ issue: 7 }) })
  assert.equal(h.state.ticket.status, 'in_progress')
  assert.equal(h.state.ticket.work.rootCause, 'maintainer root cause')
  assert(h.state.ticket.work.changeRefs.includes('https://github.com/YunYouJun/cook/issues/7'))
  assert(h.state.ticket.work.changeRefs.includes(`https://github.com/YunYouJun/cook/commit/${sha}`))
  assert.equal(h.state.ticket.work.verification, undefined)
  const count = h.calls.length
  await applied(h, change(), ledger, { bindings: bindings({ issue: 7 }) })
  assert.equal(h.calls.length, count)
  await assert.rejects(applied(h, change({ refs: [] }), ledger), { code: 'EVENT_CONFLICT' })
})

it('registers merged SHA from a bound PR so later descendant production deployments can reconcile', async () => {
  const h = harness()
  const ledger = freshLedger()
  const mapping = bindings({ commitShas: [] })
  await applied(h, change(), ledger, { bindings: mapping })
  await applied(h, deployment(), ledger, { bindings: mapping })
  assert.equal(h.state.ticket.work.verification.releaseRef, `edgeone:eo-deployment-1@${head}`)
})

it('unrelated events and untracked tickets cannot mutate or close a ticket', async () => {
  const h = harness()
  const ledger = freshLedger()
  assert.equal((await applied(h, change({ commitSha: head, refs: [] }), ledger)).matched, 0)
  assert.equal((await applied(h, reconcile(), ledger)).matched, 0)
  assert.equal(h.calls.length, 0)
})

it('deployment requires a prior requester baseline, then saves actual evidence and only a draft by default', async () => {
  const h = harness()
  const ledger = freshLedger()
  assert.equal((await applied(h, deployment(), ledger)).blocked, 1)
  assert.equal(h.calls.length, 0)
  await applied(h, change(), ledger)
  await applied(h, deployment(), ledger)
  assert.equal(h.state.ticket.work.verification.summary, deployment().deployment.summary)
  assert.equal(h.state.ticket.work.replyDraft.resolution, true)
  assert.equal(h.state.ticket.status, 'in_progress')
  assert.equal(h.state.messages.filter(item => item.resolution).length, 0)
})

it('requires both invocation and binding authorization before publishing a resolution', async () => {
  for (const sendReplies of [false, true]) {
    const h = harness()
    const ledger = freshLedger()
    await applied(h, change(), ledger)
    await applied(h, deployment(), ledger, { sendReplies, bindings: bindings({ sendReply: false }) })
    assert.equal(h.state.ticket.latestReplyId, undefined)
  }
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  await applied(h, deployment(), ledger, { bindings: bindings({ sendReply: true }), sendReplies: true })
  assert.equal(h.state.ticket.status, 'resolved')
  assert.equal(h.state.deliveries[0].status, 'available')
})

it('available or unrelated/non-portal receipts cannot close; matching actual portal delivery closes', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  await applied(h, deployment(), ledger, { bindings: bindings({ sendReply: true }), sendReplies: true })
  h.state.deliveries.push({ ticketId: 'other', messageId: h.state.ticket.latestReplyId, channel: 'portal', status: 'delivered' }, { ticketId: 'ticket-1', messageId: 'other', channel: 'portal', status: 'delivered' }, { ticketId: 'ticket-1', messageId: h.state.ticket.latestReplyId, channel: 'wechat', status: 'delivered' })
  await applied(h, reconcile(), ledger)
  assert.equal(h.state.ticket.status, 'resolved')
  h.state.deliveries[0].status = 'delivered'
  await applied(h, reconcile('reconcile:2'), ledger)
  assert.equal(h.state.ticket.status, 'closed')
})

it('new requester input after a fix invalidates automatic release and closure', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  h.state.ticket.lastRequesterMessageAt = '2026-10-28T11:00:00.000Z'
  h.state.ticket.version++
  const before = h.calls.length
  assert.equal((await applied(h, deployment(), ledger)).blocked, 1)
  assert.equal(h.calls.length, before)
})

it('detects a new requester message even when timestamp precision yields the same timestamp', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  h.state.messages.push({ id: 'requester-new-message', author: { kind: 'guest', id: 'guest-1' }, createdAt: baseline, content: 'More details', visibility: 'requester_visible', kind: 'reply' })
  h.state.ticket.version++
  const count = h.calls.length
  assert.equal((await applied(h, deployment(), ledger)).blocked, 1)
  assert.equal(h.calls.length, count)
})

it('a delivered old resolution cannot close a ticket reopened by the requester', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  await applied(h, deployment(), ledger, { bindings: bindings({ sendReply: true }), sendReplies: true })
  h.state.deliveries[0].status = 'delivered'
  h.state.ticket.status = 'in_progress'
  h.state.ticket.lastRequesterMessageAt = '2026-10-28T11:30:00.000Z'
  delete h.state.ticket.work.verification
  delete h.state.ticket.latestReplyId
  const count = h.calls.length
  assert.equal((await applied(h, reconcile(), ledger)).blocked, 1)
  assert.equal(h.calls.length, count)
  assert.equal(h.state.ticket.status, 'in_progress')
})

it('manual status changes and manual verification are never overwritten', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  h.state.ticket.status = 'waiting_user'
  const count = h.calls.length
  assert.equal((await applied(h, deployment(), ledger)).blocked, 1)
  assert.equal(h.calls.length, count)
  h.state.ticket.status = 'in_progress'
  h.state.ticket.work.verification = { summary: 'manual evidence', evidenceRefs: ['private://report'], releaseRef: 'manual', verifiedAt: now }
  assert.equal((await applied(h, deployment(), ledger)).blocked, 1)
  assert.equal(h.state.ticket.work.verification.releaseRef, 'manual')
})

it('preserves a human reply draft and never publishes it with automation authorization', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  h.state.ticket.work.replyDraft = { content: 'private maintainer draft', resolution: false }
  await applied(h, deployment(), ledger, { bindings: bindings({ sendReply: true }), sendReplies: true })
  assert.equal(h.state.ticket.work.replyDraft.content, 'private maintainer draft')
  assert.equal(h.state.ticket.status, 'in_progress')
  assert.equal(h.state.ticket.latestReplyId, undefined)
})

it('an unchanged automation draft published in the UI can resolve and close only its own latest reply', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  await applied(h, deployment(), ledger)
  h.state.ticket.latestReplyId = 'ui-published'
  h.state.messages.push({ id: 'ui-published', content: h.state.ticket.work.replyDraft.content, resolution: true, visibility: 'requester_visible', createdAt: now })
  delete h.state.ticket.work.replyDraft
  h.state.deliveries.push({ ticketId: 'ticket-1', messageId: 'ui-published', channel: 'portal', status: 'read' })
  await applied(h, reconcile(), ledger)
  assert.equal(h.state.ticket.status, 'closed')
})

it('another human resolution with a different body cannot be adopted or closed', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  await applied(h, deployment(), ledger)
  h.state.ticket.latestReplyId = 'manual'
  h.state.messages.push({ id: 'manual', content: 'Different human resolution', resolution: true, visibility: 'requester_visible', createdAt: now })
  h.state.deliveries.push({ ticketId: 'ticket-1', messageId: 'manual', channel: 'portal', status: 'read' })
  await applied(h, reconcile(), ledger)
  assert.equal(h.state.ticket.status, 'in_progress')
})

it('a subsequent release clears automation-draft ownership after a maintainer replaces the draft', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  await applied(h, deployment(), ledger)
  const oldDraft = h.state.ticket.work.replyDraft.content
  h.state.ticket.work.replyDraft = { content: 'A distinct reviewed maintainer draft', resolution: true }
  await applied(h, deployment({ eventKey: 'deployment:2', deployment: { ...deployment().deployment, id: 'eo-deployment-2' } }), ledger)
  assert.equal(ledger.tickets['ticket-1'].generatedDraftHash, null)
  h.state.ticket.latestReplyId = 'manual-copy'
  h.state.messages.push({ id: 'manual-copy', content: oldDraft, resolution: true, visibility: 'requester_visible', createdAt: now })
  h.state.deliveries.push({ ticketId: 'ticket-1', messageId: 'manual-copy', channel: 'portal', status: 'read' })
  await applied(h, reconcile(), ledger)
  assert.equal(h.state.ticket.status, 'in_progress')
})

it('unknown outcomes persist and replay the exact request and key before any newer work', async () => {
  const h = harness()
  const ledger = freshLedger()
  let first = true
  h.after(async () => {
    if (first) {
      first = false
      throw new Error('timeout after commit')
    }
  })
  await assert.rejects(applied(h, change(), ledger))
  const pending = structuredClone(h.durable().tickets['ticket-1'].pending)
  const recovered = h.durable()
  await applied(h, change(), recovered)
  assert.deepEqual(h.calls[0], h.calls[1])
  assert.deepEqual(h.calls[1], { input: pending.input, key: pending.key })
  assert.equal(h.state.ticket.status, 'in_progress')
})

it('a binding revision cannot discard an unknown frozen command', async () => {
  const h = harness()
  const ledger = freshLedger()
  h.before(async () => {
    throw new Error('unknown network outcome')
  })
  await assert.rejects(applied(h, change(), ledger))
  const pending = structuredClone(ledger.tickets['ticket-1'].pending)
  h.before(undefined)
  const result = await applied(h, change(), ledger, { bindings: bindings({ revision: 2, requesterMessageAt: baseline }) })
  assert.equal(result.blocked, 1)
  assert.deepEqual(ledger.tickets['ticket-1'].pending, pending)
  assert.equal(h.calls.length, 1)
})

it('an unknown publish result retries the same key without creating a second user reply', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  let first = true
  h.after(async (input) => {
    if (input.command.action === 'reply.send' && first) {
      first = false
      throw new Error('unknown publish result')
    }
  })
  const options = { bindings: bindings({ sendReply: true }), sendReplies: true }
  await assert.rejects(applied(h, deployment(), ledger, options))
  const recovered = h.durable()
  await applied(h, deployment(), recovered, options)
  const publishes = h.calls.filter(call => call.input.command.action === 'reply.send')
  assert.equal(publishes.length, 2)
  assert.deepEqual(publishes[0], publishes[1])
  assert.equal(h.state.messages.filter(item => item.resolution).length, 1)
  assert.equal(h.state.ticket.status, 'resolved')
})

it('rebases an explicit version conflict only when guarded work and requester state are unchanged', async () => {
  const h = harness()
  const ledger = freshLedger()
  let first = true
  h.before(async () => {
    if (first) {
      first = false
      h.state.ticket.version++
    }
  })
  await applied(h, change(), ledger)
  assert.equal(h.calls[0].input.version, 1)
  assert.equal(h.calls[1].input.version, 2)
  assert.notEqual(h.calls[0].key, h.calls[1].key)
  assert.equal(h.state.ticket.status, 'in_progress')
})

it('version conflicts caused by human work changes remain pending and do not overwrite the new work', async () => {
  const h = harness()
  const ledger = freshLedger()
  h.before(async () => {
    h.state.ticket.version++
    h.state.ticket.work.rootCause = 'new human diagnosis'
  })
  assert.equal((await applied(h, change(), ledger)).blocked, 1)
  assert.equal(h.state.ticket.work.rootCause, 'new human diagnosis')
  assert.equal(ledger.tickets['ticket-1'].pending.blocked, true)
  assert.equal(h.calls.length, 1)
})

it('a same-timestamp requester append during a version conflict cannot be treated as an unrelated version bump', async () => {
  const h = harness()
  const ledger = freshLedger()
  h.before(async () => {
    h.state.ticket.version++
    h.state.messages.push({ id: 'new-message', author: { kind: 'guest', id: 'guest-1' }, createdAt: baseline, kind: 'reply', visibility: 'requester_visible', content: 'New context' })
  })
  assert.equal((await applied(h, change(), ledger)).blocked, 1)
  assert.equal(h.calls.length, 1)
  assert.equal(h.state.ticket.work.changeRefs.length, 0)
})

it('published human replies survive commit association without a work command clearing latestReplyId', async () => {
  const h = harness({ status: 'in_progress', latestReplyId: 'human-progress' })
  h.state.messages.push({ id: 'human-progress', content: 'Still investigating', resolution: false, visibility: 'requester_visible', createdAt: now })
  await applied(h, change(), freshLedger())
  assert.equal(h.state.ticket.latestReplyId, 'human-progress')
  assert(!h.calls.some(call => call.input.command.action === 'work'))
})

it('multiple tickets may share one development task without mixing ticket commands', async () => {
  const first = harness()
  const second = harness({ id: 'ticket-2' })
  const ledger = freshLedger()
  const mapping = bindings()
  mapping.tickets.push({ ...mapping.tickets[0], ticketId: 'ticket-2' })
  await syncCookDelivery({ event: change(), bindings: mapping, ledger, apply: true }, { now: () => now, save: async () => {}, detail: id => (id === 'ticket-1' ? first.ports : second.ports).detail(id), command: (input, key) => (input.id === 'ticket-1' ? first.ports : second.ports).command(input, key) })
  assert(first.calls.every(call => call.input.id === 'ticket-1'))
  assert(second.calls.every(call => call.input.id === 'ticket-2'))
})

it('persists every change, deployment, reply and receipt phase through the production encrypted ledger port', async () => {
  const h = harness()
  const secret = 'synthetic-only-ledger-key-for-behavior-testing'
  let encrypted = encryptLedger(freshLedger(), secret)
  let saves = 0
  h.ports.save = async (value) => {
    encrypted = encryptLedger(value, secret)
    saves++
    assert.deepEqual(decryptLedger(encrypted, secret), value)
  }
  await applied(h, change(), decryptLedger(encrypted, secret))
  const associated = decryptLedger(encrypted, secret)
  assert.equal(associated.tickets['ticket-1'].expectedStatus, 'in_progress')
  await applied(h, deployment(), associated, { bindings: bindings({ sendReply: true }), sendReplies: true })
  const published = decryptLedger(encrypted, secret)
  assert.equal(h.state.ticket.status, 'resolved')
  assert.equal(published.tickets['ticket-1'].replyId, h.state.ticket.latestReplyId)
  assert.equal(Object.hasOwn(published.tickets['ticket-1'].release, 'previousReplyId'), false)
  assert.equal(h.state.deliveries[0].status, 'available')
  h.state.deliveries[0].status = 'delivered'
  await applied(h, reconcile(), decryptLedger(encrypted, secret))
  assert.equal(h.state.ticket.status, 'closed')
  assert.equal(decryptLedger(encrypted, secret).tickets['ticket-1'].expectedStatus, 'closed')
  assert(saves > 10)
})

it('encrypts optional previous-reply metadata only when a prior published reply exists', async () => {
  const h = harness({ status: 'in_progress', latestReplyId: 'human-progress' })
  h.state.messages.push({ id: 'human-progress', content: 'Still investigating', resolution: false, visibility: 'requester_visible', createdAt: baseline })
  const secret = 'another-synthetic-ledger-encryption-test-key'
  let encrypted = encryptLedger(freshLedger(), secret)
  h.ports.save = async (value) => {
    encrypted = encryptLedger(value, secret)
  }
  await applied(h, change(), decryptLedger(encrypted, secret))
  await applied(h, deployment(), decryptLedger(encrypted, secret))
  assert.equal(decryptLedger(encrypted, secret).tickets['ticket-1'].release.previousReplyId, 'human-progress')
  assert.equal(h.state.ticket.latestReplyId, undefined)
})

it('persists without undefined and stops when a requester reply arrives immediately after publication', async () => {
  const h = harness()
  const secret = 'synthetic-ledger-encryption-key-for-reply-race'
  let encrypted = encryptLedger(freshLedger(), secret)
  h.ports.save = async (value) => {
    encrypted = encryptLedger(value, secret)
  }
  await applied(h, change(), decryptLedger(encrypted, secret))
  h.after(async (input) => {
    if (input.command.action !== 'reply.send')
      return
    h.state.ticket.lastRequesterMessageAt = '2026-10-28T11:30:00.000Z'
    h.state.ticket.version++
    delete h.state.ticket.latestReplyId
    delete h.state.ticket.work.verification
  })
  const result = await applied(h, deployment(), decryptLedger(encrypted, secret), { bindings: bindings({ sendReply: true }), sendReplies: true })
  assert.equal(result.blocked, 1)
  assert.equal(h.state.ticket.status, 'in_progress')
  const restored = decryptLedger(encrypted, secret)
  assert.equal(Object.hasOwn(restored.tickets['ticket-1'], 'replyId'), false)
  assert.equal(Object.hasOwn(restored.tickets['ticket-1'], 'pending'), false)
})

for (const productionPhase of ['verification', 'reply.send', 'resolved', 'closed']) {
  it(`defers an unknown ${productionPhase} command during change until fresh production acceptance succeeds`, async () => {
    const h = harness()
    const secret = 'synthetic-encryption-key-for-production-deferral'
    let encrypted = encryptLedger(freshLedger(), secret)
    h.ports.save = async (value) => {
      encrypted = encryptLedger(value, secret)
    }
    const options = { bindings: bindings({ sendReply: true }), sendReplies: true }
    await applied(h, change(), decryptLedger(encrypted, secret))
    if (productionPhase === 'closed') {
      await applied(h, deployment(), decryptLedger(encrypted, secret), options)
      h.state.deliveries[0].status = 'delivered'
    }
    h.before(async ({ command }) => {
      const targeted = productionPhase === 'verification'
        ? command.action === 'work' && command.work.verification
        : productionPhase === 'reply.send' ? command.action === 'reply.send' : command.action === 'status' && command.status === productionPhase
      if (targeted)
        throw new Error('Unknown production write result')
    })
    const acceptanceEvent = productionPhase === 'closed' ? reconcile('reconcile:unknown-close') : deployment()
    await assert.rejects(applied(h, acceptanceEvent, decryptLedger(encrypted, secret), options))
    const pending = structuredClone(decryptLedger(encrypted, secret).tickets['ticket-1'].pending)
    const beforeChange = h.calls.length
    h.before(undefined)
    const deferred = await applied(h, change({ eventKey: `change:after-unknown-${productionPhase}` }), decryptLedger(encrypted, secret), options)
    assert.equal(deferred.deferred, 1)
    assert.equal(deferred.blocked, 0)
    assert.equal(deferred.commands, 0)
    assert.equal(h.calls.length, beforeChange)
    assert.deepEqual(decryptLedger(encrypted, secret).tickets['ticket-1'].pending, pending)
    assert.equal(Object.hasOwn(pending, 'blocked'), false)
    // The runner does not invoke deployment/reconcile after a failed fresh gate.
    const gate = async (accepted) => {
      if (!accepted)
        throw new Error('Production was rolled back')
      return applied(h, acceptanceEvent, decryptLedger(encrypted, secret), options)
    }
    await assert.rejects(gate(false))
    assert.equal(h.calls.length, beforeChange)
    assert.deepEqual(decryptLedger(encrypted, secret).tickets['ticket-1'].pending, pending)
    await gate(true)
    assert.deepEqual(h.calls[beforeChange], { input: pending.input, key: pending.key })
    assert.equal(h.state.ticket.status, productionPhase === 'closed' ? 'closed' : 'resolved')
    assert.equal(Object.hasOwn(decryptLedger(encrypted, secret).tickets['ticket-1'], 'pending'), false)
  })
}

it('publishes a saved draft after later reconcile authorization only once, then waits for actual delivery', async () => {
  const h = harness()
  const secret = 'synthetic-key-for-delayed-explicit-reply-approval'
  let encrypted = encryptLedger(freshLedger(), secret)
  h.ports.save = async (value) => {
    encrypted = encryptLedger(value, secret)
  }
  await applied(h, change(), decryptLedger(encrypted, secret))
  await applied(h, deployment(), decryptLedger(encrypted, secret))
  assert.equal(h.state.messages.filter(item => item.resolution).length, 0)
  const verification = structuredClone(h.state.ticket.work.verification)
  const workCommands = h.calls.filter(call => call.input.command.action === 'work').length
  const options = { bindings: bindings({ sendReply: true }), sendReplies: true }
  const authorized = reconcile('reconcile:explicit-send-approval')
  await applied(h, authorized, decryptLedger(encrypted, secret), options)
  assert.equal(h.state.ticket.status, 'resolved')
  assert.equal(h.state.messages.filter(item => item.resolution).length, 1)
  assert.deepEqual(h.state.ticket.work.verification, verification)
  assert.equal(h.calls.filter(call => call.input.command.action === 'work').length, workCommands)
  await applied(h, authorized, decryptLedger(encrypted, secret), options)
  await applied(h, reconcile('reconcile:still-available'), decryptLedger(encrypted, secret), options)
  assert.equal(h.state.messages.filter(item => item.resolution).length, 1)
  assert.equal(h.state.ticket.status, 'resolved')
  assert.equal(h.state.deliveries[0].status, 'available')
  h.state.deliveries[0].status = 'delivered'
  await applied(h, reconcile('reconcile:actual-delivery'), decryptLedger(encrypted, secret), options)
  assert.equal(h.state.ticket.status, 'closed')
})

it('later reconcile reply authorization explicitly blocks an edited human draft instead of sending or overwriting it', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  await applied(h, deployment(), ledger)
  h.state.ticket.work.replyDraft.content = 'A newly edited human reply'
  const before = h.calls.length
  const result = await applied(h, reconcile('reconcile:human-draft-review'), ledger, { bindings: bindings({ sendReply: true }), sendReplies: true })
  assert.equal(result.blocked, 1)
  assert.equal(result.deferred, 0)
  assert.equal(h.calls.length, before)
  assert.equal(h.state.ticket.work.replyDraft.content, 'A newly edited human reply')
  assert.equal(h.state.ticket.status, 'in_progress')
})

for (const pendingAction of ['reply.send', 'closed']) {
  it(`cannot use ticket B production proof to replay ticket A pending ${pendingAction} after A's fix is removed`, async () => {
    const ticketA = harness()
    const ticketB = harness({ id: 'ticket-2' })
    const shaB = 'c'.repeat(40)
    const productionB = 'd'.repeat(40)
    const secret = 'synthetic-ledger-key-for-per-ticket-production-proof'
    let encrypted = encryptLedger(freshLedger(), secret)
    const mapping = bindings({ sendReply: true })
    mapping.tickets.push({ ...mapping.tickets[0], ticketId: 'ticket-2', commitShas: [shaB], pullRequests: [456] })
    const ports = {
      now: () => now,
      save: async (value) => {
        encrypted = encryptLedger(value, secret)
      },
      detail: id => (id === 'ticket-1' ? ticketA.ports : ticketB.ports).detail(id),
      command: (input, key) => (input.id === 'ticket-1' ? ticketA.ports : ticketB.ports).command(input, key),
    }
    const run = (event, selected = mapping, sendReplies = false) => syncCookDelivery({ event, bindings: selected, ledger: decryptLedger(encrypted, secret), apply: true, sendReplies }, ports)
    await run(change())
    await run(change({ eventKey: 'change:ticket-B', commitSha: shaB, refs: ['https://github.com/YunYouJun/cook/pull/456'] }))
    await run(deployment({ verifiedChangeShas: [sha, shaB] }))
    const onlyA = { schemaVersion: 1, tickets: [mapping.tickets[0]] }
    if (pendingAction === 'closed') {
      await run(reconcile('reconcile:publish-A', { verifiedChangeShas: [sha, shaB] }), onlyA, true)
      ticketA.state.deliveries[0].status = 'delivered'
    }
    ticketA.before(async ({ command }) => {
      if ((pendingAction === 'reply.send' && command.action === 'reply.send') || (pendingAction === 'closed' && command.action === 'status' && command.status === 'closed'))
        throw new Error('Unknown production write result')
    })
    await assert.rejects(run(reconcile('reconcile:pending-A', { verifiedChangeShas: [sha, shaB] }), onlyA, true))
    const pending = structuredClone(decryptLedger(encrypted, secret).tickets['ticket-1'].pending)
    ticketA.before(undefined)
    const aCalls = ticketA.calls.length
    const freshProof = { commitSha: productionB, verifiedChangeShas: [shaB], deployment: { ...deployment().deployment, id: 'eo-only-ticket-B', sha: productionB } }
    const updated = await run(reconcile('reconcile:only-B-published', freshProof), mapping, true)
    assert.equal(updated.matched, 1)
    assert.equal(updated.blocked, 0)
    assert.equal(ticketB.state.ticket.status, 'resolved')
    assert.equal(ticketA.calls.length, aCalls)
    assert.notEqual(ticketA.state.ticket.status, 'closed')
    assert.deepEqual(decryptLedger(encrypted, secret).tickets['ticket-1'].pending, pending)
    ticketB.state.deliveries[0].status = 'delivered'
    const closed = await run(reconcile('reconcile:only-B-delivered', freshProof), mapping, true)
    assert.equal(closed.matched, 1)
    assert.equal(ticketB.state.ticket.status, 'closed')
    assert.equal(ticketA.calls.length, aCalls)
    assert.notEqual(ticketA.state.ticket.status, 'closed')
    assert.deepEqual(decryptLedger(encrypted, secret).tickets['ticket-1'].pending, pending)
  })
}

it('blocks later send authorization for a changed private reply template while preserving the encrypted owned draft and verification', async () => {
  const h = harness()
  const secret = 'synthetic-ledger-key-for-reply-authorization-change'
  let encrypted = encryptLedger(freshLedger(), secret)
  h.ports.save = async (value) => {
    encrypted = encryptLedger(value, secret)
  }
  await applied(h, change(), decryptLedger(encrypted, secret))
  await applied(h, deployment(), decryptLedger(encrypted, secret))
  const originalDraft = structuredClone(h.state.ticket.work.replyDraft)
  const originalVerification = structuredClone(h.state.ticket.work.verification)
  const calls = h.calls.length
  const result = await applied(h, reconcile('reconcile:updated-private-reply'), decryptLedger(encrypted, secret), {
    bindings: bindings({ sendReply: true, resolutionReply: 'A newly approved reply with different wording' }),
    sendReplies: true,
  })
  assert.equal(result.blocked, 1)
  assert.equal(result.commands, 0)
  assert.equal(h.calls.length, calls)
  assert.deepEqual(h.state.ticket.work.replyDraft, originalDraft)
  assert.deepEqual(h.state.ticket.work.verification, originalVerification)
  assert.equal(h.state.ticket.status, 'in_progress')
  assert.equal(h.state.messages.some(item => item.resolution), false)
  assert.equal(decryptLedger(encrypted, secret).tickets['ticket-1'].generatedDraftHash.length, 64)
})

it('does not replay an unknown reply.send under a changed private reply authorization', async () => {
  const h = harness()
  const secret = 'synthetic-key-for-pending-reply-authorization-change'
  let encrypted = encryptLedger(freshLedger(), secret)
  h.ports.save = async (value) => {
    encrypted = encryptLedger(value, secret)
  }
  await applied(h, change(), decryptLedger(encrypted, secret))
  await applied(h, deployment(), decryptLedger(encrypted, secret))
  h.before(async ({ command }) => {
    if (command.action === 'reply.send')
      throw new Error('Unknown publish result')
  })
  await assert.rejects(applied(h, reconcile('reconcile:original-reply-send'), decryptLedger(encrypted, secret), { bindings: bindings({ sendReply: true }), sendReplies: true }))
  h.before(undefined)
  const pending = structuredClone(decryptLedger(encrypted, secret).tickets['ticket-1'].pending)
  const calls = h.calls.length
  const result = await applied(h, reconcile('reconcile:changed-reply-send'), decryptLedger(encrypted, secret), {
    bindings: bindings({ sendReply: true, resolutionReply: 'A newly approved reply with different wording' }),
    sendReplies: true,
  })
  assert.equal(result.blocked, 1)
  assert.equal(result.commands, 0)
  assert.equal(h.calls.length, calls)
  assert.deepEqual(decryptLedger(encrypted, secret).tickets['ticket-1'].pending, pending)
  assert.equal(h.state.ticket.status, 'in_progress')
})

for (const [sendReplies, sendReply] of [[false, true], [true, false], [false, false]]) {
  it(`preserves an unknown publish intent without sending when authorization becomes ${sendReplies}/${sendReply}`, async () => {
    const h = harness()
    const ledger = freshLedger()
    await applied(h, change(), ledger)
    await applied(h, deployment(), ledger)
    h.before(async ({ command }) => {
      if (command.action === 'reply.send')
        throw new Error('Unknown publish result')
    })
    await assert.rejects(applied(h, reconcile('reconcile:authorized-publish'), ledger, { bindings: bindings({ sendReply: true }), sendReplies: true }))
    const pending = structuredClone(h.durable().tickets['ticket-1'].pending)
    const count = h.calls.length
    h.before(undefined)
    const recovered = h.durable()
    const result = await applied(h, reconcile('reconcile:revoked-publish'), recovered, { bindings: bindings({ sendReply }), sendReplies })
    assert.equal(result.blocked, 1)
    assert.equal(result.commands, 0)
    assert.equal(h.calls.length, count)
    assert.deepEqual(recovered.tickets['ticket-1'].pending, pending)
    assert.equal(h.state.ticket.latestReplyId, undefined)
    assert.equal(h.state.ticket.status, 'in_progress')
    await applied(h, reconcile('reconcile:reauthorized-publish'), recovered, { bindings: bindings({ sendReply: true }), sendReplies: true })
    assert.deepEqual(h.calls[count], { input: pending.input, key: pending.key })
    assert.equal(h.state.messages.filter(item => item.resolution).length, 1)
  })
}

it('builds and publishes a response from reviewed repair facts and the actual accepted profile', async () => {
  const h = harness()
  const ledger = freshLedger()
  const mapping = bindings({ acceptanceProfile: 'desktop-layout-v1', fixSummary: { changes: ['新增电脑侧栏，展开食材选项', '增加旧版网页入口'], userAction: '请刷新网页后体验。' }, sendReply: true })
  await applied(h, change(), ledger, { bindings: mapping })
  await applied(h, deployment({ deployment: { ...deployment().deployment, acceptanceProfiles: ['desktop-layout-v1'] } }), ledger, { bindings: mapping, sendReplies: true })
  const reply = h.state.messages.find(item => item.resolution)
  assert.match(reply.content, /新增电脑侧栏，展开食材选项/)
  assert.match(reply.content, /增加旧版网页入口/)
  assert.match(reply.content, /已验证.*电脑.*手机/)
  assert.match(reply.content, /请刷新网页后体验。/)
  assert.doesNotMatch(reply.content, /ticket-1|eo-deployment|github\.com|[a-f0-9]{40}/)
  assert.equal(h.state.ticket.status, 'resolved')
  assert.equal(h.state.deliveries[0].status, 'available')
})

it('keeps accepted production evidence without inventing a reply when repair facts are absent', async () => {
  const h = harness()
  const ledger = freshLedger()
  const mapping = bindings({ fixSummary: undefined, sendReply: true })
  await applied(h, change(), ledger, { bindings: mapping })
  const result = await applied(h, deployment(), ledger, { bindings: mapping, sendReplies: true })
  assert.equal(result.blocked, 1)
  assert(h.state.ticket.work.verification)
  assert.equal(h.state.ticket.work.replyDraft, undefined)
  assert.equal(h.state.messages.some(item => item.resolution), false)
  assert.equal(h.state.ticket.status, 'in_progress')
})

it('requires the fix acceptance profile before generating or sending even an explicit reviewed reply', async () => {
  for (const acceptanceProfiles of [undefined, ['app-smoke-v1']]) {
    const h = harness()
    const ledger = freshLedger()
    const mapping = bindings({ sendReply: true, resolutionReply: 'Reviewed desktop repair response' })
    await applied(h, change(), ledger, { bindings: mapping })
    const accepted = deployment({ deployment: { ...deployment().deployment, acceptanceProfiles } })
    const result = await applied(h, accepted, ledger, { bindings: mapping, sendReplies: true })
    assert.equal(result.blocked, 1)
    assert.equal(h.state.ticket.work.replyDraft, undefined)
    assert.equal(h.state.messages.some(item => item.resolution), false)
  }
})

it('preserves an explicit reviewed response override after its real acceptance succeeds', async () => {
  const h = harness()
  const ledger = freshLedger()
  const mapping = bindings({ fixSummary: undefined, resolutionReply: '经过核对的回复内容。', sendReply: true })
  await applied(h, change(), ledger, { bindings: mapping })
  await applied(h, deployment(), ledger, { bindings: mapping, sendReplies: true })
  assert.equal(h.state.messages.find(item => item.resolution).content, '经过核对的回复内容。')
})

it('blocks a saved draft after its reviewed repair facts change, including unknown publish retries', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  await applied(h, deployment(), ledger)
  h.before(async ({ command }) => {
    if (command.action === 'reply.send')
      throw new Error('Unknown publish result')
  })
  await assert.rejects(applied(h, reconcile('reconcile:original-facts'), ledger, { bindings: bindings({ sendReply: true }), sendReplies: true }))
  const pending = structuredClone(h.durable().tickets['ticket-1'].pending)
  const draft = structuredClone(h.state.ticket.work.replyDraft)
  const count = h.calls.length
  h.before(undefined)
  const recovered = h.durable()
  const result = await applied(h, reconcile('reconcile:changed-facts'), recovered, { bindings: bindings({ fixSummary: { changes: ['Another reviewed repair'] }, sendReply: true }), sendReplies: true })
  assert.equal(result.blocked, 1)
  assert.equal(h.calls.length, count)
  assert.deepEqual(recovered.tickets['ticket-1'].pending, pending)
  assert.deepEqual(h.state.ticket.work.replyDraft, draft)
})

it('rebuilds revised public bindings without carrying an abandoned PR association into receipt reconciliation', async () => {
  const h = harness()
  const ledger = freshLedger()
  const first = bindings({ commitShas: [], revision: 1, requesterMessageAt: baseline })
  await applied(h, change(), ledger, { bindings: first })
  await applied(h, deployment(), ledger, { bindings: first })
  const nextSha = 'c'.repeat(40)
  const next = bindings({ commitShas: [], pullRequests: [456], revision: 2, requesterMessageAt: baseline, sendReply: true, fixSummary: { changes: ['另一项已审核修复'] } })
  await applied(h, change({ eventKey: 'change:revised-pr', commitSha: nextSha, refs: ['https://github.com/YunYouJun/cook/pull/456'] }), ledger, { bindings: next })
  assert.deepEqual(ledger.tickets['ticket-1'].associatedShas, [nextSha])
  assert.equal((await applied(h, reconcile('reconcile:abandoned-pr'), ledger, { bindings: next, sendReplies: true })).matched, 0)
  const proof = { verifiedChangeShas: [nextSha] }
  assert.equal((await applied(h, reconcile('reconcile:old-release-new-pr', proof), ledger, { bindings: next, sendReplies: true })).matched, 0)
  assert.equal(h.state.messages.some(item => item.resolution), false)
  const accepted = await applied(h, deployment({ eventKey: 'deployment:revised-pr', ...proof }), ledger, { bindings: next, sendReplies: true })
  assert.equal(accepted.blocked, 0)
  assert.match(h.state.messages.find(item => item.resolution).content, /另一项已审核修复/)
  h.state.deliveries[0].status = 'read'
  await applied(h, reconcile('reconcile:revised-pr-received', proof), ledger, { bindings: next, sendReplies: true })
  assert.equal(h.state.ticket.status, 'closed')
})

for (const manualChange of ['status', 'requester-message']) {
  it(`does not use a binding revision to bypass a manual ${manualChange} guard`, async () => {
    const h = harness()
    const ledger = freshLedger()
    await applied(h, change(), ledger)
    if (manualChange === 'status')
      h.state.ticket.status = 'waiting_user'
    else
      h.state.messages.push({ id: 'new-requester-message', author: { kind: 'guest' }, createdAt: baseline, content: 'More details' })
    h.state.ticket.version++
    const count = h.calls.length
    const revised = bindings({ revision: 2, requesterMessageAt: baseline })
    const result = await applied(h, change(), ledger, { bindings: revised })
    assert.equal(result.blocked, 1)
    assert.equal(h.calls.length, count)
    assert.equal(ledger.tickets['ticket-1'].revision, 1)
  })
}

it('adds a newly linked issue on a safe revision without replaying unchanged association notes', async () => {
  const h = harness()
  const ledger = freshLedger()
  await applied(h, change(), ledger)
  const mapping = bindings({ issue: 7, revision: 2, requesterMessageAt: baseline })
  await applied(h, change(), ledger, { bindings: mapping })
  assert(h.state.ticket.work.changeRefs.includes('https://github.com/YunYouJun/cook/issues/7'))
  const count = h.calls.length
  await applied(h, change(), ledger, { bindings: mapping })
  assert.equal(h.calls.length, count)
})
