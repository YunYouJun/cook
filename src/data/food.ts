export interface StuffItem {
  /**
   * 食材名称
   */
  name: string
  /**
   * 例如：🥔
   */
  emoji: string
  /**
   * 图片链接
   */
  image?: string
  /**
   * 别名，譬如：西红柿/番茄
   */
  alias?: string
  /**
   * 图标名称
   */
  icon?: string
  /**
   * 显示标签
   */
  label?: string
}

/**
 * 素菜
 */
export const vegetable: StuffItem[] = [
  {
    name: '土豆',
    emoji: '🥔',
  },
  {
    name: '胡萝卜',
    emoji: '🥕',
  },
  {
    name: '花菜',
    emoji: '🥦',
  },
  {
    name: '白萝卜',
    emoji: '🥣',
  },
  {
    name: '西葫芦',
    emoji: '🥒',
  },
  {
    name: '番茄',
    emoji: '🍅',
    alias: '西红柿',
  },
  {
    name: '芹菜',
    emoji: '🥬',
  },
  {
    name: '黄瓜',
    emoji: '🥒',
  },
  {
    name: '洋葱',
    emoji: '🧅',
  },
  {
    name: '莴笋',
    emoji: '🥗',
  },
  {
    name: '菌菇',
    emoji: '🍄',
  },
  {
    name: '茄子',
    emoji: '🍆',
  },
  {
    name: '豆腐',
    emoji: '🍲',
  },
  {
    name: '包菜',
    emoji: '🥗',
    // image: '/images/cabbage-dog.jpg',
  },
]

/**
 * 荤菜
 */
export const meat: StuffItem[] = [
  {
    name: '午餐肉',
    emoji: '🥓',
  },
  {
    name: '香肠',
    emoji: '🌭',
  },
  {
    name: '腊肠',
    emoji: '🌭',
  },
  {
    name: '鸡翅',
    emoji: '🐤',
  },
  {
    name: '猪肉',
    emoji: '🐷',
  },
  {
    name: '鸡蛋',
    emoji: '🥚',
  },
  {
    name: '虾',
    emoji: '🦐',
  },
  {
    name: '牛肉',
    emoji: '🐮',
  },
  {
    name: '鸡胸肉',
    emoji: '💪',
  },
  {
    name: '骨头',
    emoji: '🦴',
  },
]

/**
 * 主食
 */
export const staple: StuffItem[] = [
  {
    name: '面条',
    emoji: '🍝',
  },
  {
    name: '面包',
    emoji: '🍞',
  },
  {
    name: '米饭',
    emoji: '🍚',
  },
  {
    name: '方便面',
    emoji: '🍜',
  },
]

export const tools: StuffItem[] = [
  {
    name: '烤箱',
    emoji: '',
    icon: 'i-mdi-toaster-oven',
  },
  {
    name: '空气炸锅',
    emoji: '',
    icon: 'i-fe-frying-pan',
  },
  {
    name: '微波炉',
    emoji: '',
    icon: 'i-ic-outline-microwave',
  },
  {
    name: '电饭煲',
    emoji: '',
    icon: 'i-gg-smart-home-cooker',
  },
  {
    label: '一口啥都能煮的大锅',
    name: '一口大锅',
    emoji: '',
    icon: 'i-mdi-pot-steam-outline',
  },
]
