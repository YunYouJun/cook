/**
 * Cook favorites / history sync (YunYouJun/cook#98).
 *
 * The `cook_user_data` collection is ADMINONLY: browsers never read or write
 * it directly. This function takes the caller from the verified CloudBase
 * session, never from the request, and always uses it as the document id, so
 * nobody can read, overwrite or pre-create another account's document.
 */

const { Buffer } = require('node:buffer')

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
  await getApp().database().collection(COLLECTION).doc(uid).set({ ...document, updatedAt: now })
  return { ok: true, updatedAt: now }
}

async function main(event = {}) {
  const uid = callerUid()
  if (!uid)
    return fail('LOGIN_REQUIRED')

  try {
    switch (event.action) {
      case 'get':
        return await getDocument(uid)
      case 'set':
        return await setDocument(uid, event.document, Date.now())
      default:
        return fail('UNKNOWN_ACTION')
    }
  }
  catch (error) {
    console.error('[cook-user-data]', event.action, error && error.message)
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
