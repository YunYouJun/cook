import type { CloudbaseApp } from './yunlefunAuth'
import { dependencies } from '../../package.json'

type CloudbaseSdk = typeof import('@cloudbase/js-sdk/app').default

type Register = (sdk: CloudbaseSdk) => void

interface CloudbaseGlobals {
  cloudbase?: CloudbaseSdk
  registerAuth?: Register
  registerFunctions?: Register
}

export interface CloudbaseRuntime {
  app: CloudbaseApp
  loadFunctions: () => Promise<void>
}

// Use the same pinned release as the npm fallback and its persisted sessions.
const sdkCdn = `https://static.cloudbase.net/cloudbase-js-sdk/${dependencies['@cloudbase/js-sdk']}/`
const productionOrigins = ['https://cook.yunyoujun.cn', 'https://cook.yunle.fun']

export function initCloudbase(env: string): Promise<CloudbaseRuntime> {
  if (!import.meta.dev && productionOrigins.includes(window.location.origin))
    return initFromCdn(env).catch(() => initFromNpm(env))

  return initFromNpm(env)
}

async function initFromNpm(env: string) {
  const [{ default: sdk }, { registerAuth }] = await Promise.all([
    import('@cloudbase/js-sdk/app'),
    import('@cloudbase/js-sdk/auth'),
  ])
  return createRuntime(sdk, env, registerAuth)
}

async function initFromCdn(env: string) {
  const host = window as unknown as CloudbaseGlobals
  await loadScript('cloudbase.js')
  const sdk = host.cloudbase
  if (!sdk || typeof sdk.init !== 'function')
    throw new TypeError('CloudBase CDN core did not initialize')

  await loadScript('cloudbase.auth.js')
  return createRuntime(sdk, env, host.registerAuth, async (app) => {
    await loadScript('cloudbase.functions.js')
    if (typeof app.callFunction !== 'function')
      host.registerFunctions?.(sdk)
  })
}

function createRuntime(
  sdk: CloudbaseSdk,
  env: string,
  registerAuth?: Register,
  loadCdnFunctions?: (app: CloudbaseApp) => Promise<void>,
): CloudbaseRuntime {
  const app: CloudbaseApp = sdk.init({
    auth: { detectSessionInUrl: false },
    env,
    region: 'ap-shanghai',
  })
  // Both Web distributions normally register against window.cloudbase on load.
  if (typeof app.auth !== 'function')
    registerAuth?.(sdk)
  if (typeof app.auth !== 'function')
    throw new TypeError('CloudBase auth did not register')

  let functions: Promise<void> | undefined

  return {
    app,
    async loadFunctions() {
      if (typeof app.callFunction === 'function')
        return

      functions ??= (async () => {
        // Register a bundled module on this same app if its CDN script fails.
        await loadCdnFunctions?.(app).catch(() => undefined)
        if (typeof app.callFunction !== 'function') {
          const { registerFunctions } = await import('@cloudbase/js-sdk/functions')
          if (typeof app.callFunction !== 'function')
            registerFunctions(sdk)
        }
        if (typeof app.callFunction !== 'function')
          throw new TypeError('CloudBase functions did not register')
      })().catch((error) => {
        functions = undefined
        throw error
      })
      return functions
    },
  }
}

function loadScript(file: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = sdkCdn + file
    script.onload = () => resolve()
    script.onerror = () => {
      script.remove()
      reject(new Error(`Failed to load CloudBase CDN script: ${file}`))
    }
    document.head.append(script)
  })
}
