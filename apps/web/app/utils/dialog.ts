import { actionSheetController, alertController } from '@ionic/vue'
import { yunleHaptic } from './yunleHost'

/**
 * Ionic 风格的弹窗，浏览器与云乐坊容器共用。
 */

export async function showAlert(header: string, message: string): Promise<void> {
  const alert = await alertController.create({ header, message, buttons: ['好'] })
  await alert.present()
  await alert.onDidDismiss()
}

/**
 * 破坏性操作的确认：底部操作表，确认项为红色
 */
export async function confirmDestructive(options: { header: string, confirmText: string }): Promise<boolean> {
  void yunleHaptic('medium')
  const sheet = await actionSheetController.create({
    header: options.header,
    buttons: [
      { text: options.confirmText, role: 'destructive' },
      { text: '取消', role: 'cancel' },
    ],
  })
  await sheet.present()
  const { role } = await sheet.onDidDismiss()
  return role === 'destructive'
}

/**
 * 多行文本输入；取消时返回 undefined
 */
export async function promptText(options: { header: string, message?: string, placeholder?: string, okText: string }): Promise<string | undefined> {
  const alert = await alertController.create({
    header: options.header,
    message: options.message,
    inputs: [{ name: 'value', type: 'textarea', placeholder: options.placeholder }],
    buttons: [
      { text: '取消', role: 'cancel' },
      { text: options.okText, role: 'confirm' },
    ],
  })
  await alert.present()
  const { role, data } = await alert.onDidDismiss<{ values: { value?: string } }>()
  return role === 'confirm' ? data?.values.value ?? '' : undefined
}
