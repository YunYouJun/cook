import { useDebounceFn } from '@vueuse/core'
import { applyYunleHostLayout, waitForYunleHost } from '~/utils/yunleHost'

/**
 * 在云乐坊 App 容器内打开时，按宿主的展示形态、安全区与胶囊位置调整布局
 */
export default defineNuxtPlugin({
  name: 'cook-yunle-host',
  setup() {
    void waitForYunleHost().then(async (host) => {
      if (!host)
        return

      const root = document.documentElement
      const sync = async () => {
        try {
          applyYunleHostLayout(root, await host.getEnv(), window.innerWidth)
        }
        catch {}
      }

      await sync()
      // 旋转屏幕或页面收起 / 恢复标题栏后，安全区与胶囊位置都会变化
      window.addEventListener('resize', useDebounceFn(sync, 150))
    })
  },
})
