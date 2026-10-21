import type { AppFeedbackCategory } from '~/adapters/feedback'
import { actionSheetController, alertController, toastController } from '@ionic/vue'
import { links } from '~/constants'
import { appFeedbackCategories, buildFeedbackMail, copyFeedbackText } from '~/utils/feedback'

/**
 * 意见反馈（不针对某道菜）的弹窗流程，「我的」和「应用」页共用
 * @see docs/decisions/0010-feedback-channels.md
 */
export function useAppFeedback() {
  const feedback = useRecipeFeedback()

  /** 选类型、写几句，直接提交到 cook-feedback；提交不了时复制内容，引导发邮件或去 GitHub */
  async function openAppFeedback(preset?: AppFeedbackCategory): Promise<void> {
    let category = preset
    if (!category) {
      const sheet = await actionSheetController.create({
        header: '想反馈什么？',
        buttons: [
          ...appFeedbackCategories.map(item => ({ text: item.label, data: item.category })),
          { text: '取消', role: 'cancel' },
        ],
      })
      await sheet.present()
      const { data, role } = await sheet.onDidDismiss<AppFeedbackCategory>()
      if (role === 'cancel' || !data)
        return
      category = data
    }

    const item = appFeedbackCategories.find(entry => entry.category === category)
    const alert = await alertController.create({
      header: item?.label,
      message: '不用登录。留下联系方式的话，我们处理后可以告诉你。',
      inputs: [
        { name: 'note', type: 'textarea', placeholder: item?.placeholder ?? '说说具体情况', attributes: { maxlength: 500 } },
        { name: 'contact', type: 'text', placeholder: '邮箱或微信（可选）', attributes: { maxlength: 100 } },
      ],
      buttons: [
        { text: '取消', role: 'cancel' },
        { text: '提交', role: 'confirm' },
      ],
    })
    await alert.present()
    const result = await alert.onDidDismiss<{ values: { note?: string, contact?: string } }>()
    const note = result.data?.values.note?.trim() ?? ''
    const contact = result.data?.values.contact?.trim() || undefined
    if (result.role !== 'confirm' || !note)
      return

    try {
      await feedback.sendFeedback({ category, note, contact })
      const toast = await toastController.create({ message: '收到了，谢谢反馈', duration: 1800, position: 'top' })
      await toast.present()
    }
    catch {
      const copied = await copyFeedbackText(note)
      const mail = buildFeedbackMail({
        topic: item?.label ?? '意见反馈',
        lines: [note, contact ? `联系方式：${contact}` : ''],
      })
      const fallback = await alertController.create({
        header: '暂时没能提交',
        message: `${copied ? '内容已经复制。' : ''}可以发邮件给我们，或者到 GitHub 提一个 issue。`,
        buttons: [
          { text: '取消', role: 'cancel' },
          { text: '去 GitHub', handler: () => { window.open(links.githubIssue, '_blank') } },
          { text: '发邮件', handler: () => { window.location.href = mail } },
        ],
      })
      await fallback.present()
    }
  }

  return { openAppFeedback }
}
