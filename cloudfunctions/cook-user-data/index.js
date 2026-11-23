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
const { membershipAccessExpiry } = require('./membership-access')
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

class AccountDeletionInProgress extends Error {}

async function readStoredDocument(reference) {
  try {
    const { data } = await reference.get()
    return (Array.isArray(data) ? data[0] : data) ?? null
  }
  catch (error) {
    if (error?.code === 'DOCUMENT_NOT_FOUND')
      return null
    throw error
  }
}

// Same read-only contract as account-api/lib/orders.js: canonical uid first,
// then legacy userId documents. No client-supplied entitlement is trusted.
async function activeMembership(uid, now) {
  const collection = getApp().database().collection('user_memberships')
  const canonical = await readStoredDocument(collection.doc(uid))
  let membership = canonical && (!canonical.userId || canonical.userId === uid) ? canonical : null
  if (!membership) {
    const legacy = await collection.where({ userId: uid }).limit(10).get()
    if (Array.isArray(legacy.data))
      membership = legacy.data.find(item => item?._id === uid) || legacy.data[0]
  }
  const expireAt = membershipAccessExpiry(membership)
  return Number.isSafeInteger(expireAt) && expireAt > now
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

async function accountDeletionBlocksSync(uid) {
  const profile = await readStoredDocument(getApp().database().collection('user_profiles').doc(uid))
  return profile && (profile.deletionStatus === 'pending'
    || profile.deletionStatus === 'finalizing'
    || profile.deletionStatus === 'completed'
    || profile.deletedAt
    || profile.authDeletedAt)
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
  const doc = await readStoredDocument(getApp().database().collection(COLLECTION).doc(uid))
  return { ok: true, document: stripMeta(doc) }
}

async function setDocument(uid, input, now) {
  const document = readDocument(input)
  if (!document)
    return fail('INVALID_DOCUMENT')
  let deletionBlocked = false
  try {
    // Both new sync and old set callers merge inside the same transaction. A
    // snapshot read before another device's write can never erase that write.
    return await getApp().database().runTransaction(async (transaction) => {
      const reference = transaction.collection(COLLECTION).doc(uid)
      const raw = await readStoredDocument(reference)
      const previous = raw == null ? readDocument({ version: USER_DATA_VERSION }) : readDocument(stripMeta(raw))
      if (!previous)
        return fail('INVALID_REMOTE')
      const merged = { version: USER_DATA_VERSION, ...mergeUserData(normalizeUserData(previous), normalizeUserData(document), now), updatedAt: now }
      if (!readDocument(merged))
        return fail('DOCUMENT_LIMIT')
      await reference.set(merged)
      // Recheck against the current profile while the staged write holds the library lock.
      // A profile read inside this transaction would still see its earlier snapshot.
      if (await accountDeletionBlocksSync(uid)) {
        deletionBlocked = true
        throw new AccountDeletionInProgress()
      }
      return { ok: true, document: merged, updatedAt: now, protocolVersion: 2 }
    })
  }
  catch (error) {
    if (deletionBlocked || error instanceof AccountDeletionInProgress)
      return fail('LOGIN_REQUIRED')
    throw error
  }
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
    // A still-valid token must not read or recreate an account's sync copy
    // during the cooling-off period or after the deletion worker removes it.
    if (await accountDeletionBlocksSync(uid))
      return fail('LOGIN_REQUIRED')
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
  catch (error) {
    if (error instanceof AccountDeletionInProgress)
      return fail('LOGIN_REQUIRED')
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
