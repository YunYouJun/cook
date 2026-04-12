/** 常见别名 → 数据库标准名 */
const STUFF_ALIASES: Record<string, string> = {
  西红柿: '番茄',
  土豆丝: '土豆',
  洋芋: '土豆',
  马铃薯: '土豆',
  西兰花: '花菜',
  蘑菇: '菌菇',
  香菇: '菌菇',
  金针菇: '菌菇',
  口蘑: '菌菇',
  鸡翅: '鸡肉',
  鸡腿: '鸡肉',
  鸡胸肉: '鸡肉',
  排骨: '猪肉',
  五花肉: '猪肉',
  里脊: '猪肉',
  肥牛: '牛肉',
  牛腩: '牛肉',
  虾仁: '虾',
  大白菜: '白菜',
  卷心菜: '包菜',
  泡面: '方便面',
  挂面: '面食',
  面条: '面食',
  米饭: '米',
  大米: '米',
}

/**
 * 将用户输入的食材标准化为数据库中的名称
 */
export function normalizeStuff(input: string[]): string[] {
  return input.map((s) => {
    const trimmed = s.trim()
    return STUFF_ALIASES[trimmed] ?? trimmed
  })
}
