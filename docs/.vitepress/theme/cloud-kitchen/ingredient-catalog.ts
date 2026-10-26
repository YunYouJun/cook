export const ingredientCatalog = [
  { id: 'tomato', name: '番茄', detail: '饱满的果瓣，卷起的五角叶冠。', motion: '轻弹', bounce: 1 },
  { id: 'egg', name: '鸡蛋', detail: '一高一低的两枚蛋，保留自然的上窄下宽。', motion: '摇摆', bounce: 0.7 },
  { id: 'potato', name: '土豆', detail: '不规则轮廓，点缀浅浅的芽眼。', motion: '轻滚', bounce: 0.6 },
  { id: 'carrot', name: '胡萝卜', detail: '渐细的根部，三片舒展的绿叶。', motion: '跃起', bounce: 1.1 },
  { id: 'cucumber', name: '黄瓜', detail: '深浅相间的纵纹和细小的凸点。', motion: '摇摆', bounce: 0.7 },
  { id: 'tofu', name: '豆腐', detail: '错落的两块豆腐，柔和边缘与细小孔隙。', motion: '软弹', bounce: 0.8 },
  { id: 'chicken', name: '鸡肉', detail: '圆润的鸡腿和白色骨端，小尺寸也能认出。', motion: '轻弹', bounce: 0.9 },
  { id: 'rice', name: '米', detail: '蓝釉饭碗、金色碗沿和粒粒分明的白米。', motion: '轻晃', bounce: 0.5 },
] as const

export type IngredientId = typeof ingredientCatalog[number]['id']

export function ingredientImage(name: string) {
  const ingredient = ingredientCatalog.find(item => item.name === name)
  return ingredient ? `/models/ingredients/${ingredient.id}.png` : undefined
}
