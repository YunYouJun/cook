import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { parseArgs } from 'node:util'
import { inspectAndroidApk } from '../lib/android-app-links.mjs'
import { androidDownloadMetadata } from '../lib/android-download.mjs'

async function main() {
  const { values } = parseArgs({ options: { 'apk': { type: 'string' }, 'build-tools': { type: 'string' } } })
  if (!values.apk)
    throw new Error('Usage: node scripts/qa/prepare-android-download.mjs --apk SIGNED.apk [--build-tools DIR]')

  const apk = resolve(values.apk)
  const { fingerprints, versionName, versionCode } = inspectAndroidApk(apk, { buildTools: values['build-tools'] })
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(apk))
    hash.update(chunk)

  const release = { versionName, versionCode, sha256: hash.digest('hex'), certificates: fingerprints }
  const { url } = androidDownloadMetadata(release)
  console.log(`APK URL: ${url}`)
  console.log(`COOK_ANDROID_APK_RELEASE=${JSON.stringify(release)}`)
  console.log(`COOK_ANDROID_LINKS_SHA256=${fingerprints.join(',')}`)
}

try {
  await main()
}
catch (error) {
  console.error(error.message)
  process.exitCode = 1
}
