/**
 * 把 HowToCook 的 Markdown 菜谱转成 App 能直接排版的段落（packages/data/recipe-text.json）
 *
 * 只保留文字：图片放在 GitHub 上，国内加载慢，App 里只提示原文有几张配图。
 */
import type { RecipeText, RecipeTextBlock, RecipeTextList } from '../../packages/cook/src/types/recipe-text'

export const HOWTOCOOK_REPO = 'https://github.com/Anduin2017/HowToCook'
export const HOWTOCOOK_BLOB = `${HOWTOCOOK_REPO}/blob/master/`

/** 每篇末尾的固定结束语，App 里不需要 */
const BOILERPLATE = /^如果您遵循本指南的制作流程而发现有问题或可以改进的流程/
const IMAGE = /!\[[^\]]*\]\([^)]*\)/g
const LIST_ITEM = /^([*+-]|\d+\.)\s+(\S.*)$/

/** HowToCook 链接在仓库里的相对路径，不是 HowToCook 的链接时返回 undefined */
export function howToCookPath(link?: string) {
  if (!link?.startsWith(HOWTOCOOK_BLOB))
    return undefined
  return decodeURIComponent(link.slice(HOWTOCOOK_BLOB.length))
}

/** 行内格式：相对链接在 App 里打不开，只留文字；外链和加粗保留成 Markdown */
function inline(text: string) {
  return text
    // 公式只有乘号这类简单写法
    .replace(/\$([^$]+)\$/g, (_, formula: string) => formula.replace(/\\times/g, '×'))
    .replace(/\[([^\]]*)\]\(([^)]*)\)/g, (all, label: string, url: string) => url.startsWith('http') ? all : label)
    .replace(/\s+/g, ' ')
    .trim()
}

function isTableSeparator(line: string) {
  return /^\|?\s*:?-{2,}/.test(line)
}

/** 表格行改成「单元格 · 单元格」 */
function tableRow(line: string) {
  return line.replace(/^\||\|$/g, '').split('|').map(cell => cell.trim()).filter(Boolean).join(' · ')
}

export function parseHowToCook(markdown: string): RecipeText {
  const blocks: RecipeTextBlock[] = []
  let images = 0
  let paragraph: string[] = []
  let list: RecipeTextList | undefined
  /** 列表项下面缩进的内容（补充说明、子列表）接在这一项后面 */
  let blankInList = false
  /** 代码块（流程图、伪代码）在 App 里没法看，整段跳过 */
  let inFence = false

  function flushParagraph() {
    const text = inline(paragraph.join(''))
    paragraph = []
    if (text && !BOILERPLATE.test(text))
      blocks.push({ type: 'paragraph', text })
  }
  function closeList() {
    if (list?.items.length)
      blocks.push(list)
    list = undefined
    blankInList = false
  }

  for (const raw of markdown.replace(/\r\n?/g, '\n').split('\n')) {
    images += raw.match(IMAGE)?.length ?? 0
    const line = raw.replace(IMAGE, '').replace(/\s+$/, '')
    const indent = line.length - line.trimStart().length
    const text = line.trim()

    if (text.startsWith('```')) {
      inFence = !inFence
      continue
    }
    if (inFence)
      continue

    // 空行和分隔线都结束一段
    if (!text || /^(?:-{3,}|\*{3,})$/.test(text)) {
      flushParagraph()
      if (list)
        blankInList = true
      continue
    }

    // 只有图片的列表项，去掉图片后是空的
    if (/^(?:[*+-]|\d+\.)$/.test(text))
      continue

    const heading = /^(#{1,6})\s+(\S.*)$/.exec(text)
    if (heading && indent === 0) {
      flushParagraph()
      closeList()
      // 一级标题是「XX的做法」，App 里菜名已经是页面标题
      if (heading[1]!.length > 1)
        blocks.push({ type: 'heading', text: inline(heading[2]!) })
      continue
    }

    const item = LIST_ITEM.exec(text)
    if (list?.items.length && indent >= 2) {
      // 列表项的补充说明或子列表
      const last = list.items.length - 1
      const extra = item ? `• ${inline(item[2]!)}` : inline(text)
      list.items[last] = `${list.items[last]}\n${extra}`
      blankInList = false
      continue
    }

    if (item && indent < 2) {
      flushParagraph()
      const ordered = /\d/.test(item[1]!)
      if (list && list.ordered !== ordered)
        closeList()
      if (!list) {
        const start = ordered ? Number.parseInt(item[1]!) : 1
        list = { type: 'list', ordered, items: [], ...(ordered && start !== 1 ? { start } : {}) }
      }
      const itemText = inline(item[2]!)
      if (!BOILERPLATE.test(itemText))
        list.items.push(itemText)
      blankInList = false
      continue
    }

    // 列表后面空一行再接正文，列表就结束了；没空行时是上一项换行接着写
    if (list?.items.length && !blankInList) {
      const last = list.items.length - 1
      list.items[last] = `${list.items[last]}${inline(text)}`
      continue
    }
    closeList()

    if (text.startsWith('|')) {
      flushParagraph()
      if (!isTableSeparator(text))
        blocks.push({ type: 'paragraph', text: inline(tableRow(text)) })
      continue
    }
    paragraph.push(text.replace(/^>\s*/, ''))
  }
  flushParagraph()
  closeList()

  // 去掉结束语后，「附加内容」这类标题下面可能没有内容了
  const kept = blocks.filter((block, index) => block.type !== 'heading' || (blocks[index + 1] && blocks[index + 1]!.type !== 'heading'))
  return { blocks: kept, ...(images ? { images } : {}) }
}
