const cookAndroidPackage = 'fun.yunle.cook'

export function supportsAndroidBrowserIntent(userAgent: string) {
  return /\bAndroid\b/i.test(userAgent) && /\bChrome\/\d/.test(userAgent)
    && !/;\s*wv\)|MicroMessenger/i.test(userAgent)
}

/** Wrap an already validated Cook HTTPS link, including its fragment. */
export function cookAndroidIntentUrl(httpsUrl: string) {
  return `intent:${httpsUrl.slice('https:'.length)}#Intent;scheme=https;package=${cookAndroidPackage};S.browser_fallback_url=${encodeURIComponent(httpsUrl)};end`
}
