const { createHash, createHmac } = require('node:crypto')

const COLLECTION = 'cook_ai_quota'
const MAX_ATTEMPTS = 3
const MAX_CLEANUP_DOCUMENTS = 100

class QuotaError extends Error {
  constructor(code) {
    super(code)
    this.code = code
  }
}

function documentData(result) {
  if (Array.isArray(result?.data))
    return result.data[0] ?? null
  return result?.data ?? null
}

function transactionConflict(error) {
  return error?.code === 'ResourceUnavailable.TransactionBusy'
    || (typeof error?.message === 'string' && error.message.includes('[ResourceUnavailable.TransactionBusy]'))
}

/** Reserve the global and per-account request limits in one database transaction. */
function createQuotaReservation(db, config) {
  if (!db || typeof db.runTransaction !== 'function')
    throw new QuotaError('SERVICE_UNAVAILABLE')

  return async ({ uid, now }) => {
    if (typeof uid !== 'string' || !uid || !Number.isSafeInteger(now) || now < 0
      || typeof config.quotaSalt !== 'string' || config.quotaSalt.length < 32
      || ![config.userDailyLimit, config.userMinuteLimit, config.dailyRequestBudget].every(value => Number.isSafeInteger(value) && value > 0)) {
      throw new QuotaError('SERVICE_UNAVAILABLE')
    }
    const day = new Date(now + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
    const minute = Math.floor(now / 60_000)
    const subject = createHmac('sha256', config.quotaSalt).update(uid).digest('hex')
    const counters = [
      { id: `global:${day}`, scope: 'global-day', window: day, limit: config.dailyRequestBudget, code: 'BUDGET_EXHAUSTED', expiresAt: now + 3 * 86400_000 },
      { id: `day:${subject}:${day}`, scope: 'user-day', window: day, limit: config.userDailyLimit, code: 'RATE_LIMITED', expiresAt: now + 3 * 86400_000 },
      { id: `minute:${subject}:${minute}`, scope: 'user-minute', window: minute, limit: config.userMinuteLimit, code: 'RATE_LIMITED', expiresAt: now + 2 * 60_000 },
    ]

    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      try {
        await db.runTransaction(async (transaction) => {
          const rows = []
          // Read every counter before writing; a rejected request consumes none of them.
          for (const counter of counters) {
            const id = createHash('sha256').update(counter.id).digest('hex')
            const ref = transaction.collection(COLLECTION).doc(id)
            const value = documentData(await ref.get())
            if (value && (value.scope !== counter.scope || value.window !== counter.window
              || !Number.isSafeInteger(value.count) || value.count < 0)) {
              throw new QuotaError('SERVICE_UNAVAILABLE')
            }
            const count = value?.count ?? 0
            if (count >= counter.limit)
              throw new QuotaError(counter.code)
            rows.push({ counter, ref, count })
          }
          for (const { counter, ref, count } of rows) {
            await ref.set({
              scope: counter.scope,
              window: counter.window,
              count: count + 1,
              updatedAt: now,
              expiresAt: new Date(counter.expiresAt),
            })
          }
        })
        return
      }
      catch (error) {
        if (error instanceof QuotaError)
          throw error
        if (!transactionConflict(error) || attempt + 1 === MAX_ATTEMPTS)
          throw new QuotaError('SERVICE_UNAVAILABLE')
      }
    }
  }
}

/** Remove only bounded, explicitly returned expired counters on a later admitted member request. */
async function cleanupExpiredQuota(db, now) {
  if (!db || typeof db.collection !== 'function' || typeof db.command?.lt !== 'function'
    || !Number.isSafeInteger(now) || now < 0) {
    throw new QuotaError('SERVICE_UNAVAILABLE')
  }
  const collection = db.collection(COLLECTION)
  const result = await collection.where({ expiresAt: db.command.lt(new Date(now)) }).limit(MAX_CLEANUP_DOCUMENTS).get()
  if (!Array.isArray(result?.data))
    throw new QuotaError('SERVICE_UNAVAILABLE')
  const ids = result.data.slice(0, MAX_CLEANUP_DOCUMENTS).filter(row =>
    typeof row?._id === 'string' && /^[a-f0-9]{64}$/.test(row._id)
    && row.expiresAt instanceof Date && Number.isFinite(row.expiresAt.getTime()) && row.expiresAt.getTime() < now,
  ).map(row => row._id)
  const removed = await Promise.allSettled(ids.map(id => collection.doc(id).remove()))
  if (removed.some(result => result.status === 'rejected'))
    throw new QuotaError('SERVICE_UNAVAILABLE')
  return ids.length
}

module.exports = { COLLECTION, MAX_ATTEMPTS, MAX_CLEANUP_DOCUMENTS, QuotaError, cleanupExpiredQuota, createQuotaReservation }
