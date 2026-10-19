/**
 * 致谢名单配置
 */

export interface Acknowledgement {
  /**
   * 描述或贡献说明
   */
  description?: string
  /**
   * 头像或Logo URL
   */
  avatar?: string
  /**
   * 社交媒体链接
   */
  links?: {
    /**
     * 链接类型（用于显示图标）
     */
    type?: 'github' | 'bilibili' | 'weibo' | 'twitter' | 'wechat' | 'blog' | 'website' | 'email'
    /**
     * 显示文本
     */
    label: string
    /**
     * 链接地址
     */
    href: string
    /**
     * 打开方式
     */
    target?: '_blank' | '_self'
  }[]
}

/**
 * 个人致谢名单（简单列表）
 */
export interface PersonalAcknowledgement {
  /**
   * 姓名
   */
  name: string
  /**
   * 个人链接（可选）
   */
  link?: string
}

/**
 * 致谢名单
 */
export const acknowledgements: Acknowledgement[] = [
  {
    description: '提供优质的菜谱数据来源',
    links: [
      {
        type: 'bilibili',
        label: '哔哩哔哩 美食专区',
        href: 'https://www.bilibili.com/blackboard/dynamic/306882',
        target: '_blank',
      },
    ],
  },
  {
    description: '感谢所有为本项目贡献代码、提出建议的开发者们',
    links: [
      {
        type: 'github',
        label: 'Contributors',
        href: 'https://github.com/YunYouJun/cook/graphs/contributors',
        target: '_blank',
      },
    ],
  },
  {
    description: '以下开源项目使得项目得以快速实现',
    links: [
      {
        type: 'website',
        label: 'Nuxt',
        href: 'https://nuxt.com',
        target: '_blank',
      },
      {
        type: 'website',
        label: 'Vue',
        href: 'https://vuejs.org',
        target: '_blank',
      },
      {
        type: 'website',
        label: 'UnoCSS',
        href: 'https://unocss.dev',
        target: '_blank',
      },
      {
        type: 'website',
        label: 'Ionic Framework',
        href: 'https://ionicframework.com',
        target: '_blank',
      },
      {
        type: 'website',
        label: 'Capacitor',
        href: 'https://capacitorjs.com',
        target: '_blank',
      },
      {
        type: 'website',
        label: 'VueUse',
        href: 'https://vueuse.org',
        target: '_blank',
      },
      // 其他
      {
        type: 'website',
        label: '其他',
        href: 'https://github.com/YunYouJun/cook/blob/main/package.json',
      },
    ],
  },
]

/**
 * 个人致谢名单
 */
export const personalAcknowledgements: PersonalAcknowledgement[] = [
  {
    name: 'Runny',
    link: 'https://weibo.com/runny',
  },
  {
    name: '麒麟',
  },
  {
    name: '晴方啾',
  },
  {
    name: '课代表阿伟',
  },
]
