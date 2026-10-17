import type { CloudbaseDatabase, NativeYunlefunAuthorizationAdapter } from '~/adapters/yunlefunAuth'
import type {
  YunlefunAuthController,
  YunlefunAuthSnapshot,
} from '~/utils/yunlefunAuth'
import { App } from '@capacitor/app'
import { Browser } from '@capacitor/browser'
import { Capacitor } from '@capacitor/core'
import {
  createCloudbaseIdentityAdapter,
  createNativeYunlefunAuthorizationAdapter,
  createWebYunlefunAuthorizationAdapter,
  getCloudbaseDatabase,
} from '~/adapters/yunlefunAuth'
import { stripNativeSsoCallbackHash } from '~/utils/nativeSso'
import { createYunlefunAuthController } from '~/utils/yunlefunAuth'
import { resolveYunlefunSsoConfig } from '~/utils/yunlefunSsoConfig'

interface YunlefunRuntimeConfig {
  public: {
    yunlefunCloudbaseEnv?: string
    yunlefunSsoClientId?: string
    yunlefunSsoExchangeUrl?: string
    yunlefunSsoNativeClientId?: string
    yunlefunSsoNativeRedirectUri?: string
    yunlefunSsoOrigin?: string
    yunlefunSsoRedirectUri?: string
    yunlefunSsoRedirectUris?: string
    yunlefunSsoScope?: string
  }
}

let controller: YunlefunAuthController | undefined
let cloudbaseEnv = ''
let nativeAdapter: NativeYunlefunAuthorizationAdapter | undefined
let initialization: Promise<void> | undefined
let nativeUrlListenerInstalled = false
let handlingNativeCallback = false

export function useYunlefunAuth() {
  const runtimeConfig = useRuntimeConfig() as unknown as YunlefunRuntimeConfig
  const snapshot = useState<YunlefunAuthSnapshot>('yunlefun:auth:snapshot', () => ({
    account: null,
    errorMessage: '',
    status: 'idle',
  }))
  const configurationMessage = useState<string>('yunlefun:auth:configuration-message', () => '')
  const isConfigured = computed(() => !configurationMessage.value)
  const isAuthenticated = computed(() => snapshot.value.status === 'signed-in' && Boolean(snapshot.value.account))

  async function initialize(): Promise<void> {
    if (!initialization)
      initialization = initializeOnce(runtimeConfig, snapshot, configurationMessage)
    await initialization
    publishSnapshot(snapshot)
  }

  async function signIn(): Promise<void> {
    if (!controller || !isConfigured.value)
      return
    await controller.signIn()
    publishSnapshot(snapshot)
  }

  async function signOut(): Promise<void> {
    if (!controller)
      return
    await controller.signOut()
    publishSnapshot(snapshot)
  }

  /** The CloudBase database bound to the signed-in session. */
  async function getDatabase(): Promise<CloudbaseDatabase> {
    if (!cloudbaseEnv)
      throw new Error('CloudBase is not configured')
    return getCloudbaseDatabase(cloudbaseEnv)
  }

  return {
    account: computed(() => snapshot.value.account),
    configurationMessage: readonly(configurationMessage),
    errorMessage: computed(() => snapshot.value.errorMessage),
    getDatabase,
    initialize,
    isAuthenticated,
    isConfigured,
    signIn,
    signOut,
    status: computed(() => snapshot.value.status),
  }
}

async function initializeOnce(
  runtimeConfig: YunlefunRuntimeConfig,
  snapshot: Ref<YunlefunAuthSnapshot>,
  configurationMessage: Ref<string>,
): Promise<void> {
  if (!import.meta.client)
    return

  const platform = Capacitor.isNativePlatform() ? 'native' : 'web'
  if (platform === 'web'
    && window.location.pathname === '/auth/callback'
    && new URLSearchParams(window.location.search).get('platform') === 'native') {
    const sanitizedPath = stripNativeSsoCallbackHash(window.location.href)
    if (sanitizedPath)
      window.history.replaceState(window.history.state, '', sanitizedPath)
    configurationMessage.value = '未能自动返回 Cook，请确认已安装最新版应用后重新授权'
    snapshot.value = {
      account: null,
      errorMessage: '',
      status: 'signed-out',
    }
    return
  }

  const result = resolveYunlefunSsoConfig({
    clientId: runtimeConfig.public.yunlefunSsoClientId,
    cloudbaseEnv: runtimeConfig.public.yunlefunCloudbaseEnv,
    exchangeUrl: runtimeConfig.public.yunlefunSsoExchangeUrl,
    nativeClientId: runtimeConfig.public.yunlefunSsoNativeClientId,
    nativeRedirectUri: runtimeConfig.public.yunlefunSsoNativeRedirectUri,
    redirectUri: runtimeConfig.public.yunlefunSsoRedirectUri,
    redirectUris: runtimeConfig.public.yunlefunSsoRedirectUris,
    scope: runtimeConfig.public.yunlefunSsoScope,
    ssoOrigin: runtimeConfig.public.yunlefunSsoOrigin,
  }, {
    currentOrigin: window.location.origin,
    platform,
  })

  if (!result.ok) {
    configurationMessage.value = result.message
    snapshot.value = {
      account: null,
      errorMessage: '',
      status: 'signed-out',
    }
    return
  }

  cloudbaseEnv = result.config.cloudbaseEnv
  const web = createWebYunlefunAuthorizationAdapter(result.config)
  nativeAdapter = createNativeYunlefunAuthorizationAdapter(result.config, async () => {
    const launch = await App.getLaunchUrl()
    return launch?.url ?? null
  })
  controller = createYunlefunAuthController({
    identity: createCloudbaseIdentityAdapter(result.config),
    native: nativeAdapter,
    platform,
    web,
  })

  if (platform === 'native' && !nativeUrlListenerInstalled) {
    nativeUrlListenerInstalled = true
    await App.addListener('appUrlOpen', async ({ url }) => {
      if (!controller || !nativeAdapter)
        return

      handlingNativeCallback = true
      try {
        const authorization = await nativeAdapter.consumeUrl(url)
        if (!authorization)
          return

        await controller.handleAuthorization(authorization)
        publishSnapshot(snapshot)
        if (controller.snapshot().status === 'signed-in')
          await navigateTo('/my', { replace: true })
      }
      finally {
        handlingNativeCallback = false
      }
    })
    await Browser.addListener('browserFinished', async () => {
      if (!controller
        || handlingNativeCallback
        || controller.snapshot().status !== 'signing-in') {
        return
      }

      await nativeAdapter?.cancel().catch(() => undefined)
      await controller.handleAuthorization({
        ok: false,
        reason: 'access_denied',
      })
      publishSnapshot(snapshot)
    })
  }

  await controller.initialize()
  publishSnapshot(snapshot)
  if (platform === 'web'
    && window.location.pathname === '/auth/callback'
    && controller.snapshot().status === 'signed-in') {
    await navigateTo('/my', { replace: true })
  }
}

function publishSnapshot(snapshot: Ref<YunlefunAuthSnapshot>): void {
  if (controller)
    snapshot.value = controller.snapshot()
}
