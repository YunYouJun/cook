import { createHash } from 'node:crypto'
import { parseCookDeliveryBindings, syncCookDelivery } from './cook-delivery-sync.mjs'

const hash = value => createHash('sha256').update(JSON.stringify(value)).digest('hex')

/**
 * Join explicit development bindings, fresh EdgeOne evidence and the existing Support lifecycle.
 * No reconcile or close operation is attempted when current production verification fails.
 * @param {{bindings:object,ledger:object,apply?:boolean,sendReplies?:boolean}} input Private runner state.
 * @param {object} ports Trusted GitHub, deployment, browser, Support and persistence operations.
 * @returns {Promise<object>} Counts and safe phase labels; never private ticket contents.
 */
export async function runCookSupportSync(input, ports) {
  const now = ports.now?.() ?? new Date().toISOString()
  const bindings = parseCookDeliveryBindings(input.bindings)
  const ledger = input.ledger
  ledger.inputs ??= {}
  const result = { phase: 'associated', events: 0, matched: 0, changed: 0, blocked: 0, deferred: 0, commands: 0, dryRun: input.apply !== true }
  if (bindings.some(binding => !binding.requesterMessageAt)) {
    result.phase = 'requester_baseline_required'
    result.blocked = bindings.filter(binding => !binding.requesterMessageAt).length
    return result
  }
  const source = await ports.collect(bindings)
  const deliver = async (event) => {
    const counts = await syncCookDelivery({ ...input, event, ledger }, ports)
    result.events++
    for (const key of ['matched', 'changed', 'blocked', 'commands'])
      result[key] += counts[key]
    result.deferred += counts.deferred ?? 0
  }
  for (const event of source.events)
    await deliver(event)
  if (!source.verifiedChangeShas.length) {
    result.phase = 'awaiting_merge'
    return result
  }

  let release
  let checksRef
  try {
    checksRef = await ports.checks(source.sha)
    release = await ports.release(source.sha)
  }
  catch {
    result.phase = 'awaiting_production_acceptance'
    return result
  }
  const profiles = [...new Set(bindings.map(binding => binding.acceptanceProfile))].sort()
  if (!profiles.length || profiles.some(profile => !['app-smoke-v1', 'desktop-layout-v1'].includes(profile))) {
    result.phase = 'acceptance_profile_required'
    result.blocked++
    return result
  }
  const policy = bindings.map(binding => [binding.ticketId, binding.revision, binding.acceptanceProfile]).sort((a, b) => a[0].localeCompare(b[0]))
  const eventKey = `edgeone-${source.sha}-${hash([release.id, profiles, policy, [...source.verifiedChangeShas].sort()]).slice(0, 32)}`
  let event = ledger.inputs[eventKey]
  if (!event) {
    let browser
    try {
      browser = await ports.browser(profiles)
    }
    catch {
      result.phase = 'awaiting_production_browser_acceptance'
      return result
    }
    // A completed browser run is not allowed to bless a deployment replaced during the run.
    let confirmed
    try {
      confirmed = await ports.release(source.sha)
    }
    catch {
      result.phase = 'awaiting_production_acceptance'
      return result
    }
    if (confirmed.id !== release.id) {
      result.phase = 'awaiting_production_acceptance'
      return result
    }
    event = { schemaVersion: 1, eventKey, repository: 'YunYouJun/cook', kind: 'deployment', occurredAt: now, commitSha: source.sha, refs: [], verifiedChangeShas: source.verifiedChangeShas, deployment: { ...release, summary: `${release.summary}\n${browser.summary}`, evidenceRefs: [...new Set([...release.evidenceRefs, checksRef, ...browser.evidenceRefs])], verifiedAt: now } }
    if (input.apply === true) {
      ledger.inputs[eventKey] = event
      await ports.save(ledger)
    }
  }
  await deliver(event)

  // Receipt/state changes create a new idempotent reconcile event; unchanged polling does not.
  const snapshots = []
  for (const binding of bindings) {
    const value = await ports.detail(binding.ticketId)
    const detail = value.detail ?? value
    snapshots.push({ ticketId: binding.ticketId, version: detail.ticket.version, latestReplyId: detail.ticket.latestReplyId, requester: detail.ticket.lastRequesterMessageAt, deliveries: detail.deliveries.map(item => [item.messageId, item.channel, item.status]) })
  }
  const authorization = bindings.map(binding => [binding.ticketId, binding.revision, input.sendReplies === true && binding.sendReply === true])
  const reconcileKey = `receipts-${source.sha}-${hash([snapshots, authorization, eventKey, source.verifiedChangeShas])}`
  let reconcile = ledger.inputs[reconcileKey]
  if (!reconcile) {
    reconcile = { schemaVersion: 1, eventKey: reconcileKey, repository: 'YunYouJun/cook', kind: 'reconcile', occurredAt: now, refs: [], commitSha: source.sha, verifiedChangeShas: source.verifiedChangeShas, deployment: event.deployment }
    if (input.apply === true) {
      ledger.inputs[reconcileKey] = reconcile
      await ports.save(ledger)
    }
  }
  await deliver(reconcile)
  result.phase = result.blocked ? 'maintainer_review_required' : 'production_verified'
  return result
}
