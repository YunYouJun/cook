import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAppFeedback } from '../apps/web/app/composables/useAppFeedback'

const { createAlert, createToast, createSheet } = vi.hoisted(() => ({
  createAlert: vi.fn(),
  createToast: vi.fn(),
  createSheet: vi.fn(),
}))
vi.mock('@ionic/vue', () => ({
  alertController: { create: createAlert },
  toastController: { create: createToast },
  actionSheetController: { create: createSheet },
}))

afterEach(() => {
  vi.resetAllMocks()
  vi.unstubAllGlobals()
})

function setup(fails: boolean, role = 'confirm') {
  const sendFeedback = fails ? vi.fn().mockRejectedValue(new Error('offline')) : vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('useRecipeFeedback', () => ({ sendFeedback }))
  vi.stubGlobal('navigator', {}) // Clipboard unavailable: the visible message must preserve the draft.
  createAlert.mockResolvedValueOnce({
    present: vi.fn(),
    onDidDismiss: vi.fn().mockResolvedValue({ role, data: { values: { note: '  番茄 & 鸡蛋  ', contact: ' cook+qa@example.com ' } } }),
  }).mockResolvedValue({ present: vi.fn() })
  createToast.mockResolvedValue({ present: vi.fn() })
  return sendFeedback
}

describe('app feedback flow', () => {
  it('retains the complete failed recipe submission in a selectable fallback', async () => {
    const send = setup(true)
    await useAppFeedback().openAppFeedback('recipe')
    expect(createSheet).not.toHaveBeenCalled()
    expect(send).toHaveBeenCalledWith({ category: 'recipe', note: '番茄 & 鸡蛋', contact: 'cook+qa@example.com' })
    const fallback = createAlert.mock.calls[1][0]
    expect(fallback.message).toContain('选中下方内容复制')
    expect(fallback.inputs[0].value).toContain('想要的菜没有')
    expect(fallback.inputs[0].value).toContain('联系方式：cook+qa@example.com')
    expect(fallback.inputs[0].value).toContain('kf@yunle.fun')
    expect(fallback.inputs[0].attributes.readonly).toBe(true)
    const location = { href: '' }
    vi.stubGlobal('window', { location })
    expect(fallback.buttons.find((button: { text: string }) => button.text === '发邮件').handler()).toBe(false)
    expect(new URL(location.href).searchParams.get('body')).toContain('cook+qa@example.com')
    expect(createToast).not.toHaveBeenCalled()
  })

  it('shows success only after the backend accepts the submission', async () => {
    setup(false)
    await useAppFeedback().openAppFeedback('idea')
    expect(createAlert).toHaveBeenCalledTimes(1)
    expect(createToast).toHaveBeenCalledWith(expect.objectContaining({ message: '收到了，谢谢反馈' }))
  })

  it('does not send a canceled draft', async () => {
    const send = setup(false, 'cancel')
    await useAppFeedback().openAppFeedback('bug')
    expect(send).not.toHaveBeenCalled()
  })
})
