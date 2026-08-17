/// <reference types="@capacitor/status-bar" />

import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'cn.yunyoujun.cook',
  appName: '食用手册',
  webDir: 'dist',

  server: {
    hostname: 'cook.yunyoujun.cn',
    androidScheme: 'https',
    iosScheme: 'https',
  },

  plugins: {
    StatusBar: {
      overlaysWebView: false,
      style: 'DARK',
      backgroundColor: '#ffffffff',
    },
  },
}

export default config
