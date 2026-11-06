/**
 * Cook favorites / history sync (YunYouJun/cook#98).
 *
 * The `cook_user_data` collection is ADMINONLY: browsers never read or write
 * it directly. This function takes the caller from the verified CloudBase
 * session, never from the request, and always uses it as the document id, so
 * nobody can read, overwrite or pre-create another account's document.
 */

const { Buffer } = require('node:buffer')
const { createHash } = require('node:crypto')
const { normalizeUserData, mergeUserData } = require('./user-data.cjs')

const COLLECTION = 'cook_user_data'
const USER_DATA_VERSION = 1
const MAX_DOCUMENT_BYTES = 256 * 1024
const LIST_LIMITS = {
  favorites: 5000,
  favoriteRemovals: 1000,
  histories: 500,
  historyRemovals: 1000,
}
const ANON_UIDS = new Set(['', 'anon'])

// Same read-only contract as account-api/lib/orders.js: canonical uid first,
// then legacy userId documents. No client-supplied entitlement is trusted.
async function activeMembership(uid, now) {
  const collection = getApp().database().collection('user_memberships')
  const result = await collection.doc(uid).get()
  const canonical = Array.isArray(result.data) ? result.data[0] : result.data
  let membership = canonical && (!canonical.userId || canonical.userId === uid) ? canonical : null
  if (!membership) {
    const legacy = await collection.where({ userId: uid }).limit(10).get()
    if (Array.isArray(legacy.data))
      membership = legacy.data.find(item => item?._id === uid) || legacy.data[0]
  }
  return Number.isSafeInteger(membership?.expireAt) && membership.expireAt > now
}

let app

function getApp() {
  if (!app) {
    // Loaded lazily so tests can inject a fake app without the SDK installed.
    const tcb = require('@cloudbase/node-sdk')
    app = tcb.init({ env: tcb.SYMBOL_CURRENT_ENV })
  }
  return app
}

/** The signed-in, non-anonymous caller, or '' when there is none. */
function callerUid() {
  try {
    const info = getApp().auth().getUserInfo()
    const uid = info && info.uid ? String(info.uid) : ''
    return ANON_UIDS.has(uid) || (info && info.isAnonymous === true) ? '' : uid
  }
  catch {
    return ''
  }
}

function fail(code) {
  return { ok: false, code }
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

/**
 * Check the shape and size of an uploaded document. Entry contents are
 * normalised again by every client that reads them.
 */
function readDocument(value) {
  if (!isRecord(value) || value.version !== USER_DATA_VERSION)
    return null
  if (Buffer.byteLength(JSON.stringify(value), 'utf8') > MAX_DOCUMENT_BYTES)
    return null

  const document = { version: USER_DATA_VERSION }
  for (const [key, limit] of Object.entries(LIST_LIMITS)) {
    const list = value[key] ?? []
    if (!Array.isArray(list) || list.length > limit || !list.every(isRecord))
      return null
    document[key] = list
  }
  const clearedAt = value.historyClearedAt ?? 0
  if (typeof clearedAt !== 'number' || !Number.isFinite(clearedAt) || clearedAt < 0)
    return null
  document.historyClearedAt = clearedAt
  return document
}

function stripMeta(doc) {
  if (!isRecord(doc))
    return null
  const { _id, _openid, ...rest } = doc
  return rest
}

async function getDocument(uid) {
  const result = await getApp().database().collection(COLLECTION).doc(uid).get()
  const doc = result && Array.isArray(result.data) ? result.data[0] : null
  return { ok: true, document: stripMeta(doc) }
}

async function setDocument(uid, input, now) {
  const document = readDocument(input)
  if (!document)
    return fail('INVALID_DOCUMENT')
  // Both new sync and old set callers merge inside the same transaction. A
  // snapshot read before another device's write can never erase that write.
  return getApp().database().runTransaction(async (transaction) => {
    const reference = transaction.collection(COLLECTION).doc(uid)
    const response = await reference.get()
    const raw = Array.isArray(response.data) ? response.data[0] : response.data
    const previous = raw == null ? readDocument({ version: USER_DATA_VERSION }) : readDocument(stripMeta(raw))
    if (!previous)
      return fail('INVALID_REMOTE')
    const merged = { version: USER_DATA_VERSION, ...mergeUserData(normalizeUserData(previous), normalizeUserData(document), now), updatedAt: now }
    if (!readDocument(merged))
      return fail('DOCUMENT_LIMIT')
    await reference.set(merged)
    return { ok: true, document: merged, updatedAt: now, protocolVersion: 2 }
  })
}

async function main(event = {}) {
  const uid = callerUid()
  if (!uid)
    return fail('LOGIN_REQUIRED')
  // Binding only: never use a client value to choose a document. This rejects
  // an SDK session change that occurs while the browser awaits initialization.
  if (event.ownerDigest !== undefined && event.ownerDigest !== createHash('sha256').update(uid).digest('hex'))
    return fail('SESSION_CHANGED')

  try {
    if (!await activeMembership(uid, Date.now()))
      return fail('MEMBERSHIP_REQUIRED')
    switch (event.action) {
      case 'capabilities':
        return { ok: true, protocolVersion: 2 }
      case 'get':
        return await getDocument(uid)
      case 'set':
      case 'sync':
        return await setDocument(uid, event.document, Date.now())
      default:
        return fail('UNKNOWN_ACTION')
    }
  }
  catch {
    // SDK messages can contain document IDs or payloads; keep logs categorical.
    console.error('[cook-user-data]', 'request_failed')
    return fail('INTERNAL_ERROR')
  }
}

exports.main = main
exports._internal = {
  COLLECTION,
  LIST_LIMITS,
  MAX_DOCUMENT_BYTES,
  readDocument,
  setAppForTest(nextApp) {
    app = nextApp
  },
}
