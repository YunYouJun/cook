import { getViteConfig } from '@yunyoujun/docs'

import { defineConfig } from 'vite'

const viteConfig = getViteConfig()

export default defineConfig({
  ...viteConfig,
})
