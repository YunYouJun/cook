import type {
  YunlefunAuthController,
  YunlefunAuthSnapshot,
} from '~/utils/yunlefunAuth'
import {
  callCloudbaseFunction,
  createCloudbaseIdentityAdapter,
  createWebYunlefunAuthorizationAdapter,
} from '~/adapters/yunlefunAuth'
import { isAuthCallbackUrl, isNativeAuthCallbackUrl, nativeReturnUrl } from '~/utils/authInitialization'
import { createYunlefunAuthController } from '~/utils/yunlefunAuth'
import { resolveYunlefunSsoConfig } from '~/utils/yunlefunSsoConfig'

interface YunlefunRuntimeConfig {
  public: {
    yunlefunCloudbaseEnv?: string
    yunlefunSsoClientId?: string
    yunlefunSsoExchangeUrl?: string
    yunlefunSsoOrigin?: string
    yunlefunSsoRedirectUri?: string
    yunlefunSsoRedirectUris?: string
    yunlefunSsoScope?: string
  }
}

let controller: YunlefunAuthController | undefined
let cloudbaseEnv = ''
let initialization: Promise<void> | undefined

export function useYunlefunAuth() {
  const runtimeConfig = useRuntimeConfig() as unknown as YunlefunRuntimeConfig
  const snapshot = useState<YunlefunAuthSnapshot>('yunlefun:auth:snapshot', () => ({
    account: null,
    errorMessage: '',
    status: 'idle',
  }))
  const configurationMessage = useState<string>('yunlefun:auth:configuration-message', () => '')
  const nativeReturnLink = useState<string>('yunlefun:auth:native-return-link', () => '')
  const isConfigured = computed(() => !configurationMessage.value)
  const isAuthenticated = computed(() => snapshot.value.status === 'signed-in' && Boolean(snapshot.value.account))

  async function initialize(): Promise<void> {
    // Native redirects belong to the app. Leave Web initialization available for a later Web route.
    if (!import.meta.server && isNativeAuthCallbackUrl(window.location.href)) {
      if (window.location.hash) {
        rememberNativeReturnLink(window.location.href)
        window.history.replaceState(window.history.state, '', `${window.location.pathname}${window.location.search}`)
      }
      return
    }

    if (!initialization)
      initialization = initializeOnce(runtimeConfig, snapshot, configurationMessage)
    publishSnapshot(snapshot)
    await initialization
    publishSnapshot(snapshot)
  }

  function rememberNativeReturnLink(href: string) {
    nativeReturnLink.value = nativeReturnUrl(href) ?? ''
  }

  async function signIn(): Promise<void> {
    await initialize()
    if (!controller || !isConfigured.value)
      return
    const signingIn = controller.signIn()
    publishSnapshot(snapshot)
    await signingIn
    publishSnapshot(snapshot)
  }

  async function signOut(): Promise<void> {
    if (!controller)
      return
    const signingOut = controller.signOut()
    publishSnapshot(snapshot)
    await signingOut
    publishSnapshot(snapshot)
  }

  /** Call a CloudBase cloud function as the signed-in account. */
  async function callFunction(name: string, data: Record<string, unknown>): Promise<unknown> {
    if (!cloudbaseEnv)
      throw new Error('CloudBase is not configured')
    return callCloudbaseFunction(cloudbaseEnv, name, data)
  }

  return {
    account: computed(() => snapshot.value.account),
    configurationMessage: readonly(configurationMessage),
    errorMessage: computed(() => snapshot.value.errorMessage),
    callFunction,
    initialize,
    isAuthenticated,
    isConfigured,
    nativeReturnLink: readonly(nativeReturnLink),
    rememberNativeReturnLink,
    clearNativeReturnLink: () => { nativeReturnLink.value = '' },
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
  if (import.meta.server)
    return

  const result = resolveYunlefunSsoConfig({
    clientId: runtimeConfig.public.yunlefunSsoClientId,
    cloudbaseEnv: runtimeConfig.public.yunlefunCloudbaseEnv,
    exchangeUrl: runtimeConfig.public.yunlefunSsoExchangeUrl,
    redirectUri: runtimeConfig.public.yunlefunSsoRedirectUri,
    redirectUris: runtimeConfig.public.yunlefunSsoRedirectUris,
    scope: runtimeConfig.public.yunlefunSsoScope,
    ssoOrigin: runtimeConfig.public.yunlefunSsoOrigin,
  }, {
    currentOrigin: window.location.origin,
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
  controller = createYunlefunAuthController({
    authorization: createWebYunlefunAuthorizationAdapter(result.config),
    identity: createCloudbaseIdentityAdapter(result.config),
  })

  await controller.initialize()
  publishSnapshot(snapshot)
  if (isAuthCallbackUrl(window.location.href)
    && controller.snapshot().status === 'signed-in') {
    await navigateTo('/my', { replace: true })
  }
}

function publishSnapshot(snapshot: Ref<YunlefunAuthSnapshot>): void {
  if (controller)
    snapshot.value = controller.snapshot()
}
