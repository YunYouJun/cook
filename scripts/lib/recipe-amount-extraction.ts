/* eslint-disable regexp/no-super-linear-backtracking -- 仅处理已保存的正文条目，入口限制 4096 字符，不能完整匹配的条目留待确认。 */
import { key } from './recipe-amounts'

const conditionalPattern = /可选|可无|可省|不放|不喜欢|如果|若|否则|或者?|根据|按需|实际消耗|实际用量|用于炸|油炸|没过|覆盖|±|两种|方法[一二]|酌情|多加|追加|减少|多放|少放/

function balanced(text: string) {
  let depth = 0
  for (const c of text) {
    if ('（('.includes(c))
      depth++
    if ('）)'.includes(c) && --depth < 0)
      return false
  }
  return depth === 0
}
function topLevelDelimiter(text: string) {
  let depth = 0
  for (let index = 0; index < text.length; index++) {
    const c = text[index]!
    if ('（('.includes(c))
      depth++
    if ('）)'.includes(c))
      depth--
    if (depth === 0 && '=＝：:'.includes(c))
      return index
  }
  return -1
}
export function splitTopLevel(text: string) {
  let depth = 0
  let start = 0
  const parts: string[] = []
  for (let index = 0; index < text.length; index++) {
    const c = text[index]!
    if ('（('.includes(c))
      depth++
    if ('）)'.includes(c))
      depth--
    if (depth === 0 && c === '、') {
      parts.push(text.slice(start, index))
      start = index + 1
    }
  }
  parts.push(text.slice(start))
  return parts
}

export function parsePreparationLine(raw: string): { name: string, expression: string, note?: string, conditional: boolean } | undefined {
  if (raw.length > 4096)
    return
  const text = raw.replace(/^[•\s]+/, '').replace(/\*\*/g, '').trim()
  if (!text || !balanced(text) || text.includes('\n') || /^\d+(?:\s*汤匙|\s*茶匙)/.test(text))
    return
  let name: string, expression: string
  const delimiter = topLevelDelimiter(text)
  const equation = delimiter >= 0 ? [text, text.slice(0, delimiter), text.slice(delimiter + 1)] : /^(.+?)\s*的?用量(?:通常来说)?(?:为|是)[：:]?\s*(.+)$/.exec(text)
  const spaced = /^(.+?)\s+((?:约|大约|大概|总量)?\s*(?:份数|[0-9一二三四五六七八九十两半]).+)$/.exec(text)
  const plain = /^([^0-9一二三四五六七八九十两半（(]+?)\s*(?:[（(]\s*)?((?:约|大约|大概|总量)?\s*(?:份数|[0-9一二三四五六七八九十两半]).+)$/.exec(text)
  const reverse = /^([0-9一二三四五六七八九十两半]+\s*(?:\/\s*\d+\s*)?(?:[g克个只瓣枚]|ml|毫升))\s*(?:的\s*)?([^\d（(]+)(.*)$/.exec(text)
  if (equation) {
    [, name, expression] = equation as any
  }
  else if (spaced) {
    [, name, expression] = spaced as any
  }
  else if (plain) {
    [, name, expression] = plain as any
  }
  else if (reverse) {
    name = reverse[2]!
    expression = reverse[1]! + reverse[3]!
  }
  else {
    return
  }
  name = key(name!).replace(/[（(]\s*$/, '').trim()
  if (!name || !balanced(name) || /[，,。；;：:、和或/<>+*×每\d]|如果|根据|本教程|多少|调一个/.test(name) || name.length > 15)
    return
  expression = expression!.trim()
  // 「火腿(100g)」「冷饭（份数*500ml）」这类纯包裹式仅去最外层闭括号。
  if (/[）)]$/.test(expression) && !/[（(]/.test(expression))
    expression = expression.slice(0, -1).trim()
  const notes: string[] = []
  expression = expression.replace(/[（(]([^（）()]*)[）)]/g, (all, note: string) => {
    if (/份数|[*×+=]|向[上下]取整/.test(note))
      return all
    notes.push(note)
    return ''
  })
  const comma = /[，,]/.exec(expression)
  if (comma && !/^[，,]?\s*向[上下]取整/.test(expression.slice(comma.index))) {
    notes.push(expression.slice(comma.index + 1).trim())
    expression = expression.slice(0, comma.index)
  }
  const approx = /^(?:约|大约|大概)\s*/.exec(expression)
  if (approx) {
    notes.unshift('原文约量')
    expression = expression.slice(approx[0].length)
  }
  expression = expression.replace(/^总量\s*/, '').replace(/。+$/, '').trim()
  return { name, expression, ...(notes.length ? { note: notes.join('；') } : {}), conditional: conditionalPattern.test(notes.join('；')) || /可选|或者|或/.test(raw) }
}
