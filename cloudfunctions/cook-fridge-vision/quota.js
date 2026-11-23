const { createHash, createHmac } = require('node:crypto')

const COLLECTION = 'cook_fridge_vision_quota'

class VisionError extends Error {
  constructor(code) {
    super(code)
    this.code = code
  }
}

async function readCounter(ref) {
  try {
    const { data } = await ref.get()
    return Array.isArray(data) ? data[0] : data
  }
  catch (error) {
    // CloudBase can return a 404 for the first use of a daily counter.
    if (error?.code === 'DOCUMENT_NOT_FOUND')
      return null
    throw error
  }
}

async function reserveQuota(db, { quotaSalt, userDailyLimit, dailyRequestBudget }, uid, now) {
  const day = new Date(now + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
  const subject = createHmac('sha256', quotaSalt).update(uid).digest('hex')
  const counters = [
    [`global:${day}`, dailyRequestBudget, 'BUDGET_EXHAUSTED'],
    [`user:${subject}:${day}`, userDailyLimit, 'RATE_LIMITED'],
  ]
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      await db.runTransaction(async (transaction) => {
        const writes = []
        for (const [key, limit, code] of counters) {
          const id = createHash('sha256').update(key).digest('hex')
          const ref = transaction.collection(COLLECTION).doc(id)
          const value = await readCounter(ref)
          const count = value == null ? 0 : value.count
          if (!Number.isSafeInteger(count) || count < 0)
            throw new VisionError('SERVICE_UNAVAILABLE')
          if (count >= limit)
            throw new VisionError(code)
          writes.push({ ref, count: count + 1 })
        }
        for (const { ref, count } of writes)
          await ref.set({ count, expiresAt: new Date(now + 3 * 86_400_000) })
      })
      return
    }
    catch (error) {
      const conflict = error?.code === 'ResourceUnavailable.TransactionBusy'
        || (typeof error?.message === 'string' && error.message.includes('[ResourceUnavailable.TransactionBusy]'))
      if (!conflict || attempt === 2)
        throw error
    }
  }
}

async function cleanupExpiredQuota(db, now) {
  const collection = db.collection(COLLECTION)
  const { data } = await collection.where({ expiresAt: db.command.lt(new Date(now)) }).limit(30).get()
  if (!Array.isArray(data))
    return
  const expired = data.filter(row => typeof row?._id === 'string' && /^[a-f0-9]{64}$/.test(row._id)
    && row.expiresAt instanceof Date && row.expiresAt.getTime() < now)
  await Promise.allSettled(expired.map(row => collection.doc(row._id).remove()))
}

module.exports = { COLLECTION, VisionError, cleanupExpiredQuota, reserveQuota }
