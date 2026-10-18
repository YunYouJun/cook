import { isPrivateAnalyticsUrl, resolveClarityConfig, setCookEventTracker } from '~/utils/analytics'

export default defineNuxtPlugin(() => {
  const config = useRuntimeConfig().public
  const clarity = resolveClarityConfig({
    projectId: config.clarityProjectId,
    domains: config.clarityDomains,
  }, window.location.href, import.meta.dev)
  if (!clarity || navigator.doNotTrack === '1'
    || (navigator as Navigator & { globalPrivacyControl?: boolean }).globalPrivacyControl
    || (document.referrer && isPrivateAnalyticsUrl(document.referrer))) {
    return
  }

  let stopped = false
  const script = useScriptClarity({
    id: clarity.projectId,
    scriptOptions: { bundle: false, proxy: false, trigger: 'manual' },
    scriptInput: { src: `https://www.clarity.ms/tag/${clarity.projectId}`, referrerpolicy: 'no-referrer' },
  })

  function stop() {
    stopped = true
    try {
      window.clarity?.('stop')
    }
    catch {
      // A recorder error must not prevent login navigation.
    }
  }

  // Stop before a SPA navigation can expose a callback URL to the recorder.
  useRouter().beforeEach((to) => {
    if (isPrivateAnalyticsUrl(new URL(to.fullPath, window.location.origin).href)) {
      stop()
    }
  })

  setCookEventTracker((name) => {
    if (!stopped && !isPrivateAnalyticsUrl(window.location.href))
      return script.proxy.clarity('event', name)
  })

  onNuxtReady(() => {
    if (stopped || isPrivateAnalyticsUrl(window.location.href))
      return
    void script.load().then(() => {
      if (stopped)
        stop()
    }).catch(() => undefined)
  })
})
