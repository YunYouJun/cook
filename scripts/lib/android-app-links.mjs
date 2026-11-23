import { execFileSync } from 'node:child_process'
import { X509Certificate } from 'node:crypto'
import { existsSync, mkdtempSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
import { isDeepStrictEqual } from 'node:util'

export const ANDROID_LINKS_PACKAGE = 'fun.yunle.cook'
export const ANDROID_LINKS_PATH = '.well-known/assetlinks.json'
const LINK_RELATION = 'delegate_permission/common.handle_all_urls'
const DYNAMIC_LINK_COMPONENTS = [
  { '/': '/recipes/share', '?': { id: '?*' } },
  { '/': '/recipes/share/', '?': { id: '?*' } },
  { '/': '/auth/callback', '?': { platform: 'native' }, '#': 'ylf_sso=?*' },
  { '/': '/auth/callback', '?': { platform: 'native' }, '#': 'cook_handoff=?*' },
  { '/': '*', 'exclude': true },
]
const CERT_FINGERPRINT = /^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$/
const MAX_DOCUMENT_BYTES = 256 * 1024
const HOSTS = ['cook.yunle.fun', 'cook.yunyoujun.cn']
const DEVICE_LINKS = [
  ['Recipe share', 'https://cook.yunle.fun/recipes/share/?id=250'],
  ['Recipe share without trailing slash', 'https://cook.yunle.fun/recipes/share?id=250'],
  ['Native sign-in callback', 'https://cook.yunyoujun.cn/auth/callback?platform=native#ylf_sso=qa'],
  ['Native account handoff callback', 'https://cook.yunyoujun.cn/auth/callback?platform=native#cook_handoff=qa'],
]
const MAIN_ACTIVITY = `${ANDROID_LINKS_PACKAGE}/cn.yunyoujun.cook.MainActivity`
const MATCH_DEFAULT_ONLY = '65536'
const AAPT2_NAME = process.platform === 'win32' ? 'aapt2.exe' : 'aapt2'
const APKSIGNER_JAR = join('lib', 'apksigner.jar')
const ANDROID_DEBUG_SUBJECT = /(?:^|[,\n]\s*)CN=Android Debug(?:[,\n]|$)/i

export function parseAndroidLinkFingerprints(value) {
  if (!value?.trim())
    return []
  const fingerprints = [...new Set(value.split(',').map(fingerprint => fingerprint.trim().toUpperCase()))]
  if (fingerprints.some(fingerprint => !CERT_FINGERPRINT.test(fingerprint)))
    throw new Error('COOK_ANDROID_LINKS_SHA256 must contain comma-separated SHA-256 certificate fingerprints in AA:BB:… format')
  return fingerprints
}

export function createAndroidLinksDocument(fingerprints) {
  return [{
    relation: [LINK_RELATION],
    target: {
      namespace: 'android_app',
      package_name: ANDROID_LINKS_PACKAGE,
      sha256_cert_fingerprints: fingerprints,
    },
    relation_extensions: {
      [LINK_RELATION]: {
        dynamic_app_link_components: structuredClone(DYNAMIC_LINK_COMPONENTS),
      },
    },
  }]
}

function findBuildTools(environment) {
  const sdk = environment.ANDROID_HOME || environment.ANDROID_SDK_ROOT
  if (!sdk)
    throw new Error('Set ANDROID_HOME or supply --build-tools to read the signing certificate')
  const root = join(sdk, 'build-tools')
  const candidates = existsSync(root) ? readdirSync(root).filter(name => /^\d+(?:\.\d+){2}$/.test(name)) : []
  candidates.sort((a, b) => b.localeCompare(a, 'en', { numeric: true }))
  const directory = candidates.map(name => join(root, name)).find(dir => [AAPT2_NAME, APKSIGNER_JAR].every(name => existsSync(join(dir, name))))
  if (!directory)
    throw new Error('Android SDK Build Tools with aapt2 and apksigner are required')
  return directory
}

function rejectAndroidDebugCertificate(subject) {
  if (ANDROID_DEBUG_SUBJECT.test(subject))
    throw new Error('An Android Debug certificate cannot be used for production App Links')
}

export function readAndroidCertificateFingerprint(certificate) {
  const parsed = new X509Certificate(certificate)
  rejectAndroidDebugCertificate(parsed.subject)
  return parsed.fingerprint256
}

export function parseApkSignerFingerprints(output) {
  for (const [, subject] of output.matchAll(/^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate DN: (.+)$/gim))
    rejectAndroidDebugCertificate(subject)
  const fingerprints = [...output.matchAll(/^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest: ([a-f0-9]{64})\s*$/gim)]
    .map(([, digest]) => digest.match(/.{2}/g).join(':').toUpperCase())
  if (fingerprints.length === 0)
    throw new Error('apksigner did not report a signing certificate SHA-256 digest')
  return [...new Set(fingerprints)]
}

export function readAndroidApkFingerprints(apk, { buildTools, environment = process.env } = {}) {
  const directory = buildTools ?? findBuildTools(environment)
  const run = (tool, args) => execFileSync(tool, args, {
    encoding: 'utf8',
    env: { ...environment, LC_ALL: 'C' },
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: 30_000,
  })
  let badging
  try {
    badging = run(join(directory, AAPT2_NAME), ['dump', 'badging', apk])
  }
  catch {
    throw new Error('Could not read the APK application ID and debug flag with aapt2')
  }
  const name = /^package: name='([^']+)'/m.exec(badging)?.[1]
  if (name !== ANDROID_LINKS_PACKAGE)
    throw new Error(`Expected APK application ID ${ANDROID_LINKS_PACKAGE}, got ${name || '(empty)'}`)
  if (/^application-debuggable\r?$/m.test(badging))
    throw new Error('A debuggable APK cannot be used for production App Links')

  let output
  try {
    const java = environment.JAVA_HOME
      ? join(environment.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
      : 'java'
    output = run(java, ['-jar', join(directory, APKSIGNER_JAR), 'verify', '--print-certs', apk])
  }
  catch {
    throw new Error('APK signing verification failed; supply an APK signed for the intended distribution channel')
  }
  return parseApkSignerFingerprints(output)
}

function androidDeviceCommand(serial, environment) {
  const sdk = environment.ANDROID_HOME || environment.ANDROID_SDK_ROOT
  const executable = process.platform === 'win32' ? 'adb.exe' : 'adb'
  const sdkAdb = sdk && join(sdk, 'platform-tools', executable)
  const adb = sdkAdb && existsSync(sdkAdb) ? sdkAdb : executable
  const device = serial ? ['-s', serial] : []
  return (args, timeout = 30_000) => execFileSync(adb, [...device, ...args], {
    encoding: 'utf8',
    env: { ...environment, LC_ALL: 'C' },
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout,
  })
}

export function readInstalledAndroidApkFingerprints({ serial, buildTools, environment = process.env } = {}) {
  const run = androidDeviceCommand(serial, environment)

  const notInstalled = `${ANDROID_LINKS_PACKAGE} is not installed for the current Android user`
  let output
  try {
    output = run(['shell', 'pm', 'path', '--user', 'current', ANDROID_LINKS_PACKAGE])
  }
  catch (error) {
    if (error.code === 'ENOENT')
      throw new Error('Android SDK Platform Tools with adb are required', { cause: error })
    const detail = error.stderr?.trim() || error.stdout?.trim()
    if (error.status === 1 && !detail)
      throw new Error(notInstalled, { cause: error })
    throw new Error(`Could not query the installed APK with adb${detail ? `: ${detail}` : ''}`, { cause: error })
  }

  const paths = output.split(/\r?\n/).map(line => line.trim()).filter(line => line.startsWith('package:/') && line.endsWith('.apk')).map(line => line.slice('package:'.length))
  if (!paths.length)
    throw new Error(notInstalled)
  const base = paths.find(path => path.endsWith('/base.apk')) ?? (paths.length === 1 ? paths[0] : undefined)
  if (!base)
    throw new Error('Could not identify the base APK among the installed package splits')

  const toolsDirectory = buildTools ?? findBuildTools(environment)
  const directory = mkdtempSync(join(tmpdir(), 'cook-app-links-'))
  try {
    const apk = join(directory, 'base.apk')
    try {
      run(['pull', base, apk], 120_000)
    }
    catch (error) {
      const detail = error.stderr?.trim() || error.code
      throw new Error(`Could not pull the installed base APK with adb${detail ? `: ${detail}` : ''}`, { cause: error })
    }
    return readAndroidApkFingerprints(apk, { buildTools: toolsDirectory, environment })
  }
  finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

export function verifyAndroidDeviceLinks({
  serial,
  environment = process.env,
  run = androidDeviceCommand(serial, environment),
} = {}) {
  const sdk = Number(run(['shell', 'getprop', 'ro.build.version.sdk']).trim())
  if (!Number.isInteger(sdk) || sdk < 31)
    throw new Error('Device link verification requires Android 12 (API 31) or later')

  const output = run(['shell', 'pm', 'get-app-links', '--user', 'current', ANDROID_LINKS_PACKAGE])
  const stateOutput = output.split(/^[ \t]*Domain verification state:[ \t]*\r?$/m)[1] ?? ''
  const domainSection = stateOutput.split(/^[ \t]*User \d+:[ \t]*\r?$/m)[0]
  const states = new Map([...domainSection.matchAll(/^[ \t]+([\w.-]+):[ \t]+(\S+)[ \t]*\r?$/gm)]
    .map(([, host, state]) => [host, state]))
  const results = HOSTS.map((host) => {
    const state = states.get(host)
    return {
      label: host,
      ok: state === 'verified',
      message: state === 'verified' ? 'Android verified the domain' : `Expected verified, got ${state ?? '(missing)'}`,
    }
  })
  if (/^[ \t]*Verification link handling allowed:[ \t]+false[ \t]*\r?$/m.test(output)) {
    results.push({ label: ANDROID_LINKS_PACKAGE, ok: false, message: 'Open supported links is disabled for the current Android user' })
  }
  for (const [label, url] of DEVICE_LINKS) {
    const resolved = run([
      'shell',
      'cmd',
      'package',
      'resolve-activity',
      '--brief',
      '--user',
      'current',
      '--query-flags',
      MATCH_DEFAULT_ONLY,
      '-a',
      'android.intent.action.VIEW',
      '-c',
      'android.intent.category.BROWSABLE',
      '-d',
      `'${url}'`,
    ]).trim().split(/\r?\n/).at(-1)
    results.push({
      label,
      ok: resolved === MAIN_ACTIVITY,
      message: resolved === MAIN_ACTIVITY ? 'Resolves to Cook' : `Expected Cook, got ${resolved || '(no activity)'}`,
    })
  }
  return results
}

async function readJson(response) {
  const reader = response.body?.getReader()
  if (!reader)
    throw new Error('Empty response')
  let bytes = 0
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let raw = ''
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done)
        break
      bytes += value.byteLength
      if (bytes > MAX_DOCUMENT_BYTES)
        throw new Error('JSON exceeds 256 KiB')
      raw += decoder.decode(value, { stream: true })
    }
    return JSON.parse(raw + decoder.decode())
  }
  finally {
    await reader.cancel().catch(() => {})
  }
}

export async function verifyAndroidLinks(fingerprints, fetchDocument = fetch, { requireExactFingerprints = false } = {}) {
  return Promise.all(HOSTS.map(async (host) => {
    const url = `https://${host}/${ANDROID_LINKS_PATH}`
    try {
      const response = await fetchDocument(url, { redirect: 'manual', signal: AbortSignal.timeout(10_000) })
      if (response.status !== 200 || response.redirected) {
        await response.body?.cancel()
        return { url, ok: false, message: `Expected a direct HTTP 200, got ${response.status}` }
      }
      const mime = response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase()
      if (mime !== 'application/json') {
        await response.body?.cancel()
        return { url, ok: false, message: `Expected application/json, got ${mime || '(missing)'}` }
      }
      const document = await readJson(response)
      if (!Array.isArray(document))
        return { url, ok: false, message: 'Expected a JSON array of association statements' }
      const found = new Set()
      const unexpectedRules = new Set()
      for (const statement of document) {
        const target = statement?.target
        if (target?.namespace !== 'android_app' || target.package_name !== ANDROID_LINKS_PACKAGE
          || !Array.isArray(statement.relation) || !statement.relation.includes(LINK_RELATION)
          || !Array.isArray(target.sha256_cert_fingerprints)) {
          continue
        }
        const components = statement.relation_extensions?.[LINK_RELATION]?.dynamic_app_link_components
        const matchesRules = isDeepStrictEqual(components, DYNAMIC_LINK_COMPONENTS)
        for (const fingerprint of target.sha256_cert_fingerprints) {
          const normalized = typeof fingerprint === 'string' ? fingerprint.toUpperCase() : ''
          if (CERT_FINGERPRINT.test(normalized)) {
            found.add(normalized)
            if (!matchesRules)
              unexpectedRules.add(normalized)
          }
        }
      }
      if (!found.size)
        return { url, ok: false, message: `No valid direct association for ${ANDROID_LINKS_PACKAGE}` }
      if (!fingerprints.length)
        return { url, ok: false, message: 'Association found; supply the installed APK or its certificate fingerprints to check it' }
      const missing = fingerprints.filter(fingerprint => !found.has(fingerprint))
      if (missing.length)
        return { url, ok: false, message: `Missing signing certificate: ${missing.join(', ')}` }
      if (requireExactFingerprints) {
        const extra = [...found].filter(fingerprint => !fingerprints.includes(fingerprint))
        if (extra.length)
          return { url, ok: false, message: `Unexpected signing certificate: ${extra.join(', ')}` }
      }
      if (fingerprints.some(fingerprint => unexpectedRules.has(fingerprint)))
        return { url, ok: false, message: 'Dynamic App Links rules do not match the recipe and native callback paths' }
      return { url, ok: true, message: 'Signing certificates and Dynamic App Links rules match' }
    }
    catch (error) {
      return { url, ok: false, message: error.message }
    }
  }))
}
