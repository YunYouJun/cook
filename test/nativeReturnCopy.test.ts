import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import NativeReturnCopy from '../apps/web/app/components/auth/NativeReturnCopy.vue'
import { cookAndroidIntentUrl } from '../apps/web/app/utils/androidBrowserIntent'

vi.unmock('vue')

const callback = 'https://cook.yunyoujun.cn/auth/callback?platform=native#ylf_sso=synthetic_return'
const chromeAndroid = 'Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 Chrome/143.0.0.0 Mobile Safari/537.36'

function showCopyControl(writeText: (text: string) => Promise<void>, userAgent = '') {
  vi.stubGlobal('navigator', { clipboard: { writeText }, userAgent })
  return mount(NativeReturnCopy, {
    attachTo: document.body,
    props: { link: callback },
    global: {
      stubs: {
        'ion-item': { template: '<section><slot /></section>' },
        'ion-label': { template: '<span><slot /></span>' },
      },
    },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe('native return link clipboard fallback', () => {
  it('copies the exact link without rendering it when the clipboard is available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    const wrapper = showCopyControl(writeText)
    try {
      await wrapper.get('section').trigger('click')
      await flushPromises()

      expect(writeText).toHaveBeenCalledExactlyOnceWith(callback)
      expect(wrapper.get('[role=status]').text()).toContain('已复制')
      expect(wrapper.find('textarea').exists()).toBe(false)
    }
    finally {
      wrapper.unmount()
    }
  })

  it('exposes a selectable, read-only link after clipboard denial and clears it for a different callback', async () => {
    const wrapper = showCopyControl(vi.fn().mockRejectedValue(new DOMException('Clipboard denied', 'NotAllowedError')))
    try {
      expect(wrapper.find('textarea').exists()).toBe(false)
      await wrapper.get('section').trigger('click')
      await flushPromises()

      const input = wrapper.get('textarea').element
      expect(input.value).toBe(callback)
      expect(input.readOnly).toBe(true)
      await wrapper.get('button').trigger('click')
      expect(document.activeElement).toBe(input)
      expect(input.selectionStart).toBe(0)
      expect(input.selectionEnd).toBe(callback.length)

      await wrapper.setProps({ link: callback.replace('synthetic_return', 'new_return') })
      expect(wrapper.find('textarea').exists()).toBe(false)
    }
    finally {
      wrapper.unmount()
    }
  })
})

describe('native return in Android Chrome', () => {
  it('keeps the callback fragment in the packaged intent and in its browser fallback', () => {
    const handoff = `https://cook.yunyoujun.cn/auth/callback?platform=native#cook_handoff=${'A'.repeat(43)}.${'B'.repeat(43)}`
    for (const link of [callback, handoff]) {
      expect(cookAndroidIntentUrl(link)).toBe(
        `intent:${link.slice(6)}#Intent;scheme=https;package=fun.yunle.cook;S.browser_fallback_url=${encodeURIComponent(link)};end`,
      )
    }
  })

  it('offers the explicit app action alongside copy for a valid native callback', async () => {
    const wrapper = showCopyControl(vi.fn(), chromeAndroid)
    try {
      await nextTick()
      expect(wrapper.text()).toContain('在云食册中继续')
      expect(wrapper.text()).toContain('复制回调链接')
      expect(wrapper.html()).not.toContain('synthetic_return')

      await wrapper.setProps({ link: callback.replace('cook.yunyoujun.cn', 'cook.yunyoujun.cn.example') })
      expect(wrapper.text()).not.toContain('在云食册中继续')
      await wrapper.setProps({ link: `${callback}#Intent;package=other;end` })
      expect(wrapper.text()).not.toContain('在云食册中继续')
    }
    finally {
      wrapper.unmount()
    }
  })

  it.each([
    chromeAndroid.replace('K)', 'K; wv)'),
    `${chromeAndroid} MicroMessenger/8.0.64`,
    'Mozilla/5.0 (iPhone; CPU iPhone OS 27_0 like Mac OS X) Mobile Safari/537.36',
  ])('retains copy in browsers without the Android app action', async (userAgent) => {
    const wrapper = showCopyControl(vi.fn(), userAgent)
    try {
      await nextTick()
      expect(wrapper.text()).toContain('复制回调链接')
      expect(wrapper.text()).not.toContain('在云食册中继续')
    }
    finally {
      wrapper.unmount()
    }
  })
})
