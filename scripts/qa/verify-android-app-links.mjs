import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { parseArgs } from 'node:util'
import {
  parseAndroidLinkFingerprints,
  readAndroidApkFingerprints,
  readAndroidCertificateFingerprint,
  readInstalledAndroidApkFingerprints,
  verifyAndroidDeviceLinks,
  verifyAndroidLinks,
} from '../lib/android-app-links.mjs'

async function main() {
  const { values } = parseArgs({
    options: {
      'apk': { type: 'string', multiple: true },
      'build-tools': { type: 'string' },
      'certificate': { type: 'string', multiple: true },
      'device': { type: 'boolean' },
      'help': { type: 'boolean', short: 'h' },
      'print-fingerprints': { type: 'boolean' },
      'serial': { type: 'string', short: 's' },
      'verify-device': { type: 'boolean' },
    },
  })
  if (values.help) {
    console.log('Usage: node scripts/qa/verify-android-app-links.mjs [--apk SIGNED.apk] [--certificate FILE] [--device|--verify-device] [-s SERIAL] [--build-tools DIR] [--print-fingerprints]')
    console.log('--apk and --certificate (a public X.509 certificate in PEM or DER format) can be repeated.')
    console.log('--device reads and verifies the APK currently installed on the adb device.')
    console.log('--verify-device also checks Android domain verification and link resolution without opening links (Android 12+).')
    console.log('--print-fingerprints prints comma-separated fingerprints and skips fetching both domains.')
    console.log('Without inputs, requires the exact certificate set in COOK_ANDROID_LINKS_SHA256 when configured.')
    return
  }
  const device = values.device || values['verify-device']
  if (values.serial !== undefined && !device)
    throw new Error('--serial requires --device or --verify-device')
  if (values.serial !== undefined && !values.serial.trim())
    throw new Error('--serial requires a non-empty adb device serial')
  if (values['build-tools'] && !values.apk && !device)
    throw new Error('--build-tools requires --apk, --device, or --verify-device')
  if (values['print-fingerprints'] && values['verify-device'])
    throw new Error('--print-fingerprints cannot be combined with --verify-device')
  const buildTools = values['build-tools'] ? resolve(values['build-tools']) : undefined
  const suppliedFingerprints = [
    ...(values.apk ?? []).flatMap(apk => readAndroidApkFingerprints(resolve(apk), { buildTools })),
    ...(values.certificate ?? []).map(file => readAndroidCertificateFingerprint(readFileSync(file))),
    ...(device ? readInstalledAndroidApkFingerprints({ serial: values.serial, buildTools }) : []),
  ]
  const fingerprints = suppliedFingerprints.length
    ? [...new Set(suppliedFingerprints)]
    : parseAndroidLinkFingerprints(process.env.COOK_ANDROID_LINKS_SHA256)
  if (values['print-fingerprints']) {
    if (!suppliedFingerprints.length)
      throw new Error('--print-fingerprints requires --apk, --certificate, or --device')
    console.log(fingerprints.join(','))
    return
  }
  console.log(suppliedFingerprints.length ? 'Source: APK signatures or supplied certificates' : fingerprints.length ? 'Source: configured fingerprints' : 'No signing certificate supplied')
  const results = await verifyAndroidLinks(fingerprints, fetch, { requireExactFingerprints: suppliedFingerprints.length === 0 })
  for (const result of results)
    console.log(`${result.ok ? 'PASS' : 'FAIL'} ${result.url} — ${result.message}`)
  const deviceResults = values['verify-device'] ? verifyAndroidDeviceLinks({ serial: values.serial }) : []
  for (const result of deviceResults)
    console.log(`${result.ok ? 'PASS' : 'FAIL'} [device] ${result.label} — ${result.message}`)
  if ([...results, ...deviceResults].some(result => !result.ok))
    process.exitCode = 1
}

try {
  await main()
}
catch (error) {
  console.error(error.message)
  process.exitCode = 1
}
