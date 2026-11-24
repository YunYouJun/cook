import { iosVersion, links } from './constants.ts'

/** Kept in sync with docs/index.md frontmatter. */
export const productSiteDescription = '云食册是食用手册的原生 iOS 应用，现已正式上架 App Store。根据家里现有食材与厨具找菜，内置离线图文做法、3D 云厨房、冰箱临期提醒与私人菜谱；网页版同样打开即用。'

export const productFaq = [
  {
    question: '云食册和食用手册是什么关系？',
    answer: '它们是同一款产品。「食用手册」是陪伴大家多年的网页版，打开浏览器就能快速找菜；「云食册」是专为 iPhone 与 iPad 打造的原生 iOS 应用，在此基础上加入了 3D 云厨房、厨房工作台与计时器、冰箱记录与本地临期提醒、私人菜谱与做菜足迹。',
  },
  {
    question: '下载和基础找菜免费吗？需要登录吗？',
    answer: '云食册可在 App Store 免费下载，选食材找菜、看菜谱做法、收藏与冰箱记录等核心功能都不要求注册或登录，打开就能放心做一顿饭。',
  },
  {
    question: '没有网络能用吗？数据怎么保存和备份？',
    answer: 'iOS 版的本地菜谱与内置图文做法支持离线阅读；菜谱配图与外部视频跳转需要网络。所有记录默认保存在本机，支持导入/导出 JSON 文件随时备份；目前 iOS 与网页版不会自动同步。',
  },
  {
    question: '冰箱的临期提醒可以判断食材还能不能吃吗？',
    answer: '不能。食材日期与临期提醒只用于日常下厨安排，并非食品安全检测。请结合包装保质期、实际储存条件与食材新鲜度判断；如有过敏、特殊饮食或健康顾虑，请咨询专业人士。',
  },
] as const

/** Keep in sync with apps/web/app/constants/seo.ts. */
export const personIdentity = {
  '@type': 'Person',
  '@id': 'https://www.yunyoujun.cn/#identity',
  'name': '云游君',
  'alternateName': 'YunYouJun',
  'url': 'https://www.yunyoujun.cn/',
} as const

/** Keep in sync with apps/web/app/constants/seo.ts. */
export const organizationIdentity = {
  '@type': 'Organization',
  '@id': 'https://www.yunle.fun/#organization',
  'name': '云乐坊',
  'url': 'https://www.yunle.fun/',
} as const

const person = personIdentity
const organization = organizationIdentity

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
        'publisher': { '@id': organization['@id'] },
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
        'publisher': { '@id': organization['@id'] },
      },
      {
        '@type': 'SoftwareApplication',
        '@id': 'https://cook.yunle.fun/#ios',
        'name': '云食册',
        'alternateName': ['食用手册', 'Cook'],
        'applicationCategory': 'LifestyleApplication',
        'operatingSystem': 'iOS 17 或更新版本',
        'softwareVersion': iosVersion,
        'url': 'https://cook.yunle.fun/',
        'downloadUrl': links.appStore,
        'installUrl': links.appStore,
        'isAccessibleForFree': true,
        'offers': {
          '@type': 'Offer',
          'price': '0',
          'priceCurrency': 'CNY',
        },
        'sameAs': [links.appStore, links.testflight],
        'author': { '@id': person['@id'] },
        'publisher': { '@id': organization['@id'] },
      },
      person,
      organization,
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

export function guideArticleJsonLd(page: { title: string, description?: string, path: string }) {
  const url = `https://cook.yunle.fun/${page.path.replace(/^\//, '')}`
  return {
    '@context': 'https://schema.org',
    '@type': 'TechArticle',
    'headline': page.title,
    ...(page.description ? { description: page.description } : {}),
    'inLanguage': 'zh-CN',
    'url': url,
    'mainEntityOfPage': url,
    'author': personIdentity,
    'publisher': organizationIdentity,
  }
}

export function serializeJsonLd(data: unknown) {
  return JSON.stringify(data).replace(/</g, '\\u003c')
}

export function serializeProductJsonLd() {
  return serializeJsonLd(productHomeJsonLd())
}
