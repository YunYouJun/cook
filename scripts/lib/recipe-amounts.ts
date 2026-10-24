/* eslint-disable regexp/prefer-d, regexp/prefer-character-class, regexp/no-useless-non-capturing-group, regexp/no-super-linear-backtracking -- 拼接的计量语法保留显式分组；入口限制 literal 256 / formula 4096 字符，超长式保守拒绝。 */
import type { RecipeText, RecipeTextBlock } from '../../packages/cook/src/types/recipe-text'
import { Buffer } from 'node:buffer'

export interface AmountAnchor { block: number, item?: number, text: string }
export type AmountFormula
  = | { op: 'literal', lower: number, upper?: number, unit: string }
    | { op: 'scalar', lower: number }
    | { op: 'servings' }
    | { op: 'reference', name: string }
    | { op: 'count', name: string, unit: string }
    | { op: 'multiply' | 'divide' | 'add' | 'ceil' | 'floor', args: AmountFormula[] }
export interface AmountQuantity {
  name: string
  formula: AmountFormula
  evidence: AmountAnchor[]
  aliases: string[]
  note?: string
  conditional: boolean
}
export interface AmountUsage {
  anchor: AmountAnchor
  ingredient: string
  kind: 'amount' | 'total' | 'remaining' | 'unknown' | 'reference'
  formula?: AmountFormula
  target?: string
  conditional: boolean
  display?: boolean
  evidence: AmountAnchor[]
}
export interface AmountEntry {
  sourceFingerprint: string
  servingEvidence: AmountAnchor[]
  servingDescription?: string
  referenceServings?: number
  quantities: AmountQuantity[]
  stepSections: AmountAnchor[]
  usages: AmountUsage[]
  blockedIngredients: string[]
}
export interface AmountAnnotations { schemaVersion: 1, recipes: Record<string, AmountEntry> }

export function sourceFingerprint(source: RecipeText) {
  const tokens = source.blocks.flatMap((block): string[] => {
    switch (block.type) {
      case 'heading': case 'paragraph': return [block.type, block.text]
      case 'list': return ['list', String(block.ordered), String(block.start ?? 1), String(block.items.length), ...block.items]
      case 'image': return ['image', block.src, String(block.width), String(block.height), block.alt ?? '']
      default: return []
    }
  })
  let hash = 14695981039346656037n
  for (const byte of Buffer.from(tokens.join('\0'), 'utf8')) hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * 1099511628211n)
  return hash.toString(16).padStart(16, '0')
}

export function sourceLines(source: RecipeText) {
  let section = '简介'
  return source.blocks.flatMap((block, index) => {
    if (block.type === 'heading')
      section = block.text
    const lines = block.type === 'list' ? block.items.map((text, item) => ({ block: index, item, text })) : block.type === 'image' ? [] : [{ block: index, text: block.text }]
    return lines.map(anchor => ({ ...anchor, section, type: block.type }))
  })
}

export const stepHeadings = new Set([
  '操作',
  '操作步骤',
  '制作步骤',
  '步骤',
  '做法',
  '最终步骤',
  '开始制作',
  '备料',
  '原材料准备',
  '配菜处理',
  '鸡蛋的预处理',
  '准备原料',
  '简易版本',
  '裹粉',
  '牛肉的预处理',
  '烹饪',
  '准备工作',
  '1. 食材准备',
  '2. 烧煮过程',
  '3.冷藏后加热',
  '火腿肠预处理',
  '面的处理',
  '酱料处理',
  '制作葱油酱汁',
  '煮面条 （按份操作）',
  '混合拌面 （按份操作）',
  '初步煎炸',
  '最终煎炸',
  '稍加复杂但是更加令人垂涎欲滴的版本',
  '切配准备',
  '煎制过程',
  '烹制过程1（前一小时）',
  '烹制过程2（后半小时）',
  '预制',
  '再加工',
  '备菜',
  '切花',
  '泡水',
  '油炸',
  '装盘',
  '鸭肉焯水以去腥去血水',
  '开始煮',
  '五花肉一段处理',
  '五花肉二段处理',
  '开始炒肉',
  '预处理：食材切配与防氧化',
  '预处理：调料封装与异步任务',
  '初步加工：排骨焯水与脱水',
  '核心炒制：糖色与爆香（高并发阶段）',
  '炖煮收汁：挂霜与出锅',
  '葱姜水制作',
  '猪肉处理',
  '猪肉裹粉',
  '初炸定形状',
  '复炸出成品',
  '炒熟',
  '鸡肉的预处理',
  '佐料的准备',
  '烤制',
  '鸡爪处理',
  '去骨',
  '调配腌料',
  '鸡翅的预处理',
  '烤制过程',
  '腌制鸡翅（提前一晚）',
  '裹粉准备',
  '炸制',
  '准备',
  '制作',
  '准备馅料',
  '处理青椒',
  '酿入肉馅',
  '调酱汁',
  '煎制焖煮',
  '虾滑 fork 版本',
  '起锅',
  '制作腌料和香碗汁',
  '处理原料',
  '炒熟各种材料',
  '腌制鸡肉（提前 1 小时）',
  '烤制鸡肉',
  '制作酱汁底料',
  '炖煮',
  '备菜阶段',
  '炒制阶段',
  '使用海鲈鱼、罗非鱼、大比目鱼或者龙脷鱼',
  '腌制鱼头',
  '剁肉',
  '调味',
  '汆丸子',
  '制作丸子汤',
  '煮汤',
  '烹饪过程',
  '第一次蒸',
  '切菜',
  '炒制',
  '第二次蒸',
  '热锅炒肉',
  '炒制河粉',
  '食材的预处理',
  '制作西红柿鸡蛋臊子',
  '最后煮面条',
  '准备动作',
  '煮面与调味',
  '泼油',
  '可控火候微波炉',
  '不可控火候微波炉',
])
export const ingredientHeadings = new Set(['必备原料和工具', '主料', '辅料', '葱姜水', '腌制调料', '丸子肉配料', '丸子汤配料', '主食材', '副食材', '可选原料', '可额外添加的材料', '香料', '配料', '酱料'])

export function anchorOf(line: AmountAnchor): AmountAnchor {
  return { block: line.block, ...(line.item === undefined ? {} : { item: line.item }), text: line.text }
}
export function key(name: string) {
  return name.replace(/`|\*\*/g, '').replace(/[（(]可选[）)]/g, '').replace(/(?:的数量|的用量|数量|用量|量)$/, '').trim()
}

/** “芝麻油”里的“芝麻”不是另一次芝麻投料；只比较完整材料名的覆盖位置。 */
export function isContainedIngredientMention(text: string, name: string, offset: number, names: string[]) {
  return names.some((other) => {
    if (other.length <= name.length || !other.includes(name))
      return false
    let start = text.indexOf(other)
    while (start !== -1) {
      if (start <= offset && start + other.length >= offset + name.length)
        return true
      start = text.indexOf(other, start + 1)
    }
    return false
  })
}
export const scalar = (lower: number): AmountFormula => ({ op: 'scalar', lower })
export const literal = (lower: number, unit: string, upper?: number): AmountFormula => ({ op: 'literal', lower, ...(upper === undefined || upper === lower ? {} : { upper }), unit })
export const servings: AmountFormula = { op: 'servings' }
export const multiply = (a: AmountFormula, b: AmountFormula): AmountFormula => ({ op: 'multiply', args: [a, b] })
export const divide = (a: AmountFormula, b: AmountFormula): AmountFormula => ({ op: 'divide', args: [a, b] })
export const add = (a: AmountFormula, b: AmountFormula): AmountFormula => ({ op: 'add', args: [a, b] })
export const count = (name: string, unit: string): AmountFormula => ({ op: 'count', name, unit })
const chinese: Record<string, number> = { 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10, 半: 0.5 }
const number = '(?:[0-9]+(?:\\.[0-9]+)?|[一二三四五六七八九十两半])'
export const units = '千克|公斤|毫升|汤匙|茶匙|瓶盖|小块|kg|KG|ml|mL|ML|g|G|L|l|克|斤|升|个|颗|只|根|瓣|片|朵|张|支|块|勺|把|杯|袋|包|盒|瓶|罐|枚|粒|棵|条|碗|滴|扎|圈|节|段|株|叶|卷|头|份'
function numeric(value: string) {
  return chinese[value] ?? Number(value)
}
export function canonicalUnit(value: string) {
  return ({ 克: 'g', G: 'g', 千克: 'kg', 公斤: 'kg', KG: 'kg', 毫升: 'ml', mL: 'ml', ML: 'ml', 升: 'L', l: 'L' } as Record<string, string>)[value] ?? value
}
export function parseLiteral(source: string): AmountFormula | undefined {
  if (source.length > 256)
    return
  const text = source.trim()
  const range = new RegExp(`^(${number})\\s*(${units})?\\s*[-–~～至到]\\s*(${number})\\s*(${units})$`).exec(text)
  if (range) {
    if (range[2] && canonicalUnit(range[2]) !== canonicalUnit(range[4]!))
      return
    const lower = numeric(range[1]!)
    const upper = numeric(range[3]!)
    if (upper < lower)
      return
    return literal(lower, canonicalUnit(range[4]!), upper)
  }
  const mixed = new RegExp(`^(${number})\\s+(${number})\\s*/\\s*(${number})\\s*(${units})$`).exec(text)
  if (mixed && numeric(mixed[3]!) > 0)
    return literal(numeric(mixed[1]!) + numeric(mixed[2]!) / numeric(mixed[3]!), canonicalUnit(mixed[4]!))
  const fraction = new RegExp(`^(${number})\\s*/\\s*(${number})\\s*(${units})$`).exec(text)
  if (fraction && numeric(fraction[2]!) > 0)
    return literal(numeric(fraction[1]!) / numeric(fraction[2]!), canonicalUnit(fraction[3]!))
  const match = new RegExp(`^(${number})\\s*(${units})$`).exec(text)
  if (match)
    return literal(numeric(match[1]!), canonicalUnit(match[2]!))
}

/** 有限、完整匹配的语法；不从不理解的后缀中截出部分数值。 */
export function parseFormula(text: string, names: Map<string, string>, knownUnits: Map<string, string>): AmountFormula | undefined {
  if (text.length > 4096)
    return
  text = text.replace(/\\\*/g, '*').replace(/`/g, '').trim().replace(/[。，,]+$/, '').trim()
  const rounding = /(?:[，,]\s*)?向([上下])取整$/.exec(text)
  if (rounding) {
    const inner = parseFormula(text.slice(0, rounding.index), names, knownUnits)
    return inner ? { op: rounding[1] === '上' ? 'ceil' : 'floor', args: [inner] } : undefined
  }
  if (text.includes('+')) {
    const parts = text.split('+').map(value => parseFormula(value, names, knownUnits))
    return parts.every(Boolean) ? parts.reduce((a, b) => add(a!, b!)) : undefined
  }
  const product = text.split(/[*×]/)
  if (product.length > 1) {
    const parts = product.map(value => parseFormula(value, names, knownUnits))
    return parts.every(Boolean) ? parts.reduce((a, b) => multiply(a!, b!)) : undefined
  }
  if (text === '份数')
    return servings
  const value = parseLiteral(text)
  if (value)
    return value
  const perPerson = /^(.*?)\s*\/\s*(?:([一二三四五六七八九十两]|\d+)\s*)?(?:人|份)$/.exec(text)
  if (perPerson) {
    const amount = parseLiteral(perPerson[1]!)
    const people = perPerson[2] ? numeric(perPerson[2]) : 1
    return amount && people > 0 ? divide(multiply(amount, servings), scalar(people)) : undefined
  }
  const reference = /^(.+?)\s*\/\s*(.+)$/.exec(text)
  if (reference && names.has(key(reference[1]!))) {
    const name = names.get(key(reference[1]!))!
    const unit = knownUnits.get(name)
    if (unit && canonicalUnit(reference[2]!.trim()) === unit)
      return count(name, unit)
    const divisor = parseLiteral(reference[2]!.trim())
    if (divisor?.op === 'literal' && divisor.lower > 0 && divisor.unit === unit)
      return divide({ op: 'reference', name }, scalar(divisor.lower))
  }
  const name = names.get(key(text))
  if (name && knownUnits.has(name))
    return count(name, knownUnits.get(name)!)
}

export function matchesAnchor(anchor: AmountAnchor, source: RecipeText) {
  const block = source.blocks[anchor.block]
  if (!block)
    return false
  return block.type === 'list'
    ? anchor.item !== undefined && block.items[anchor.item] === anchor.text
    : (block.type === 'heading' || block.type === 'paragraph') && anchor.item === undefined && block.text === anchor.text
}
export function sectionFor(blocks: RecipeTextBlock[], index: number) {
  return blocks.slice(0, index + 1).findLast(block => block.type === 'heading')?.text ?? '简介'
}
