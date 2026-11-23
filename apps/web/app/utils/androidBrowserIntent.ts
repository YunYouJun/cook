const cookAndroidPackage = 'fun.yunle.cook'

export function supportsAndroidBrowserIntent(userAgent: string) {
  return /\bAndroid\b/i.test(userAgent) && /\bChrome\/\d/.test(userAgent)
    && !/;\s*wv\)|MicroMessenger/i.test(userAgent)
}

function hasControlCharacter(value: string) {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i)
    if (code <= 0x1F || code === 0x7F)
      return true
  }
  return false
}

/** Wrap an already validated Cook HTTPS link, including its fragment. */
export function cookAndroidIntentUrl(httpsUrl: string) {
  if (!httpsUrl.startsWith('https://') || /\s/.test(httpsUrl) || hasControlCharacter(httpsUrl) || /#intent;/i.test(httpsUrl))
    throw new Error('Invalid Cook HTTPS URL')

  return `intent:${httpsUrl.slice('https:'.length)}#Intent;scheme=https;package=${cookAndroidPackage};S.browser_fallback_url=${encodeURIComponent(httpsUrl)};end`
}
