import { describe, expect, it } from 'vitest'
import { resolveYunlefunSsoConfig } from '../apps/web/app/utils/yunlefunSsoConfig'

const validConfig = {
  clientId: 'cook-web',
  cloudbaseEnv: 'yunlefun-8g7ybcxc7345c490',
  exchangeUrl: 'https://api.yunle.fun/sso-ticket',
  redirectUri: 'https://cook.yunyoujun.cn/auth/callback',
  redirectUris: 'https://cook.yunyoujun.cn/auth/callback,https://cook.yunle.fun/auth/callback',
  scope: 'identity:bootstrap',
  ssoOrigin: 'https://www.yunle.fun',
}

describe('resolveYunlefunSsoConfig', () => {
  it('accepts the exact registered production web origin', () => {
    expect(resolveYunlefunSsoConfig(validConfig, {
      currentOrigin: 'https://cook.yunyoujun.cn',
    })).toEqual({
      ok: true,
      config: {
        cloudbaseEnv: 'yunlefun-8g7ybcxc7345c490',
        exchange: {
          exchangeUrl: 'https://api.yunle.fun/sso-ticket',
        },
        redirect: {
          clientId: 'cook-web',
          redirectUri: 'https://cook.yunyoujun.cn/auth/callback',
          scope: ['identity:bootstrap'],
          ssoOrigin: 'https://www.yunle.fun',
        },
      },
    })
  })

  it('selects the registered callback for the current production web origin', () => {
    expect(resolveYunlefunSsoConfig(validConfig, {
      currentOrigin: 'https://cook.yunle.fun',
    })).toMatchObject({
      ok: true,
      config: {
        redirect: {
          clientId: 'cook-web',
          redirectUri: 'https://cook.yunle.fun/auth/callback',
        },
      },
    })
  })

  it('fails closed on an unregistered HTTP development origin', () => {
    expect(resolveYunlefunSsoConfig(validConfig, {
      currentOrigin: 'http://127.0.0.1:4173',
    })).toEqual({
      ok: false,
      message: '当前域名尚未登记为登录回跳地址',
    })
  })

  it('fails closed on an unregistered HTTPS origin', () => {
    expect(resolveYunlefunSsoConfig(validConfig, {
      currentOrigin: 'https://cook.example.com',
    })).toEqual({
      ok: false,
      message: '当前域名尚未登记为登录回跳地址',
    })
  })

  it('rejects a native origin instead of using a Web callback', () => {
    expect(resolveYunlefunSsoConfig(validConfig, {
      currentOrigin: 'capacitor://localhost',
    })).toEqual({
      ok: false,
      message: '当前域名尚未登记为登录回跳地址',
    })
  })
})
