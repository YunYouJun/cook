import { isClient } from '@vueuse/core'

const shareOptions = {
  title: '好的，今天我们来做菜',
  link: 'https://cook.yunyoujun.cn',
  imgUrl: '/favicon.svg',
  success: () => {},
}

const jsApiList = [
  'updateAppMessageShareData',
  'updateTimelineShareData',
]

/**
 * 微信分享
 */
export function useWeixin() {
  if (isClient) {
    useScriptTag('http://res.wx.qq.com/open/js/jweixin-1.6.0.js', async() => {
      const data = await fetch('http://api.welomo.com/wx/r/?token_id=12691').then(res => res.json())

      const wx = window.wx
      if (wx) {
        wx.config({
          debug: true,
          appId: data.appId,
          timestamp: data.timestamp,
          nonceStr: data.nonceStr,
          signature: data.signature,
          jsApiList,
        })

        wx.ready(() => {
          wx.updateAppMessageShareData(shareOptions)
          wx.updateTimelineShareData(shareOptions)
        })
      }
    })
  }
}
