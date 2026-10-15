import type { AcquisitionContext, AcquisitionTouch } from '@yunlefun/sso'
import { sanitizeAcquisitionContext } from '@yunlefun/sso'

const FIRST_TOUCH_KEY = 'cook:acquisition:v1:first-touch'
const REGISTER_TOUCH_KEY = 'cook:acquisition:v1:register-touch'
const FIRST_TOUCH_TTL_MS = 30 * 24 * 60 * 60 * 1000
const UTM_RE = /^[\p{L}\p{N}\p{M} ._/-]*$/u
let fallback: AcquisitionContext | null = null
const pendingWrites = new Map<string, { previousValue: string | null | undefined, touch: AcquisitionTouch }>()

type AcquisitionBrowser = Pick<Window, 'location' | 'document' | 'localStorage' | 'sessionStorage'>

/** Called once at document entry, before auth can consume or redirect a callback. */
export function captureCookAcquisition(
  browser: AcquisitionBrowser | undefined = typeof window === 'undefined' ? undefined : window,
  now = Date.now(),
): AcquisitionContext | null {
  if (!browser)
    return null
  try {
    if (/^\/auth(?:\/|$)/.test(browser.location.pathname))
      return readCookAcquisition(browser, now)
    const query = new URLSearchParams(browser.location.search)
    const touch = readTouch({
      capturedAt: now,
      landingHost: browser.location.hostname,
      landingPath: browser.location.pathname,
      referrerHost: readReferrerHost(browser.document.referrer, browser.location.hostname),
      utmSource: readUtm(query.get('utm_source')),
      utmMedium: readUtm(query.get('utm_medium')),
      utmCampaign: readUtm(query.get('utm_campaign')),
    }, now)
    if (!touch)
      return null
    const firstRead = readStoredTouch(browser, 'localStorage', FIRST_TOUCH_KEY, now)
    const storedFirst = firstRead.accessible ? firstRead.touch : validFallbackTouch(fallback?.firstTouch, now)
    const firstTouch = storedFirst && storedFirst.capturedAt + FIRST_TOUCH_TTL_MS > now
      ? storedFirst
      : touch
    fallback = { version: 1, firstTouch, registerTouch: touch }
    writeStoredTouch(browser, 'localStorage', FIRST_TOUCH_KEY, firstTouch)
    writeStoredTouch(browser, 'sessionStorage', REGISTER_TOUCH_KEY, touch)
    return fallback
  }
  catch {
    return readCookAcquisition(browser, now)
  }
}

/** Read at auth start so both host and redirect paths use the same entry snapshot. */
export function readCookAcquisition(
  browser: AcquisitionBrowser | undefined = typeof window === 'undefined' ? undefined : window,
  now = Date.now(),
): AcquisitionContext | null {
  if (!browser)
    return null
  const registerRead = readStoredTouch(browser, 'sessionStorage', REGISTER_TOUCH_KEY, now)
  const registerTouch = registerRead.accessible ? registerRead.touch : validFallbackTouch(fallback?.registerTouch, now)
  if (!registerTouch) {
    // Readable deletion/corruption is authoritative; never resurrect an old campaign from memory.
    fallback = null
    return null
  }
  const firstRead = readStoredTouch(browser, 'localStorage', FIRST_TOUCH_KEY, now)
  const storedFirst = firstRead.accessible ? firstRead.touch : validFallbackTouch(fallback?.firstTouch, now)
  const firstTouch = storedFirst && storedFirst.capturedAt + FIRST_TOUCH_TTL_MS > now
    ? storedFirst
    : registerTouch
  fallback = sanitizeAcquisitionContext({ version: 1, firstTouch, registerTouch })
  return fallback
}

function readTouch(input: unknown, now: number): AcquisitionTouch | null {
  const touch = sanitizeAcquisitionContext({ version: 1, firstTouch: input, registerTouch: input })?.firstTouch
  return touch && touch.capturedAt <= now && touch.capturedAt + FIRST_TOUCH_TTL_MS > now ? touch : null
}

function validFallbackTouch(touch: AcquisitionTouch | undefined, now: number): AcquisitionTouch | null {
  return touch ? readTouch(touch, now) : null
}

function readStoredTouch(browser: AcquisitionBrowser, storage: 'localStorage' | 'sessionStorage', key: string, now: number) {
  let value: string | null
  try {
    value = browser[storage].getItem(key)
  }
  catch {
    return { accessible: false, touch: null }
  }
  const pending = pendingWrites.get(key)
  if (pending && value === pending.previousValue) {
    // Quota failures can leave the previous campaign readable. Prefer the new
    // document's unpersisted touch while that exact previous value is unchanged.
    const touch = readTouch(pending.touch, now)
    if (!touch)
      pendingWrites.delete(key)
    return { accessible: true, touch }
  }
  pendingWrites.delete(key)
  try {
    return { accessible: true, touch: value ? readTouch(JSON.parse(value), now) : null }
  }
  catch {
    return { accessible: true, touch: null }
  }
}

function writeStoredTouch(browser: AcquisitionBrowser, storage: 'localStorage' | 'sessionStorage', key: string, touch: AcquisitionTouch) {
  let previousValue: string | null | undefined
  try {
    previousValue = browser[storage].getItem(key)
  }
  catch {
    // The fallback is validated separately when storage access is denied.
  }
  try {
    browser[storage].setItem(key, JSON.stringify(touch))
    pendingWrites.delete(key)
  }
  catch {
    // Storage restrictions affect attribution only; auth must remain usable.
    pendingWrites.set(key, { previousValue, touch })
  }
}

function readReferrerHost(input: string, landingHost: string): string {
  try {
    const url = new URL(input)
    const host = url.hostname.replace(/\.$/, '')
    const internal = host === landingHost || host === 'yunle.fun'
      || host.endsWith('.yunle.fun') || host === 'cook.yunyoujun.cn'
    return (url.protocol === 'https:' || url.protocol === 'http:')
      && !url.username && !url.password && !internal
      ? host
      : ''
  }
  catch {
    return ''
  }
}

function readUtm(input: string | null): string {
  const value = input?.trim() ?? ''
  return value.length <= 120 && UTM_RE.test(value) ? value : ''
}
