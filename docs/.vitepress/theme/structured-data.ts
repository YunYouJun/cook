import { iosVersion, links } from './constants.ts'

/** Kept in sync with docs/index.md frontmatter. */
export const productSiteDescription = `云食册是食用手册的原生 iOS 应用。根据已有食材找菜，查看产品真实界面、使用指南、用户协议和隐私政策。网页版可直接使用；iOS ${iosVersion} 已开放首批 30 人 TestFlight 外测，可通过公开链接自助加入。`

export const productFaq = [
  {
    question: '云食册和食用手册是什么关系？',
    answer: '云食册是食用手册的原生 iOS 应用，沿用根据食材找菜的思路。网页版继续使用「食用手册」这个名字；iOS 版增加私人菜谱、冰箱与本地临期提醒等功能。',
  },
  {
    question: '需要登录吗？数据会自动同步吗？',
    answer: '基础选食材、找菜和查看做法不要求注册或登录。云乐坊账号与 Apple 账户相互独立，iOS 与网页版目前不会自动同步。请使用相应版本的导出功能定期备份；iCloud 同步仍待生产环境和双设备验收。',
  },
  {
    question: '没有网络时还能用吗？',
    answer: 'iOS 版的本地菜谱与内置图文正文可离线阅读；配图、外部视频、账号服务与 iCloud 同步需要网络。网页版不承诺完整离线可用。',
  },
  {
    question: '食材提醒可以判断是否安全食用吗？',
    answer: '不能。食材日期和临期提醒只用于日常安排，不是食品安全检测。请结合包装说明、实际储存条件与食材状态判断；有过敏、特殊饮食或健康问题时，请咨询专业人士。',
  },
] as const

const person = {
  '@type': 'Person',
  '@id': 'https://github.com/YunYouJun#person',
  'name': '云游君',
  'alternateName': 'YunYouJun',
  'url': 'https://github.com/YunYouJun',
}

export function productHomeJsonLd() {
  return {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'WebSite',
        '@id': 'https://cook.yunle.fun/#website',
        'name': '云食册',
        'alternateName': ['食用手册', 'Cook'],
        'url': 'https://cook.yunle.fun/',
        'description': productSiteDescription,
        'inLanguage': 'zh-CN',
        'publisher': { '@id': person['@id'] },
      },
      {
        '@type': 'WebApplication',
        '@id': 'https://cook.yunyoujun.cn/#app',
        'name': '食用手册',
        'alternateName': ['Cook', '云食册'],
        'url': 'https://cook.yunyoujun.cn/',
        'description': '根据家里现有的食材和厨具，找到今天能做的家常菜。食用手册（Cook）免费使用，也叫云食册。',
        'applicationCategory': 'LifestyleApplication',
        'operatingSystem': 'Web',
        'isAccessibleForFree': true,
        'offers': {
          '@type': 'Offer',
          'price': '0',
          'priceCurrency': 'CNY',
        },
        'author': { '@id': person['@id'] },
        'creator': { '@id': person['@id'] },
      },
      {
        '@type': 'SoftwareApplication',
        '@id': 'https://cook.yunle.fun/#ios',
        'name': '云食册',
        'alternateName': ['食用手册', 'Cook'],
        'applicationCategory': 'LifestyleApplication',
        'operatingSystem': 'iOS 17 或更新版本',
        'url': 'https://cook.yunle.fun/',
        'sameAs': [links.testflight],
        'author': { '@id': person['@id'] },
      },
      person,
      {
        '@type': 'FAQPage',
        '@id': 'https://cook.yunle.fun/#faq',
        'url': 'https://cook.yunle.fun/',
        'inLanguage': 'zh-CN',
        'mainEntity': productFaq.map(item => ({
          '@type': 'Question',
          'name': item.question,
          'acceptedAnswer': {
            '@type': 'Answer',
            'text': item.answer,
          },
        })),
      },
    ],
  }
}

export function serializeProductJsonLd() {
  return JSON.stringify(productHomeJsonLd()).replace(/</g, '\\u003c')
}
