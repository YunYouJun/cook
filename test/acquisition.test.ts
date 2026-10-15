import { beforeEach, describe, expect, it, vi } from 'vitest'

function browser(url = 'https://cook.yunle.fun/?utm_source=知乎&utm_medium=social&utm_campaign=秋季菜谱', referrer = 'https://www.baidu.com/search?q=private') {
  const local = new Map<string, string>()
  const session = new Map<string, string>()
  const storage = (values: Map<string, string>) => ({
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
  })
  return {
    document: { referrer },
    localStorage: storage(local),
    location: new URL(url),
    sessionStorage: storage(session),
  } as unknown as Pick<Window, 'location' | 'document' | 'localStorage' | 'sessionStorage'>
}

beforeEach(() => vi.resetModules())

describe('cook document acquisition', () => {
  it.each([
    'https://yunle.fun/',
    'https://www.yunle.fun/login',
    'https://apps.yunle.fun/',
    'https://nested.apps.yunle.fun/',
    'https://cook.yunyoujun.cn/',
    'https://yunle.fun./login',
    'https://cook.yunle.fun/random',
  ])('excludes internal ecosystem navigation from external acquisition: %s', async (referrer) => {
    const { captureCookAcquisition } = await import('../app/utils/acquisition')
    const context = captureCookAcquisition(browser('https://cook.yunle.fun/', referrer), 1000)!
    expect(context.firstTouch.referrerHost).toBe('')
    expect(context.registerTouch.referrerHost).toBe('')
  })

  it.each(['https://github.com/YunYouJun/cook', 'https://www.zhihu.com/', 'https://notyunle.fun/'])(
    'retains an actual external referrer: %s',
    async (referrer) => {
      const { captureCookAcquisition } = await import('../app/utils/acquisition')
      const context = captureCookAcquisition(browser('https://cook.yunle.fun/', referrer), 1000)!
      expect(context.registerTouch.referrerHost).toBe(new URL(referrer).hostname)
    },
  )

  it('keeps first touch for 30 days while updating the current document entry', async () => {
    const { captureCookAcquisition } = await import('../app/utils/acquisition')
    const entry = browser()
    const first = captureCookAcquisition(entry, 1000)!
    entry.location = new URL('https://cook.yunle.fun/random?utm_source=github') as unknown as Location
    const second = captureCookAcquisition(entry, 2000)!
    expect(second.firstTouch).toEqual(first.firstTouch)
    expect(second.registerTouch.landingPath).toBe('/random')
    expect(second.registerTouch.utmSource).toBe('github')
    expect(second.registerTouch.referrerHost).toBe('www.baidu.com')
    expect(JSON.stringify(second)).not.toContain('q=private')

    const expired = captureCookAcquisition(entry, 1000 + 30 * 86400000)!
    expect(expired.firstTouch).toEqual(expired.registerTouch)
    expect(expired.firstTouch.utmSource).toBe('github')
  })

  it('preserves the pre-auth entry through a callback and never captures OAuth referrers', async () => {
    const { captureCookAcquisition, readCookAcquisition } = await import('../app/utils/acquisition')
    const entry = browser()
    const context = captureCookAcquisition(entry, 1000)
    entry.location = new URL('https://cook.yunle.fun/auth/callback?code=private#ylf_sso=private') as unknown as Location
    Object.assign(entry.document, { referrer: 'https://github.com/login/oauth' })
    expect(captureCookAcquisition(entry, 2000)).toEqual(context)
    expect(readCookAcquisition(entry, 2000)).toEqual(context)
    expect(JSON.stringify(context)).not.toContain('private')
  })

  it('does not invent attribution for a callback without an earlier document entry', async () => {
    const { captureCookAcquisition } = await import('../app/utils/acquisition')
    expect(captureCookAcquisition(browser('https://cook.yunle.fun/auth/callback'), 1000)).toBeNull()
  })

  it.each([null, '{broken', JSON.stringify({ capturedAt: -1 })])('does not restore a readable deleted or invalid entry from memory: %s', async (stored) => {
    const { captureCookAcquisition, readCookAcquisition } = await import('../app/utils/acquisition')
    const entry = browser()
    expect(captureCookAcquisition(entry, 1000)?.registerTouch.utmSource).toBe('知乎')
    entry.sessionStorage.getItem = () => stored
    expect(readCookAcquisition(entry, 2000)).toBeNull()
    entry.sessionStorage.getItem = () => {
      throw new Error('storage blocked')
    }
    expect(readCookAcquisition(entry, 3000)).toBeNull()
  })

  it('uses the current entry when readable first touch is cleared, without reviving its old campaign', async () => {
    const { captureCookAcquisition, readCookAcquisition } = await import('../app/utils/acquisition')
    const entry = browser()
    captureCookAcquisition(entry, 1000)
    entry.location = new URL('https://cook.yunle.fun/random?utm_source=github') as unknown as Location
    captureCookAcquisition(entry, 2000)
    entry.localStorage.getItem = () => null
    expect(readCookAcquisition(entry, 3000)?.firstTouch.utmSource).toBe('github')
    entry.localStorage.getItem = () => {
      throw new Error('storage blocked')
    }
    expect(readCookAcquisition(entry, 4000)?.firstTouch.utmSource).toBe('github')
  })

  it('keeps a validated memory first touch across blocked storage and expires stale entry data', async () => {
    const { captureCookAcquisition, readCookAcquisition } = await import('../app/utils/acquisition')
    const entry = browser()
    for (const storage of [entry.localStorage, entry.sessionStorage]) {
      storage.getItem = () => {
        throw new Error('storage blocked')
      }
      storage.setItem = () => {
        throw new Error('storage blocked')
      }
    }
    captureCookAcquisition(entry, 1000)
    entry.location = new URL('https://cook.yunle.fun/random?utm_source=github') as unknown as Location
    const current = captureCookAcquisition(entry, 2000)!
    expect(current.firstTouch.utmSource).toBe('知乎')
    expect(current.registerTouch.utmSource).toBe('github')
    expect(readCookAcquisition(entry, 2000 + 30 * 86400000)).toBeNull()
  })

  it('uses the new entry when a failed write leaves the old value readable, then respects deletion', async () => {
    const { captureCookAcquisition, readCookAcquisition } = await import('../app/utils/acquisition')
    const entry = browser('https://cook.yunle.fun/?utm_source=old')
    captureCookAcquisition(entry, 1000)
    entry.sessionStorage.setItem = () => {
      throw new Error('quota exceeded')
    }
    entry.location = new URL('https://cook.yunle.fun/random?utm_source=new') as unknown as Location
    expect(captureCookAcquisition(entry, 2000)?.registerTouch.utmSource).toBe('new')
    expect(readCookAcquisition(entry, 3000)?.registerTouch.utmSource).toBe('new')
    expect(readCookAcquisition(entry, 3000)?.firstTouch.utmSource).toBe('old')
    entry.sessionStorage.getItem = () => null
    expect(readCookAcquisition(entry, 4000)).toBeNull()
    entry.sessionStorage.getItem = () => {
      throw new Error('storage blocked')
    }
    expect(readCookAcquisition(entry, 5000)).toBeNull()
  })

  it('ignores credentials, invalid UTM and storage failures without blocking authentication', async () => {
    const { captureCookAcquisition, readCookAcquisition } = await import('../app/utils/acquisition')
    const entry = browser('https://cook.yunle.fun/?utm_source=hello%0Aworld&token=private', 'https://user:pass@example.com/path')
    for (const storage of ['localStorage', 'sessionStorage']) {
      Object.defineProperty(entry, storage, {
        get() {
          throw new Error('storage blocked')
        },
      })
    }
    const context = captureCookAcquisition(entry, 1000)!
    expect(context.registerTouch.referrerHost).toBe('')
    expect(context.registerTouch.utmSource).toBe('')
    expect(readCookAcquisition(entry, 2000)).toEqual(context)
    expect(JSON.stringify(context)).not.toContain('private')
  })
})
