/**
 * Cook recipe feedback and search signals (YunYouJun/cook#100 follow-up).
 *
 * - `report`: a user flags a recipe (dead video, wrong ingredients, ...).
 *   Signing in is optional; anonymous reports are keyed by a random device id.
 * - `signal`: anonymous, aggregated search signals (a selection with no
 *   results, or which result was opened at which rank) used to tune filtering.
 * - `feedback`: private app feedback delegated to Support through its SDK.
 *   Existing Web/iOS callers keep the same protocol; no new local feedback rows.
 * - `export`: maintainers pull everything for the recipe-data-review skill.
 *   Only over the HTTP trigger (GET) with the COOK_FEEDBACK_EXPORT_TOKEN header.
 *
 * The web app calls the function through the CloudBase SDK. The native iOS app
 * has no CloudBase SDK, so it POSTs the same `report` / `signal` / `feedback`
 * bodies to the HTTP trigger, under the same device id and daily quota.
 *
 * All collections are ADMINONLY; clients only reach them through this
 * function. Raw device ids and uids are hashed before they are stored as a
 * reporter key; the uid itself is kept only on reports from signed-in users so
 * maintainers can follow up.
 */

const { Buffer } = require('node:buffer')
const crypto = require('node:crypto')
const process = require('node:process')
const { submitAppFeedback } = require('./support')

// A deployment setting, never a caller-selected collection or environment.
const ENVIRONMENT = process.env.COOK_FEEDBACK_ENVIRONMENT || 'production'
const SUFFIX = ENVIRONMENT === 'test' ? '_test' : ''
const FEEDBACK = `cook_recipe_feedback${SUFFIX}`
const SIGNALS = `cook_search_signals${SUFFIX}`
const QUOTA = `cook_feedback_quota${SUFFIX}`
const APP_FEEDBACK = `cook_app_feedback${SUFFIX}`
const PRODUCTION_ORIGINS = new Set(['https://cook.yunyoujun.cn', 'https://cook.yunle.fun'])
const LINK_ISSUES = new Set(['', 'unavailable', 'loading', 'login', 'wrong-content', 'other'])

const KINDS = new Set(['video', 'text', 'stuff', 'info', 'other'])
const MODES = new Set(['loose', 'strict', 'survival'])
const SIGNAL_TYPES = new Set(['empty', 'open'])
const CATEGORIES = new Set(['bug', 'idea', 'recipe', 'other'])
const PLATFORMS = new Set(['web', 'ios'])
const PUBLIC_ACTIONS = new Set(['report', 'signal', 'feedback'])
const DAILY_QUOTA = 200
const MAX_NOTE = 200
const MAX_NAME = 100
const MAX_STUFF = 12
const MAX_WORD = 20
const MAX_MESSAGE = 500
const MAX_CONTACT = 100
const MAX_BODY = 4096
const DEVICE_ID_RE = /^[\w-]{16,64}$/
const EXPORT_PAGE = 1000
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

function hash(text) {
  return crypto.createHash('sha256').update(text).digest('hex').slice(0, 32)
}

/** Calendar day in China, which is when the quota resets. */
function today(now) {
  return new Date(now + 8 * 3600 * 1000).toISOString().slice(0, 10)
}

function isWord(value, max = MAX_WORD) {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= max
}

function isIndex(value, max) {
  return Number.isInteger(value) && value >= 0 && value <= max
}

/** Who is calling: the account when signed in, otherwise the device. */
function readReporter(event, anonymousOnly = false) {
  const uid = anonymousOnly ? '' : callerUid()
  if (uid)
    return { key: hash(`uid:${uid}`), uid }
  if (typeof event.deviceId === 'string' && DEVICE_ID_RE.test(event.deviceId))
    return { key: hash(`device:${event.deviceId}`), uid: '' }
  return null
}

/** Count one call against the reporter's daily quota. */
async function takeQuota(reporter, now) {
  const db = getApp().database()
  const id = hash(`${reporter.key}|${today(now)}`)
  const doc = db.collection(QUOTA).doc(id)
  const result = await doc.update({ count: db.command.inc(1) })
  if (!result || !result.updated) {
    await doc.set({ count: 1, day: today(now) })
    return true
  }
  const current = await doc.get()
  const count = current && current.data && current.data[0] ? current.data[0].count : 0
  return count <= DAILY_QUOTA
}

/** Which app sent it: `platform` defaults to web, so older clients and rows count as web. */
function readSource(event) {
  const platform = event.platform ?? 'web'
  const version = event.version ?? ''
  if (!PLATFORMS.has(platform) || (version && !isWord(version, 32)))
    return null
  const context = readContext(event.context, platform)
  if (context === null)
    return null
  return { platform, version, ...(context ? { context } : {}), environment: ENVIRONMENT }
}

/** Client-reported diagnostics, not identity or proof of an official client. No query strings. */
function readContext(value, platform) {
  if (value === undefined)
    return undefined
  if (!value || typeof value !== 'object' || Array.isArray(value))
    return null
  const { entryPoint, origin = '', path = '', build = '', recipeVersion = '' } = value
  if (!['recipe-detail', 'app-feedback', 'search'].includes(entryPoint)
    || typeof build !== 'string' || !/^[\w.+-]{0,64}$/.test(build)
    || typeof recipeVersion !== 'string' || !/^[a-f0-9]{0,64}$/.test(recipeVersion)
    || typeof path !== 'string' || path.length > 200 || (path && !/^\/[\w/%.-]*$/.test(path))) {
    return null
  }
  if (typeof origin !== 'string' || origin.length > 200)
    return null
  if (origin) {
    try {
      const url = new URL(origin)
      if (!['https:', 'http:'].includes(url.protocol) || url.origin !== origin)
        return null
    }
    catch { return null }
  }
  if (platform === 'web' && ENVIRONMENT === 'production' && !PRODUCTION_ORIGINS.has(origin))
    return null
  return { entryPoint, origin, path, build, recipeVersion }
}

/** Store a bounded, inert source snapshot. Never fetch caller-provided URLs. */
function readRecipeSources(event) {
  if (event.sourceBv === undefined && event.sourceLink === undefined)
    return {}
  const sourceBv = event.sourceBv ?? ''
  const sourceLink = event.sourceLink ?? ''
  if (typeof sourceBv !== 'string' || (sourceBv && !/^(?:BV[0-9A-Za-z]{10}|av\d+)$/.test(sourceBv)) || typeof sourceLink !== 'string' || sourceLink.length > 2048)
    return null
  if (sourceLink) {
    try {
      const url = new URL(sourceLink)
      if (url.protocol !== 'https:' || url.username || url.password)
        return null
    }
    catch {
      return null
    }
  }
  if ((event.kind === 'video' && !sourceBv) || (event.kind === 'text' && !sourceLink))
    return null
  return { sourceBv, sourceLink }
}

async function report(event, reporter, now) {
  const { recipeId, recipeName, kind } = event
  const note = typeof event.note === 'string' ? event.note.trim().slice(0, MAX_NOTE) : ''
  const source = readSource(event)
  const recipeSources = readRecipeSources(event)
  const linkIssue = event.linkIssue ?? ''
  if (!isIndex(recipeId, 1e6) || !isWord(recipeName, MAX_NAME) || !KINDS.has(kind) || !source || !recipeSources || !LINK_ISSUES.has(linkIssue) || (linkIssue && !['video', 'text'].includes(kind)))
    return fail('INVALID_REPORT')
  if (!await takeQuota(reporter, now))
    return fail('RATE_LIMITED')

  // One report per reporter, recipe and kind per day; repeats just update the note.
  const day = today(now)
  const id = hash(`${reporter.key}|${recipeId}|${kind}|${day}`)
  await getApp().database().collection(FEEDBACK).doc(id).set({
    recipeId,
    recipeName: recipeName.trim(),
    kind,
    ...(event.linkIssue !== undefined ? { linkIssue } : {}),
    note,
    ...source,
    ...recipeSources,
    reporter: reporter.key,
    uid: reporter.uid,
    day,
    createdAt: now,
  })
  return { ok: true }
}

async function appFeedback(event, reporter, now) {
  const { category } = event
  const note = typeof event.note === 'string' ? event.note.trim() : ''
  const contact = typeof event.contact === 'string' ? event.contact.trim() : ''
  const source = readSource(event)
  if (!CATEGORIES.has(category) || !note || note.length > MAX_MESSAGE || contact.length > MAX_CONTACT || !source)
    return fail('INVALID_FEEDBACK')
  if (!await takeQuota(reporter, now))
    return fail('RATE_LIMITED')

  const submissionId = event.submissionId
  if (submissionId !== undefined && (typeof submissionId !== 'string' || !/^[\w-]{8,128}$/.test(submissionId)))
    return fail('INVALID_FEEDBACK')
  if (ENVIRONMENT === 'test') {
    const id = hash(`${reporter.key}|${submissionId || JSON.stringify([category, note, contact, today(now)])}`)
    // Test app feedback is private and isolated; never forward it to real Support.
    await getApp().database().collection(APP_FEEDBACK).doc(id).set({ category, note, contact, ...source, createdAt: now })
    return { ok: true, receipt: { id: `test_${id}`, productId: 'cook', status: 'received', createdAt: new Date(now).toISOString() } }
  }
  return submitAppFeedback({ category, note, contact, source, reporter, day: today(now), submissionId })
}

function readSelection(event) {
  const stuff = Array.isArray(event.stuff) ? event.stuff : null
  if (!stuff || !stuff.length || stuff.length > MAX_STUFF || !stuff.every(item => isWord(item)))
    return null
  const tool = event.tool ?? ''
  const mode = event.mode ?? 'loose'
  if ((tool && !isWord(tool)) || !MODES.has(mode))
    return null
  return { stuff: [...new Set(stuff.map(item => item.trim()))].sort(), tool, mode }
}

async function signal(event, reporter, now) {
  const selection = readSelection(event)
  const source = readSource(event)
  if (!selection || !source || !SIGNAL_TYPES.has(event.type))
    return fail('INVALID_SIGNAL')
  const open = event.type === 'open'
  if (open && (!isIndex(event.recipeId, 1e6) || !isIndex(event.rank, 999)))
    return fail('INVALID_SIGNAL')
  if (!await takeQuota(reporter, now))
    return fail('RATE_LIMITED')

  const db = getApp().database()
  const recipeId = open ? event.recipeId : -1
  const rank = open ? event.rank : 0
  // Counted per platform, since the web and iOS screens differ. Web keeps its
  // original key, so rows aggregated before platforms existed keep counting.
  const key = [event.type, selection.stuff.join('、'), selection.tool, selection.mode, recipeId]
  if (source.platform !== 'web')
    key.push(source.platform)
  const doc = db.collection(SIGNALS).doc(hash(key.join('|')))
  const day = today(now)
  const result = await doc.update({ count: db.command.inc(1), rankSum: db.command.inc(rank), lastDay: day, lastVersion: source.version })
  if (!result || !result.updated)
    await doc.set({ type: event.type, ...selection, recipeId, platform: source.platform, count: 1, rankSum: rank, firstDay: day, lastDay: day, lastVersion: source.version })
  return { ok: true }
}

function sameToken(given, expected) {
  if (typeof given !== 'string' || !expected)
    return false
  const a = Buffer.from(given)
  const b = Buffer.from(expected)
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

async function readAll(name, where) {
  const db = getApp().database()
  const rows = []
  for (let skip = 0; ; skip += EXPORT_PAGE) {
    const query = where ? db.collection(name).where(where) : db.collection(name)
    const result = await query.skip(skip).limit(EXPORT_PAGE).get()
    const page = (result && result.data) || []
    rows.push(...page.map(({ _id, _openid, uid, ...rest }) => ({ ...rest, signedIn: Boolean(uid) })))
    if (page.length < EXPORT_PAGE)
      return rows
  }
}

async function exportAll(since) {
  const db = getApp().database()
  const after = Number.isFinite(since) && since > 0 ? since : 0
  return {
    ok: true,
    feedback: await readAll(FEEDBACK, after ? { createdAt: db.command.gt(after) } : null),
    signals: await readAll(SIGNALS, null),
    appFeedback: await readAll(APP_FEEDBACK, after ? { createdAt: db.command.gt(after) } : null),
  }
}

async function handleAction(event, anonymousOnly = false) {
  if (!['production', 'test'].includes(ENVIRONMENT)
    || (event.environment !== undefined && event.environment !== ENVIRONMENT)
    || (ENVIRONMENT === 'test' && event.environment !== 'test')) {
    return fail('ENVIRONMENT_MISMATCH')
  }
  const reporter = readReporter(event, anonymousOnly)
  if (!reporter)
    return fail('REPORTER_REQUIRED')
  const now = Date.now()
  switch (event.action) {
    case 'report':
      return await report(event, reporter, now)
    case 'signal':
      return await signal(event, reporter, now)
    case 'feedback':
      return await appFeedback(event, reporter, now)
    default:
      return fail('UNKNOWN_ACTION')
  }
}

function respond(statusCode, body) {
  return { statusCode, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }
}

/** A JSON object body from the HTTP trigger, or null. */
function readBody(event) {
  let body = event.body
  if (body && typeof body === 'object')
    return Array.isArray(body) ? null : body
  if (typeof body !== 'string')
    return null
  if (event.isBase64Encoded)
    body = Buffer.from(body, 'base64').toString('utf8')
  if (body.length > MAX_BODY)
    return null
  try {
    const data = JSON.parse(body)
    return data && typeof data === 'object' && !Array.isArray(data) ? data : null
  }
  catch {
    return null
  }
}

/**
 * Requests through the HTTP trigger: POST carries the same actions as
 * callFunction (for the native app); anything else is the token-protected export.
 */
async function handleHttp(event) {
  if (event.httpMethod === 'POST') {
    const data = readBody(event)
    if (!data || !PUBLIC_ACTIONS.has(data.action))
      return respond(400, fail('INVALID_BODY'))
    const origin = event.headers && (event.headers.origin || event.headers.Origin)
    if (ENVIRONMENT === 'production' && origin && !PRODUCTION_ORIGINS.has(origin))
      return respond(400, fail('ENVIRONMENT_MISMATCH'))
    // This HTTP endpoint has no authenticated session. Never adopt a body/header UID.
    const result = await handleAction(data, true)
    const status = result.ok ? 200 : result.code === 'RATE_LIMITED' ? 429 : result.code === 'SUPPORT_UNAVAILABLE' ? 503 : result.code === 'SUBMISSION_CONFLICT' ? 409 : 400
    return respond(status, result)
  }
  const headers = event.headers || {}
  if (!sameToken(headers['x-cook-token'], process.env.COOK_FEEDBACK_EXPORT_TOKEN || ''))
    return { statusCode: 401, body: JSON.stringify(fail('UNAUTHORIZED')) }
  const params = event.queryStringParameters || {}
  return respond(200, await exportAll(Number(params.since)))
}

async function main(event = {}) {
  try {
    return event.httpMethod ? await handleHttp(event) : await handleAction(event)
  }
  catch (error) {
    console.error('[cook-feedback]', event.action || event.httpMethod, error && error.message)
    return event.httpMethod ? respond(500, fail('INTERNAL_ERROR')) : fail('INTERNAL_ERROR')
  }
}

exports.main = main
exports._internal = {
  ENVIRONMENT,
  APP_FEEDBACK,
  DAILY_QUOTA,
  FEEDBACK,
  QUOTA,
  SIGNALS,
  setAppForTest(nextApp) {
    app = nextApp
  },
}
