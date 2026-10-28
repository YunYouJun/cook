import assert from 'node:assert/strict'
// eslint-disable-next-line test/no-import-node-test -- The release bridge has a dependency-injected Node interface.
import { test } from 'node:test'
import { runCookSupportSync } from '../scripts/lib/cook-support-runner.mjs'
import { decryptLedger, encryptLedger } from '../scripts/lib/support-ledger.mjs'

const sha = 'a'.repeat(40)
const then = '2026-10-28T10:00:00Z'
const now = '2026-10-28T12:00:00Z'
const secret = 'a dedicated synthetic encryption key for tests'
const binding = { ticketId: 'ticket-test', repository: 'YunYouJun/cook', commitShas: [sha], requesterMessageAt: then, acceptanceProfile: 'desktop-layout-v1', fixSummary: { changes: ['展开电脑网页的食材选项'] } }
const change = { schemaVersion: 1, eventKey: `commit-${sha}`, repository: binding.repository, kind: 'change', commitSha: sha, occurredAt: then, refs: [] }
const release = { id: 'edgeone:deployment-a-deployment-b', provider: 'edgeone', environment: 'production', sha, origin: 'https://cook.yunyoujun.cn', verified: true, verifiedAt: now, summary: 'Live production source and HTML verified', evidenceRefs: ['https://cook.yunyoujun.cn/.well-known/cook-build.json'] }

function setup(overrides = {}) {
  let current = { ticket: { id: binding.ticketId, productId: 'cook', version: 1, status: 'triaged', lastRequesterMessageAt: then, work: { reproduction: '', rootCause: '', changeRefs: [] } }, messages: [], deliveries: [] }
  const commands = []
  const saved = []
  const ledger = { schemaVersion: 1, events: {}, tickets: {}, inputs: {} }
  const ports = {
    now: () => now,
    collect: async () => ({ sha, events: [change], verifiedChangeShas: [sha] }),
    checks: async () => `https://github.com/YunYouJun/cook/commit/${sha}/checks`,
    release: async () => release,
    browser: async () => ({ summary: 'Desktop and mobile production interactions passed', evidenceRefs: ['https://cook.yunle.fun/home'] }),
    detail: async () => structuredClone(current),
    command: async (input) => {
      assert.equal(input.version, current.ticket.version)
      commands.push(input.command)
      current.ticket.version++
      if (input.command.action === 'work') {
        current.ticket.work = structuredClone(input.command.work)
        delete current.ticket.latestReplyId
      }
      if (input.command.action === 'status')
        current.ticket.status = input.command.status
      if (input.command.action === 'note')
        current.messages.push({ id: `note-${current.ticket.version}`, kind: 'internal_note', content: input.command.content })
      if (input.command.action === 'reply.send') {
        const id = `reply-${current.ticket.version}`
        current.messages.push({ id, kind: 'reply', visibility: 'requester_visible', resolution: true, content: current.ticket.work.replyDraft.content, createdAt: now })
        current.ticket.latestReplyId = id
        delete current.ticket.work.replyDraft
        current.deliveries.push({ ticketId: binding.ticketId, messageId: id, channel: 'portal', status: 'available' })
      }
      return { detail: structuredClone(current) }
    },
    save: async (value) => {
      saved.push(decryptLedger(encryptLedger(value, secret), secret))
    },
    ...overrides,
  }
  return { ledger, ports, commands, saved, input: { bindings: { schemaVersion: 1, tickets: [binding] }, ledger, apply: true }, current: () => current, setCurrent: value => current = value }
}

test('production or CI failures associate work but never resolve or close', async () => {
  for (const port of ['release', 'checks']) {
    const context = setup({ [port]: async () => {
      throw new Error('not deployed or checks pending')
    } })
    const result = await runCookSupportSync(context.input, context.ports)
    assert.equal(result.phase, 'awaiting_production_acceptance')
    assert(context.commands.some(command => command.action === 'note'))
    assert(!context.commands.some(command => command.status === 'resolved' || command.status === 'closed' || command.work?.verification))
  }
})

test('browser regression and deployment replacement cannot bless production', async () => {
  const failed = setup({ browser: async () => {
    throw new Error('regression')
  } })
  assert.equal((await runCookSupportSync(failed.input, failed.ports)).phase, 'awaiting_production_browser_acceptance')
  assert(!failed.commands.some(command => command.work?.verification))
  let calls = 0
  const replaced = setup({ release: async () => ({ ...release, id: ++calls === 1 ? release.id : 'edgeone:new-deployment' }) })
  assert.equal((await runCookSupportSync(replaced.input, replaced.ports)).phase, 'awaiting_production_acceptance')
  assert(!replaced.commands.some(command => command.work?.verification))
})

test('passed production writes real evidence and a draft without sending or closing', async () => {
  const context = setup()
  const result = await runCookSupportSync(context.input, context.ports)
  assert.equal(result.phase, 'production_verified')
  assert(context.current().ticket.work.verification.summary.includes('Desktop and mobile production interactions passed'))
  assert(context.current().ticket.work.replyDraft.resolution)
  assert.match(context.current().ticket.work.replyDraft.content, /展开电脑网页的食材选项/)
  assert.match(context.current().ticket.work.replyDraft.content, /已验证电脑.*手机/)
  assert(!context.commands.some(command => command.action === 'reply.send' || command.status === 'resolved' || command.status === 'closed'))
  assert(context.saved.length > 0)
})

test('a repeated unchanged deployment reuses frozen evidence and does not duplicate commands', async () => {
  const context = setup()
  await runCookSupportSync(context.input, context.ports)
  const count = context.commands.length
  const result = await runCookSupportSync(context.input, { ...context.ports, now: () => '2026-10-28T13:00:00Z', browser: async () => {
    throw new Error('unchanged deployment does not need to rerun this')
  } })
  assert.equal(result.phase, 'production_verified')
  assert.equal(context.commands.length, count)
})

test('a rollback blocks receipt reconciliation, including an otherwise closable ledger', async () => {
  const context = setup()
  await runCookSupportSync(context.input, context.ports)
  const state = context.ledger.tickets[binding.ticketId]
  context.setCurrent({ ...context.current(), ticket: { ...context.current().ticket, status: 'resolved', latestReplyId: 'reply-owned' }, messages: [{ id: 'reply-owned', resolution: true, kind: 'reply', visibility: 'requester_visible', createdAt: now, content: context.current().ticket.work.replyDraft.content }], deliveries: [{ ticketId: binding.ticketId, messageId: 'reply-owned', channel: 'portal', status: 'delivered' }] })
  state.expectedStatus = 'resolved'
  state.replyId = 'reply-owned'
  const count = context.commands.length
  const result = await runCookSupportSync(context.input, { ...context.ports, release: async () => {
    throw new Error('production rolled back')
  } })
  assert.equal(result.phase, 'awaiting_production_acceptance')
  assert.equal(context.commands.length, count)
})

test('unreviewed acceptance profiles block release verification writes', async () => {
  const context = setup()
  context.input.bindings = { schemaVersion: 1, tickets: [{ ...binding, acceptanceProfile: 'run-untrusted-code' }] }
  const result = await runCookSupportSync(context.input, context.ports)
  assert.equal(result.phase, 'acceptance_profile_required')
  assert.equal(result.blocked, 1)
  assert(!context.commands.some(command => command.work?.verification))
})

test('cached acceptance cannot bypass a changed or newly invalid binding profile', async () => {
  const context = setup()
  context.input.bindings = { schemaVersion: 1, tickets: [{ ...binding, acceptanceProfile: 'app-smoke-v1' }] }
  await runCookSupportSync(context.input, context.ports)
  const count = context.commands.length
  context.input.bindings = { schemaVersion: 1, tickets: [{ ...binding, acceptanceProfile: 'desktop-layout-v1' }] }
  assert.equal((await runCookSupportSync(context.input, { ...context.ports, browser: async () => {
    throw new Error('new profile fails')
  } })).phase, 'awaiting_production_browser_acceptance')
  assert.equal(context.commands.length, count)
  context.input.bindings = { schemaVersion: 1, tickets: [{ ...binding, acceptanceProfile: 'unknown' }] }
  assert.equal((await runCookSupportSync(context.input, context.ports)).phase, 'acceptance_profile_required')
  assert.equal(context.commands.length, count)
})

test('a PR-only binding revision retains its merged association and closes after its real reply receipt', async () => {
  const context = setup()
  let merged = false
  context.ports.collect = async () => ({ sha, events: [{ ...change, refs: ['https://github.com/YunYouJun/cook/pull/123'] }], verifiedChangeShas: merged ? [sha] : [] })
  const first = { ...binding, commitShas: [], pullRequests: [123], revision: 1, sendReply: true }
  context.input.bindings = { schemaVersion: 1, tickets: [first] }
  context.input.sendReplies = true
  assert.equal((await runCookSupportSync(context.input, context.ports)).phase, 'awaiting_merge')
  const notes = context.commands.filter(command => command.action === 'note').length
  context.input.bindings = { schemaVersion: 1, tickets: [{ ...first, revision: 2 }] }
  merged = true
  const accepted = await runCookSupportSync(context.input, context.ports)
  assert.equal(accepted.blocked, 0)
  assert.deepEqual(context.ledger.tickets[binding.ticketId].associatedShas, [sha])
  assert.equal(context.current().ticket.status, 'resolved')
  assert.equal(context.commands.filter(command => command.action === 'note').length, notes)
  context.current().deliveries[0].status = 'delivered'
  await runCookSupportSync(context.input, context.ports)
  assert.equal(context.current().ticket.status, 'closed')
})

test('a reviewed facts revision can update an owned draft without adopting manual verification', async () => {
  const context = setup()
  context.input.bindings = { schemaVersion: 1, tickets: [{ ...binding, commitShas: [], pullRequests: [123], revision: 1 }] }
  context.ports.collect = async () => ({ sha, events: [{ ...change, refs: ['https://github.com/YunYouJun/cook/pull/123'] }], verifiedChangeShas: [sha] })
  await runCookSupportSync(context.input, context.ports)
  context.input.bindings.tickets[0] = { ...context.input.bindings.tickets[0], revision: 2, fixSummary: { changes: ['补充旧版入口和电脑导航'] } }
  assert.equal((await runCookSupportSync(context.input, context.ports)).blocked, 0)
  assert.match(context.current().ticket.work.replyDraft.content, /补充旧版入口和电脑导航/)
  context.current().ticket.work.verification.summary = 'Manual replacement evidence'
  context.input.bindings.tickets[0].revision = 3
  const manual = await runCookSupportSync(context.input, context.ports)
  assert(manual.blocked > 0)
  assert.equal(context.current().ticket.work.verification.summary, 'Manual replacement evidence')
})
