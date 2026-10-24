import type { Difficulty } from '../types/index.js'
import food from '../../../data/food.json'
import rules from '../../../data/query.json'
import { STUFF_ALIASES } from './alias.js'

/**
 * 整句识别：把「冰箱里有土豆和鸡蛋，不要辣」这样的一句话拆成推荐用的筛选条件。
 *
 * 只靠 packages/data 里的词表做最长匹配，不调用模型；iOS 的 CookCore 按同样的规则实现，
 * 两端都跑 packages/data/fixtures/query.json。
 */
export interface CookQuery {
  /** 识别到的食材（标准名） */
  stuff: string[]
  /** 词典里没有、但看起来像食材的词，原样保留（自己添加的菜谱可能用到） */
  unknown: string[]
  /** 忌口 */
  avoid: string[]
  /** 厨具，只取第一个 */
  tool?: string
  noSpicy: boolean
  difficulty: Difficulty[]
  /** 对应 METHOD_GROUPS 的 label */
  methodGroups: string[]
}

type Term
  = | { kind: 'stuff' | 'tool' | 'method', value: string }
    | { kind: 'difficulty', value: Difficulty }
    | { kind: 'have' | 'avoid' | 'lack' | 'noSpicy' | 'ignore' }

type Scope = 'have' | 'avoid' | 'lack'

const CLAUSE_SEPARATOR = /[，,。.;；！!？?\n]|但是|但|不过|可是/
const LIST_SEPARATOR = /[、\s和跟与及]+/
/** 否定词后面只剩这些字时，它说的是前面的食材，如「香菜不要」「虾过敏」 */
const TAIL = /^[了的哦吧啊呀呢嘛哈~～\s]*$/
/** 含这些字的片段多半是句子成分而不是食材名，如「冰箱里」「两个」「能做的」 */
const NOT_STUFF = /[的了吗呢吧啊呀哦嘛做吃要想能么什怎啥咋我你他她有没还都就很太些里剩用给帮看找来去在是个只点下可以会把对也再又一二两三四五六七八九十半几颗根块斤克条盒包袋瓶]/
const UNKNOWN_STUFF = /^[\u4E00-\u9FFF]{1,6}$/

function createTerms(extraStuff: string[], extraTools: string[] = []): Map<string, Term> {
  const terms = new Map<string, Term>()
  // 先放优先级低的，后放的同名词覆盖前面的
  for (const name of extraStuff)
    terms.set(name, { kind: 'stuff', value: STUFF_ALIASES[name] ?? name })
  for (const item of [...food.vegetable, ...food.meat, ...food.staple] as { name: string, alias?: string }[]) {
    terms.set(item.name, { kind: 'stuff', value: item.name })
    if (item.alias)
      terms.set(item.alias, { kind: 'stuff', value: item.name })
  }
  for (const [alias, name] of Object.entries({ ...STUFF_ALIASES, ...rules.stuff }))
    terms.set(alias, { kind: 'stuff', value: name })
  for (const [label, words] of Object.entries(rules.methodGroups)) {
    for (const word of words)
      terms.set(word, { kind: 'method', value: label })
  }
  for (const name of extraTools)
    terms.set(name, { kind: 'tool', value: name })
  for (const item of food.tools)
    terms.set(item.name, { kind: 'tool', value: item.name })
  for (const [alias, name] of Object.entries(rules.tools))
    terms.set(alias, { kind: 'tool', value: name })
  for (const kind of ['have', 'lack', 'avoid', 'noSpicy', 'ignore'] as const) {
    for (const word of rules[kind])
      terms.set(word, { kind })
  }
  for (const [level, words] of Object.entries(rules.difficulty)) {
    for (const word of words)
      terms.set(word, { kind: 'difficulty', value: level as Difficulty })
  }
  return terms
}

const defaultTerms = createTerms([])
const longestTerm = (terms: Map<string, Term>) => Math.max(...[...terms.keys()].map(word => word.length))
const defaultMaxLength = longestTerm(defaultTerms)

interface Token { term: Term, start: number, end: number }

/** 从左到右，每个位置取最长的词 */
function scan(text: string, terms: Map<string, Term>, maxLength: number): Token[] {
  const tokens: Token[] = []
  let start = 0
  while (start < text.length) {
    let found: Token | undefined
    for (let length = Math.min(maxLength, text.length - start); length > 0; length--) {
      const term = terms.get(text.slice(start, start + length))
      if (term) {
        found = { term, start, end: start + length }
        break
      }
    }
    if (found)
      tokens.push(found)
    start = found ? found.end : start + 1
  }
  return tokens
}

function unknownStuff(gap: string): string[] {
  return gap.split(LIST_SEPARATOR).filter(piece => UNKNOWN_STUFF.test(piece) && !NOT_STUFF.test(piece))
}

/**
 * 把一句话拆成筛选条件。
 *
 * @param text 用户输入的一句话
 * @param extraStuff 词典以外也要认的食材，比如自己添加的菜谱里用到的
 */
export function parseCookQuery(text: string, extraStuff: string[] = [], extraTools: string[] = []): CookQuery {
  const terms = (extraStuff.length || extraTools.length) ? createTerms(extraStuff, extraTools) : defaultTerms
  const maxLength = (extraStuff.length || extraTools.length) ? longestTerm(terms) : defaultMaxLength
  const stuff: string[] = []
  const unknown: string[] = []
  const avoid: string[] = []
  const tools: string[] = []
  const difficulty: Difficulty[] = []
  const methodGroups: string[] = []
  let noSpicy = false

  for (const clause of text.split(CLAUSE_SEPARATOR)) {
    let scope: Scope = 'have'
    // 当前作用范围里已经算作「有」的食材，遇到「香菜不要」这种后置否定时要改判
    let pending: { list: string[], name: string }[] = []
    const add = (list: string[], name: string) => {
      list.push(name)
      pending.push({ list, name })
    }
    const addStuff = (name: string, isUnknown = false) => {
      if (scope === 'have')
        add(isUnknown ? unknown : stuff, name)
      else if (scope === 'avoid' && !isUnknown)
        avoid.push(name)
    }

    const tokens = scan(clause, terms, maxLength)
    let cursor = 0
    for (const { term, start, end } of tokens) {
      for (const name of unknownStuff(clause.slice(cursor, start)))
        addStuff(name, true)
      cursor = end

      switch (term.kind) {
        case 'stuff':
          addStuff(term.value)
          break
        case 'have':
          scope = 'have'
          pending = []
          break
        case 'avoid':
        case 'lack':
          if (TAIL.test(clause.slice(end))) {
            for (const { list, name } of pending) {
              list.splice(list.lastIndexOf(name), 1)
              if (term.kind === 'avoid' && list === stuff)
                avoid.push(name)
            }
          }
          scope = term.kind
          pending = []
          break
        case 'noSpicy':
          noSpicy = true
          break
        case 'tool':
          if (scope === 'have')
            tools.push(term.value)
          break
        case 'method':
          if (scope === 'have')
            methodGroups.push(term.value)
          break
        case 'difficulty':
          difficulty.push(term.value)
          break
      }
    }
    for (const name of unknownStuff(clause.slice(cursor)))
      addStuff(name, true)
  }

  const avoided = new Set(avoid)
  const unique = <T>(items: T[]) => [...new Set(items)]
  return {
    stuff: unique(stuff).filter(name => !avoided.has(name)),
    unknown: unique(unknown),
    avoid: unique(avoid),
    tool: tools[0],
    noSpicy,
    difficulty: unique(difficulty),
    methodGroups: unique(methodGroups),
  }
}

/** 一句话里有没有认出任何东西 */
export function isEmptyCookQuery(query: CookQuery): boolean {
  return !query.stuff.length && !query.unknown.length && !query.avoid.length && !query.tool
    && !query.noSpicy && !query.difficulty.length && !query.methodGroups.length
}
