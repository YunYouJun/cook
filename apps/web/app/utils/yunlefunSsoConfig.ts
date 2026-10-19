import type { RedirectSsoOptions, SsoCodeExchangeOptions } from '@yunlefun/sso'
import {
  readSsoClientId,
  readSsoRedirectUri,
  readSsoScope,
} from '@yunlefun/sso'

export interface YunlefunSsoConfigInput {
  clientId?: string
  cloudbaseEnv?: string
  exchangeUrl?: string
  nativeClientId?: string
  nativeRedirectUri?: string
  redirectUri?: string
  redirectUris?: string | string[]
  scope?: string
  ssoOrigin?: string
}

export interface YunlefunSsoConfigContext {
  currentOrigin: string
  platform: 'native' | 'web'
}

export interface YunlefunSsoConfig {
  cloudbaseEnv: string
  exchange: SsoCodeExchangeOptions
  redirect: RedirectSsoOptions
}

export type YunlefunSsoConfigResult
  = | { ok: true, config: YunlefunSsoConfig }
    | { ok: false, message: string }

const CLOUDBASE_ENV_RE = /^[a-z0-9][a-z0-9-]{0,63}$/i
const TRAILING_SLASH_RE = /\/+$/

export function resolveYunlefunSsoConfig(
  input: YunlefunSsoConfigInput,
  context: YunlefunSsoConfigContext,
): YunlefunSsoConfigResult {
  const clientId = readSsoClientId(
    context.platform === 'native' ? input.nativeClientId : input.clientId,
  )
  const cloudbaseEnv = normalizeValue(input.cloudbaseEnv)
  const exchangeUrl = readSsoRedirectUri(input.exchangeUrl)
  const webRedirectUris = readWebRedirectUris(input.redirectUris ?? input.redirectUri)
  const redirectUri = context.platform === 'native'
    ? readSsoRedirectUri(input.nativeRedirectUri)
    : webRedirectUris.find(uri => new URL(uri).origin === context.currentOrigin)
  const scope = readSsoScope(input.scope)
  const ssoOrigin = normalizeHttpsOrigin(input.ssoOrigin)

  if (!clientId
    || !CLOUDBASE_ENV_RE.test(cloudbaseEnv)
    || !exchangeUrl
    || (context.platform === 'native' ? !redirectUri : webRedirectUris.length === 0)
    || scope.length === 0
    || !ssoOrigin) {
    return {
      ok: false,
      message: '登录服务尚未完成 SSO v3 配置',
    }
  }

  if (!redirectUri) {
    return {
      ok: false,
      message: '当前域名尚未登记为登录回跳地址',
    }
  }

  return {
    ok: true,
    config: {
      cloudbaseEnv,
      exchange: { exchangeUrl },
      redirect: {
        clientId,
        redirectUri,
        scope,
        ssoOrigin,
      },
    },
  }
}

function readWebRedirectUris(value: string | string[] | undefined): string[] {
  const candidates = Array.isArray(value)
    ? value
    : typeof value === 'string'
      ? value.split(/[\n,]/)
      : []

  return [...new Set(candidates
    .map(candidate => readSsoRedirectUri(candidate.trim()))
    .filter((candidate): candidate is string => Boolean(candidate)))]
}

function normalizeHttpsOrigin(value: string | undefined): string {
  const normalized = normalizeValue(value).replace(TRAILING_SLASH_RE, '')
  if (!normalized)
    return ''

  try {
    const url = new URL(normalized)
    return url.protocol === 'https:'
      && !url.username
      && !url.password
      && url.origin === normalized
      ? url.origin
      : ''
  }
  catch {
    return ''
  }
}

function normalizeValue(value: string | undefined): string {
  return typeof value === 'string' ? value.trim() : ''
}
