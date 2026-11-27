import { defineLoader } from 'vitepress'
import { readAndroidDownload } from '../../../scripts/lib/android-download.mjs'

declare const data: ReturnType<typeof readAndroidDownload>
export { data }

export default defineLoader({
  load: () => readAndroidDownload(),
})
