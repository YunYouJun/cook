import { createHash } from 'node:crypto'

const repository = 'YunYouJun/cook'
const shaPattern = /^[a-f\d]{40}$/
const ticketPattern = /^[\w-]{1,128}$/
const origins = new Set(['https://cook.yunyoujun.cn', 'https://cook.yunle.fun'])
const statuses = new Set(['new', 'triaged', 'in_progress', 'waiting_user', 'resolved', 'closed'])
const reservedKeys = new Set(['__proto__', 'constructor', 'prototype'])
const acceptanceDescriptions = {
  'app-smoke-v1': '已验证网页选材和菜谱结果。',
  'desktop-layout-v1': '已验证电脑端侧栏、食材展开、菜谱结果和旧版入口，并检查手机端布局。',
}
const hash = value => createHash('sha256').update(typeof value === 'string' ? value : JSON.stringify(value)).digest('hex')
const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right)
const unique = values => [...new Set(values)]
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const instant = value => typeof value === 'string' && Number.isFinite(Date.parse(value))
const strings = (value, limit = 20, size = 2048) => Array.isArray(value) && value.length <= limit && value.every(item => typeof item === 'string' && item.length > 0 && item.length <= size)

function invalid() {
  throw Object.assign(new Error('Invalid Cook delivery input'), { code: 'INVALID_INPUT' })
}

function ref(value) {
  return typeof value === 'string' && /^https:\/\/github\.com\/YunYouJun\/cook\/(?:commit\/[a-f\d]{40}|(?:pull|issues)\/[1-9]\d*)$/.test(value)
}

function replyPolicyHash(binding) {
  return hash([binding.fixSummary ?? null, binding.resolutionReply ?? null, binding.acceptanceProfile ?? null])
}

function bindingRefsHash(binding) {
  return hash([unique(binding.commitShas).sort(), unique(binding.pullRequests).sort((left, right) => left - right)])
}

function resolutionReply(binding, deployment) {
  if (!Object.hasOwn(acceptanceDescriptions, binding.acceptanceProfile) || !deployment.acceptanceProfiles?.includes(binding.acceptanceProfile))
    return null
  if (binding.resolutionReply)
    return binding.resolutionReply.trim()
  if (!binding.fixSummary)
    return null
  const changes = binding.fixSummary.changes.map(change => `- ${change}`).join('\n')
  return `相关修复已上线。\n\n${changes}\n\n${acceptanceDescriptions[binding.acceptanceProfile]}\n${binding.fixSummary.userAction ?? '请刷新网页后体验；如果仍有问题，欢迎在这里补充。'}`
}

/** Parse the private runner envelope; deployment evidence is accepted only from the trusted EdgeOne runner. */
export function parseCookDeliveryEvent(value, now = new Date().toISOString()) {
  if (!object(value) || value.schemaVersion !== 1 || value.repository !== repository || !['change', 'deployment', 'reconcile'].includes(value.kind))
    invalid()
  const key = value.eventKey ?? value.id
  if (typeof key !== 'string' || reservedKeys.has(key) || !/^[\w:./-]{1,256}$/.test(key) || (value.id && value.eventKey && value.id !== value.eventKey) || !instant(value.occurredAt) || Date.parse(value.occurredAt) > Date.parse(now) + 300_000)
    invalid()
  if (value.refs !== undefined && (!strings(value.refs) || !value.refs.every(ref)))
    invalid()
  if (value.commitSha !== undefined && !shaPattern.test(value.commitSha))
    invalid()
  if (value.kind === 'change' && !value.commitSha && !(value.refs ?? []).some(item => /\/pull\//.test(item)))
    invalid()
  if (value.kind === 'deployment' || value.kind === 'reconcile') {
    const deployment = value.deployment
    if (!object(deployment) || deployment.provider !== 'edgeone' || deployment.environment !== 'production' || deployment.verified !== true || !shaPattern.test(deployment.sha) || !origins.has(deployment.origin) || !instant(deployment.verifiedAt) || Date.parse(deployment.verifiedAt) > Date.parse(now) + 300_000)
      invalid()
    if (typeof deployment.id !== 'string' || !/^[\w:.-]{1,128}$/.test(deployment.id) || typeof deployment.summary !== 'string' || deployment.summary.trim().length < 5 || deployment.summary.length > 4000 || !strings(deployment.evidenceRefs) || !deployment.evidenceRefs.length || deployment.evidenceRefs.some(item => item.includes('#access=')))
      invalid()
    if (!strings(value.verifiedChangeShas, 1000, 40) || !value.verifiedChangeShas.length || !value.verifiedChangeShas.every(item => shaPattern.test(item)) || !shaPattern.test(value.commitSha) || value.commitSha !== deployment.sha)
      invalid()
    if (deployment.acceptanceProfiles !== undefined && (!strings(deployment.acceptanceProfiles, 2, 40) || deployment.acceptanceProfiles.some(profile => !Object.hasOwn(acceptanceDescriptions, profile))))
      invalid()
  }
  return structuredClone({ ...value, eventKey: key, refs: value.refs ?? [] })
}

/** Bind private ticket IDs to public development references without publishing a ticket mirror. */
export function parseCookDeliveryBindings(value) {
  if (!object(value) || value.schemaVersion !== 1 || !Array.isArray(value.tickets) || value.tickets.length > 1000)
    invalid()
  const seen = new Set()
  return value.tickets.map((entry) => {
    if (!object(entry) || entry.repository !== repository || !ticketPattern.test(entry.ticketId) || reservedKeys.has(entry.ticketId) || seen.has(entry.ticketId))
      invalid()
    seen.add(entry.ticketId)
    const commits = entry.commitShas ?? []
    const pulls = entry.pullRequests ?? []
    if (!strings(commits, 1000, 40) || !commits.every(item => shaPattern.test(item)) || !Array.isArray(pulls) || pulls.length > 100 || !pulls.every(item => Number.isSafeInteger(item) && item > 0))
      invalid()
    if (entry.requesterMessageAt !== undefined && !instant(entry.requesterMessageAt))
      invalid()
    if (entry.sendReply !== undefined && typeof entry.sendReply !== 'boolean')
      invalid()
    if (entry.resolutionReply !== undefined && (typeof entry.resolutionReply !== 'string' || !entry.resolutionReply.trim() || entry.resolutionReply.length > 20_000))
      invalid()
    if (entry.fixSummary !== undefined && (!object(entry.fixSummary) || Object.keys(entry.fixSummary).some(key => !['changes', 'userAction'].includes(key)) || !strings(entry.fixSummary.changes, 8, 500) || !entry.fixSummary.changes.length || entry.fixSummary.changes.some(change => !change.trim()) || (entry.fixSummary.userAction !== undefined && (typeof entry.fixSummary.userAction !== 'string' || !entry.fixSummary.userAction.trim() || entry.fixSummary.userAction.length > 1000))))
      invalid()
    const revision = entry.revision ?? 1
    if (!Number.isSafeInteger(revision) || revision < 1)
      invalid()
    const issue = Number.isSafeInteger(entry.issue) && entry.issue > 0 ? `https://github.com/${repository}/issues/${entry.issue}` : entry.issue
    if (issue !== undefined && (!ref(issue) || !issue.includes('/issues/')))
      invalid()
    return { ...entry, revision, commitShas: commits, pullRequests: pulls, ...(issue ? { issue } : {}) }
  })
}

function detail(value, id) {
  const current = value?.detail ?? value
  const ticket = current?.ticket
  if (!object(ticket) || ticket.id !== id || ticket.productId !== 'cook' || !Number.isSafeInteger(ticket.version) || ticket.version < 1 || !statuses.has(ticket.status) || !instant(ticket.lastRequesterMessageAt) || !object(ticket.work) || !Array.isArray(ticket.work.changeRefs) || !Array.isArray(current.messages) || !Array.isArray(current.deliveries))
    invalid()
  return structuredClone(current)
}

function requesterMessages(current) {
  return hash(current.messages.filter(item => ['user', 'guest'].includes(item.author?.kind)).map(item => [item.id, item.createdAt]).sort((a, b) => String(a[0]).localeCompare(String(b[0]))))
}

function guard(ticket) {
  return { status: ticket.status, work: ticket.work, ...(ticket.latestReplyId ? { latestReplyId: ticket.latestReplyId } : {}), lastRequesterMessageAt: ticket.lastRequesterMessageAt }
}

function productionCommand(command) {
  return command.action === 'reply.send' || (command.action === 'status' && ['resolved', 'closed'].includes(command.status)) || (command.action === 'work' && command.work?.verification)
}

function matched(binding, event, state) {
  if (event.kind === 'reconcile' && (!state?.release || state.release.bindingRefsHash !== bindingRefsHash(binding)))
    return false
  if (event.kind === 'deployment' || event.kind === 'reconcile')
    return event.verifiedChangeShas.some(sha => [...binding.commitShas, ...(state?.associatedShas ?? [])].includes(sha))
  return binding.commitShas.includes(event.commitSha) || event.refs.some(item => binding.pullRequests.some(number => item === `https://github.com/${repository}/pull/${number}`))
}

function mergeRefs(current, additions) {
  const next = unique([...current, ...additions])
  if (next.length > 20)
    throw Object.assign(new Error('Ticket change reference limit requires review'), { code: 'REFERENCE_LIMIT' })
  return next
}

function simulate(current, command, now) {
  const next = structuredClone(current)
  next.ticket.version++
  if (command.action === 'work') {
    next.ticket.work = structuredClone(command.work)
    delete next.ticket.latestReplyId
  }
  else if (command.action === 'status') {
    next.ticket.status = command.status
  }
  else if (command.action === 'reply.send') {
    const id = 'preview_resolution'
    next.messages.push({ id, kind: 'reply', visibility: 'requester_visible', createdAt: now, ...next.ticket.work.replyDraft })
    next.ticket.latestReplyId = id
    delete next.ticket.work.replyDraft
    next.deliveries.push({ ticketId: next.ticket.id, messageId: id, channel: 'portal', status: 'available' })
  }
  return next
}

/**
 * Reconcile one trusted event through the same versioned commands as the Support UI.
 * ports.detail/command/save are the only I/O seam; dry runs never call command or save.
 * Unknown outcomes retain the exact intent and key; only unrelated version bumps can be rebased.
 */
export async function syncCookDelivery(input, ports) {
  const now = ports.now?.() ?? new Date().toISOString()
  const event = parseCookDeliveryEvent(input.event, now)
  const bindings = parseCookDeliveryBindings(input.bindings)
  const apply = input.apply === true
  const ledger = apply ? input.ledger : structuredClone(input.ledger)
  if (!object(ledger) || ledger.schemaVersion !== 1 || !object(ledger.events) || !object(ledger.tickets))
    invalid()
  const fingerprint = hash(event)
  const prior = ledger.events[event.eventKey]
  if (prior && prior.fingerprint !== fingerprint)
    throw Object.assign(new Error('Event key was already used for different input'), { code: 'EVENT_CONFLICT' })
  const record = ledger.events[event.eventKey] ??= { fingerprint, tickets: {} }
  if (!object(record.tickets))
    invalid()
  const counts = { matched: 0, changed: 0, skipped: 0, blocked: 0, deferred: 0, commands: 0, dryRun: !apply }
  const save = async () => {
    if (apply)
      await ports.save(ledger)
  }
  await save()

  for (const binding of bindings) {
    let state = ledger.tickets[binding.ticketId]
    if (!matched(binding, event, state))
      continue
    counts.matched++
    const refsHash = bindingRefsHash(binding)
    if (record.tickets[binding.ticketId] === binding.revision && state?.bindingRefsHash === refsHash) {
      counts.skipped++
      continue
    }
    let current = detail(await ports.detail(binding.ticketId), binding.ticketId)
    if (!state || state.revision !== binding.revision) {
      if (state?.pending) {
        counts.blocked++
        continue
      }
      if (state && (binding.requesterMessageAt !== current.ticket.lastRequesterMessageAt || state.requesterMessageAt !== current.ticket.lastRequesterMessageAt || requesterMessages(current) !== state.requesterMessagesHash || current.ticket.status !== state.expectedStatus)) {
        counts.blocked++
        continue
      }
      if (event.kind !== 'change' && binding.requesterMessageAt !== current.ticket.lastRequesterMessageAt) {
        counts.blocked++
        continue
      }
      state = ledger.tickets[binding.ticketId] = { ...state, revision: binding.revision, bindingRefsHash: refsHash, requesterMessageAt: binding.requesterMessageAt ?? current.ticket.lastRequesterMessageAt, requesterMessagesHash: requesterMessages(current), expectedStatus: current.ticket.status, associatedShas: state?.bindingRefsHash === refsHash ? state.associatedShas : [], phases: state?.phases ?? {} }
      await save()
    }
    else if (state.bindingRefsHash !== refsHash) {
      counts.blocked++
      continue
    }
    const blocked = () => current.ticket.lastRequesterMessageAt !== state.requesterMessageAt || requesterMessages(current) !== state.requesterMessagesHash || current.ticket.status !== state.expectedStatus
    let changes = 0
    let deferred = false
    const stopped = () => {
      if (deferred)
        counts.deferred++
      else
        counts.blocked++
    }

    async function runPending() {
      while (state.pending) {
        const pending = state.pending
        if (pending.blocked)
          return false
        // A change event runs before fresh production acceptance. Even an unknown
        // prior result must wait for the deployment/reconcile gate before replay.
        if (event.kind === 'change' && productionCommand(pending.input.command)) {
          deferred = true
          return false
        }
        if (pending.input.command.action === 'reply.send' && (input.sendReplies !== true || binding.sendReply !== true || replyPolicyHash(binding) !== state.release?.replyPolicyHash))
          return false
        if (!apply) {
          current = simulate(current, pending.input.command, now)
        }
        else {
          try {
            await ports.command(structuredClone(pending.input), pending.key)
            current = detail(await ports.detail(binding.ticketId), binding.ticketId)
          }
          catch (error) {
            if (error?.code !== 'STATE_CONFLICT')
              throw error
            const fresh = detail(await ports.detail(binding.ticketId), binding.ticketId)
            if ((pending.rebases ?? 0) < 2 && fresh.ticket.version !== pending.input.version && requesterMessages(fresh) === state.requesterMessagesHash && equal(guard(fresh.ticket), pending.guard)) {
              pending.input.version = fresh.ticket.version
              pending.key = `cook_delivery_${hash([event.eventKey, binding.ticketId, pending.phase, pending.input])}`
              pending.rebases = (pending.rebases ?? 0) + 1
              current = fresh
              await save()
              continue
            }
            pending.blocked = true
            current = fresh
            await save()
            return false
          }
        }
        state.phases[pending.phase] = true
        Object.assign(state, pending.after)
        if (pending.input.command.action === 'reply.send' && state.release) {
          const published = current.messages.find(item => item.id === current.ticket.latestReplyId)
          if (published?.resolution && published.visibility === 'requester_visible' && hash(published.content) === state.release.replyHash && instant(published.createdAt) && Date.parse(published.createdAt) >= Date.parse(state.release.draftCreatedAt))
            state.replyId = published.id
        }
        if (pending.input.command.action === 'status')
          state.expectedStatus = pending.input.command.status
        delete state.pending
        counts.commands++
        changes++
        await save()
        if (blocked())
          return false
      }
      return true
    }

    async function command(phase, value, after = {}) {
      const key = `${event.eventKey}:${phase}:${hash(value).slice(0, 16)}`
      if (state.phases[key])
        return true
      if (blocked())
        return false
      const input = { id: binding.ticketId, version: current.ticket.version, command: value }
      state.pending = { phase: key, input, key: `cook_delivery_${hash([event.eventKey, binding.ticketId, key, input])}`, guard: guard(current.ticket), after }
      await save()
      return runPending()
    }

    if (!await runPending() || blocked()) {
      stopped()
      continue
    }
    if (current.ticket.status === 'closed' || (event.kind !== 'reconcile' && ['resolved', 'waiting_user'].includes(current.ticket.status))) {
      counts.skipped++
      continue
    }

    if (event.kind === 'change') {
      const additions = unique([...event.refs, ...(event.commitSha ? [`https://github.com/${repository}/commit/${event.commitSha}`] : []), ...(binding.issue ? [binding.issue] : [])])
      if (event.commitSha)
        state.associatedShas = unique([...state.associatedShas, event.commitSha])
      await save()
      // A work command invalidates latestReplyId. Keep published human replies intact.
      if (!current.ticket.latestReplyId) {
        const work = { ...current.ticket.work, changeRefs: mergeRefs(current.ticket.work.changeRefs, additions) }
        if (!equal(work, current.ticket.work) && !await command('references', { action: 'work', work })) {
          stopped()
          continue
        }
      }
      if (!await command('association-note', { action: 'note', content: `Cook 修复关联（待生产发布验收）：\n${additions.join('\n')}` })) {
        stopped()
        continue
      }
      if (current.ticket.status === 'new' && !await command('triage', { action: 'status', status: 'triaged' })) {
        stopped()
        continue
      }
      if (current.ticket.status === 'triaged' && !await command('progress', { action: 'status', status: 'in_progress' })) {
        stopped()
        continue
      }
    }

    if (event.kind === 'deployment') {
      const deployed = event.deployment
      if (Date.parse(deployed.verifiedAt) < Date.parse(state.requesterMessageAt)) {
        stopped()
        continue
      }
      const existing = current.ticket.work.verification
      if (existing && (!state.release || hash(existing) !== state.release.verificationHash)) {
        stopped()
        continue
      }
      const latest = current.messages.find(item => item.id === current.ticket.latestReplyId)
      if (latest?.resolution && latest.id !== state.replyId) {
        stopped()
        continue
      }
      const verification = { summary: deployed.summary, evidenceRefs: deployed.evidenceRefs, releaseRef: `edgeone:${deployed.id}@${deployed.sha}`, verifiedAt: deployed.verifiedAt }
      const reply = resolutionReply(binding, deployed)
      const previousDraft = current.ticket.work.replyDraft
      const ownedDraft = !previousDraft || (state.generatedDraftHash && hash(previousDraft) === state.generatedDraftHash)
      const replyDraft = ownedDraft && reply ? { content: reply, resolution: true } : previousDraft
      const work = { ...current.ticket.work, changeRefs: mergeRefs(current.ticket.work.changeRefs, [`https://github.com/${repository}/commit/${deployed.sha}`, ...event.refs]), verification, ...(replyDraft ? { replyDraft } : {}) }
      const after = { release: { id: deployed.id, sha: deployed.sha, bindingRefsHash: refsHash, verificationHash: hash(verification), replyHash: reply ? hash(reply) : null, replyPolicyHash: replyPolicyHash(binding), verifiedAt: deployed.verifiedAt, draftCreatedAt: now, ...(current.ticket.latestReplyId ? { previousReplyId: current.ticket.latestReplyId } : {}) }, generatedDraftHash: ownedDraft && reply ? hash(replyDraft) : null }
      if (!await command('verified-release', { action: 'work', work }, after)) {
        stopped()
        continue
      }
    }

    if (event.kind !== 'change' && state.release) {
      const release = state.release
      let latest = current.messages.find(item => item.id === current.ticket.latestReplyId)
      const ownedReply = item => item?.resolution === true && item.visibility === 'requester_visible' && hash(item.content) === release.replyHash && instant(item.createdAt) && Date.parse(item.createdAt) >= Date.parse(release.draftCreatedAt) && item.id !== release.previousReplyId
      // A maintainer may publish the unchanged automation draft in the UI.
      if (!state.replyId && state.generatedDraftHash && ownedReply(latest)) {
        state.replyId = latest.id
        await save()
      }
      if (input.sendReplies === true && binding.sendReply === true && !state.replyId) {
        const draft = current.ticket.work.replyDraft
        if (replyPolicyHash(binding) !== release.replyPolicyHash || latest?.resolution || !state.generatedDraftHash || !draft?.resolution || hash(draft) !== state.generatedDraftHash || hash(draft.content) !== release.replyHash || !current.ticket.work.verification || hash(current.ticket.work.verification) !== release.verificationHash) {
          stopped()
          continue
        }
        // A later explicit authorization may publish the saved draft, without
        // rewriting its verification or treating the old deployment as new work.
        if (!await command('publish-resolution', { action: 'reply.send' })) {
          stopped()
          continue
        }
        latest = current.messages.find(item => item.id === current.ticket.latestReplyId)
      }
      if (!blocked() && state.replyId && current.ticket.latestReplyId === state.replyId && ownedReply(latest) && current.ticket.work.verification && hash(current.ticket.work.verification) === release.verificationHash) {
        if (['triaged', 'in_progress', 'waiting_user'].includes(current.ticket.status) && !await command('resolved', { action: 'status', status: 'resolved' })) {
          stopped()
          continue
        }
        const received = current.deliveries.some(item => item.ticketId === binding.ticketId && item.messageId === state.replyId && item.channel === 'portal' && ['delivered', 'read'].includes(item.status))
        // Support computes the no-contact guest exemption; keep receipt evidence unchanged.
        if (current.ticket.status === 'resolved' && (received || current.closeWithoutDelivery === true) && !await command('closed', { action: 'status', status: 'closed' })) {
          stopped()
          continue
        }
      }
    }
    record.tickets[binding.ticketId] = binding.revision
    await save()
    if (changes)
      counts.changed++
    else
      counts.skipped++
  }
  return counts
}
