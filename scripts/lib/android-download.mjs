import process from 'node:process'
import { parseAndroidLinkFingerprints } from './android-app-links.mjs'

const releaseOrigin = 'https://assets.yunle.fun/cook/android/releases/'

export function androidDownloadMetadata({ versionName, versionCode, sha256, certificates }) {
  if (typeof versionName !== 'string' || !/^\d+\.\d+\.\d+(?:-[\da-z.-]+)?$/i.test(versionName)
    || !Number.isSafeInteger(versionCode) || versionCode <= 0
    || typeof sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(sha256)
    || !Array.isArray(certificates) || certificates.length === 0
    || certificates.some(value => typeof value !== 'string' || !/^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$/.test(value))) {
    throw new Error('Invalid COOK_ANDROID_APK_RELEASE metadata; generate it from the signed APK')
  }
  const filename = `cook-${versionName}-${versionCode}-${sha256}.apk`
  return { versionName, versionCode, sha256, url: `${releaseOrigin}${filename}` }
}

export function readAndroidDownload(environment = process.env) {
  const raw = environment.COOK_ANDROID_APK_RELEASE?.trim()
  if (!raw)
    return null
  let release
  try {
    release = JSON.parse(raw)
  }
  catch {
    throw new Error('COOK_ANDROID_APK_RELEASE must be a JSON object generated from the signed APK')
  }
  if (!release || typeof release !== 'object' || Array.isArray(release))
    throw new Error('COOK_ANDROID_APK_RELEASE must be a JSON object generated from the signed APK')
  const download = androidDownloadMetadata(release)
  const associated = parseAndroidLinkFingerprints(environment.COOK_ANDROID_LINKS_SHA256)
  if (!release.certificates.every(certificate => associated.includes(certificate)))
    throw new Error('The APK signing certificate must be present in COOK_ANDROID_LINKS_SHA256')
  return download
}
