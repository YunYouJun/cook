/// <reference types="@capacitor/status-bar" />

import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'cn.yunyoujun.cook',
  appName: 'cook',
  webDir: 'dist',

  plugins: {
    StatusBar: {
      overlaysWebView: false,
      style: 'DARK',
      backgroundColor: '#ffffffff',
    },
  },
}

export default config
